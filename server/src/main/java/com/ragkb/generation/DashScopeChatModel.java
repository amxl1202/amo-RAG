package com.ragkb.generation;

import com.ragkb.common.http.HttpPoster;
import com.ragkb.common.http.JdkHttpPoster;
import com.ragkb.common.http.RetryPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 走 OpenAI 兼容协议的对话客户端。
 *
 * <h2>三件必须明确处理的事</h2>
 * <ol>
 *   <li><b>HTTP 200 也可能失败。</b>兼容层在部分错误(模型名不存在、内容审核拦截)上
 *       仍返回 200,把错误放在 {@code choices[0].finish_reason} 或 {@code error} 字段里。
 *       只看状态码会把一个空答案当成"模型无话可说"传给用户。</li>
 *   <li><b>空 choices 必须抛错,不能返回空串。</b>空串会被下游当成"模型拒答",
 *       于是一个服务端故障就被记成一次正确的拒答,拒答准确率虚高。</li>
 *   <li><b>拒答由上游判断,这里不做。</b>客户端只负责把模型说的话原样带回去 ——
 *       判断"这是不是拒答"要结合材料与阈值,属于证据评估的职责。</li>
 * </ol>
 *
 * 重试判据与向量化共用 {@link RetryPolicy}:两处各写一套迟早会分叉。
 */
public class DashScopeChatModel implements ChatModel {

    private static final String AUTH_HEADER = "Authorization";
    private static final int MAX_REPORTED_BODY = 300;

    private final ChatProperties properties;
    private final HttpPoster poster;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public DashScopeChatModel(ChatProperties properties) {
        this(properties, new JdkHttpPoster(properties.timeoutSeconds()));
    }

    public DashScopeChatModel(ChatProperties properties, HttpPoster poster) {
        this.properties = properties;
        this.poster = poster;
    }

    @Override
    public ChatResponse complete(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("系统提示词不能为空");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("用户提示词不能为空");
        }
        if (!properties.hasApiKey()) {
            throw new ChatException("缺少 DASHSCOPE_API_KEY:生成需要调用对话接口,请先在环境变量里配置", false);
        }
        String requestBody = buildRequestBody(systemPrompt, userPrompt);
        String lastError = "未知错误";

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            long startedAt = System.nanoTime();
            try {
                HttpPoster.Response response = poster.post(properties.endpoint(), authHeaders(), requestBody);
                long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
                if (response.status() == 200) {
                    return parseResponse(response.body(), elapsed);
                }
                if (!RetryPolicy.isRetryableStatus(response.status())) {
                    throw new ChatException("对话接口返回 " + response.status()
                            + ",属于不可重试的错误: " + abbreviate(response.body()), false);
                }
                lastError = "HTTP " + response.status() + ": " + abbreviate(response.body());
            } catch (ChatException e) {
                if (!e.retryable()) {
                    throw e;
                }
                lastError = e.getMessage();
            } catch (InterruptedException e) {
                // 收到中断就退出,并把中断标志还回去,而不是吞掉当普通失败重试
                Thread.currentThread().interrupt();
                throw new ChatException("生成被中断", false, e);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (attempt < properties.maxAttempts() && !RetryPolicy.sleepBeforeRetry(attempt)) {
                throw new ChatException("生成被中断", false);
            }
        }
        throw new ChatException("生成失败(已尝试 " + properties.maxAttempts() + " 次): " + lastError, true);
    }

    private Map<String, String> authHeaders() {
        return Map.of(AUTH_HEADER, "Bearer " + properties.apiKey(), "Content-Type", "application/json");
    }

    private String buildRequestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.model());
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        payload.put("temperature", properties.temperature());
        payload.put("max_tokens", properties.maxTokens());
        return mapper.writeValueAsString(payload);
    }

    private ChatResponse parseResponse(String body, long elapsedMillis) {
        JsonNode root = mapper.readTree(body);
        JsonNode error = root.get("error");
        // 兼容层把部分错误也塞进 200:不看 error 字段就会把失败读成"模型没说话"
        if (error != null && !error.isNull()) {
            JsonNode message = error.get("message");
            throw new ChatException("对话接口返回错误: "
                    + (message == null ? abbreviate(body) : message.asString()), false);
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new ChatException("响应缺少 choices:" + abbreviate(body), false);
        }
        JsonNode message = choices.get(0).get("message");
        JsonNode contentNode = message == null ? null : message.get("content");
        if (contentNode == null || contentNode.isNull()) {
            throw new ChatException("响应缺少 choices[0].message.content:" + abbreviate(body), false);
        }
        String content = contentNode.asString();
        if (content.isBlank()) {
            // 空正文不能当成"模型拒答"往下传:那会把服务端异常记成一次正确拒答
            JsonNode finishReason = choices.get(0).get("finish_reason");
            throw new ChatException("模型返回空正文,finish_reason="
                    + (finishReason == null ? "未知" : finishReason.asString()), false);
        }

        int promptTokens = 0;
        int completionTokens = 0;
        JsonNode usage = root.get("usage");
        if (usage != null) {
            promptTokens = intField(usage, "prompt_tokens");
            completionTokens = intField(usage, "completion_tokens");
        }
        JsonNode modelNode = root.get("model");
        String model = modelNode == null || modelNode.isNull() ? properties.model() : modelNode.asString();
        return new ChatResponse(content, model, promptTokens, completionTokens, elapsedMillis);
    }

    private static int intField(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || !value.isNumber() ? 0 : value.asInt();
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_REPORTED_BODY ? body : body.substring(0, MAX_REPORTED_BODY) + "…";
    }
}
