package com.ragkb.auth;

import com.ragkb.auth.mapper.RefreshTokenMapper;
import com.ragkb.common.ApiException;
import com.ragkb.common.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 刷新令牌的签发、轮换与吊销。
 *
 * <h2>轮换到底防住了什么</h2>
 * 只做"每次刷新换新的"并不防重放 —— 攻击者拿到旧令牌照样能用,
 * 直到它过期。真正的防线是<b>记录"这枚令牌是否已经被用过"</b>:
 * 一旦已用过的令牌再次出现,说明至少有两方持有同一枚令牌(合法用户 + 窃取者),
 * 此时把整族吊销,让双方都重新登录 —— 攻击者失去持续访问,用户知道自己该改密码了。
 *
 * <h2>为什么并发安全靠数据库而不是内存锁</h2>
 * 抢占用 {@code UPDATE ... WHERE used_at IS NULL} 的条件更新,拿到 1 行的那个请求才算赢。
 * 内存锁只在单实例有效,而令牌校验天然会被并发触发(前端重试、多标签页同时刷新)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    /** 令牌原文的字节数。32 字节 = 256 位熵,远超暴力猜测的可能性。 */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenMapper mapper;
    private final AuthProperties properties;

    /**
     * @param raw      交给客户端的明文令牌(只在签发时出现一次,库里存的是哈希)
     * @param familyId 令牌族
     */
    public record Issued(String raw, long userId, UUID familyId) {
    }

    /**
     * @param userId 令牌所属用户
     * @param raw    新的明文令牌
     */
    public record Rotated(long userId, String raw) {
    }

    /** 新登录:开一个新族。 */
    public Issued issueNewFamily(long userId) {
        return issue(userId, UUID.randomUUID());
    }

    /**
     * 轮换:校验旧令牌 → 标记已用 → 在同族里发一枚新的。
     *
     * @throws ApiException 401,携带"重放"或"失效"两种可区分的原因
     */
    public Rotated rotate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(401, "缺少刷新令牌,请重新登录");
        }
        LocalDateTime now = LocalDateTime.now();
        RefreshTokenEntity row = mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RefreshTokenEntity>()
                        .eq(RefreshTokenEntity::getTokenHash, sha256(raw)));
        if (row == null) {
            throw new ApiException(401, "刷新令牌无效,请重新登录");
        }
        if (row.getUsedAt() != null) {
            // 已用过的令牌再次出现 = 重放。整族吊销,宁可让用户重登一次,也不能让窃取者继续刷新
            mapper.revokeFamily(row.getFamilyId(), now);
            log.warn("refresh token replay detected: userId={}, family={}", row.getUserId(), row.getFamilyId());
            throw new ApiException(401, "检测到令牌重放,已注销全部会话,请重新登录");
        }
        if (row.getRevokedAt() != null) {
            throw new ApiException(401, "该会话已注销,请重新登录");
        }
        if (row.getExpiresAt().isBefore(now)) {
            throw new ApiException(401, "刷新令牌已过期,请重新登录");
        }
        if (mapper.claim(row.getId(), now) == 0) {
            // 条件更新没抢到:另一个请求刚刚用掉了它。与"已用过"同义,按重放处理
            mapper.revokeFamily(row.getFamilyId(), now);
            log.warn("concurrent refresh detected: userId={}, family={}", row.getUserId(), row.getFamilyId());
            throw new ApiException(401, "刷新令牌已被使用,请重新登录");
        }
        return new Rotated(row.getUserId(), issue(row.getUserId(), row.getFamilyId()).raw());
    }

    /** 退出登录:吊销这一族(其它设备不受影响)。 */
    public void revokeFamily(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        RefreshTokenEntity row = mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RefreshTokenEntity>()
                        .eq(RefreshTokenEntity::getTokenHash, sha256(raw)));
        if (row != null) {
            mapper.revokeFamily(row.getFamilyId(), LocalDateTime.now());
        }
    }

    /** 注销某用户的全部会话(改密后应当调用)。 */
    public int revokeAllOfUser(long userId) {
        return mapper.revokeAllOfUser(userId, LocalDateTime.now());
    }

    private Issued issue(long userId, UUID familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        LocalDateTime now = LocalDateTime.now();
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUserId(userId);
        entity.setTokenHash(sha256(raw));
        entity.setFamilyId(familyId);
        entity.setIssuedAt(now);
        entity.setExpiresAt(now.plusDays(properties.getRefreshTtlDays()));
        mapper.insert(entity);
        return new Issued(raw, userId, familyId);
    }

    /** SHA-256 十六进制。选它而不是 BCrypt:高熵随机串没有字典攻击面,也不该给刷新加几十毫秒。 */
    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256;走到这里说明运行环境损坏,直接失败比降级到明文安全
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
