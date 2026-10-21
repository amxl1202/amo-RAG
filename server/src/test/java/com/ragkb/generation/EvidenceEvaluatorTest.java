package com.ragkb.generation;

import com.ragkb.chunk.TokenCounter;
import com.ragkb.vector.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两阶段拒答。
 *
 * 这里最要紧的不是"会不会拒答",而是<b>拒错方向</b>:阶段一判错会误拒真问题,
 * 阶段二判错会放过编造的句子。两种错在指标上分别记为误拒率与引用正确率,
 * 但都被算成"模型效果差",因此必须在单测层面分开钉住。
 */
class EvidenceEvaluatorTest {

    private final TokenCounter counter = new TokenCounter();
    private final EvidenceEvaluator evaluator =
            new EvidenceEvaluator(new EvidenceEvaluator.Thresholds(0.45, 0.5));

    private static ScoredChunk chunk(long id, double score) {
        return new ScoredChunk(id, 1L, "第" + id + "条证据的正文。", "第一章", 0, 10, null, null, score);
    }

    private Prompt prompt() {
        List<Evidence> evidences = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            evidences.add(new Evidence(i, 9, "笔记.md", "第三章", 0, 10, null, null,
                    "第" + i + "条证据的正文。", 0.8 - i * 0.01));
        }
        return new PromptBuilder(counter).build("混合检索为什么不能直接相加?", evidences);
    }

    /** 按脚本回答自检提问的模型。 */
    private static final class ScriptedChecker implements ChatModel {
        private final String output;
        private final List<String> received = new ArrayList<>();

        ScriptedChecker(String output) {
            this.output = output;
        }

        @Override
        public ChatResponse complete(String systemPrompt, String userPrompt) {
            received.add(userPrompt);
            return new ChatResponse(output, "checker", 50, 10, 5);
        }
    }

    private static ChatModel failingChecker() {
        return (system, user) -> {
            throw new ChatException("自检接口 503", true);
        };
    }

    // ---------------------------------------------------------------- 阶段一

    @Test
    @DisplayName("两路都空:直接拒答,不调模型")
    void noHitsRefuses() {
        EvidenceEvaluator.Gate gate = evaluator.gate(
                new EvidenceEvaluator.RetrievalSignals(List.of(), List.of(), false, false, 0));

        assertThat(gate.refuse()).isTrue();
        assertThat(gate.reason()).isEqualTo(EvidenceEvaluator.Reason.NO_HITS);
    }

    @Test
    @DisplayName("相似度达标就不拒,即便关键词一路没命中")
    void strongSimilarityPasses() {
        EvidenceEvaluator.Gate gate = evaluator.gate(new EvidenceEvaluator.RetrievalSignals(
                List.of(chunk(1, 0.72)), List.of(), false, false, 10));

        assertThat(gate.refuse()).isFalse();
        assertThat(gate.reason()).isEqualTo(EvidenceEvaluator.Reason.NONE);
    }

    @Test
    @DisplayName("相似度不达标但关键词覆盖高:也不拒 —— 专有名词靠字面命中")
    void strongKeywordCoveragePasses() {
        EvidenceEvaluator.Gate gate = evaluator.gate(new EvidenceEvaluator.RetrievalSignals(
                List.of(chunk(1, 0.30)), List.of(chunk(2, 9.0)), false, false, 10));

        assertThat(gate.refuse()).isFalse();
        assertThat(gate.keywordCoverage()).isCloseTo(0.9, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("两条判据都不达标才拒,并带上实测分数便于排查")
    void weakEvidenceRefuses() {
        EvidenceEvaluator.Gate gate = evaluator.gate(new EvidenceEvaluator.RetrievalSignals(
                List.of(chunk(1, 0.31)), List.of(chunk(2, 2.0)), false, false, 10));

        assertThat(gate.refuse()).isTrue();
        assertThat(gate.reason()).isEqualTo(EvidenceEvaluator.Reason.WEAK_EVIDENCE);
        assertThat(gate.vectorTop1()).isEqualTo(0.31);
        assertThat(gate.keywordCoverage()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("关键词覆盖率用词长之和做分母,与检索的打分口径一致")
    void keywordMaxScoreMatchesRetrieverScoring() {
        // "HNSW 与 IVFFlat 的核心差别" → 抽词后长度之和,只断言为正且随问题变化
        double shortQuestion = EvidenceEvaluator.maxKeywordScore("HNSW");
        double longQuestion = EvidenceEvaluator.maxKeywordScore("HNSW 与 IVFFlat 的核心差别在哪里");

        assertThat(shortQuestion).isPositive();
        assertThat(longQuestion).isGreaterThan(shortQuestion);
    }

    @Test
    @DisplayName("阈值越界直接报错 —— 0~1 之外的阈值意味着单位用错了")
    void thresholdRangeIsValidated() {
        assertThatThrownBy(() -> new EvidenceEvaluator.Thresholds(1.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceEvaluator.Thresholds(0.5, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 阶段二

    @Test
    @DisplayName("模型自己拒答时不再自检 —— 没有句子可判,再调一次只是白花钱")
    void modelRefusalSkipsSelfCheck() {
        ParsedAnswer parsed = new CitationParser().parse(PromptBuilder.INSUFFICIENT, prompt());
        ScriptedChecker checker = new ScriptedChecker("{\"unsupported\":[]}");

        EvidenceEvaluator.Verdict verdict = evaluator.verify(prompt(), parsed, checker);

        assertThat(verdict.refuse()).isTrue();
        assertThat(verdict.reason()).isEqualTo(EvidenceEvaluator.Reason.MODEL_INSUFFICIENT);
        assertThat(checker.received).isEmpty();
    }

    @Test
    @DisplayName("自检全部通过:原答案保留,编号不丢")
    void allSupportedKeepsAnswer() {
        ParsedAnswer parsed = new CitationParser().parse("第一句[E1]。第二句[E2]。", prompt());

        EvidenceEvaluator.Verdict verdict = evaluator.verify(prompt(), parsed,
                new ScriptedChecker("{\"unsupported\":[]}"));

        assertThat(verdict.refuse()).isFalse();
        assertThat(verdict.kept()).isEqualTo(2);
        assertThat(verdict.dropped()).isZero();
        assertThat(verdict.text()).contains("[E1]").contains("[E2]");
    }

    @Test
    @DisplayName("剔除无支撑的句子,其余保留")
    void unsupportedSentenceIsDropped() {
        ParsedAnswer parsed = new CitationParser().parse("有支撑[E1]。编的[E2]。", prompt());

        EvidenceEvaluator.Verdict verdict = evaluator.verify(prompt(), parsed,
                new ScriptedChecker("{\"unsupported\":[2]}"));

        assertThat(verdict.refuse()).isFalse();
        assertThat(verdict.text()).contains("有支撑").doesNotContain("编的");
        assertThat(verdict.dropped()).isEqualTo(1);
        assertThat(verdict.kept()).isEqualTo(1);
    }

    @Test
    @DisplayName("全部被剔除则整体拒答")
    void allUnsupportedRefuses() {
        ParsedAnswer parsed = new CitationParser().parse("甲[E1]。乙[E2]。", prompt());

        EvidenceEvaluator.Verdict verdict = evaluator.verify(prompt(), parsed,
                new ScriptedChecker("{\"unsupported\":[1,2]}"));

        assertThat(verdict.refuse()).isTrue();
        assertThat(verdict.reason()).isEqualTo(EvidenceEvaluator.Reason.UNSUPPORTED);
        assertThat(verdict.text()).isEqualTo(PromptBuilder.INSUFFICIENT);
    }

    @Test
    @DisplayName("自检调用失败时保留原答案并标记 unavailable —— 附加检查坏了不该让系统乱拒答")
    void selfCheckFailureDegradesToKeepAnswer() {
        ParsedAnswer parsed = new CitationParser().parse("甲[E1]。", prompt());

        EvidenceEvaluator.Verdict verdict = evaluator.verify(prompt(), parsed, failingChecker());

        assertThat(verdict.refuse()).isFalse();
        assertThat(verdict.unavailable()).isTrue();
        assertThat(verdict.reason()).isEqualTo(EvidenceEvaluator.Reason.CHECK_UNAVAILABLE);
        assertThat(verdict.text()).isEqualTo("甲[E1]。");
    }

    @Test
    @DisplayName("自检输出不可解析时同样降级,而不是把所有句子判成无支撑")
    void unparsableSelfCheckDegrades() {
        ParsedAnswer parsed = new CitationParser().parse("甲[E1]。", prompt());

        EvidenceEvaluator.Verdict verdict = evaluator.verify(prompt(), parsed,
                new ScriptedChecker("我觉得都挺好的"));

        assertThat(verdict.refuse()).isFalse();
        assertThat(verdict.unavailable()).isTrue();
    }

    @Test
    @DisplayName("越界的句子序号被忽略:模型数错句子不能删掉真答案")
    void outOfRangeIndexIsIgnored() {
        ParsedAnswer parsed = new CitationParser().parse("甲[E1]。", prompt());

        EvidenceEvaluator.Verdict verdict = evaluator.verify(prompt(), parsed,
                new ScriptedChecker("{\"unsupported\":[7,0,-1]}"));

        assertThat(verdict.refuse()).isFalse();
        assertThat(verdict.kept()).isEqualTo(1);
    }

    @Test
    @DisplayName("自检提示词里带上材料与逐句文本,但句子不带编号")
    void selfCheckPromptCarriesMaterialsWithoutMarkers() {
        ParsedAnswer parsed = new CitationParser().parse("甲[E1]。", prompt());
        ScriptedChecker checker = new ScriptedChecker("{\"unsupported\":[]}");

        evaluator.verify(prompt(), parsed, checker);

        assertThat(checker.received).hasSize(1);
        assertThat(checker.received.get(0))
                .contains("[E1] 《笔记.md》")
                .contains("1. 甲");
        assertThat(checker.received.get(0)).doesNotContain("1. 甲[E1]");
    }

    @Test
    @DisplayName("自检输出带代码块围栏也能解析")
    void selfCheckOutputToleratesFence() {
        ParsedAnswer parsed = new CitationParser().parse("甲[E1]。乙[E2]。", prompt());

        EvidenceEvaluator.Verdict verdict = evaluator.verify(prompt(), parsed,
                new ScriptedChecker("```json\n{\"unsupported\": [2]}\n```"));

        assertThat(verdict.refuse()).isFalse();
        assertThat(verdict.kept()).isEqualTo(1);
    }
}
