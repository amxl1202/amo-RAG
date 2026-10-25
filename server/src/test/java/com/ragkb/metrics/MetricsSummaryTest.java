package com.ragkb.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标口径换算。
 *
 * <h2>为什么这组用例值得写</h2>
 * 这些数字是要拿给人看的("平均单题成本 0.002 元"),而算错的时候不会报错 ——
 * 只会安静地少报一半。三个最容易错的地方:分母为 0、只算生成不算自检、
 * 输入输出按同一个价算。每条用例钉一个。
 */
class MetricsSummaryTest {

    private static MetricsProperties prices() {
        MetricsProperties properties = new MetricsProperties();
        properties.setInputPricePer1k(1.0);
        properties.setOutputPricePer1k(2.0);
        return properties;
    }

    private static MetricsRow row() {
        MetricsRow row = new MetricsRow();
        row.setTotal(10L);
        row.setAnswered(8L);
        row.setRefused(1L);
        row.setFailed(1L);
        row.setP50RetrievalMs(480.0);
        row.setP95RetrievalMs(720.0);
        row.setP50AnswerMs(2400.0);
        row.setP95AnswerMs(3900.0);
        row.setGenerationPromptTokens(1000L);
        row.setGenerationCompletionTokens(200L);
        row.setCheckPromptTokens(800L);
        row.setCheckCompletionTokens(50L);
        row.setAvgCitations(2.5);
        return row;
    }

    @Test
    @DisplayName("拒答率与异常率各算各的 —— 混在一起会把故障读成正常拒答")
    void ratesAreSeparate() {
        MetricsSummary summary = MetricsSummary.from(row(), prices(), 7);

        assertThat(summary.refusalRate()).isEqualTo(0.1);
        assertThat(summary.failureRate()).isEqualTo(0.1);
        assertThat(summary.total()).isEqualTo(10);
    }

    @Test
    @DisplayName("成本按输入/输出分别计价,且必须包含自检那一次调用")
    void costCountsBothCallsAndBothDirections() {
        MetricsSummary summary = MetricsSummary.from(row(), prices(), 7);

        // 输入 (1000+800) 千分之一 × 1 元 + 输出 (200+50) 千分之一 × 2 元
        assertThat(summary.estimatedCostYuan()).isEqualTo(2.3);
        assertThat(summary.totalTokens()).isEqualTo(2050);
        assertThat(summary.avgCostPerQuestionYuan()).isEqualTo(0.23);
    }

    @Test
    @DisplayName("只算生成会少报:自检 token 必须计进总量")
    void selfCheckTokensAreCounted() {
        MetricsSummary summary = MetricsSummary.from(row(), prices(), 7);

        long generationOnly = summary.generationPromptTokens() + summary.generationCompletionTokens();
        assertThat(summary.totalTokens()).isGreaterThan(generationOnly);
        assertThat(summary.checkPromptTokens() + summary.checkCompletionTokens()).isEqualTo(850);
    }

    @Test
    @DisplayName("没有流量时分母为 0,不能抛除零异常,也不该给出 NaN")
    void emptyWindowIsSafe() {
        MetricsSummary summary = MetricsSummary.from(new MetricsRow(), prices(), 7);

        assertThat(summary.total()).isZero();
        assertThat(summary.refusalRate()).isZero();
        assertThat(summary.avgCostPerQuestionYuan()).isZero();
        assertThat(summary.totalTokens()).isZero();
    }

    @Test
    @DisplayName("数据库返回 null(无匹配行)时按空窗口处理")
    void nullAggregatesAreTreatedAsZero() {
        MetricsRow row = new MetricsRow();
        row.setTotal(null);
        row.setP50RetrievalMs(null);

        MetricsSummary summary = MetricsSummary.from(row, prices(), 30);

        assertThat(summary.total()).isZero();
        assertThat(summary.p50RetrievalMs()).isZero();
        assertThat(summary.days()).isEqualTo(30);
    }
}
