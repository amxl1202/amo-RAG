package com.ragkb.retrieval;

import com.ragkb.vector.ScoredChunk;

import java.util.List;

/**
 * 双通道检索:向量 + 关键词,并行取回,分别降级。
 *
 * <h2>为什么"只写并行"不算做完</h2>
 * 用 {@code CompletableFuture} 把两路并行起来只是第一步。真实故障是:
 * <ul>
 *   <li>向量通道因网络抖动卡 5 秒 —— 串行时整个请求卡 5 秒,并行也不会更快,必须<b>超时</b>;</li>
 *   <li>某一路抛异常 —— 若用 {@code join()} 直接抛,<b>单通道故障会让整个问答失败</b>,
 *       而另一路其实已经拿到了可用结果。</li>
 * </ul>
 * 所以正确行为是:<b>超时或异常 → 该通道返回空列表并标记降级,另一路照常使用</b>。
 * 两路都空时才由上层拒答(不进模型,省一次调用)。
 *
 * <h2>为什么降级要单独标记</h2>
 * "只有一路结果"和"两路都正常但结果少"在下游看起来一样,排查时却完全不同。
 * 标记出来才能写进日志、也才能在评估里区分"效果差"和"通道挂了"。
 */
public class HybridRetriever {

    /**
     * @param vectorHits      向量通道结果(降级时为空列表)
     * @param keywordHits     关键词通道结果(降级时为空列表)
     * @param vectorDegraded  向量通道是否超时或异常
     * @param keywordDegraded 关键词通道是否超时或异常
     */
    public record HybridResult(
            List<ScoredChunk> vectorHits,
            List<ScoredChunk> keywordHits,
            boolean vectorDegraded,
            boolean keywordDegraded) {

        public HybridResult {
            vectorHits = List.copyOf(vectorHits);
            keywordHits = List.copyOf(keywordHits);
        }

        /** 两路都没有结果 —— 上层应当直接拒答,不要再调模型。 */
        public boolean isEmpty() {
            return vectorHits.isEmpty() && keywordHits.isEmpty();
        }

        public boolean anyDegraded() {
            return vectorDegraded || keywordDegraded;
        }
    }

    /** 单通道的执行结果,带上"是否降级"。 */
    private record Outcome(List<ScoredChunk> hits, boolean degraded) {
    }

    private final ChunkSearcher vectorRetriever;
    private final ChunkSearcher keywordRetriever;
    private final java.util.concurrent.Executor executor;
    private final long timeoutMillis;

    public HybridRetriever(ChunkSearcher vectorRetriever, ChunkSearcher keywordRetriever,
                           java.util.concurrent.Executor executor, long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis 必须为正: " + timeoutMillis);
        }
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.executor = executor;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 并行取两路结果。任何一路超时/异常都降级为空列表,不影响另一路。
     *
     * @param question        问题原文
     * @param limitPerChannel 每路各取多少条(融合前不宜太少,否则 RRF 没有名次可用)
     */
    public HybridResult search(String question, int limitPerChannel, long tenantId) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }
        if (limitPerChannel <= 0) {
            return new HybridResult(List.of(), List.of(), false, false);
        }

        var vectorFuture = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> new Outcome(vectorRetriever.search(question, limitPerChannel, tenantId), false), executor)
                .orTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .exceptionally(error -> new Outcome(List.of(), true));

        var keywordFuture = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> new Outcome(keywordRetriever.search(question, limitPerChannel, tenantId), false), executor)
                .orTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .exceptionally(error -> new Outcome(List.of(), true));

        Outcome vector = vectorFuture.join();
        Outcome keyword = keywordFuture.join();
        return new HybridResult(vector.hits(), keyword.hits(), vector.degraded(), keyword.degraded());
    }

    /**
     * 默认线程池:守护线程,避免应用关闭时被这些任务拖住。
     * 通道内是 IO 等待(向量接口、数据库),不需要很大的池子。
     */
    public static java.util.concurrent.ExecutorService defaultExecutor() {
        return java.util.concurrent.Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "retrieval-" + System.nanoTime());
            thread.setDaemon(true);
            return thread;
        });
    }
}
