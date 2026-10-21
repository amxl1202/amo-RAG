package com.ragkb.retrieval;

import com.ragkb.vector.ScoredChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 倒数排名融合(Reciprocal Rank Fusion)。
 *
 * <h2>为什么不用加权求和</h2>
 * 两路的分数不可比:向量相似度落在 0~1,全文检索的分数没有固定上界,量纲完全不同。
 * 加权求和就必须先归一化,而归一化本身又要选一个基线 —— 引入新的取舍。
 * RRF 绕开这件事:<b>只用名次,不用分数</b>。第 r 名的贡献是 {@code 1 / (k + r)},
 * 分数尺度的差异因此被完全消除。
 *
 * <h2>k 的作用</h2>
 * k 是平滑常数:越大,名次之间的差距越平缓(第 1 名和第 5 名的贡献接近);
 * 越小,头部越强势。<b>k = 0 时第一名贡献 1.0,第二名只有 0.5</b> —— 会让某一路的第一名
 * 几乎独裁整个结果。原始论文推荐 60,但这属于要用数据选的参数(见 D15 的 k=0/20/60 对比)。
 */
public class RrfFuser {

    /**
     * 一条融合后的结果。
     *
     * @param vectorRank  在向量通道里的名次(1 基);未出现为 0
     * @param keywordRank 在关键词通道里的名次(1 基);未出现为 0
     */
    public record Hit(ScoredChunk chunk, double rrfScore, int vectorRank, int keywordRank) {

        /** 只在单通道出现。调参时这类命中值得单独看:它们决定了一路是否"白搭"。 */
        public boolean fromSingleChannel() {
            return vectorRank == 0 || keywordRank == 0;
        }
    }

    /**
     * 融合两路结果。
     *
     * @param k     RRF 平滑常数,必须 ≥ 0
     * @param limit 最多返回多少条;小于等于 0 时返回空列表
     */
    public List<Hit> fuse(List<ScoredChunk> vectorHits, List<ScoredChunk> keywordHits, int k, int limit) {
        if (k < 0) {
            throw new IllegalArgumentException("RRF 的 k 不能为负: " + k);
        }
        if (limit <= 0) {
            return List.of();
        }
        // 用 LinkedHashMap 保持"首次出现"的顺序,作为同分时的稳定次序来源
        Map<Long, Accumulator> merged = new LinkedHashMap<>();
        collect(merged, vectorHits, k, true);
        collect(merged, keywordHits, k, false);

        List<Hit> hits = new ArrayList<>(merged.size());
        for (Accumulator accumulator : merged.values()) {
            hits.add(new Hit(accumulator.chunk, accumulator.score, accumulator.vectorRank, accumulator.keywordRank));
        }
        // 同分时按 chunkId 升序 —— 不加确定性次序的话,同一个查询两次跑排名可能不同,评估不可复现
        hits.sort(Comparator.comparingDouble(Hit::rrfScore).reversed()
                .thenComparingLong(hit -> hit.chunk().chunkId()));
        return List.copyOf(hits.size() > limit ? hits.subList(0, limit) : hits);
    }

    private void collect(Map<Long, Accumulator> merged, List<ScoredChunk> hits, int k, boolean vectorChannel) {
        if (hits == null) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            ScoredChunk chunk = hits.get(i);
            int rank = i + 1;
            Accumulator accumulator = merged.computeIfAbsent(chunk.chunkId(), id -> new Accumulator(chunk));
            accumulator.score += 1.0 / (k + rank);
            if (vectorChannel) {
                accumulator.vectorRank = rank;
            } else {
                accumulator.keywordRank = rank;
            }
        }
    }

    private static final class Accumulator {
        private final ScoredChunk chunk;
        private double score;
        private int vectorRank;
        private int keywordRank;

        private Accumulator(ScoredChunk chunk) {
            this.chunk = chunk;
        }
    }
}
