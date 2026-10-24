package com.ragkb.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 刷新令牌。
 *
 * <h2>为什么存哈希而不是明文</h2>
 * 与密码同理:库被读走时,明文令牌等于现成的登录凭据。这个令牌本身是高熵随机串,
 * 不需要加盐慢哈希(不存在字典攻击的空间),SHA-256 足够,而且校验时不用等几十毫秒。
 *
 * <h2>为什么有 family_id</h2>
 * 一次登录派生出的一串令牌属于同一族。轮换时旧令牌作废、新令牌入族;
 * 一旦检测到"已用过的令牌再次出现",说明这一串可能被复制过 —— 此时吊销整族,
 * 让合法用户和攻击者都得重新登录,而不是只作废那一枚(攻击者手里还有新的)。
 */
@Data
@TableName("refresh_tokens")
public class RefreshTokenEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** SHA-256 哈希的十六进制串(64 字符)。 */
    private String tokenHash;

    /** 令牌族。同一族共享一次登录的生命周期。 */
    private UUID familyId;

    private LocalDateTime issuedAt;

    private LocalDateTime expiresAt;

    /** 被轮换掉的时刻。非空 = 已经用过,再次出现即为重放。 */
    private LocalDateTime usedAt;

    private LocalDateTime revokedAt;
}
