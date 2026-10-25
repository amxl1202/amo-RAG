package com.ragkb.metrics;

/**
 * 线上问答的成本与延迟画像。
 *
 * <h2>为什么要和评估基线分开看</h2>
 * 评估集是固定的 59 条(含 10 条刻意问的文档外问题),它的拒答率 17% 只代表"那套题";
 * 线上的拒答率取决于用户实际问什么。两个数字放在一起才有意义:
 * <b>线上拒答率明显高于基线,通常说明用户问的问题超出了语料范围</b>,而不是检索坏了。
 * 所以这里的字段刻意与 {@code docs/eval-baseline.md} 的口径对齐(拒答率、引用数、两层延迟)。
 *
 * @param days                     统计窗口(天)
 * @param total                    总题数
 * @param answered                 正常作答数
 * @param refused                  拒答数
 * @param failed                   服务异常数
 * @param refusalRate              拒答率 = refused / total
 * @param failureRate              异常率 = failed / total
 * @param p50RetrievalMs           检索 P50
 * @param p95RetrievalMs           检索 P95
 * @param p50AnswerMs              生成+自检 P50
 * @param p95AnswerMs              生成+自检 P95
 * @param generationPromptTokens   生成输入 token
 * @param generationCompletionTokens 生成输出 token
 * @param checkPromptTokens        自检输入 token
 * @param checkCompletionTokens    自检输出 token
 * @param totalTokens              四次相加总量
 * @param avgCitations             平均引用条数
 * @param estimatedCostYuan        窗口内估算成本(元)
 * @param avgCostPerQuestionYuan   单题平均成本(元)
 */
public record MetricsSummary(
        int days,
        long total,
        long answered,
        long refused,
        long failed,
        double refusalRate,
        double failureRate,
        double p50RetrievalMs,
        double p95RetrievalMs,
        double p50AnswerMs,
        double p95AnswerMs,
        long generationPromptTokens,
        long generationCompletionTokens,
        long checkPromptTokens,
        long checkCompletionTokens,
        long totalTokens,
        double avgCitations,
        double estimatedCostYuan,
        double avgCostPerQuestionYuan) {

    /**
     * 把数据库的原始聚合值换算成对外口径。
     *
     * <p>在 Java 里算而不是在 SQL 里算,是因为这里全是<b>业务口径</b>
     * (什么算拒答率、成本怎么摊到单题),而口径应该能被单测钉住,
     * 而不是藏在一条 SQL 的表达式里 —— 后者改一个字段名就可能静默算错。
     */
    public static MetricsSummary from(MetricsRow row, MetricsProperties prices, int days) {
        long total = value(row.getTotal());
        long answered = value(row.getAnswered());
        long refused = value(row.getRefused());
        long failed = value(row.getFailed());
        long generationPrompt = value(row.getGenerationPromptTokens());
        long generationCompletion = value(row.getGenerationCompletionTokens());
        long checkPrompt = value(row.getCheckPromptTokens());
        long checkCompletion = value(row.getCheckCompletionTokens());

        long totalTokens = generationPrompt + generationCompletion + checkPrompt + checkCompletion;
        double cost = (generationPrompt + checkPrompt) / 1000.0 * prices.getInputPricePer1k()
                + (generationCompletion + checkCompletion) / 1000.0 * prices.getOutputPricePer1k();

        return new MetricsSummary(
                days,
                total, answered, refused, failed,
                ratio(refused, total),
                ratio(failed, total),
                round(row.getP50RetrievalMs()),
                round(row.getP95RetrievalMs()),
                round(row.getP50AnswerMs()),
                round(row.getP95AnswerMs()),
                generationPrompt, generationCompletion, checkPrompt, checkCompletion,
                totalTokens,
                round(row.getAvgCitations()),
                round(cost),
                total == 0 ? 0 : round(cost / total));
    }

    private static long value(Long raw) {
        return raw == null ? 0 : raw;
    }

    private static double ratio(long part, long total) {
        return total == 0 ? 0 : Math.round(part * 10000.0 / total) / 10000.0;
    }

    /** 保留四位小数,避免把 double 的尾巴原样吐给前端。 */
    private static double round(Double raw) {
        return raw == null ? 0 : Math.round(raw * 10000.0) / 10000.0;
    }
}
