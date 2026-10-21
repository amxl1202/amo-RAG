package com.ragkb.embedding;

import java.util.List;

/**
 * 向量化能力:文本进,向量出。
 *
 * 刻意<b>不</b>暴露 HTTP 细节,也不依赖 Spring —— 上层只关心"给我向量",
 * 换模型或换供应商时只改实现类,检索与入库逻辑不动。
 */
public interface EmbeddingClient {

    /** 向量维度,必须与向量表 DDL 一致。 */
    int dimension();

    /**
     * 产出向量的模型名。
     *
     * 入库时要写进 {@code chunk_embeddings.model},检索时要靠它限定范围 ——
     * 换模型重刷期间,新旧向量同库共存,混在一起排序会让结果失真。
     */
    String model();

    /** 单条文本向量化。 */
    float[] embed(String text);

    /**
     * 批量向量化。
     *
     * 返回的向量顺序与入参<b>严格一致</b> —— 服务端可能乱序返回(带 text_index),
     * 实现必须重排回来,否则向量会和切片错配,而这种错配不会报错,只会让检索结果变差。
     */
    EmbeddingResult embedBatch(List<String> texts);

    /**
     * 批量向量化的结果。
     *
     * {@code totalTokens} 是服务端回报的计费用量,用来校准本地 token 计数:
     * 本地用 cl100k_base,和实际模型的分词不完全一致,中文上偏差可能达到一倍以上。
     * 不校准的话,"每片不超过 N token"这个约束在真实调用时是失真的。
     */
    record EmbeddingResult(List<float[]> vectors, int totalTokens) {

        public EmbeddingResult {
            if (vectors == null) {
                throw new IllegalArgumentException("vectors 不能为空");
            }
            vectors = List.copyOf(vectors);
        }

        public int size() {
            return vectors.size();
        }
    }
}
