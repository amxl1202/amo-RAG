package com.ragkb.generation;

/**
 * 一次生成的产出。
 *
 * @param text             模型输出正文(已去首尾空白);拒答时是拒答固定串
 * @param refused          模型是否输出了"材料中未提及"。这是<b>模型自述</b>的拒答,
 *                         不等于系统最终拒答 —— 系统还要再做一次证据校验
 * @param model            实际使用的模型名
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param latencyMillis   耗时
 * @param skipped          是否因为"提示词里没有任何材料"而没有调用模型。
 *                         与 {@code refused} 分开记:前者省了一次调用,后者花了钱才拒答
 */
public record GeneratedAnswer(
        String text,
        boolean refused,
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMillis,
        boolean skipped) {

    public GeneratedAnswer {
        text = text == null ? "" : text.strip();
    }

    /** 没调模型就直接给的拒答。 */
    public static GeneratedAnswer skippedWithoutEvidence() {
        return new GeneratedAnswer(PromptBuilder.INSUFFICIENT, true, "-", 0, 0, 0, true);
    }

    /** 两侧 token 之和,用于成本统计。 */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
