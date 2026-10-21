package com.ragkb.qa;

import com.ragkb.chunk.TokenCounter;
import com.ragkb.generation.AnswerGenerator;
import com.ragkb.generation.ChatException;
import com.ragkb.generation.ChatModel;
import com.ragkb.generation.ChatResponse;
import com.ragkb.generation.EvidenceEvaluator;
import com.ragkb.generation.PromptBuilder;
import com.ragkb.retrieval.ChunkSearcher;
import com.ragkb.retrieval.HybridRetriever;
import com.ragkb.retrieval.RrfFuser;
import com.ragkb.vector.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编排层。
 *
 * 断言的重点是<b>跳过了哪一步</b>,而不是答案文本 —— 文本由模型决定,测它没有意义;
 * 而"该省的那次调用有没有省"决定了成本,也决定了出故障时的表现。
 */
class QaServiceTest {

    private final TokenCounter counter = new TokenCounter();

    private static ScoredChunk chunk(long id, String content, double score) {
        return new ScoredChunk(id, 1L, content, "第一章", 0, content.length(), null, null, score);
    }

    /** 按队列回放输出,并记录调用次数。 */
    private static final class ScriptedModel implements ChatModel {
        private final Deque<String> outputs = new ArrayDeque<>();
        private final List<String> calls = new ArrayList<>();

        ScriptedModel(String... scripts) {
            outputs.addAll(List.of(scripts));
        }

        @Override
        public ChatResponse complete(String systemPrompt, String userPrompt) {
            calls.add(userPrompt);
            if (outputs.isEmpty()) {
                throw new IllegalStateException("模型被多调用了一次");
            }
            return new ChatResponse(outputs.poll(), "fake", 100, 20, 10);
        }
    }

    private QaService service(ChunkSearcher vector, ChunkSearcher keyword, ChatModel model) {
        HybridRetriever retriever = new HybridRetriever(vector, keyword,
                Runnable::run, 500);
        return new QaService(retriever, new RrfFuser(),
                new PromptBuilder(counter),
                new AnswerGenerator(model),
                new EvidenceEvaluator(new EvidenceEvaluator.Thresholds(0.45, 0.5)),
                model, documentId -> "笔记.md", 20);
    }

    @Test
    @DisplayName("正常路径:检索 → 生成 → 解析 → 自检,答案带引用")
    void answeredPath() {
        ScriptedModel model = new ScriptedModel("结论是这样的[E1]。", "{\"unsupported\":[]}");
        QaService service = service(
                (q, limit) -> List.of(chunk(1, "材料正文甲。", 0.72)),
                (q, limit) -> List.of(chunk(1, "材料正文甲。", 4.0)),
                model);

        QaAnswer answer = service.ask("材料里说了什么?");

        assertThat(answer.status()).isEqualTo(QaAnswer.Status.ANSWERED);
        assertThat(answer.text()).contains("[E1]");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.gate().refuse()).isFalse();
        assertThat(answer.usedCount()).isEqualTo(1);
        assertThat(model.calls).as("一次生成 + 一次自检").hasSize(2);
    }

    @Test
    @DisplayName("两路全空:一次模型调用都不发生")
    void emptyHitsSkipModelEntirely() {
        ScriptedModel model = new ScriptedModel();
        QaService service = service((q, limit) -> List.of(), (q, limit) -> List.of(), model);

        QaAnswer answer = service.ask("文档里没有的问题");

        assertThat(answer.status()).isEqualTo(QaAnswer.Status.REFUSED);
        assertThat(answer.reason()).isEqualTo(EvidenceEvaluator.Reason.NO_HITS);
        assertThat(model.calls).isEmpty();
    }

    @Test
    @DisplayName("证据太弱:门控直接拒答,同样不调模型")
    void weakEvidenceSkipsGeneration() {
        ScriptedModel model = new ScriptedModel();
        QaService service = service(
                (q, limit) -> List.of(chunk(1, "无关内容。", 0.20)),
                (q, limit) -> List.of(chunk(1, "无关内容。", 1.0)),
                model);

        QaAnswer answer = service.ask("完全不相干的问题");

        assertThat(answer.status()).isEqualTo(QaAnswer.Status.REFUSED);
        assertThat(answer.reason()).isEqualTo(EvidenceEvaluator.Reason.WEAK_EVIDENCE);
        assertThat(model.calls).isEmpty();
    }

    @Test
    @DisplayName("模型自述材料不足:不再做自检,省下第二次调用")
    void modelRefusalSkipsSelfCheck() {
        ScriptedModel model = new ScriptedModel("材料中未提及。");
        QaService service = service(
                (q, limit) -> List.of(chunk(1, "材料正文甲。", 0.72)),
                (q, limit) -> List.of(),
                model);

        QaAnswer answer = service.ask("问题");

        assertThat(answer.status()).isEqualTo(QaAnswer.Status.REFUSED);
        assertThat(answer.reason()).isEqualTo(EvidenceEvaluator.Reason.MODEL_INSUFFICIENT);
        assertThat(answer.text()).isEqualTo(PromptBuilder.INSUFFICIENT);
        assertThat(model.calls).as("只有生成那一次").hasSize(1);
    }

    @Test
    @DisplayName("自检把唯一的句子剔掉:整体拒答,引用列表清空")
    void unsupportedEverythingRefuses() {
        ScriptedModel model = new ScriptedModel("编的结论[E1]。", "{\"unsupported\":[1]}");
        QaService service = service(
                (q, limit) -> List.of(chunk(1, "材料正文甲。", 0.72)),
                (q, limit) -> List.of(),
                model);

        QaAnswer answer = service.ask("问题");

        assertThat(answer.status()).isEqualTo(QaAnswer.Status.REFUSED);
        assertThat(answer.reason()).isEqualTo(EvidenceEvaluator.Reason.UNSUPPORTED);
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    @DisplayName("生成失败:状态是 FAILED 而不是 REFUSED —— 一个该修服务,一个是对的")
    void generationFailureIsNotARefusal() {
        ChatModel failing = (system, user) -> {
            throw new ChatException("上游 503", true);
        };
        QaService service = service(
                (q, limit) -> List.of(chunk(1, "材料正文甲。", 0.72)),
                (q, limit) -> List.of(),
                failing);

        QaAnswer answer = service.ask("问题");

        assertThat(answer.status()).isEqualTo(QaAnswer.Status.FAILED);
        assertThat(answer.text()).isEmpty();
    }

    @Test
    @DisplayName("单通道降级照常作答,但把降级标记带出来")
    void degradedChannelIsReported() {
        ScriptedModel model = new ScriptedModel("结论[E1]。", "{\"unsupported\":[]}");
        QaService service = service(
                (q, limit) -> List.of(chunk(1, "材料正文甲。", 0.72)),
                (q, limit) -> {
                    throw new IllegalStateException("关键词通道挂了");
                },
                model);

        QaAnswer answer = service.ask("问题");

        assertThat(answer.status()).isEqualTo(QaAnswer.Status.ANSWERED);
        assertThat(answer.degraded()).isTrue();
    }

    @Test
    @DisplayName("编造的编号被剔除,但编号清单透传给上层")
    void unknownLabelsAreSurfaced() {
        ScriptedModel model = new ScriptedModel("真话[E1]。假话[E9]。", "{\"unsupported\":[]}");
        QaService service = service(
                (q, limit) -> List.of(chunk(1, "材料正文甲。", 0.72)),
                (q, limit) -> List.of(),
                model);

        QaAnswer answer = service.ask("问题");

        assertThat(answer.unknownLabels()).containsExactly("E9");
        assertThat(answer.text()).doesNotContain("E9").contains("[E1]");
    }
}
