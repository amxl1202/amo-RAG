package com.ragkb.generation;

import com.ragkb.common.http.HttpPoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 流式对话客户端。
 *
 * <h2>为什么这组用例特别值钱</h2>
 * 流式解析有四个只在真实网络里才出现的形态:一行被拆成两段到达、增量里混着空 choices、
 * usage 单独占最后一个 chunk、结尾的 {@code [DONE]}。用假 {@link HttpPoster}
 * 喂预置的行,可以把这四种都构造出来 —— 真接口一次只会给你其中一种。
 */
class DashScopeChatModelStreamTest {

    private static ChatProperties properties() {
        return new ChatProperties("test-key", "qwen-plus", "http://localhost/unused",
                0.2, 512, 5, 1);
    }

    /** 把预置的行一次性喂给回调;记录请求体,便于断言 stream 参数确实发出去了。 */
    private static final class StreamingPoster implements HttpPoster {
        private final List<String> lines;
        private final int status;
        private final String errorBody;
        private final List<String> bodies = new ArrayList<>();

        StreamingPoster(List<String> lines, int status, String errorBody) {
            this.lines = lines;
            this.status = status;
            this.errorBody = errorBody;
        }

        @Override
        public Response post(String url, Map<String, String> header, String jsonBody) {
            throw new IllegalStateException("流式路径不该走一次性 POST");
        }

        @Override
        public Response postStreaming(String url, Map<String, String> header, String jsonBody,
                                      Consumer<String> onLine) {
            bodies.add(jsonBody);
            if (status != 200) {
                return new Response(status, errorBody);
            }
            lines.forEach(onLine);
            return new Response(200, "");
        }
    }

    private static String chunk(String content) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"}}]}";
    }

    @Test
    @DisplayName("逐块增量按顺序流出,并拼成完整答案")
    void streamsDeltasInOrder() {
        StreamingPoster poster = new StreamingPoster(List.of(
                chunk("RRF 是"), chunk("按名次"), chunk("融合。"),
                "data: [DONE]"), 200, null);
        DashScopeChatModel model = new DashScopeChatModel(properties(), poster);
        List<String> deltas = new ArrayList<>();

        ChatResponse response = model.stream("系统", "用户", deltas::add);

        assertThat(deltas).containsExactly("RRF 是", "按名次", "融合。");
        assertThat(response.content()).isEqualTo("RRF 是按名次融合。");
    }

    @Test
    @DisplayName("usage 在末尾单独一块,必须被读到 —— 漏了会让成本少算整次调用")
    void readsUsageFromTrailingChunk() {
        StreamingPoster poster = new StreamingPoster(List.of(
                chunk("答"),
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":8}}",
                "data: [DONE]"), 200, null);
        DashScopeChatModel model = new DashScopeChatModel(properties(), poster);

        ChatResponse response = model.stream("系统", "用户", delta -> {
        });

        assertThat(response.promptTokens()).isEqualTo(120);
        assertThat(response.completionTokens()).isEqualTo(8);
    }

    @Test
    @DisplayName("请求体里必须带 stream 与 include_usage")
    void requestBodyAsksForUsage() {
        StreamingPoster poster = new StreamingPoster(List.of(chunk("答"), "data: [DONE]"), 200, null);
        new DashScopeChatModel(properties(), poster).stream("系统", "用户", delta -> {
        });

        assertThat(poster.bodies.get(0))
                .contains("\"stream\":true")
                .contains("include_usage");
    }

    @Test
    @DisplayName("空行、非 data 行、[DONE] 之后的内容都忽略")
    void ignoresNoiseLines() {
        StreamingPoster poster = new StreamingPoster(List.of(
                "",
                ": ping",
                chunk("甲"),
                "data: [DONE]",
                chunk("乙")), 200, null);
        DashScopeChatModel model = new DashScopeChatModel(properties(), poster);
        List<String> deltas = new ArrayList<>();

        ChatResponse response = model.stream("系统", "用户", deltas::add);

        assertThat(deltas).containsExactly("甲");
        assertThat(response.content()).isEqualTo("甲");
    }

    @Test
    @DisplayName("chunk 里带 error 就抛异常,不能把错误当内容吞掉")
    void errorChunkThrows() {
        StreamingPoster poster = new StreamingPoster(List.of(
                "data: {\"error\":{\"message\":\"quota exceeded\"}}"), 200, null);
        DashScopeChatModel model = new DashScopeChatModel(properties(), poster);

        assertThatThrownBy(() -> model.stream("系统", "用户", delta -> {
        }))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("quota");
    }

    @Test
    @DisplayName("非 200 时把错误体带进异常,便于定位")
    void nonOkStatusCarriesBody() {
        StreamingPoster poster = new StreamingPoster(List.of(), 400, "{\"error\":\"bad model\"}");
        DashScopeChatModel model = new DashScopeChatModel(properties(), poster);

        assertThatThrownBy(() -> model.stream("系统", "用户", delta -> {
        }))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("bad model");
    }

    @Test
    @DisplayName("一个字都没产出时也算失败,不能返回空答案")
    void emptyStreamIsFailure() {
        StreamingPoster poster = new StreamingPoster(List.of("data: [DONE]"), 200, null);
        DashScopeChatModel model = new DashScopeChatModel(properties(), poster);

        assertThatThrownBy(() -> model.stream("系统", "用户", delta -> {
        }))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("没有产出");
    }

    @Test
    @DisplayName("不支持流式的实现走默认实现:一次性给完,调用方无感")
    void defaultStreamFallsBackToComplete() {
        ChatModel oneShot = (system, user) -> new ChatResponse("整段答案", "fake", 10, 5, 1);
        List<String> deltas = new ArrayList<>();

        ChatResponse response = oneShot.stream("系统", "用户", deltas::add);

        assertThat(deltas).containsExactly("整段答案");
        assertThat(response.content()).isEqualTo("整段答案");
    }
}
