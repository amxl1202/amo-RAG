package com.ragkb.common.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 用 JDK 自带的 HttpClient 发送请求。
 *
 * 不用 Spring 的 RestClient,是因为调用模型接口的这一层要保持"脱离框架也能跑" ——
 * 向量化、生成、评估跑批都要能在命令行工具与单元测试里单独验证。
 */
public class JdkHttpPoster implements HttpPoster {

    private final HttpClient client;
    private final Duration timeout;

    public JdkHttpPoster(int timeoutSeconds) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public Response post(String url, Map<String, String> headers, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }
}
