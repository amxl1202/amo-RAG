package com.ragkb.generation;

import java.util.List;

/**
 * 答案按句拆开后的样子。
 *
 * <h2>为什么以"句"为单位</h2>
 * 引用校验要回答的问题是"这句话有没有材料支撑",判断对象只能是句子。
 * 如果只在整个答案层面判断"有没有引用",那么一段十句话里只要有一句带了编号,
 * 整段就算"有依据"—— 编造的那九句会被一起放过。
 *
 * @param text         原句(含编号标记)
 * @param plain        去掉全部编号标记后的正文,交给自检提示词用 ——
 *                     带着 {@code [E1]} 去问"这句有没有支撑",模型会把编号本身当成依据
 * @param citations    这句话里出现的<b>合法</b>编号;非法编号已剔除
 * @param hasUnknown   这句话里是否出现过不存在的编号。它不影响合法性判断,
 *                     但需要留痕:大量出现说明模型在编编号,是提示词该改的信号
 */
public record ParsedSentence(String text, String plain, List<Citation> citations, boolean hasUnknown) {

    public ParsedSentence {
        citations = List.copyOf(citations);
    }

    /** 这句带了合法编号 —— 引用覆盖率的分母就是"有实质内容的句子数"。 */
    public boolean cited() {
        return !citations.isEmpty();
    }

    /** 只有编号、没有内容的碎片句子(例如答案末尾孤零零一个 {@code [E2]})。 */
    public boolean blank() {
        return plain == null || plain.isBlank();
    }
}
