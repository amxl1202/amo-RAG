package com.ragkb.generation;

import com.ragkb.chunk.TokenCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引用解析。
 *
 * 关键分支是"编号形态对但内容不存在"和"编号形态歪但内容真实"这两种:
 * 前者必须拦下,后者必须放过 —— 判反任何一个,引用正确率都会失真,
 * 而且失真的方向相反,不会互相抵消。
 */
class CitationParserTest {

    private final TokenCounter counter = new TokenCounter();
    private final CitationParser parser = new CitationParser();

    private Prompt prompt(int evidenceCount) {
        List<Evidence> evidences = new java.util.ArrayList<>();
        for (int i = 1; i <= evidenceCount; i++) {
            evidences.add(new Evidence(i, 9, "笔记.md", "第三章", 0, 10, null, null,
                    "第" + i + "条证据的正文。", 0.9 - i * 0.01));
        }
        return new PromptBuilder(counter).build("问题", evidences);
    }

    @Test
    @DisplayName("正常答案:逐句拆开,每句带自己的编号")
    void parsesSentencesWithTheirCitations() {
        ParsedAnswer answer = parser.parse("第一句结论[E1]。第二句结论[E2]。", prompt(2));

        assertThat(answer.sentences()).hasSize(2);
        assertThat(answer.sentences().get(0).citations()).extracting(Citation::label).containsExactly("E1");
        assertThat(answer.sentences().get(1).citations()).extracting(Citation::label).containsExactly("E2");
        assertThat(answer.citations()).extracting(Citation::label).containsExactly("E1", "E2");
        assertThat(answer.uncitedSentences()).isZero();
        assertThat(answer.citationCoverage()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("编造 [E9]:不进引用列表、从展示文本里删掉、但留下清单")
    void fabricatedLabelIsRejected() {
        ParsedAnswer answer = parser.parse("这句是真的[E1]。这句是编的[E9]。", prompt(3));

        assertThat(answer.citations()).extracting(Citation::label).containsExactly("E1");
        assertThat(answer.unknownLabels()).containsExactly("E9");
        assertThat(answer.hasUnknownLabels()).isTrue();
        assertThat(answer.sanitized()).doesNotContain("E9").contains("[E1]");
        // 原文保持不动,排查时以它为准
        assertThat(answer.raw()).contains("[E9]");
        // 编造那句没有任何合法编号,必须计入"无引用句"
        assertThat(answer.sentences().get(1).hasUnknown()).isTrue();
        assertThat(answer.uncitedSentences()).isEqualTo(1);
        assertThat(answer.citationCoverage()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("括号里多一个空格也算编号 —— 实测模型会这样写")
    void labelWithInnerSpaceIsAccepted() {
        ParsedAnswer answer = parser.parse("是否需要训练[ E1]。", prompt(2));

        assertThat(answer.citations()).extracting(Citation::label).containsExactly("E1");
        assertThat(answer.uncitedSentences()).isZero();
        assertThat(answer.unknownLabels()).isEmpty();
    }

    @Test
    @DisplayName("全角括号与【】都能识别")
    void alternativeBracketsAreAccepted() {
        assertThat(parser.parse("甲［E1］。", prompt(2)).citations()).hasSize(1);
        assertThat(parser.parse("甲【E1】。", prompt(2)).citations()).hasSize(1);
        assertThat(parser.parse("甲[E1]。", prompt(2)).citations()).hasSize(1);
    }

    @Test
    @DisplayName("一条标记里并列多个编号")
    void multipleLabelsInOneMarker() {
        ParsedAnswer answer = parser.parse("两段共同支撑[E1, E2]。", prompt(3));

        assertThat(answer.sentences().get(0).citations())
                .extracting(Citation::label).containsExactly("E1", "E2");
        assertThat(answer.citations()).hasSize(2);
    }

    @Test
    @DisplayName("编号归一化:E01 与 e1 都指向 E1")
    void labelIsNormalized() {
        assertThat(parser.parse("甲[E01]。", prompt(2)).citations())
                .extracting(Citation::label).containsExactly("E1");
        assertThat(parser.parse("甲[e1]。", prompt(2)).citations())
                .extracting(Citation::label).containsExactly("E1");
    }

    @Test
    @DisplayName("非编号的方括号原样保留:别把 [见附录] 当编号删掉")
    void nonLabelBracketsAreKeptAsText() {
        ParsedAnswer answer = parser.parse("详见[见附录][E1]。", prompt(2));

        assertThat(answer.sanitized()).contains("[见附录]");
        assertThat(answer.citations()).extracting(Citation::label).containsExactly("E1");
    }

    @Test
    @DisplayName("一条标记里真假编号混写:整条删掉,不留半条")
    void mixedMarkerIsDroppedWhole() {
        ParsedAnswer answer = parser.parse("甲[E1, E9]。", prompt(3));

        assertThat(answer.citations()).isEmpty();
        assertThat(answer.unknownLabels()).containsExactly("E9");
        assertThat(answer.sanitized()).doesNotContain("[E1").doesNotContain("E9");
        assertThat(answer.uncitedSentences()).isEqualTo(1);
    }

    @Test
    @DisplayName("编号去重:同一编号在两句里出现,引用列表只有一条")
    void citationsAreDeduplicatedAcrossSentences() {
        ParsedAnswer answer = parser.parse("甲[E1]。乙[E1]。", prompt(2));

        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.sentences()).hasSize(2);
        assertThat(answer.citations().get(0).chunkId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("只有编号的碎片句不进覆盖率分母")
    void markerOnlyFragmentIsNotCounted() {
        ParsedAnswer answer = parser.parse("甲[E1]。[E2]", prompt(2));

        assertThat(answer.sentences()).hasSize(2);
        assertThat(answer.sentences().get(1).blank()).isTrue();
        assertThat(answer.contentSentences()).isEqualTo(1);
        assertThat(answer.citationCoverage()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("完全不带编号的答案:覆盖率 0,但不是异常")
    void answerWithoutCitationsHasZeroCoverage() {
        ParsedAnswer answer = parser.parse("第一句。第二句。", prompt(2));

        assertThat(answer.citations()).isEmpty();
        assertThat(answer.uncitedSentences()).isEqualTo(2);
        assertThat(answer.citationCoverage()).isZero();
    }

    @Test
    @DisplayName("英文句点不切分小数与域名")
    void decimalPointIsNotSentenceEnd() {
        ParsedAnswer answer = parser.parse("阈值取 3.14 比较稳[E1]。", prompt(2));

        assertThat(answer.sentences()).hasSize(1);
        assertThat(answer.citations()).hasSize(1);
    }

    @Test
    @DisplayName("语义检查用的纯文本里不能残留任何编号")
    void plainTextHasNoMarkers() {
        ParsedAnswer answer = parser.parse("甲[E1]。乙[E9]。", prompt(2));

        // 句末标点保留:自检提示词里"这句有没有材料支撑"问的是一句完整的话
        assertThat(answer.sentences().get(0).plain()).isEqualTo("甲。");
        assertThat(answer.sentences().get(1).plain()).isEqualTo("乙。");
        assertThat(answer.sentences().get(1).plain()).doesNotContain("[");
    }

    @Test
    @DisplayName("空答案返回空结构,不抛异常")
    void blankAnswerYieldsEmptyResult() {
        ParsedAnswer answer = parser.parse("   ", prompt(1));

        assertThat(answer.sentences()).isEmpty();
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.citationCoverage()).isZero();
    }

    @Test
    @DisplayName("没给提示词直接拒绝 —— 没有编号表就无法判断编号真假")
    void nullPromptIsRejected() {
        assertThatThrownBy(() -> parser.parse("甲[E1]。", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
