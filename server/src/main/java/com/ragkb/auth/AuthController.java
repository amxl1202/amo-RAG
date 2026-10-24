package com.ragkb.auth;

import com.ragkb.common.ApiResponse;
import com.ragkb.common.AuthProperties;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * 认证接口:登录 / 注册 / 刷新 / 退出。
 *
 * <h2>两种令牌放在两个地方</h2>
 * <ul>
 *   <li><b>访问令牌</b>走响应体,前端放内存或 localStorage,请求时带 {@code Authorization} 头;</li>
 *   <li><b>刷新令牌</b>走 {@code Set-Cookie},httpOnly —— JS 读不到,所以 XSS 也偷不走。</li>
 * </ul>
 * 这个组合的意义在于:即使页面上出现 XSS,攻击者最多拿到 30 分钟有效的访问令牌,
 * 而拿不到那枚能一直续期的刷新令牌。
 *
 * <h2>为什么不给刷新接口配 CSRF token</h2>
 * 刷新是"用 cookie 换新令牌"的状态变更接口,理论上要防 CSRF。这里靠三件事共同收敛:
 * {@code SameSite=Strict}(跨站请求根本不带这枚 cookie)、只接受 POST、
 * 以及不允许跨源(没有 CORS 配置)。真要做跨域部署时必须补 CSRF 令牌,不能只靠这三条。
 *
 * <h2>为什么 refresh / logout 在白名单里</h2>
 * 它们本身就是"没有有效访问令牌时"用的 —— 要求先有令牌等于自相矛盾。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 刷新令牌的 cookie 名。 */
    private static final String REFRESH_COOKIE = "refresh_token";

    private final AuthService authService;
    private final AuthProperties properties;

    public AuthController(AuthService authService, AuthProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    /** 注册成功即返回令牌,省掉"注册后再调一次登录"的往返。 */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody AuthRequest request) {
        return withRefreshCookie(authService.register(request.username(), request.password()));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody AuthRequest request) {
        return withRefreshCookie(authService.login(request.username(), request.password()));
    }

    /**
     * 刷新:换一对新令牌,旧刷新令牌立即作废。
     *
     * <p>cookie 不存在时返回 401 而不是 400:对客户端来说"没有会话"与"会话失效"是同一种处理。
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    /** 退出登录:吊销当前这一族,并让浏览器立刻丢掉 cookie。 */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        ResponseCookie expired = cookie("", Duration.ZERO);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(ApiResponse.ok(Map.of("loggedOut", true)));
    }

    /** 把刷新令牌写进 httpOnly cookie,并把访问令牌放进响应体。 */
    private ResponseEntity<ApiResponse<AuthResponse>> withRefreshCookie(AuthService.Session session) {
        ResponseCookie cookie = cookie(session.refreshToken(), Duration.ofDays(properties.getRefreshTtlDays()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.ok(session.response()));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                // Strict:跨站请求不会带上它,这是这里唯一一道 CSRF 防线,不能改成 Lax
                .sameSite("Strict")
                // 只发给认证接口:其它接口没机会把这枚 cookie 带出去,减少泄漏面
                .path("/api/auth")
                .maxAge(maxAge)
                .secure(properties.isCookieSecure())
                .build();
    }
}
