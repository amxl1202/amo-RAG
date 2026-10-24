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

    /**
     * 流式发送。
     *
     * <h2>为什么用 ofLines 而不是自己切 buffer</h2>
     * SSE 的帧边界就是换行,JDK 的 ofLines 已经处理了分块到达、跨块拼行的问题。
     * 自己按字节切片要额外处理"一行被 TCP 分成两段"的情况,而这正是流式解析最常出错的地方。
     *
     * <h2>非 2xx 为什么要读完整个 body</h2>
     * 错误响应通常很短,但必须整段读出来才能给出可诊断的信息(模型的 400 会说明哪个字段不对)。
     * 流式成功时则一行都不缓存 —— 那是流式的意义。
     */
    @Override
    public Response postStreaming(String url, Map<String, String> headers, String jsonBody,
                                  java.util.function.Consumer<String> onLine) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        headers.forEach(builder::header);

        HttpResponse<java.util.stream.Stream<String>> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
        try (java.util.stream.Stream<String> lines = response.body()) {
            if (response.statusCode() / 100 != 2) {
                String body = lines.collect(java.util.stream.Collectors.joining("\n"));
                return new Response(response.statusCode(), body);
            }
            lines.forEach(onLine);
            return new Response(response.statusCode(), "");
        }
    }
}
