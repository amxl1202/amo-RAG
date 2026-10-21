package com.ragkb.retrieval;

import com.ragkb.vector.ScoredChunk;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 基于 PostgreSQL 的关键词检索,提供两种模式用于对比实验。
 *
 * <h2>为什么不用 tsvector</h2>
 * PG 默认分词器不切中文,实测 {@code to_tsvector('simple','合规审计要求每季度进行一次')}
 * 整句只得到一个词项,搜「审计」返回 false(英文对照能命中)。所以裸全文索引在这套语料上
 * 等于召回恒为 0 —— 这不是配置问题,是分词器不支持。
 *
 * <h2>两种模式的区别</h2>
 * <ul>
 *   <li>{@link Mode#ILIKE}:纯子串匹配。召回由"字面出现"决定,排序用"命中位置越靠前越相关"
 *       这个朴素代理。优点是绝不会因为分词而漏,缺点是对同义改写完全没有办法。</li>
 *   <li>{@link Mode#TRGM}:子串匹配 ∪ 三元组相似匹配,排序用 {@code similarity()}。
 *       多出来的那部分是"模糊命中"—— 字面不完全一致但三元组重合度高的片段。
 *       它能把 ILIKE 漏掉的近义表达捞回来,代价是引入噪声,且中文短词的相似度打分很粗
 *       (实测 {@code similarity('合规审计要求','季度审计')} = 0,因为三元组是连续三字窗口)。</li>
 * </ul>
 * 哪个更好不靠推理,靠同一评估集上的召回率对比(见 scripts/eval-compare.sh)。
 *
 * <h2>为什么排序里都带 {@code c.id}</h2>
 * 同分时若不加确定性次序,同一个查询两次跑出来的顺序可能不同,评估结果就不可复现。
 */
public class JdbcKeywordRetriever implements KeywordRetriever {

    public enum Mode {
        /** 纯子串匹配,排序按命中位置。 */
        ILIKE,
        /** 子串 ∪ 三元组模糊匹配,排序按 similarity。 */
        TRGM
    }

    private static final String SELECT_COLUMNS = """
            SELECT c.id            AS chunk_id,
                   c.document_id   AS document_id,
                   c.content       AS content,
                   c.section_path  AS section_path,
                   c.char_start    AS char_start,
                   c.char_end      AS char_end,
                   c.page_start    AS page_start,
                   c.page_end      AS page_end,
            """;

    private static final String ILIKE_SQL = SELECT_COLUMNS + """
                   1.0 / (1 + strpos(lower(c.content), lower(?))) AS score
            FROM document_chunks c
            WHERE c.content ILIKE '%' || ? || '%'
            ORDER BY score DESC, c.id
            LIMIT ?
            """;

    private static final String TRGM_SQL = SELECT_COLUMNS + """
                   similarity(c.content, ?) AS score
            FROM document_chunks c
            WHERE c.content ILIKE '%' || ? || '%' OR c.content % ?
            ORDER BY score DESC, c.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Mode mode;

    public JdbcKeywordRetriever(JdbcTemplate jdbcTemplate, Mode mode) {
        this.jdbcTemplate = jdbcTemplate;
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public List<ScoredChunk> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        String trimmed = query.trim();
        List<ScoredChunk> rows = mode == Mode.ILIKE
                ? jdbcTemplate.query(ILIKE_SQL, this::mapRow, trimmed, trimmed, limit)
                : jdbcTemplate.query(TRGM_SQL, this::mapRow, trimmed, trimmed, trimmed, limit);
        return rows == null ? List.of() : List.copyOf(rows);
    }

    private ScoredChunk mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ScoredChunk(
                rs.getLong("chunk_id"),
                rs.getLong("document_id"),
                rs.getString("content"),
                rs.getString("section_path"),
                rs.getInt("char_start"),
                rs.getInt("char_end"),
                (Integer) rs.getObject("page_start"),
                (Integer) rs.getObject("page_end"),
                rs.getDouble("score"));
    }
}
