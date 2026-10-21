package com.ragkb.auth;

import com.ragkb.common.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 签发与校验 JWT。
 *
 * <h2>三处刻意的取舍</h2>
 * <ol>
 *   <li><b>密钥长度在构造时就检查。</b>HS256 要求 ≥256 位,不够时 JJWT 会在<b>签名那一刻</b>
 *       抛 {@code WeakKeyException} —— 那时服务已经起来了,登录接口才炸。
 *       放到构造器里,启动即失败,问题暴露在最便宜的时机。</li>
 *   <li><b>校验时比对 issuer。</b>不比对的话,任何用同一密钥签出来的令牌都能用 ——
 *       同一套部署里如果有多个服务共享密钥,边界就没了。</li>
 *   <li><b>令牌里只放身份,不放权限细节。</b>角色放进去是为了少查一次库,
 *       但真正的权限判断将来仍应以库为准:令牌签发后改不了,把可变信息塞进去,
 *       等于给"改了权限但旧令牌还有效"留一个窗口。</li>
 * </ol>
 *
 * <h2>为什么没有 refresh token</h2>
 * 当前是单角色、单页应用的演示形态,一个有效期 2 小时的 access token 够用。
 * refresh 轮换要配"一次性令牌 + 重放检测 + 服务端存储",那是独立的一块工作,
 * 现在加只会得到一个"看起来有 refresh、实际没有重放保护"的半成品。
 */
@Component
public class JwtTokenProvider {

    /** HS256 的最小密钥长度(字节)。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;

    public JwtTokenProvider(AuthProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 JWT_SECRET:请在 .env 里配置至少 32 字节的随机串(不要用可猜的短语)");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET 太短:" + bytes.length
                    + " 字节,HS256 要求至少 " + MIN_SECRET_BYTES + " 字节");
        }
        if (properties.getTtlMinutes() <= 0) {
            throw new IllegalStateException("app.auth.ttl-minutes 必须为正: " + properties.getTtlMinutes());
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.issuer = properties.getIssuer();
        this.ttl = Duration.ofMinutes(properties.getTtlMinutes());
    }

    /** 令牌有效期(秒),登录响应里要告诉前端什么时候该重新登录。 */
    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    /**
     * 签发。
     *
     * @param principal 已确认的身份
     * @return 紧凑格式的 JWT
     */
    public String issue(AuthPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(principal.userId()))
                .claim("username", principal.username())
                .claim("role", principal.role())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并取出身份。
     *
     * @return 校验通过的身份;令牌过期、签名不对、issuer 不符、格式错都返回 null
     *         —— 调用方只需要"认不认这个人",不需要区分失败原因,
     *         而把原因写进响应反而会给攻击者提示
     */
    public AuthPrincipal parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            long userId = Long.parseLong(claims.getSubject());
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
            return new AuthPrincipal(userId, username, role);
        } catch (JwtException | IllegalArgumentException e) {
            // 过期、签名错、结构错都在这里;不记完整令牌,避免把凭据写进日志
            return null;
        }
    }
}
