package com.ragkb.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragkb.user.UserEntity;
import com.ragkb.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全链路的端到端验收:真的起容器、真的走过滤器链、真的发 HTTP。
 *
 * <h2>为什么不用 MockMvc</h2>
 * Boot 4 把 WebMvc 的测试自动配置拆成了独立模块,加进来要多一个依赖;
 * 而这里要验证的恰恰是"过滤器链有没有把请求挡住"—— 用真的 HTTP 客户端打真端口,
 * 比 MockMvc 的模拟派发更接近真实,也不依赖测试自动配置模块的拆包结果。
 *
 * <h2>为什么每条用例都独立拿令牌</h2>
 * 这组用例的关键是"默认拒绝":没令牌必须 401、白名单必须放行、令牌有效必须放行。
 * 三者共用一次登录会让失败时看不出是哪一环坏了。
 *
 * 依赖真库(users 表)。库没起时本类会整体失败 —— 这是有意的:认证链路没法离线验证,
 * 与其 skip 掉假装通过,不如让它红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.secret=test-secret-0123456789abcdef0123456789abcdef")
class SecurityChainTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Autowired
    private UserMapper userMapper;

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 注册一个本次用例专用的用户,用例结束后删掉,避免污染开发库。 */
    private String registerAndGetToken(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"s3cret-pass\"}";
        HttpResponse<String> response = postJson("/api/auth/register", body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Matcher matcher = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.body());
        assertThat(matcher.find()).as(response.body()).isTrue();
        return matcher.group(1);
    }

    private void deleteUser(String username) {
        userMapper.delete(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
    }

    @Test
    @DisplayName("白名单:探活接口不需要令牌")
    void healthIsPublic() throws Exception {
        assertThat(get("/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("默认拒绝:没令牌访问业务接口得到 401,而不是 404/403")
    void protectedEndpointRejectsAnonymous() throws Exception {
        HttpResponse<String> response = get("/api/documents", null);

        assertThat(response.statusCode()).isEqualTo(401);
        // 401 的响应体要和其它接口同形,前端才只需要一套解析逻辑
        assertThat(response.body()).contains("\"code\":401");
    }

    @Test
    @DisplayName("令牌无效时同样 401")
    void invalidTokenIsRejected() throws Exception {
        assertThat(get("/api/documents", "totally.invalid.token").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("默认拒绝覆盖新接口:未在白名单里的一律要身份")
    void unknownPathIsProtectedToo() throws Exception {
        // 这个路径根本不存在。没有令牌时应当是 401 而不是 404 ——
        // 说明拦住它的是过滤器链,不是"接口不存在"。这条正是"默认拒绝"与"逐个加注解"的分界
        assertThat(get("/api/not-yet-implemented", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("注册 → 拿令牌 → 带令牌访问业务接口 200")
    void registerThenAccess() throws Exception {
        String username = "it_user_" + System.nanoTime();
        try {
            String token = registerAndGetToken(username);

            HttpResponse<String> response = get("/api/documents", token);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"code\":0");
        } finally {
            deleteUser(username);
        }
    }

    @Test
    @DisplayName("登录:密码错返回 401,且与用户不存在同一句话")
    void loginFailuresLookIdentical() throws Exception {
        HttpResponse<String> unknown = postJson("/api/auth/login",
                "{\"username\":\"nobody_here\",\"password\":\"whatever-1\"}");
        assertThat(unknown.statusCode()).isEqualTo(401);

        String username = "it_login_" + System.nanoTime();
        try {
            registerAndGetToken(username);
            HttpResponse<String> wrong = postJson("/api/auth/login",
                    "{\"username\":\"" + username + "\",\"password\":\"wrong-pass\"}");
            assertThat(wrong.statusCode()).isEqualTo(401);
            assertThat(wrong.body()).isEqualTo(unknown.body());
        } finally {
            deleteUser(username);
        }
    }

    @Test
    @DisplayName("参数不合规是 400,不是 401")
    void validationFailureIsBadRequest() throws Exception {
        HttpResponse<String> response = postJson("/api/auth/register",
                "{\"username\":\"a\",\"password\":\"short\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        // 具体报哪一条取决于字段错误的顺序,所以断言"指出了是哪个字段",
        // 而不是断言某一句固定文案 —— 后者会在字段顺序变化时假红
        assertThat(response.body()).contains("\"code\":400")
                .satisfiesAnyOf(
                        body -> assertThat(body).contains("用户名"),
                        body -> assertThat(body).contains("密码"));
    }
}
