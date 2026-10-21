package com.ragkb.generation;

import com.ragkb.chunk.TokenCounter;
import com.ragkb.vector.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提示词拼装。
 *
 * 这一层的错误都不会让程序崩,只会让答案悄悄变差:编号错位、材料被截断、
 * 预算按分项求和算多。所以断言全部钉在"编好的文本本身"上,而不是钉在对象字段上。
 */
class PromptBuilderTest {

    private final TokenCounter counter = new TokenCounter();

    /** 足够大的预算:只测排版,不测预算。 */
    private PromptBuilder unlimited() {
        return new PromptBuilder(counter, new PromptBuilder.Limits(99, Integer.MAX_VALUE));
    }

    private static Evidence evidence(long id, String content) {
        return new Evidence(id, 7L, "笔记.md", "第三章 > 3.2 融合", 100, 200, 3, 4, content, 0.9);
    }

    /** 构造一段内容不重复的中文,避免 BPE 把重复字符压成极少的 token 而让预算测试失真。 */
    private static String prose(String tag) {
        return tag + ":融合检索的两条通道分别负责语义相近与精确匹配,分数不可直接相加,"
                + "因为它们量纲完全不同,必须先归一化或者改用名次。";
    }

    @Test
    @DisplayName("编号按顺序生成,且每个编号后面紧跟它自己的正文")
    void labelsMatchTheirOwnContent() {
        Prompt prompt = unlimited().build("两条通道各自擅长什么?",
                List.of(evidence(11, prose("甲")), evidence(22, prose("乙"))));

        assertThat(prompt.citations()).extracting(Citation::label).containsExactly("E1", "E2");
        assertThat(prompt.userPrompt())
                .contains("[E1] 《笔记.md》 · 章节:第三章 > 3.2 融合 · 页:3-4")
                .contains(prose("甲"))
                .contains("[E2]")
                .contains(prose("乙"));
        // 编号与正文的先后关系:甲必须出现在 [E1] 之后、[E2] 之前
        assertThat(prompt.userPrompt().indexOf(prose("甲")))
                .isLessThan(prompt.userPrompt().indexOf("[E2]"));
    }

    @Test
    @DisplayName("问题的位置在材料之后 —— 材料不能被放在最后一句的位置上")
    void questionComesAfterMaterials() {
        Prompt prompt = unlimited().build("问题文本", List.of(evidence(1, prose("甲"))));

        assertThat(prompt.userPrompt().indexOf(prose("甲")))
                .isLessThan(prompt.userPrompt().indexOf("问题文本"));
    }

    @Test
    @DisplayName("超预算的证据整条丢弃,正文一个字都不能进提示词")
    void overBudgetEvidenceIsDroppedWhole() {
        Evidence first = evidence(1, prose("甲"));
        Evidence second = evidence(2, prose("乙"));
        // 先量出"只放第一条"要多少 token,再把预算卡在这个值上
        int oneBlock = unlimited().build("q", List.of(first)).contextTokens();
        PromptBuilder tight = new PromptBuilder(counter, new PromptBuilder.Limits(9, oneBlock));

        Prompt prompt = tight.build("q", List.of(first, second));

        assertThat(prompt.citations()).hasSize(1);
        assertThat(prompt.citations().get(0).chunkId()).isEqualTo(1L);
        assertThat(prompt.droppedCount()).isEqualTo(1);
        // 关键断言:不是截断 —— 第二条的正文里没有任何片段出现
        assertThat(prompt.userPrompt()).doesNotContain(prose("乙"));
        assertThat(prompt.userPrompt()).doesNotContain("乙:融合检索");
    }

    @Test
    @DisplayName("被跳过的候选不占编号:留下的那条仍然是 E1,不留空号")
    void skippedCandidateLeavesNoGap() {
        Evidence huge = evidence(1, prose("甲").repeat(6));
        Evidence small = evidence(2, prose("乙"));
        int smallBlock = unlimited().build("q", List.of(small)).contextTokens();
        assertThat(unlimited().build("q", List.of(huge)).contextTokens())
                .as("前半段必须确实放不下,否则这条测试测不到跳号")
                .isGreaterThan(smallBlock);

        Prompt prompt = new PromptBuilder(counter, new PromptBuilder.Limits(9, smallBlock))
                .build("q", List.of(huge, small));

        assertThat(prompt.citations()).extracting(Citation::label).containsExactly("E1");
        assertThat(prompt.citations().get(0).chunkId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("条数上限之外的证据也计入丢弃数,便于日志解释'为什么少了一条'")
    void evidenceCountIsCapped() {
        Prompt prompt = new PromptBuilder(counter, new PromptBuilder.Limits(2, Integer.MAX_VALUE))
                .build("q", List.of(evidence(1, prose("甲")), evidence(2, prose("乙")), evidence(3, prose("丙"))));

        assertThat(prompt.citations()).hasSize(2);
        assertThat(prompt.droppedCount()).isEqualTo(1);
        assertThat(prompt.userPrompt()).doesNotContain(prose("丙"));
    }

    @Test
    @DisplayName("同一 chunkId 只出现一次 —— 重复会让引用正确率算错")
    void duplicateChunkIdIsCollapsed() {
        Prompt prompt = unlimited().build("q",
                List.of(evidence(5, prose("甲")), evidence(5, prose("乙"))));

        assertThat(prompt.citations()).hasSize(1);
        assertThat(prompt.userPrompt()).contains(prose("甲")).doesNotContain(prose("乙"));
    }

    @Test
    @DisplayName("contextTokens 是材料段的实测值,不是各块之和")
    void contextTokensAreMeasuredOnJoinedText() {
        Prompt prompt = unlimited().build("q", List.of(evidence(1, prose("甲")), evidence(2, prose("乙"))));
        String userPrompt = prompt.userPrompt();
        String body = userPrompt.substring("材料:\n".length(), userPrompt.indexOf("\n\n问题:"));

        assertThat(prompt.contextTokens()).isEqualTo(counter.count(body));
    }

    @Test
    @DisplayName("没有可用证据时 isEmpty 为真,但仍保留问题文本")
    void emptyEvidenceYieldsEmptyPrompt() {
        Prompt prompt = unlimited().build("孤立的问题", List.of());

        assertThat(prompt.isEmpty()).isTrue();
        assertThat(prompt.citations()).isEmpty();
        assertThat(prompt.userPrompt()).contains("孤立的问题");
    }

    @Test
    @DisplayName("缺页码时不写 '页:null'")
    void missingPageNumbersAreOmitted() {
        Evidence noPage = new Evidence(1, 7L, "纯文本.txt", null, 0, 10, null, null, prose("甲"), 0.5);

        Prompt prompt = unlimited().build("q", List.of(noPage));

        assertThat(prompt.userPrompt()).doesNotContain("null").doesNotContain("页:");
    }

    @Test
    @DisplayName("系统提示词带上拒答固定串 —— 下游靠它判断'模型拒答了'")
    void systemPromptCarriesRefusalSentinel() {
        Prompt prompt = unlimited().build("q", List.of(evidence(1, prose("甲"))));

        assertThat(prompt.systemPrompt()).contains(PromptBuilder.INSUFFICIENT);
    }

    @Test
    @DisplayName("按编号回查:带方括号、大小写不同都能查到;不存在的编号返回空")
    void citationLookupByLabel() {
        Prompt prompt = unlimited().build("q", List.of(evidence(1, prose("甲")), evidence(2, prose("乙"))));

        assertThat(prompt.citationOf("E2")).isPresent();
        assertThat(prompt.citationOf("[E2]")).isPresent();
        assertThat(prompt.citationOf("e2")).isPresent();
        assertThat(prompt.citationOf("E9")).isEmpty();
        assertThat(prompt.citationOf("E2").orElseThrow().chunkId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("空问题直接拒绝,而不是拼出一个没有问题的提示词")
    void blankQuestionIsRejected() {
        assertThatThrownBy(() -> unlimited().build("  ", List.of(evidence(1, prose("甲")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("证据构造:标题为空白时填占位符,正文空白直接拒绝")
    void evidenceGuards() {
        ScoredChunk chunk = new ScoredChunk(3, 9, "正文", "第一章", 0, 2, null, null, 0.7);

        assertThat(Evidence.from(chunk, " ").documentTitle()).isEqualTo(Evidence.UNKNOWN_TITLE);
        assertThat(Evidence.from(chunk, "a.pdf").documentTitle()).isEqualTo("a.pdf");
        assertThatThrownBy(() -> new Evidence(1, 1, "x", null, 0, 0, null, null, "   ", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
