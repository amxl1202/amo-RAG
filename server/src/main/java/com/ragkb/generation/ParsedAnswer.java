package com.ragkb.generation;

import java.util.List;

/**
 * 解析过的答案:逐句编号、去重后的引用列表、以及被剔除的非法编号。
 *
 * <h2>为什么非法编号要单独留一份</h2>
 * 只把它们删掉、不记录下来,就永远发现不了"模型在系统性编编号"这件事 ——
 * 表现是引用正确率一直不高,但原因看不出来。留一份清单,日志里一眼能看到
 * "这次答案里出现了 [E9],而材料只有 5 条"。
 *
 * @param raw            模型原始输出
 * @param sanitized      剔除非法编号后的文本,用于展示与前端渲染
 * @param sentences      逐句结果,自检与引用覆盖率都基于它
 * @param citations      合法且去重的引用,按首次出现顺序
 * @param unknownLabels  被剔除的编号,去重后按首次出现顺序,形如 {@code E9}
 * @param uncitedSentences 没有带任何合法编号的实质句子数
 */
public record ParsedAnswer(
        String raw,
        String sanitized,
        List<ParsedSentence> sentences,
        List<Citation> citations,
        List<String> unknownLabels,
        int uncitedSentences) {

    public ParsedAnswer {
        sentences = List.copyOf(sentences);
        citations = List.copyOf(citations);
        unknownLabels = List.copyOf(unknownLabels);
    }

    /** 有实质内容的句子数(排除只有编号的碎片)。 */
    public int contentSentences() {
        return (int) sentences.stream().filter(sentence -> !sentence.blank()).count();
    }

    /** 引用覆盖率:带编号的句子 / 有内容的句子。分母为 0 时记 0,不抛异常。 */
    public double citationCoverage() {
        int total = contentSentences();
        return total == 0 ? 0 : (double) (total - uncitedSentences) / total;
    }

    /** 是否出现过编造的编号。 */
    public boolean hasUnknownLabels() {
        return !unknownLabels.isEmpty();
    }
}
