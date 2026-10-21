package com.ragkb.vector;

/**
 * 一条待入库的向量。
 *
 * 只带 chunkId 而不带整份切片内容 —— 向量表只负责"这个切片对应哪个向量",
 * 内容仍以 {@code document_chunks} 为准。两份数据各管一件事,避免同一段文本存两遍后不同步。
 *
 * @param chunkId   切片 id,一个切片最多一条向量(主键约束保证)
 * @param model     产出该向量的模型名,换模型后能分辨旧数据
 * @param embedding 向量本体,长度必须等于向量表 DDL 里的维度
 */
public record ChunkVector(long chunkId, String model, float[] embedding) {

    public ChunkVector {
        if (chunkId <= 0) {
            throw new IllegalArgumentException("chunkId 必须为正: " + chunkId);
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空 —— 没有它就无法分辨哪些向量是旧模型算的");
        }
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding 不能为空");
        }
    }

    public int dimension() {
        return embedding.length;
    }
}
