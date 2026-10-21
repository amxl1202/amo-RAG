package com.ragkb.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 指标计算的正确性。
 *
 * 断言用"构造已知名次的检索结果 → 算预期值"的方式,而不是跑真实检索后看数字像不像。
 * 因为指标代码一旦算错,真实数据上只会给出一个"看起来合理"的错数,
 * 而你不会有机会拿手算结果去对照。
 */
class EvalRunnerTest {

    private static EvalCase factoid(String id, String question, String... keywords) {
        return new EvalCase(id, question, List.of(keywords), EvalCaseType.FACTOID, false);
    }

    private static EvalCase outOfScope(String id, String question) {
        return new EvalCase(id, question, List.of(), EvalCaseType.OUT_OF_SCOPE, true);
    }

    private static EvalDataset dataset(EvalCase... cases) {
        return new EvalDataset(List.of(cases));
    }

    /** 造一个"第 rank 位是命中片段,其余是无关片段"的检索器。rank 从 1 开始;0 表示全不命中。 */
    private static EvalRunner.Retriever retrieverWithHitAt(int rank, String keywords) {
        return (question, limit) -> {
            java.util.List<String> result = new java.util.ArrayList<>();
            for (int i = 1; i <= limit; i++) {
                result.add(i == rank ? "无关前言 " + keywords + " 无关后缀" : "无关内容 " + i);
            }
            return result;
        };
    }

    @Test
    @DisplayName("命中第 1 位:两个 recall 都是 1,MRR 也是 1")
    void hitAtFirstRank() {
        EvalDataset set = dataset(factoid("c01", "问题", "断点"));
        EvalReport report = new EvalRunner().run(set, retrieverWithHitAt(1, "断点"), "fake");

        assertThat(report.retrieval().recallAt5()).isEqualTo(1.0);
        assertThat(report.retrieval().recallAt20()).isEqualTo(1.0);
        assertThat(report.retrieval().mrr()).isEqualTo(1.0);
        assertThat(report.failedCases()).isEmpty();
    }

    @Test
    @DisplayName("命中第 6 位:recall@5 掉到 0 而 recall@20 仍是 1 —— 这正是两个 k 值的用途")
    void hitBeyondNearWindow() {
        EvalDataset set = dataset(factoid("c01", "问题", "断点"));
        EvalReport report = new EvalRunner().run(set, retrieverWithHitAt(6, "断点"), "fake");

        assertThat(report.retrieval().recallAt5()).isZero();
        assertThat(report.retrieval().recallAt20()).isEqualTo(1.0);
        assertThat(report.retrieval().mrr()).isCloseTo(1.0 / 6, within(1e-9));
    }

    @Test
    @DisplayName("全不命中:三个指标都是 0,且失败明细里能看出是哪条用例")
    void nothingHit() {
        EvalDataset set = dataset(factoid("c01", "问题一", "断点"), factoid("c02", "问题二", "训练"));
        EvalReport report = new EvalRunner().run(set, (q, k) -> List.of("全是无关内容"), "fake");

        assertThat(report.retrieval().recallAt5()).isZero();
        assertThat(report.retrieval().recallAt20()).isZero();
        assertThat(report.retrieval().mrr()).isZero();
        assertThat(report.failedCases()).hasSize(2);
        assertThat(report.failedCases()).extracting(EvalReport.FailedCase::id)
                .containsExactly("c01", "c02");
    }

    @Test
    @DisplayName("部分命中:分母只算正向用例,比例按用例数算")
    void partialHitKeepsDenominatorRight() {
        EvalDataset set = dataset(factoid("c01", "问题一", "断点"), factoid("c02", "问题二", "训练"));
        // 只有含"断点"的片段算命中,问题二检索不到
        EvalRunner.Retriever retriever = (q, k) -> List.of("无关", "这里有断点", "无关");

        EvalReport report = new EvalRunner().run(set, retriever, "fake");

        assertThat(report.retrieval().recallAt5()).isEqualTo(0.5);
        assertThat(report.retrieval().mrr()).isCloseTo(0.5 / 2, within(1e-9));
        assertThat(report.failedCases()).extracting(EvalReport.FailedCase::id).containsExactly("c02");
    }

    @Test
    @DisplayName("文档外负例不进召回率的分母 —— 它们本来就没有目标片段")
    void refusalCasesAreExcludedFromRecall() {
        EvalDataset set = dataset(
                factoid("c01", "问题", "断点"),
                outOfScope("c09", "服务器型号"),
                outOfScope("c10", "联系邮箱"));
        EvalReport report = new EvalRunner().run(set, retrieverWithHitAt(1, "断点"), "fake");

        assertThat(report.caseCount()).isEqualTo(3);
        assertThat(report.evaluableCases()).isEqualTo(1);
        assertThat(report.retrieval().recallAt5()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("单条检索抛异常时记为失败并留原因,不能让整批跑不动")
    void retrieverExceptionIsRecordedNotSwallowed() {
        EvalDataset set = dataset(factoid("c01", "问题", "断点"));
        EvalRunner.Retriever broken = (q, k) -> {
            throw new IllegalStateException("向量库连接超时");
        };

        EvalReport report = new EvalRunner().run(set, broken, "broken");

        assertThat(report.retrieval().recallAt5()).isZero();
        assertThat(report.failedCases()).hasSize(1);
        assertThat(report.failedCases().get(0).reason()).contains("检索异常", "向量库连接超时");
    }

    @Test
    @DisplayName("生成层四项此时为 null 而不是 0 —— null 表示算不出,0 表示测了全错")
    void generationMetricsAreNullUntilWired() {
        EvalDataset set = dataset(factoid("c01", "问题", "断点"));
        EvalReport report = new EvalRunner().run(set, retrieverWithHitAt(1, "断点"), "fake");

        assertThat(report.generation().citationPrecision()).isNull();
        assertThat(report.generation().answerSupportRate()).isNull();
        assertThat(report.generation().refusalAccuracy()).isNull();
        assertThat(report.generation().falseRefusalRate()).isNull();
        assertThat(report.generationNote()).contains("尚未接入");
    }

    @Test
    @DisplayName("JSON 输出含两层共 7 个指标键,且空值写成 null")
    void jsonCarriesBothLayers() {
        EvalDataset set = dataset(factoid("c01", "问题", "断点"));
        String json = new EvalRunner().run(set, retrieverWithHitAt(1, "断点"), "fake").toJson();

        assertThat(json).contains(
                "\"recall_at_5\"", "\"recall_at_20\"", "\"mrr\"",
                "\"citation_precision\"", "\"answer_support_rate\"",
                "\"refusal_accuracy\"", "\"false_refusal_rate\"",
                "\"citation_precision\": null",
                "\"evaluable_cases\": 1");
    }

    @Test
    @DisplayName("跨段落用例按前 r 片并集判命中,不要求同一片含全部关键词")
    void multiHopMatchesByUnionOfTopK() {
        EvalCase multiHop = new EvalCase("c05", "两路分数为什么不能相加,怎么绕开",
                List.of("量纲完全不同", "归一化"), EvalCaseType.MULTI_HOP, false);
        EvalDataset set = new EvalDataset(List.of(multiHop));
        // 两个关键词分别落在第 1 片和第 2 片
        EvalRunner.Retriever retriever = (q, k) -> List.of("这里只讲量纲完全不同", "这里只讲归一化");

        EvalReport report = new EvalRunner().run(set, retriever, "fake");

        assertThat(report.retrieval().recallAt5()).as("并集覆盖即算命中").isEqualTo(1.0);
        assertThat(report.retrieval().mrr()).as("覆盖发生在第 2 片").isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("跨段落用例在关键词始终不齐时仍算未命中")
    void multiHopMissesWhenKeywordsNeverAllAppear() {
        EvalCase multiHop = new EvalCase("c05", "问题", List.of("甲关键词", "乙关键词"),
                EvalCaseType.MULTI_HOP, false);
        EvalDataset set = new EvalDataset(List.of(multiHop));
        EvalRunner.Retriever retriever = (q, k) -> List.of("只有甲关键词", "还是只有甲关键词");

        EvalReport report = new EvalRunner().run(set, retriever, "fake");

        assertThat(report.retrieval().recallAt20()).isZero();
        assertThat(report.failedCases()).hasSize(1);
    }
}
