package com.ragkb.auth;

/**
 * 已通过校验的调用者身份。
 *
 * 只放"请求处理期间真的要用"的字段:用户 ID 用于将来按用户隔离数据,
 * username 用于日志(比 ID 好认),role 用于将来的权限判断。
 * <b>不放密码哈希</b> —— 身份对象会被塞进 SecurityContext,而它可能被日志或调试工具打印出来。
 *
 * @param userId   用户主键
 * @param username 登录名
 * @param role     角色,当前只有 USER
 * @param tenantId 所属租户。放进令牌是为了让每个请求都能拿到,
 *                 而不必为了一次归属判断再查一次库
 */
public record AuthPrincipal(long userId, String username, String role, long tenantId) {

    public AuthPrincipal {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须为正: " + userId);
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username 不能为空");
        }
        role = role == null || role.isBlank() ? "USER" : role;
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId 必须为正: " + tenantId);
        }
    }
}
