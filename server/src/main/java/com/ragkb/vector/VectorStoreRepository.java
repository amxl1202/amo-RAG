package com.ragkb.vector;

import java.util.List;

/**
 * 向量存储的读写能力。
 *
 * 抽成接口是为了让检索层能在没有数据库的情况下被测试 —— 排序、限量、空结果这些逻辑
 * 如果只能连库验证,那么 CI 里跑不了,本地也得先起容器。
 */
public interface VectorStoreRepository {

    /**
     * 批量写入(按 chunkId 覆盖)。
     *
     * 用覆盖而不是"先删后插":重跑同一批数据不该产生重复行,也不该因为中途失败留下半份。
     */
    int saveAll(List<ChunkVector> vectors);

    /**
     * 取与查询向量最相近的若干切片,按相似度降序。
     *
     * @param queryVector 查询向量
     * @param limit       最多返回多少条;小于等于 0 时返回空列表而不是抛异常 —— 调用方传 0 的意图通常是"不要结果"
     */
    List<ScoredChunk> search(float[] queryVector, int limit);

    /** 已入库的向量条数,用于确认"全量刷完没有"。 */
    long count();
}
