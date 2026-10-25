package com.ragkb.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户。
 *
 * 只存密码的<b>哈希</b>,字段名也刻意叫 passwordHash —— 让"这里不该出现明文"这件事
 * 在类型层面就看得见。BCrypt 的输出自带盐与前缀(如 {@code $2a$10$...}),
 * 所以不需要单独的 salt 列:盐混在哈希串里,换算法时也只改前缀。
 */
@Data
@TableName("users")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名。唯一约束在库上,不靠"先查再插"的间隙判断。 */
    private String username;

    /** BCrypt 哈希,固定 60 字符。绝不返回给任何接口。 */
    private String passwordHash;

    /** USER / ADMIN。当前只有 USER,留着是为了加管理员时不用改表。 */
    private String role;

    /** 所属租户。当前单租户演示,但所有读写路径都已按它过滤,加租户不需要改链路。 */
    private Long tenantId;

    private LocalDateTime createdAt;
}
