package com.ragkb.retrieval;

import com.ragkb.vector.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双通道编排:并行、超时、降级。
 *
 * 这三件事都只能靠"故意制造慢响应/异常"来验证。这里不连库也不打接口 ——
 * 通道用 lambda 构造,想让它慢就 sleep,想让它挂就抛。
 */
class HybridRetrieverTest {

    private static ScoredChunk chunk(long id) {
        return new ScoredChunk(id, 1L, "内容" + id, "第一章", 0, 10, null, null, 0.9);
    }

    private static ChunkSearcher slow(long millis, List<ScoredChunk> result) {
        return (question, limit) -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result;
        };
    }

    @Test
    @DisplayName("两路并行:总耗时接近较慢的那一路,而不是两者之和")
    void runsChannelsInParallel() {
        HybridRetriever retriever = new HybridRetriever(
                slow(300, List.of(chunk(1))), slow(300, List.of(chunk(2))),
                HybridRetriever.defaultExecutor(), 5_000);

        long start = System.nanoTime();
        HybridRetriever.HybridResult result = retriever.search("问题", 10);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.vectorHits()).hasSize(1);
        assertThat(result.keywordHits()).hasSize(1);
        assertThat(elapsedMillis).as("串行会是 600ms 以上").isLessThan(550);
    }

    @Test
    @DisplayName("通道超时:降级为空列表,另一路结果照常可用")
    void timeoutDegradesOneChannelOnly() {
        HybridRetriever retriever = new HybridRetriever(
                (question, limit) -> List.of(chunk(1)), slow(3_000, List.of(chunk(2))),
                HybridRetriever.defaultExecutor(), 500);

        long start = System.nanoTime();
        HybridRetriever.HybridResult result = retriever.search("问题", 10);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).as("不应等满 3 秒").isLessThan(1_500);
        assertThat(result.vectorHits()).hasSize(1);
        assertThat(result.keywordHits()).isEmpty();
        assertThat(result.keywordDegraded()).isTrue();
        assertThat(result.vectorDegraded()).isFalse();
        assertThat(result.anyDegraded()).isTrue();
    }

    @Test
    @DisplayName("通道抛异常:同样降级,不让单通道故障拖垮整个请求")
    void exceptionDegradesOneChannelOnly() {
        ChunkSearcher broken = (question, limit) -> {
            throw new IllegalStateException("向量库连接失败");
        };
        HybridRetriever retriever = new HybridRetriever(
                broken, (question, limit) -> List.of(chunk(9)),
                HybridRetriever.defaultExecutor(), 5_000);

        HybridRetriever.HybridResult result = retriever.search("问题", 10);

        assertThat(result.vectorHits()).isEmpty();
        assertThat(result.vectorDegraded()).isTrue();
        assertThat(result.keywordHits()).hasSize(1);
        assertThat(result.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("两路都空时标记为空 —— 上层据此直接拒答,不进模型")
    void bothEmptyIsReported() {
        HybridRetriever retriever = new HybridRetriever(
                (question, limit) -> List.of(), (question, limit) -> List.of(),
                HybridRetriever.defaultExecutor(), 5_000);

        HybridRetriever.HybridResult result = retriever.search("文档里没有的问题", 10);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.anyDegraded()).as("空结果不等于降级").isFalse();
    }

    @Test
    @DisplayName("limit 小于等于 0 时两路都不调用")
    void nonPositiveLimitSkipsBothChannels() {
        AtomicInteger calls = new AtomicInteger();
        ChunkSearcher counting = (question, limit) -> {
            calls.incrementAndGet();
            return List.of(chunk(1));
        };
        HybridRetriever retriever = new HybridRetriever(
                counting, counting, HybridRetriever.defaultExecutor(), 5_000);

        HybridRetriever.HybridResult result = retriever.search("问题", 0);

        assertThat(result.isEmpty()).isTrue();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("空问题与非法超时直接拒绝")
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> new HybridRetriever(
                (q, l) -> List.of(), (q, l) -> List.of(), HybridRetriever.defaultExecutor(), 0))
                .isInstanceOf(IllegalArgumentException.class);

        HybridRetriever retriever = new HybridRetriever(
                (q, l) -> List.of(), (q, l) -> List.of(), HybridRetriever.defaultExecutor(), 1_000);
        assertThatThrownBy(() -> retriever.search("  ", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
