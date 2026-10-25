package com.ragkb.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragkb.common.ApiException;
import com.ragkb.document.mapper.DocumentChunkMapper;
import com.ragkb.embedding.EmbeddingClient;
import com.ragkb.vector.ChunkVector;
import com.ragkb.vector.VectorStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化:把某文档已入库的切片算成向量并写进向量表。
 *
 * <h2>为什么整篇一起算,而不是一条一条来</h2>
 * 接口按条计费、按批限流。整篇切片一次批量提交,调用次数从 N 降到 ceil(N/batchSize),
 * 失败重试的代价也小得多。{@code EmbeddingClient.embedDocuments} 内部已经做了分批与重试,
 * 这里只负责把切片喂进去、把结果按顺序写回来。
 *
 * <h2>为什么写向量前不先删旧向量</h2>
 * {@code chunk_embeddings} 以 chunk_id 为主键,写入是覆盖语义。重新入库会重建切片
 * (chunk_id 随之变化),旧切片被级联删除时旧向量也跟着没了 —— 不需要额外清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorizeService {

    private final DocumentChunkMapper chunkMapper;
    private final VectorStoreRepository vectorStore;
    private final EmbeddingClient embeddingClient;
    private final com.ragkb.document.mapper.DocumentMapper documentMapper;

    /** @param totalTokens 接口回报的用量,用来核对成本;不是本地估算 */
    public record VectorizeResult(long documentId, int chunkCount, int totalTokens) {
    }

    /**
     * 向量化一个文档的全部切片。
     *
     * @throws ApiException 文档没有切片(400)
     */
    public VectorizeResult vectorize(long documentId) {
        List<DocumentChunkEntity> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunkEntity>()
                        .eq(DocumentChunkEntity::getDocumentId, documentId)
                        .orderByAsc(DocumentChunkEntity::getChunkIndex));
        if (chunks.isEmpty()) {
            throw ApiException.badRequest("文档 " + documentId + " 还没有切片,请先入库(POST /{id}/ingest)");
        }

        List<String> contents = chunks.stream().map(DocumentChunkEntity::getContent).toList();
        EmbeddingClient.EmbeddingResult result = embeddingClient.embedDocuments(contents);

        // 顺序必须与入参一致:embedBatch 的契约是按 text_index 重排过
        List<ChunkVector> vectors = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            vectors.add(new ChunkVector(chunks.get(i).getId(), embeddingClient.model(), result.vectors().get(i)));
        }
        int saved = vectorStore.saveAll(vectors);
        // READY 只在"切片 + 向量"都齐了之后才置:之前 CHUNKED 表示"能搜关键词、搜不到语义",
        // 这个区别对使用者有意义 —— 界面上"已切片(未向量化)"和"可检索"不该是同一个状态
        documentMapper.updateStatusClearingReason(documentId, "READY", java.time.LocalDateTime.now());
        log.info("document vectorized: id={}, chunks={}, tokens={}", documentId, saved, result.totalTokens());
        return new VectorizeResult(documentId, saved, result.totalTokens());
    }
}
