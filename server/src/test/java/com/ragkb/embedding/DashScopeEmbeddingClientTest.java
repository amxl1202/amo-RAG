package com.ragkb.embedding;

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
 * 向量化客户端的协议层逻辑。
 *
 * 全部用假的 {@link HttpPoster},不打真实接口。原因是这里最容易错的三件事(乱序重排、
 * 维度校验、重试策略)只能在"构造出特定响应"的前提下验证 —— 真实接口不会给你返回
 * 一个乱序的、少一条的、或者 512 维变 768 维的响应。
 */
class DashScopeEmbeddingClientTest {

    private static final int DIM = 512;

    private static EmbeddingProperties props(int batchSize, int maxAttempts) {
        return new EmbeddingProperties("test-key", "text-embedding-v3", DIM, batchSize,
                "http://localhost/unused", 5, maxAttempts);
    }

    /** 记录每次请求,并按队列回放预置响应。 */
    private static final class RecordingPoster implements HttpPoster {
        private final List<String> bodies = new ArrayList<>();
        private final List<Map<String, String>> headers = new ArrayList<>();
        private final Deque<Response> queue = new ArrayDeque<>();

        RecordingPoster(Response... responses) {
            queue.addAll(List.of(responses));
        }

        @Override
        public Response post(String url, Map<String, String> header, String jsonBody) {
            bodies.add(jsonBody);
            headers.add(header);
            if (queue.isEmpty()) {
                throw new IllegalStateException("测试没有预置足够的响应");
            }
            return queue.poll();
        }
    }

    /** 造一条首元素为 marker、其余为 0 的 512 维向量,便于断言"第 i 条有没有被放到第 i 位"。 */
    private static String vectorJson(double marker) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < DIM; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i == 0 ? marker : 0.0);
        }
        return sb.append(']').toString();
    }

    private static String okBody(int... markersInIndexOrder) {
        StringBuilder sb = new StringBuilder("{\"output\":{\"embeddings\":[");
        for (int i = 0; i < markersInIndexOrder.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"text_index\":").append(i)
                    .append(",\"embedding\":").append(vectorJson(markersInIndexOrder[i])).append('}');
        }
        return sb.append("]},\"usage\":{\"total_tokens\":42}}").toString();
    }

    @Test
    @DisplayName("请求体带上模型、文本与维度参数")
    void requestCarriesModelTextsAndDimension() {
        RecordingPoster poster = new RecordingPoster(new HttpPoster.Response(200, okBody(1)));
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(props(10, 1), poster);

        client.embedDocument("合规审计的触发条件是什么");

        assertThat(poster.bodies).hasSize(1);
        assertThat(poster.bodies.get(0))
                .contains("\"model\":\"text-embedding-v3\"")
                .contains("\"texts\":[\"合规审计的触发条件是什么\"]")
                .contains("\"dimension\":" + DIM);
        assertThat(poster.headers.get(0)).containsEntry("Authorization", "Bearer test-key");
    }

    @Test
    @DisplayName("服务端乱序返回时必须按 text_index 重排,否则向量会与切片错配")
    void outOfOrderResponseIsReordered() {
        // 故意把 text_index=1 放在前面
        String body = "{\"output\":{\"embeddings\":["
                + "{\"text_index\":1,\"embedding\":" + vectorJson(200) + "},"
                + "{\"text_index\":0,\"embedding\":" + vectorJson(100) + "}"
                + "]},\"usage\":{\"total_tokens\":10}}";
        RecordingPoster poster = new RecordingPoster(new HttpPoster.Response(200, body));
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(props(10, 1), poster);

        EmbeddingClient.EmbeddingResult result = client.embedDocuments(List.of("第一段", "第二段"));

        assertThat(result.vectors()).hasSize(2);
        assertThat(result.vectors().get(0)[0]).as("第 0 条应是 text_index=0 的向量").isEqualTo(100.0f);
        assertThat(result.vectors().get(1)[0]).isEqualTo(200.0f);
        assertThat(result.totalTokens()).isEqualTo(10);
    }

    @Test
    @DisplayName("维度与配置不符时立刻报错,不要等到入库才炸")
    void dimensionMismatchIsRejected() {
        String body = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[0.1,0.2,0.3]}]},"
                + "\"usage\":{\"total_tokens\":3}}";
        RecordingPoster poster = new RecordingPoster(new HttpPoster.Response(200, body));
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(props(10, 1), poster);

        assertThatThrownBy(() -> client.embedDocument("短文本"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("维度不符");
    }

    @Test
    @DisplayName("返回条数与请求不一致时报错 —— 少一条就会让后面所有向量错位")
    void countMismatchIsRejected() {
        RecordingPoster poster = new RecordingPoster(new HttpPoster.Response(200, okBody(1)));
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(props(10, 1), poster);

        assertThatThrownBy(() -> client.embedDocuments(List.of("甲", "乙")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("5xx 会重试,第二次成功就正常返回")
    void retriesOnServerError() {
        RecordingPoster poster = new RecordingPoster(
                new HttpPoster.Response(503, "service unavailable"),
                new HttpPoster.Response(200, okBody(7)));
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(props(10, 3), poster);

        EmbeddingClient.EmbeddingResult result = client.embedDocuments(List.of("甲"));

        assertThat(poster.bodies).hasSize(2);
        assertThat(result.vectors().get(0)[0]).isEqualTo(7.0f);
    }

    @Test
    @DisplayName("4xx 不重试:参数或鉴权错了,重试只是浪费时间并把原因埋掉")
    void doesNotRetryOnClientError() {
        RecordingPoster poster = new RecordingPoster(new HttpPoster.Response(401, "invalid api key"));
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(props(10, 3), poster);

        assertThatThrownBy(() -> client.embedDocument("甲"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("401");
        assertThat(poster.bodies).as("只应发一次请求").hasSize(1);
    }

    @Test
    @DisplayName("超过 batchSize 时拆成多次请求,结果仍按原顺序拼接")
    void splitsIntoBatches() {
        RecordingPoster poster = new RecordingPoster(
                new HttpPoster.Response(200, okBody(1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
                new HttpPoster.Response(200, okBody(2, 2)));
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(props(10, 1), poster);

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            texts.add("第 " + i + " 段");
        }
        EmbeddingClient.EmbeddingResult result = client.embedDocuments(texts);

        assertThat(poster.bodies).hasSize(2);
        assertThat(result.vectors()).hasSize(12);
        assertThat(result.vectors().get(10)[0]).as("第二批的第一条").isEqualTo(2.0f);
        assertThat(result.totalTokens()).as("两次用量应累加").isEqualTo(84);
    }

    @Test
    @DisplayName("没有 API Key 时给一句能指导操作的话,而不是空指针")
    void missingApiKeyGivesActionableMessage() {
        RecordingPoster poster = new RecordingPoster(new HttpPoster.Response(200, okBody(1)));
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(
                new EmbeddingProperties("", "text-embedding-v3", DIM, 10, "http://localhost/unused", 5, 1),
                poster);

        assertThatThrownBy(() -> client.embedDocument("甲"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
        assertThat(poster.bodies).isEmpty();
    }

    @Test
    @DisplayName("空输入直接返回空结果,不发请求")
    void emptyInputShortCircuits() {
        RecordingPoster poster = new RecordingPoster();
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(props(10, 1), poster);

        EmbeddingClient.EmbeddingResult result = client.embedDocuments(List.of());

        assertThat(result.vectors()).isEmpty();
        assertThat(result.totalTokens()).isZero();
        assertThat(poster.bodies).isEmpty();
    }

    @Test
    @DisplayName("退避等待中被中断时直接放弃,不再多发一次外部调用")
    void interruptDuringBackoffAborts() {
        RecordingPoster poster = new RecordingPoster(
                new HttpPoster.Response(503, "busy"),
                new HttpPoster.Response(200, okBody(1)));
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(props(10, 3), poster);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.embedDocument("甲"))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("中断");
        } finally {
            Thread.interrupted();
        }
        assertThat(poster.bodies).as("中断之后不应再发请求").hasSize(1);
    }

    @Test
    @DisplayName("向量数组里混进 null 时报明确错误,而不是空指针")
    void nullElementInVectorIsReported() {
        String body = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[0.1,null,0.3]}]},"
                + "\"usage\":{\"total_tokens\":3}}";
        RecordingPoster poster = new RecordingPoster(new HttpPoster.Response(200, body));
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(
                new EmbeddingProperties("k", "m", 3, 10, "http://localhost/unused", 5, 1), poster);

        assertThatThrownBy(() -> client.embedDocument("甲"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("不是数字");
    }

    @Test
    @DisplayName("查询侧必须带 text_type=query —— 不传时服务端按 document 处理,查询向量会被削弱")
    void querySideSendsTextType() {
        RecordingPoster poster = new RecordingPoster(new HttpPoster.Response(200, okBody(1)));
        new DashScopeEmbeddingClient(props(10, 1), poster).embedQuery("合规审计的触发条件是什么");

        assertThat(poster.bodies.get(0)).contains("\"text_type\":\"query\"");
    }

    @Test
    @DisplayName("入库侧带 text_type=document")
    void documentSideSendsTextType() {
        RecordingPoster poster = new RecordingPoster(new HttpPoster.Response(200, okBody(1)));
        new DashScopeEmbeddingClient(props(10, 1), poster).embedDocuments(List.of("甲"));

        assertThat(poster.bodies.get(0)).contains("\"text_type\":\"document\"");
    }
}
