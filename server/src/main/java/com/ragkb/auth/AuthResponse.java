package com.ragkb.auth;

/**
 * 登录/注册成功后的响应。
 *
 * 不含密码哈希,也不含用户 ID 之外的任何内部字段 —— 响应体是"给客户端用的"，
 * 多一个字段就多一处将来要维护的兼容性负担。
 *
 * @param token            紧凑格式的 JWT
 * @param tokenType        固定 Bearer,前端拼 Authorization 头用
 * @param expiresInSeconds 有效期,前端可据此提前提示重新登录
 * @param username         登录名
 * @param role             角色
 */
public record AuthResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        String username,
        String role) {

    public static AuthResponse of(String token, long ttlSeconds, AuthPrincipal principal) {
        return new AuthResponse(token, "Bearer", ttlSeconds, principal.username(), principal.role());
    }
}
