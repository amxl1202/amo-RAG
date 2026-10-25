package com.ragkb.metrics;

import lombok.Data;

/**
 * 聚合结果(数据库直接算出来的原始值)。
 *
 * <p>用可变 POJO 而不是 record:MyBatis 按列名回填,record 需要构造器映射,
 * 而这张表有十几个别名列 —— 一次 @ConstructorArgs 写错就会静默得到 null。
 *
 * <p>具体原型的转换(成本、比率)放在 {@link MetricsSummary#from} 里做,
 * 因为那是业务口径,应该在可单测的地方算,而不是塞进 SQL。
 */
@Data
public class MetricsRow {

    private Long total;

    private Long answered;

    private Long refused;

    private Long failed;

    private Double p50RetrievalMs;

    private Double p95RetrievalMs;

    private Double p50AnswerMs;

    private Double p95AnswerMs;

    /** 生成调用的输入 token。成本按输入/输出分别计价,所以要分开留。 */
    private Long generationPromptTokens;

    private Long generationCompletionTokens;

    /** 自检调用的用量:漏掉它,单题成本会少报近一半。 */
    private Long checkPromptTokens;

    private Long checkCompletionTokens;

    private Double avgCitations;
}
