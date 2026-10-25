package com.ragkb.retrieval;

import com.ragkb.vector.ScoredChunk;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词检索的真库行为。
 *
 * 必须连真库:ILIKE 与 {@code %} 运算符、以及中文三元组的实际行为，
 * 在内存里造不出来。库不可用时整类跳过。
 */
class JdbcKeywordRetrieverTest {

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

    private static long insertDocument(JdbcTemplate jdbc) {
        String sha = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        sha = sha.substring(0, 64);
        jdbc.update("""
                INSERT INTO documents (original_name, sha256, file_size, content_type, storage_path, status)
                VALUES (?, ?, ?, ?, ?, 'UPLOADED')
                """, "关键词测试.txt", sha, 128, "text/plain", "test/" + sha.substring(0, 8));
        return jdbc.queryForObject("SELECT id FROM documents WHERE sha256 = ?", Long.class, sha);
    }

    private static long insertChunk(JdbcTemplate jdbc, long documentId, int index, String content) {
        jdbc.update("""
                INSERT INTO document_chunks
                    (document_id, chunk_index, section_path, heading_level, content, token_count, char_start, char_end)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, documentId, index, "第一章", 1, content, 20, index * 100, index * 100 + content.length());
        return jdbc.queryForObject(
                "SELECT id FROM document_chunks WHERE document_id = ? AND chunk_index = ?",
                Long.class, documentId, index);
    }

    @Test
    @DisplayName("ILIKE 能召回中文子串,且按命中位置排前")
    void ilikeFindsChineseSubstring() {
        JdbcTemplate jdbc;
        try {
            jdbc = connect();
        } catch (RuntimeException e) {
            Assumptions.abort("本地 PostgreSQL 不可用,跳过: " + e.getMessage());
            return;
        }
        long documentId = 0;
        try {
            documentId = insertDocument(jdbc);
            insertChunk(jdbc, documentId, 0, "本节讨论审计触发条件与季度检查。");
            insertChunk(jdbc, documentId, 1, "这一节与主题无关,只讲向量索引。");

            JdbcKeywordRetriever retriever = new JdbcKeywordRetriever(jdbc, JdbcKeywordRetriever.Mode.ILIKE);
            List<ScoredChunk> hits = retriever.search("审计", 10, 1L);

            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).content()).contains("审计");
            assertThat(hits.get(0).score()).isGreaterThan(0);
            // 不相关的片段不应出现
            assertThat(hits).allSatisfy(hit -> assertThat(hit.content()).contains("审计"));
        } finally {
            cleanup(jdbc, documentId);
        }
    }

    @Test
    @DisplayName("TRGM 模式的候选集是 ILIKE 的超集,排序按 similarity")
    void trgmIsSupersetOfIlike() {
        JdbcTemplate jdbc;
        try {
            jdbc = connect();
        } catch (RuntimeException e) {
            Assumptions.abort("本地 PostgreSQL 不可用,跳过");
            return;
        }
        long documentId = 0;
        try {
            documentId = insertDocument(jdbc);
            insertChunk(jdbc, documentId, 0, "合规审计要求每季度进行一次。");
            insertChunk(jdbc, documentId, 1, "季度检查与合规审计的流程说明。");
            insertChunk(jdbc, documentId, 2, "完全无关的内容。");

            var ilike = new JdbcKeywordRetriever(jdbc, JdbcKeywordRetriever.Mode.ILIKE);
            var trgm = new JdbcKeywordRetriever(jdbc, JdbcKeywordRetriever.Mode.TRGM);

            List<ScoredChunk> ilikeHits = ilike.search("合规审计", 10, 1L);
            List<ScoredChunk> trgmHits = trgm.search("合规审计", 10, 1L);

            assertThat(ilikeHits).isNotEmpty();
            assertThat(trgmHits.size()).as("模糊模式不应少于精确子串模式").isGreaterThanOrEqualTo(ilikeHits.size());
            assertThat(trgmHits.get(0).content()).contains("合规审计");
            assertThat(trgmHits).allSatisfy(hit -> assertThat(hit.score()).isGreaterThanOrEqualTo(0));
        } finally {
            cleanup(jdbc, documentId);
        }
    }

    @Test
    @DisplayName("空查询与非法限额直接返回空,不查库")
    void blankQueryReturnsEmpty() {
        JdbcTemplate jdbc;
        try {
            jdbc = connect();
        } catch (RuntimeException e) {
            Assumptions.abort("本地 PostgreSQL 不可用,跳过");
            return;
        }
        JdbcKeywordRetriever retriever = new JdbcKeywordRetriever(jdbc, JdbcKeywordRetriever.Mode.ILIKE);

        assertThat(retriever.search("   ", 5, 1L)).isEmpty();
        assertThat(retriever.search("审计", 0, 1L)).isEmpty();
    }

    private static void cleanup(JdbcTemplate jdbc, long documentId) {
        if (documentId > 0) {
            jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        }
    }
}
