package com.ragkb.vector;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * pgvector 的读写实现。
 *
 * 两个关键取舍:
 * <ol>
 *   <li><b>向量以字符串形式传参并显式 {@code ::vector} 转换</b>。JDBC 没有 float[] 到 pgvector
 *       的原生映射,拼成 {@code [0.1,0.2,…]} 再让服务端转换是最稳的做法;用 PreparedStatement
 *       参数而不是字符串拼接,避免注入与转义问题。</li>
 *   <li><b>相似度用 {@code 1 - 余弦距离}</b>。pgvector 的 {@code <=>} 返回的是距离(越小越近),
 *       直接拿去当分数会导致"分越高越不相关",排序逻辑反过来 —— 这是接入向量检索时最常见的错。</li>
 * </ol>
 */
public class JdbcVectorStoreRepository implements VectorStoreRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO chunk_embeddings (chunk_id, model, dim, embedding)
            VALUES (?, ?, ?, ?::vector)
            ON CONFLICT (chunk_id) DO UPDATE
            SET model = EXCLUDED.model,
                dim = EXCLUDED.dim,
                embedding = EXCLUDED.embedding,
                created_at = now()
            """;

    private static final String SEARCH_SQL = """
            SELECT c.id            AS chunk_id,
                   c.document_id   AS document_id,
                   c.content       AS content,
                   c.section_path  AS section_path,
                   c.char_start    AS char_start,
                   c.char_end      AS char_end,
                   c.page_start    AS page_start,
                   c.page_end      AS page_end,
                   1 - (e.embedding <=> ?::vector) AS score
            FROM chunk_embeddings e
            JOIN document_chunks c ON c.id = e.chunk_id
            WHERE e.model = ?
            ORDER BY e.embedding <=> ?::vector
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final String model;

    public JdbcVectorStoreRepository(JdbcTemplate jdbcTemplate, String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空:检索必须限定模型,否则新旧向量会混在一起排序");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.model = model;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int saveAll(List<ChunkVector> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(vectors.size());
        for (ChunkVector vector : vectors) {
            if (!model.equals(vector.model())) {
                throw new IllegalArgumentException("向量模型与本仓库不一致: 仓库=" + model
                        + ", 待写入=" + vector.model() + "(chunkId=" + vector.chunkId() + ")");
            }
            args.add(new Object[]{
                    vector.chunkId(), vector.model(), vector.dimension(), toLiteral(vector.embedding())});
        }
        int[] affected = jdbcTemplate.batchUpdate(UPSERT_SQL, args);
        int total = 0;
        for (int count : affected) {
            // 批量执行的返回值可能是 SUCCESS_NO_INFO(-2),这时按 1 计,否则条数会算成负数
            total += count > 0 ? count : 1;
        }
        return total;
    }

    @Override
    public List<ScoredChunk> search(float[] queryVector, int limit) {
        if (queryVector == null || queryVector.length == 0 || limit <= 0) {
            return List.of();
        }
        String literal = toLiteral(queryVector);
        List<ScoredChunk> results = jdbcTemplate.query(SEARCH_SQL, (rs, rowNum) -> new ScoredChunk(
                        rs.getLong("chunk_id"),
                        rs.getLong("document_id"),
                        rs.getString("content"),
                        rs.getString("section_path"),
                        rs.getInt("char_start"),
                        rs.getInt("char_end"),
                        (Integer) rs.getObject("page_start"),
                        (Integer) rs.getObject("page_end"),
                        rs.getDouble("score")),
                literal, model, literal, limit);
        return results == null ? List.of() : List.copyOf(results);
    }

    @Override
    public long count() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunk_embeddings WHERE model = ?", Long.class, model);
        return value == null ? 0 : value;
    }

    /** pgvector 的字面量格式:方括号包裹、逗号分隔。用 Locale.ROOT 避免某些区域设置下小数点变成逗号。 */
    static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.7f", vector[i]));
        }
        return sb.append(']').toString();
    }
}
