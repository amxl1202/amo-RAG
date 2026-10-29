package com.ragkb.retrieval;

import com.ragkb.vector.ScoredChunk;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 字符二元组(bigram)关键词通道。
 *
 * <h2>它和 pg_trgm 的区别在哪</h2>
 * {@code pg_trgm} 把文本切成<b>三元组</b>并做相似度比较,阈值默认 0.3 —— 对中文来说,
 * 三个字往往已经跨过了一个词,短问句里能被匹配上的三元组很少,相似度上不去。
 * bigram 只要求两个字连续出现,对"问法与原文措辞不同但共享关键词"的中文检索更宽松。
 *
 * <h2>为什么不用 zhparser / pg_jieba</h2>
 * 那两个是 PostgreSQL 扩展,要在容器里编译安装。当前环境装不上(离线、无构建工具),
 * 而 bigram 不需要词典、不需要扩展、行为完全可预测 —— 作为"无分词器时的中文基线"
 * 反而更合适:它把"没有中文分词"这件事的代价量化出来,而不是拿一个装不上的方案当结论。
 *
 * <h2>实测结论:不采用(2026-10-29)</h2>
 * 在 59 条评估集上,它比现有的 {@code pg_trgm} 通道<b>差 10 个点</b>:
 * recall@5 0.8776 对 0.9796,MRR 0.8226 对 0.8754,还有 1 题进不了前 20。
 * 原因是二元组太宽松("重叠量"切出的"重叠"在语料里到处都是),噪声候选把正确片段挤了下去。
 * <b>保留这个类只为让那次对比可复现</b>,生产链路没有接它 —— 别看到它就以为该换通道。
 *
 * <h2>打分方式</h2>
 * 命中二元组的<b>种类数</b>(去重),与关键词通道现有的"命中词数"口径一致 ——
 * 换成命中次数会让长文本天然占优,和"相关性"反而无关。
 */
public class JdbcBigramRetriever implements ChunkSearcher {

    /** 最多取多少个二元组参与查询:防止长问题拼出上百个条件。 */
    private static final int MAX_TERMS = 16;

    private final JdbcTemplate jdbcTemplate;

    public JdbcBigramRetriever(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ScoredChunk> search(String question, int limit, long tenantId) {
        List<String> terms = terms(question);
        if (terms.isEmpty() || limit <= 0) {
            return List.of();
        }
        StringBuilder score = new StringBuilder();
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                score.append(" + ");
                where.append(" OR ");
            }
            score.append("CASE WHEN position(? in c.content) > 0 THEN 1 ELSE 0 END");
            where.append("position(? in c.content) > 0");
        }
        String sql = """
                SELECT c.id            AS chunk_id,
                       c.document_id   AS document_id,
                       c.content       AS content,
                       c.section_path  AS section_path,
                       c.char_start    AS char_start,
                       c.char_end      AS char_end,
                       c.page_start    AS page_start,
                       c.page_end      AS page_end,
                       (%s)            AS score
                  FROM document_chunks c
                  JOIN documents d ON d.id = c.document_id
                 WHERE d.tenant_id = ?
                   AND (%s)
                 ORDER BY score DESC, c.id
                 LIMIT ?
                """.formatted(score, where);

        List<Object> args = new ArrayList<>();
        args.addAll(terms);
        args.add(tenantId);
        args.addAll(terms);
        args.add(limit);

        return jdbcTemplate.query(sql, (rs, rowNum) -> new ScoredChunk(
                rs.getLong("chunk_id"),
                rs.getLong("document_id"),
                rs.getString("content"),
                rs.getString("section_path"),
                rs.getInt("char_start"),
                rs.getInt("char_end"),
                (Integer) rs.getObject("page_start"),
                (Integer) rs.getObject("page_end"),
                rs.getDouble("score")), args.toArray());
    }

    /**
     * 把问题切成检索词:汉字取相邻两字的二元组,英文与数字按整词。
     *
     * <p>为什么要分两条路:英文单词的二元组("rr"、"rf")会把 RRF 这类术语拆坏,
     * 而且英文本来就有空格分隔,不需要 n-gram。中文没有空格,二元组是最小可用的近似。
     */
    static List<String> terms(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder cjkRun = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (char c : question.toCharArray()) {
            if (isCjk(c)) {
                flushWord(word, terms);
                cjkRun.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjkRun, terms);
                word.append(c);
            } else {
                flushCjk(cjkRun, terms);
                flushWord(word, terms);
            }
        }
        flushCjk(cjkRun, terms);
        flushWord(word, terms);

        List<String> result = new ArrayList<>(terms);
        return result.size() > MAX_TERMS ? result.subList(0, MAX_TERMS) : result;
    }

    private static void flushCjk(StringBuilder run, Set<String> terms) {
        if (run.length() == 1) {
            // 单个汉字也保留:像"熵""栈"这类单字词在技术笔记里确实存在
            terms.add(run.toString());
        } else {
            for (int i = 0; i + 1 < run.length(); i++) {
                terms.add(run.substring(i, i + 2));
            }
        }
        run.setLength(0);
    }

    private static void flushWord(StringBuilder word, Set<String> terms) {
        if (word.length() >= 2) {
            terms.add(word.toString().toLowerCase(java.util.Locale.ROOT));
        }
        word.setLength(0);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }
}
