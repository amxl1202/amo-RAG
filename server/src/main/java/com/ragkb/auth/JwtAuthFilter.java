package com.ragkb.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 把 {@code Authorization: Bearer <token>} 翻译成 SecurityContext 里的身份。
 *
 * <h2>为什么这里不写 401</h2>
 * 这一层只负责"如果令牌有效,就把身份放进上下文";令牌无效时什么都不做,
 * 由 {@link SecurityConfig} 的 entry point 统一决定响应。
 * 理由:白名单路径(登录、探活)不该因为客户端多带了一个过期令牌而被拦住 ——
 * 如果这里直接返回 401,一次"登录页带着旧令牌刷新"就会变成白屏。
 *
 * <h2>为什么是 OncePerRequestFilter</h2>
 * 请求可能在容器内部转发(例如 ERROR 派发),普通 Filter 会跑两遍。
 * 这个类只做无副作用的解析,跑两遍不会错,但"只跑一遍"是更省的默认假设。
 *
 * <h2>这个 Filter 只能被注册一次</h2>
 * 它既是 {@code @Component}(会被 Boot 自动注册进 servlet 全局链),又被
 * {@code SecurityConfig.addFilterBefore} 放进 security chain。两处注册会导致同一实例跑两遍,
 * 而"现在没事"依赖 security chain 的 order 更靠前这个巧合 —— 顺序一旦反了,
 * 全局那份设的认证会被 security chain 的 {@code SecurityContextHolderFilter} 清空,
 * 结果是所有接口静默 401。{@code SecurityConfig.jwtAuthFilterRegistration} 用
 * {@code setEnabled(false)} 关掉了自动注册,改动这里之前先看那处注释。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            AuthPrincipal principal = tokenProvider.parse(header.substring(PREFIX.length()).trim());
            if (principal != null) {
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        chain.doFilter(request, response);
    }
}
