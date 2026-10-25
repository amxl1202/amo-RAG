package com.ragkb.retrieval;

import com.ragkb.embedding.EmbeddingClient;
import com.ragkb.vector.ScoredChunk;
import com.ragkb.vector.VectorStoreRepository;

import java.util.List;

/**
 * 语义检索:把问题变成向量,再取最相近的切片。
 *
 * 这一层刻意很薄 —— 它只做"问题 → 向量 → Top-K"这一条直线,不含融合、不含重排。
 * 混合检索会把它和关键词通道并列调用,所以这里必须保持无状态、可重复调用。
 */
public class VectorRetriever implements ChunkSearcher {

    private final EmbeddingClient embeddingClient;
    private final VectorStoreRepository repository;

    public VectorRetriever(EmbeddingClient embeddingClient, VectorStoreRepository repository) {
        this.embeddingClient = embeddingClient;
        this.repository = repository;
    }

    /**
     * @param question 用户问题
     * @param limit    最多返回多少条;小于等于 0 直接返回空列表,不去调接口浪费一次调用
     */
    @Override
    public List<ScoredChunk> search(String question, int limit, long tenantId) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }
        if (limit <= 0) {
            return List.of();
        }
        // 查询侧必须是 embedQuery:与入库用的 document 侧不是同一个分布
        float[] queryVector = embeddingClient.embedQuery(question);
        return repository.search(queryVector, limit, tenantId);
    }

    /**
     * 只要片段正文的版本,方便直接接到评估跑批
     * ({@code EvalRunner.Retriever} 需要的正是"问题 → 正文列表")。
     */
    public List<String> searchContents(String question, int limit, long tenantId) {
        return search(question, limit, tenantId).stream().map(ScoredChunk::content).toList();
    }
}
