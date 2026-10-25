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
 * 等于召回恒为 0 —— 不是配置问题,是分词器不支持。
 *
 * <h2>为什么先把问题抽成词</h2>
 * 见 {@link QueryTerms}:整句问题在语料里不存在,直接当子串匹配必然 0 命中。
 * 抽成 2-gram 与英文整词之后,匹配的是"概念"而不是"句子"。
 *
 * <h2>两种模式的区别只在候选集,不在打分</h2>
 * 两模式用同一套打分(命中词出现次数),区别是:
 * <ul>
 *   <li>{@link Mode#ILIKE}:候选 = 正则命中(任一抽出词出现);</li>
 *   <li>{@link Mode#TRGM}:候选 = 正则命中 <b>∪</b> 三元组相似命中。
 *       多出来的那部分就是"模糊召回" —— 字面不完全一致但三元组重合度高的片段。</li>
 * </ul>
 * 刻意让打分保持一致,这样对比出来的是<b>候选集放宽带来的净效果</b>(召回涨了还是噪声多了),
 * 而不是"两种打分哪个碰巧更好"。中文短词的三元组相似度很粗
 * (实测 {@code similarity('合规审计要求','季度审计')} = 0,因为三元组是连续三字窗口),
 * 所以模糊这一步能不能带来收益,只能靠数据说话。
 *
 * <h2>为什么排序都带 {@code c.id}</h2>
 * 同分时若没有确定性次序,同一个查询两次跑的顺序可能不同,评估结果就不可复现。
 */
public class JdbcKeywordRetriever implements KeywordRetriever {

    public enum Mode {
        /** 候选只来自字串命中。 */
        ILIKE,
        /** 候选 = 字串命中 ∪ 三元组模糊命中。 */
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

    /**
     * 打分:命中的不同词按<b>词长加权</b>求和。
     *
     * 为什么不是词频(出现次数):没有 IDF 的情况下,词频几乎必然奖励"长且反复用常见词"的片段,
     * 而不是"覆盖了问题概念"的片段。这是检索里的常识判断,不是本语料测出来的结果 ——
     * 评估集只有 8 条正向用例,五种配置的 recall@5 / MRR 完全相同(见 docs/keyword-comparison.md),
     * 现在还给不出"换打分带来多少提升"的数字。
     *
     * 为什么按词长加权:词越长越具体。HNSW(4) / IVFFlat(7) 这类型号与专名,
     * 应当压过"的是""可以"这种两字虚词 —— 这正是关键词通道存在的理由(精确串、型号、错误码)。
     * 它仍然不是 IDF;语义匹配交给向量通道,不要把这里做成半个检索引擎。
     */
    private static final String SCORE_EXPR = """
                   (SELECT coalesce(sum(length(matched)), 0) FROM (
                       SELECT DISTINCT unnest(regexp_matches(c.content, ?, 'g')) AS matched
                   ) distinct_terms) AS score
            """;

    private static final String ILIKE_SQL = SELECT_COLUMNS + SCORE_EXPR + """
            FROM document_chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE c.content ~* ?
              AND d.tenant_id = ?
            ORDER BY score DESC, c.id
            LIMIT ?
            """;

    private static final String TRGM_SQL = SELECT_COLUMNS + SCORE_EXPR + """
            FROM document_chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE (c.content ~* ? OR c.content % ?)
              AND d.tenant_id = ?
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
    public List<ScoredChunk> search(String query, int limit, long tenantId) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        List<String> terms = QueryTerms.extract(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        String pattern = QueryTerms.toRegex(terms);
        List<ScoredChunk> rows = mode == Mode.ILIKE
                ? jdbcTemplate.query(ILIKE_SQL, this::mapRow, pattern, pattern, limit)
                : jdbcTemplate.query(TRGM_SQL, this::mapRow, pattern, pattern, query, limit);
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
