package com.ragkb.generation;

import com.ragkb.chunk.TokenCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生成器。
 *
 * 用 lambda 扮演模型:返回编造编号的答案、返回带客套的拒答、返回空串 —— 这些分支
 * 用真实接口造不出来,而它们决定了"拒答"与"故障"会不会被混为一谈。
 */
class AnswerGeneratorTest {

    private final TokenCounter counter = new TokenCounter();

    /** 记录调用次数,并回放预置输出。 */
    private static final class ScriptedModel implements ChatModel {
        private final List<String> prompts = new ArrayList<>();
        private final String output;
        private final int promptTokens;

        ScriptedModel(String output) {
            this(output, 100);
        }

        ScriptedModel(String output, int promptTokens) {
            this.output = output;
            this.promptTokens = promptTokens;
        }

        @Override
        public ChatResponse complete(String systemPrompt, String userPrompt) {
            prompts.add(userPrompt);
            return new ChatResponse(output, "fake-model", promptTokens, 20, 12);
        }
    }

    private Prompt prompt() {
        Evidence evidence = new Evidence(1, 9, "笔记.md", "第三章", 0, 10, null, null,
                "融合只使用名次,不使用分数。", 0.8);
        return new PromptBuilder(counter).build("为什么不能直接相加?", List.of(evidence));
    }

    @Test
    @DisplayName("提示词里没有材料时不调模型:省一次调用,也不给模型凭常识作答的机会")
    void noEvidenceSkipsTheCall() {
        ScriptedModel model = new ScriptedModel("不该被调用");
        Prompt empty = new PromptBuilder(counter).build("孤立问题", List.of());

        GeneratedAnswer answer = new AnswerGenerator(model).generate(empty);

        assertThat(model.prompts).isEmpty();
        assertThat(answer.skipped()).isTrue();
        assertThat(answer.refused()).isTrue();
        assertThat(answer.text()).isEqualTo(PromptBuilder.INSUFFICIENT);
        assertThat(answer.totalTokens()).isZero();
    }

    @Test
    @DisplayName("正常回答:正文与用量透传,不误判为拒答")
    void normalAnswerPassesThrough() {
        ScriptedModel model = new ScriptedModel("两路分数量纲不同,必须归一化[E1]。");

        GeneratedAnswer answer = new AnswerGenerator(model).generate(prompt());

        assertThat(answer.text()).contains("[E1]");
        assertThat(answer.refused()).isFalse();
        assertThat(answer.skipped()).isFalse();
        assertThat(answer.promptTokens()).isEqualTo(100);
        assertThat(answer.completionTokens()).isEqualTo(20);
        assertThat(answer.totalTokens()).isEqualTo(120);
        assertThat(model.prompts).hasSize(1);
    }

    @Test
    @DisplayName("模型自己说'材料中未提及'时标记为拒答")
    void sentinelIsDetected() {
        GeneratedAnswer answer = new AnswerGenerator(new ScriptedModel(PromptBuilder.INSUFFICIENT))
                .generate(prompt());

        assertThat(answer.refused()).isTrue();
        assertThat(answer.skipped()).as("这次是花了钱才拒答的,与没调模型不同").isFalse();
    }

    @Test
    @DisplayName("句号、引号、客套话都不影响拒答判定")
    void refusalDetectionToleratesFormatting() {
        assertThat(AnswerGenerator.looksLikeRefusal("材料中未提及。")).isTrue();
        assertThat(AnswerGenerator.looksLikeRefusal("抱歉，材料中未提及该信息。")).isTrue();
        assertThat(AnswerGenerator.looksLikeRefusal("「材料中未提及」")).isTrue();
        assertThat(AnswerGenerator.looksLikeRefusal("材料中未提及，\n但可以推测……")).isTrue();
        assertThat(AnswerGenerator.looksLikeRefusal("材料中提到 HNSW 不需要训练[E1]。")).isFalse();
        assertThat(AnswerGenerator.looksLikeRefusal("")).isFalse();
        assertThat(AnswerGenerator.looksLikeRefusal(null)).isFalse();
    }

    @Test
    @DisplayName("空答案报错,不当作拒答 —— 两者在指标里含义完全不同")
    void blankAnswerIsAnError() {
        assertThatThrownBy(() -> new AnswerGenerator(new ScriptedModel("   ")).generate(prompt()))
                .isInstanceOf(ChatException.class)
                .satisfies(e -> assertThat(((ChatException) e).retryable()).isFalse());
    }

    @Test
    @DisplayName("用量可以跨多次调用累加,供评估报告统计成本")
    void totalTokensSumsUp() {
        ScriptedModel model = new ScriptedModel("答案[E1]。");
        AnswerGenerator generator = new AnswerGenerator(model);

        int total = AnswerGenerator.totalTokens(List.of(generator.generate(prompt()), generator.generate(prompt())));

        assertThat(total).isEqualTo(240);
    }
}
