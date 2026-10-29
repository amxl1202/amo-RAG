package com.ragkb.metrics.mapper;

import com.ragkb.metrics.MetricsRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 问答记录的汇总查询。
 *
 * <h2>为什么直接写 SQL 而不是拉到内存里算</h2>
 * P95 需要排序,记录一多就是"把整表读进 JVM 再排序"。PostgreSQL 的
 * {@code percentile_cont} 能在库里算完只回一行 —— 这也是个能把"指标计算放在哪一层"
 * 讲清楚的小例子。
 *
 * <h2>为什么用 count(*) FILTER 而不是多次查</h2>
 * 一次扫描出全部计数,避免同一时间段被重复扫三遍。
 *
 * <h2>为什么要 tenant_id 条件</h2>
 * 缺了它,{@code /api/metrics/summary} 会返回<b>全平台</b>的题量、拒答率与成本 ——
 * 任何登录用户都能据此推断别家租户的用量。聚合结果的泄漏不像明细那么扎眼,
 * 但一样是泄漏,而且更难在后知后觉时补救(没有明细可回溯是谁看了什么)。
 */
@Mapper
public interface MetricsMapper {

    @Select("""
            SELECT count(*)                                                       AS total,
                   count(*) FILTER (WHERE status = 'ANSWERED')                    AS answered,
                   count(*) FILTER (WHERE status = 'REFUSED')                     AS refused,
                   count(*) FILTER (WHERE status = 'FAILED')                      AS failed,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY retrieval_ms)      AS p50_retrieval_ms,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY retrieval_ms)     AS p95_retrieval_ms,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY answer_ms)         AS p50_answer_ms,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY answer_ms)        AS p95_answer_ms,
                   coalesce(sum(prompt_tokens), 0)                                AS generation_prompt_tokens,
                   coalesce(sum(completion_tokens), 0)                            AS generation_completion_tokens,
                   coalesce(sum(check_prompt_tokens), 0)                          AS check_prompt_tokens,
                   coalesce(sum(check_completion_tokens), 0)                      AS check_completion_tokens,
                   coalesce(avg(citation_count), 0)                               AS avg_citations
              FROM qa_records
             WHERE tenant_id = #{tenantId}
               AND created_at >= now() - make_interval(days => #{days})
            """)
    MetricsRow summarize(@Param("tenantId") long tenantId, @Param("days") int days);
}
