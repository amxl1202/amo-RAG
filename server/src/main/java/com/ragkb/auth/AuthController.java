package com.ragkb.auth;

import com.ragkb.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * 这两个路径在 {@link SecurityConfig} 的白名单里 —— 登录本身当然不能要求先登录。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 注册成功即返回令牌,省掉"注册后再调一次登录"的往返。 */
    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody AuthRequest request) {
        return ApiResponse.ok(authService.register(request.username(), request.password()));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        return ApiResponse.ok(authService.login(request.username(), request.password()));
    }
}
