package com.ragkb.vector;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 向量表的真实读写。
 *
 * 这组测试连真库,因为 SQL 里的两个东西单元测试查不出来:
 * {@code <=>} 是"距离"还是"相似度"、以及 {@code ?::vector} 的类型转换能不能被 pgvector 接受。
 * 这两处写错都不会编译失败,只会让检索结果整体反过来或者直接抛类型错误。
 *
 * 库连不上时整类跳过(用 Assumptions),这样没有 Docker 的环境也能跑其余测试。
 */
class JdbcVectorStoreRepositoryTest {

    private static final String URL = "jdbc:postgresql://localhost:5433/rag_kb";

    private static JdbcTemplate connect() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(URL);
        dataSource.setUser("postgres");
        dataSource.setPassword("rag123456");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.queryForObject("SELECT 1", Integer.class);
        return jdbc;
    }

    /** 造一个 512 维稀疏向量:第 index 位为 1,其余为 0,便于算出确定的余弦关系。 */
    private static float[] unit(int index) {
        float[] v = new float[512];
        v[index] = 1f;
        return v;
    }

    private static float[] mixed() {
        float[] v = new float[512];
        v[0] = 1f;
        v[1] = 1f;
        return v;
    }

    @Test
    @DisplayName("写入后能按余弦相似度取回,且 <=> 的语义没有搞反")
    void savesAndSearchesByCosineSimilarity() {
        JdbcTemplate jdbc;
        try {
            jdbc = connect();
        } catch (RuntimeException e) {
            Assumptions.abort("本地 PostgreSQL 不可用,跳过集成测试: " + e.getMessage());
            return;
        }

        long documentId = 0;
        try {
            documentId = insertDocument(jdbc);
            long first = insertChunk(jdbc, documentId, 0, "第一段:讲切片策略");
            long second = insertChunk(jdbc, documentId, 1, "第二段:讲向量索引");
            long third = insertChunk(jdbc, documentId, 2, "第三段:讲融合排序");

            JdbcVectorStoreRepository repository = new JdbcVectorStoreRepository(jdbc, "text-embedding-v3");
            int saved = repository.saveAll(List.of(
                    new ChunkVector(first, "text-embedding-v3", unit(0)),
                    new ChunkVector(second, "text-embedding-v3", unit(1)),
                    new ChunkVector(third, "text-embedding-v3", mixed())));
            assertThat(saved).isEqualTo(3);

            List<ScoredChunk> hits = repository.search(unit(0), 3, 1L);

            assertThat(hits).hasSize(3);
            assertThat(hits.get(0).chunkId())
                    .as("与查询向量完全一致的那条应排第一 —— 若 <=> 被当成相似度用,这里会反过来")
                    .isEqualTo(first);
            assertThat(hits.get(0).score()).isCloseTo(1.0, within(1e-6));
            assertThat(hits.get(1).chunkId()).isEqualTo(third);
            assertThat(hits.get(1).score()).isCloseTo(Math.sqrt(0.5), within(1e-6));
            assertThat(hits.get(0).content()).isEqualTo("第一段:讲切片策略");
            assertThat(hits.get(0).sectionPath()).isEqualTo("第一章");
        } finally {
            cleanup(jdbc, documentId);
        }
    }

    @Test
    @DisplayName("同一个切片重复写入是覆盖,且检索只在当前模型内进行")
    void upsertDoesNotDuplicateAndSearchIsModelScoped() {
        JdbcTemplate jdbc;
        try {
            jdbc = connect();
        } catch (RuntimeException e) {
            Assumptions.abort("本地 PostgreSQL 不可用,跳过集成测试");
            return;
        }

        long documentId = 0;
        try {
            documentId = insertDocument(jdbc);
            long chunkId = insertChunk(jdbc, documentId, 0, "会被写入两次的切片");
            JdbcVectorStoreRepository repoA = new JdbcVectorStoreRepository(jdbc, "model-a");
            JdbcVectorStoreRepository repoB = new JdbcVectorStoreRepository(jdbc, "model-b");

            repoA.saveAll(List.of(new ChunkVector(chunkId, "model-a", unit(0))));
            repoB.saveAll(List.of(new ChunkVector(chunkId, "model-b", unit(2))));

            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM chunk_embeddings WHERE chunk_id = ?", Integer.class, chunkId);
            assertThat(rows).as("主键冲突应覆盖而不是新增一行").isEqualTo(1);

            // 换模型期间旧行还在,但检索必须只看当前模型 —— 否则余弦相似度跨模型比较会让排序失真
            assertThat(repoA.search(unit(0), 5, 1L)).as("model-a 已无数据,应查不到").isEmpty();
            assertThat(repoB.search(unit(2), 1, 1L).get(0).chunkId()).isEqualTo(chunkId);
            assertThat(repoA.count()).isZero();
            assertThat(repoB.count()).isEqualTo(1);

            assertThatThrownBy(() -> repoA.saveAll(List.of(new ChunkVector(chunkId, "model-c", unit(0)))))
                    .as("写入与仓库模型不一致的向量应当被拒绝")
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            cleanup(jdbc, documentId);
        }
    }

    @Test
    @DisplayName("限额为 0 或向量为空时不查库")
    void emptyArgumentsReturnEmpty() {
        JdbcTemplate jdbc;
        try {
            jdbc = connect();
        } catch (RuntimeException e) {
            Assumptions.abort("本地 PostgreSQL 不可用,跳过集成测试");
            return;
        }
        JdbcVectorStoreRepository repository = new JdbcVectorStoreRepository(jdbc, "text-embedding-v3");

        assertThat(repository.search(unit(0), 0, 1L)).isEmpty();
        assertThat(repository.search(new float[0], 5, 1L)).isEmpty();
    }

    private static long insertDocument(JdbcTemplate jdbc) {
        String sha = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO documents (original_name, sha256, file_size, content_type, storage_path, status)
                VALUES (?, ?, ?, ?, ?, 'UPLOADED')
                """, "向量测试文档.txt", sha.substring(0, 64), 128, "text/plain", "test/" + sha.substring(0, 8));
        return jdbc.queryForObject("SELECT id FROM documents WHERE sha256 = ?", Long.class, sha.substring(0, 64));
    }

    private static long insertChunk(JdbcTemplate jdbc, long documentId, int index, String content) {
        jdbc.update("""
                INSERT INTO document_chunks
                    (document_id, chunk_index, section_path, heading_level, content, token_count, char_start, char_end)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, documentId, index, "第一章", 1, content, 10, index * 100, index * 100 + content.length());
        return jdbc.queryForObject(
                "SELECT id FROM document_chunks WHERE document_id = ? AND chunk_index = ?",
                Long.class, documentId, index);
    }

    private static void cleanup(JdbcTemplate jdbc, long documentId) {
        if (documentId > 0) {
            // 切片与向量都有 ON DELETE CASCADE,删文档即可连带清理
            jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        }
    }
}
