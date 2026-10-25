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
     * 本仓库当前服务的向量模型。
     *
     * 检索必须按模型过滤:换模型重刷期间,旧模型的行仍在库里,而余弦相似度跨模型没有可比性,
     * 混在一起排序会让结果失真。schema 里存 model 列就是为了这件事,不兑现它等于白存。
     */
    String model();

    /**
     * 批量写入(按 chunkId 覆盖)。
     *
     * 用覆盖而不是"先删后插":重跑同一批数据不该产生重复行,也不该因为中途失败留下半份。
     *
     * @throws IllegalArgumentException 出现与本仓库模型不一致的向量 —— 说明上游装配错了
     */
    int saveAll(List<ChunkVector> vectors);

    /**
     * 取与查询向量最相近的若干切片,按相似度降序。只在本仓库的模型内检索。
     *
     * @param queryVector 查询向量
     * @param limit       最多返回多少条;小于等于 0 时返回空列表而不是抛异常 —— 调用方传 0 的意图通常是"不要结果"
     */
    List<ScoredChunk> search(float[] queryVector, int limit, long tenantId);

    /** 已入库的向量条数(仅本模型),用于确认"全量刷完没有"。 */
    long count();
}
