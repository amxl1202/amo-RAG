package com.ragkb.auth;

import com.ragkb.common.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
 * <h2>为什么不配 CORS</h2>
 * 前端开发期由 Vite 代理 {@code /api},生产由反向代理把前端与 API 挂在同一域名下 ——
 * 浏览器看到的始终是同源请求。配一套 CORS 等于主动开一个当前不需要的跨域口子,
 * 而放行规则一旦写松(例如 {@code allowedOriginPatterns("*")}),就等于把令牌暴露给任意站点。
 * 将来真要跨域部署,再连同 {@code allowedOriginPatterns} 与预检缓存一起加,而不是留一行
 * 半套的 {@code OPTIONS permitAll} 让人以为已经处理过。
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
                        // 白名单只列具体路径,不用 /api/auth/** 这种通配:
                        // 将来 auth 包下加"改密码""注销全部会话"时,通配会把它们一起公开,
                        // 而那种失误没有任何提示 —— 与"默认拒绝"要防的正是同一类事情。
                        // 这里不限定 HTTP 方法:限定成 POST 会让 GET 落进 anyRequest 变成 401,
                        // 而"登录接口要鉴权"是个误导;放开后 GET 会正常得到 405(方法不允许)
                        .requestMatchers("/api/auth/login", "/api/auth/register",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // 探活与错误页给运维和容器探测用
                        // live/ready 也必须免鉴权:探针不会带令牌,401 会被编排读成"不健康"
                        .requestMatchers("/health", "/health/live", "/health/ready", "/error").permitAll()
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
     * 把 {@link JwtAuthFilter} 从 servlet 容器的全局过滤器链里摘掉。
     *
     * <h2>为什么需要这个 Bean</h2>
     * 它同时是 {@code @Component}(Boot 会把每个 Filter Bean 自动注册进全局链)和
     * 被 {@code addFilterBefore} 塞进 security chain —— <b>同一个实例被注册两次</b>。
     * 现在没出问题靠两个巧合:security chain 的 order(-100)先执行并设好认证;
     * 全局那份随后被 OncePerRequestFilter 的 already-filtered 标记跳过。
     *
     * 一旦有人给这个 Filter 加 {@code @Order} 提升优先级,执行顺序就反过来:
     * 全局那份先设认证 → security chain 的 {@code SecurityContextHolderFilter} 把它清空 →
     * chain 内那份因 already-filtered 被跳过 → <b>所有接口静默 401</b>,而且改的那行看起来无害。
     * 显式禁用自动注册,把这个巧合变成明确约定。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
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
