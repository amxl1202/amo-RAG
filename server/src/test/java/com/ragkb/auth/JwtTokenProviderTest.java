package com.ragkb.auth;

import com.ragkb.common.AuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌签发与校验。
 *
 * 重点不在"正常能签能解",而在四条失败路径:过期、换密钥、换 issuer、结构损坏。
 * 这四条里任何一条被放过,认证就等于没有 —— 而它们都不会在日常使用中暴露。
 */
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";

    private static AuthProperties props(String secret, int ttlMinutes, String issuer) {
        AuthProperties p = new AuthProperties();
        p.setSecret(secret);
        p.setTtlMinutes(ttlMinutes);
        p.setIssuer(issuer);
        return p;
    }

    private static JwtTokenProvider provider() {
        return new JwtTokenProvider(props(SECRET, 120, "amo-rag"));
    }

    @Test
    @DisplayName("签发后能解回同一个身份")
    void roundTrip() {
        JwtTokenProvider provider = provider();
        AuthPrincipal original = new AuthPrincipal(42, "demo", "USER", 1L);

        AuthPrincipal parsed = provider.parse(provider.issue(original));

        assertThat(parsed).isNotNull();
        assertThat(parsed.userId()).isEqualTo(42);
        assertThat(parsed.username()).isEqualTo("demo");
        assertThat(parsed.role()).isEqualTo("USER");
    }

    @Test
    @DisplayName("令牌里不含密码,也不含任何与凭据有关的字段")
    void tokenCarriesNoSecretMaterial() {
        JwtTokenProvider provider = provider();
        String token = provider.issue(new AuthPrincipal(7, "alice", "USER", 1L));

        // JWT 的 payload 是 base64url(不是加密),直接解码就能看内容 —— 所以能放什么必须明确
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertThat(payload).contains("\"username\":\"alice\"")
                .doesNotContain("password")
                .doesNotContain("hash");
    }

    @Test
    @DisplayName("过期令牌不被接受")
    void expiredTokenIsRejected() {
        JwtTokenProvider provider = provider();
        String expired = Jwts.builder()
                .subject("42")
                .claim("username", "demo")
                .claim("role", "USER")
                .issuer("amo-rag")
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(provider.parse(expired)).isNull();
    }

    @Test
    @DisplayName("换一把密钥签的令牌不被接受")
    void tokenFromAnotherSecretIsRejected() {
        JwtTokenProvider provider = provider();
        String foreign = Jwts.builder()
                .subject("42")
                .claim("username", "demo")
                .issuer("amo-rag")
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(
                        "another-secret-0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(provider.parse(foreign)).isNull();
    }

    @Test
    @DisplayName("issuer 不符不被接受 —— 否则同一密钥下别的服务的令牌也能用")
    void tokenFromAnotherIssuerIsRejected() {
        JwtTokenProvider provider = provider();
        String foreign = Jwts.builder()
                .subject("42")
                .claim("username", "demo")
                .issuer("some-other-service")
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(provider.parse(foreign)).isNull();
    }

    @Test
    @DisplayName("结构损坏或空串都返回 null,不抛异常")
    void malformedTokenIsRejectedQuietly() {
        JwtTokenProvider provider = provider();

        assertThat(provider.parse("not-a-token")).isNull();
        assertThat(provider.parse("a.b.c")).isNull();
        assertThat(provider.parse("")).isNull();
        assertThat(provider.parse(null)).isNull();
    }

    @Test
    @DisplayName("密钥为空或过短时启动即失败,而不是等到第一次登录")
    void weakSecretFailsFast() {
        assertThatThrownBy(() -> new JwtTokenProvider(props("", 120, "amo-rag")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");

        assertThatThrownBy(() -> new JwtTokenProvider(props("short", 120, "amo-rag")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("太短");
    }

    @Test
    @DisplayName("有效期对外可见,登录响应要用它告诉前端何时该重新登录")
    void exposesTtl() {
        assertThat(provider().ttlSeconds()).isEqualTo(120 * 60);
    }
}
