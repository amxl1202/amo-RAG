package com.ragkb.auth;

import com.ragkb.common.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * 安全策略:<b>默认拒绝</b>,白名单显式列出。
 *
 * <h2>为什么是"默认拒绝"</h2>
 * 反过来写(默认放行、敏感接口再单独加保护)的失败模式是"新增接口忘了加注解" ——
 * 那一刻接口是裸的,而且没有任何迹象。默认拒绝的失败模式是"新接口访问不了",
 * 会立刻被发现,修起来只是往白名单加一行。<b>两种错误的代价不对称,所以选后者。</b>
 *
 * <h2>为什么关掉 session 与 csrf</h2>
 * 这是纯 token 的 API:凭据放在 {@code Authorization} 头里,浏览器不会自动携带,
 * 因此不存在"跨站请求自动带上凭据"的前提,CSRF 防护没有对象。
 * 关掉 session 之后每个请求都要重新校验令牌,也就不存在"服务端会话被固定"的问题。
 *
 * <h2>401 与 403 为什么分开</h2>
 * 两者的处理方式完全不同:401 该跳登录页,403 该提示"没权限,换个账号"。
 * 统一返回 401 会让前端在权限不足时反复重定向,看起来像登录状态丢了。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // 无状态:不建会话、不写 cookie,令牌失效就只能重新登录
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 预检请求不带凭据,拦掉它会让浏览器直接报跨域错,而错误信息毫无指向性
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 登录/注册本身不能要令牌;探活与错误页给运维和容器探测用
                        .requestMatchers("/api/auth/**", "/health", "/error").permitAll()
                        // 其余一律需要身份 —— 包括将来新加的接口,不需要记得加注解
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, 401, "未登录或登录已失效"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, 403, "没有访问该资源的权限")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 密码编码器。
     *
     * 用 BCrypt 而不是 SHA-256:后者是通用摘要函数,算得快 —— 对攻击者也是好事。
     * BCrypt 自带盐、且 cost 可调,默认 10 意味着每次校验约几十毫秒,
     * 单次登录感觉不到,暴力破解的成本却涨了几个数量级。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 与 {@code ApiResponse} 同形的错误体:前端只需要一套解析逻辑。 */
    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(
                new ErrorBody(status, message)));
    }

    private record ErrorBody(int code, String message) {
    }
}
