package com.ragkb.generation;

import com.ragkb.common.http.HttpPoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对话客户端。
 *
 * 全部用假的 {@link HttpPoster}。这里最值钱的四个分支(200 里藏 error、空 choices、
 * 空正文、4xx 不重试)用真实接口几乎造不出来 —— 真实接口不会故意给你一个
 * "HTTP 200 + 空答案"的响应,而这正是把服务故障记成"模型拒答"的那条路径。
 */
class DashScopeChatModelTest {

    private static ChatProperties properties(int maxAttempts) {
        return new ChatProperties("test-key", "qwen-plus", "http://localhost/unused",
                0.2, 512, 5, maxAttempts);
    }

    private static final class RecordingPoster implements HttpPoster {
        private final List<String> bodies = new ArrayList<>();
        private final Deque<Response> queue = new ArrayDeque<>();

        RecordingPoster(Response... responses) {
            queue.addAll(List.of(responses));
        }

        @Override
        public Response post(String url, Map<String, String> header, String jsonBody) {
            bodies.add(jsonBody);
            if (queue.isEmpty()) {
                throw new IllegalStateException("测试没有预置足够的响应");
            }
            return queue.poll();
        }
    }

    private static String okBody(String content, String finishReason) {
        return """
                {"model":"qwen-plus","choices":[{"index":0,"finish_reason":"%s",
                "message":{"role":"assistant","content":"%s"}}],
                "usage":{"prompt_tokens":120,"completion_tokens":30}}""".formatted(finishReason, content);
    }

    @Test
    @DisplayName("正常响应:正文与 usage 一并带回")
    void parsesContentAndUsage() {
        RecordingPoster poster = new RecordingPoster(
                new HttpPoster.Response(200, okBody("固定长度切分不知道断点在哪[E1]。", "stop")));

        ChatResponse response = new DashScopeChatModel(properties(1), poster).complete("系统", "用户");

        assertThat(response.content()).isEqualTo("固定长度切分不知道断点在哪[E1]。");
        assertThat(response.promptTokens()).isEqualTo(120);
        assertThat(response.completionTokens()).isEqualTo(30);
        assertThat(response.model()).isEqualTo("qwen-plus");
    }

    @Test
    @DisplayName("请求体:system 与 user 两条消息,温度与 max_tokens 来自配置")
    void buildsOpenAiCompatibleBody() {
        RecordingPoster poster = new RecordingPoster(
                new HttpPoster.Response(200, okBody("答案", "stop")));

        new DashScopeChatModel(properties(1), poster).complete("系统提示", "用户提示");

        assertThat(poster.bodies).hasSize(1);
        assertThat(poster.bodies.get(0))
                .contains("\"role\":\"system\"").contains("\"系统提示\"")
                .contains("\"role\":\"user\"").contains("\"用户提示\"")
                .contains("\"model\":\"qwen-plus\"")
                .contains("\"temperature\":0.2")
                .contains("\"max_tokens\":512");
    }

    @Test
    @DisplayName("HTTP 200 但带 error 字段:必须抛错,不能当成模型没说话")
    void errorFieldInsideOkResponse() {
        RecordingPoster poster = new RecordingPoster(
                new HttpPoster.Response(200, "{\"error\":{\"message\":\"data_inspection_failed\"}}"));

        assertThatThrownBy(() -> new DashScopeChatModel(properties(1), poster).complete("s", "u"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("data_inspection_failed");
    }

    @Test
    @DisplayName("空 choices:抛错而不是返回空串 —— 空串会被下游记成一次正确拒答")
    void emptyChoicesIsAnError() {
        RecordingPoster poster = new RecordingPoster(
                new HttpPoster.Response(200, "{\"choices\":[]}"));

        assertThatThrownBy(() -> new DashScopeChatModel(properties(1), poster).complete("s", "u"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("choices");
    }

    @Test
    @DisplayName("正文为空白:报错并带出 finish_reason,便于判断是长度截断还是审核拦截")
    void blankContentReportsFinishReason() {
        RecordingPoster poster = new RecordingPoster(
                new HttpPoster.Response(200, okBody("   ", "length")));

        assertThatThrownBy(() -> new DashScopeChatModel(properties(1), poster).complete("s", "u"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("length");
    }

    @Test
    @DisplayName("401 不重试:鉴权错重试只是把真正的原因埋在重试日志里")
    void unauthorizedIsNotRetried() {
        RecordingPoster poster = new RecordingPoster(
                new HttpPoster.Response(401, "{\"error\":\"invalid api key\"}"),
                new HttpPoster.Response(200, okBody("不该走到这里", "stop")));

        assertThatThrownBy(() -> new DashScopeChatModel(properties(3), poster).complete("s", "u"))
                .isInstanceOf(ChatException.class)
                .satisfies(e -> assertThat(((ChatException) e).retryable()).isFalse());
        assertThat(poster.bodies).hasSize(1);
    }

    @Test
    @DisplayName("503 重试后成功,且总调用次数等于预置响应数")
    void serverErrorIsRetried() {
        RecordingPoster poster = new RecordingPoster(
                new HttpPoster.Response(503, "busy"),
                new HttpPoster.Response(200, okBody("第二次成功[E1]。", "stop")));

        ChatResponse response = new DashScopeChatModel(properties(3), poster).complete("s", "u");

        assertThat(response.content()).isEqualTo("第二次成功[E1]。");
        assertThat(poster.bodies).hasSize(2);
    }

    @Test
    @DisplayName("没有 Key 时在调用前就报错,而不是打一个必然 401 的请求")
    void missingApiKeyFailsFast() {
        ChatProperties noKey = new ChatProperties("", "qwen-plus", "http://localhost/unused",
                0.2, 512, 5, 1);
        RecordingPoster poster = new RecordingPoster();

        assertThatThrownBy(() -> new DashScopeChatModel(noKey, poster).complete("s", "u"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
        assertThat(poster.bodies).isEmpty();
    }

    @Test
    @DisplayName("空提示词直接拒绝,不发请求")
    void blankPromptIsRejected() {
        RecordingPoster poster = new RecordingPoster();

        assertThatThrownBy(() -> new DashScopeChatModel(properties(1), poster).complete(" ", "u"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(poster.bodies).isEmpty();
    }
}
