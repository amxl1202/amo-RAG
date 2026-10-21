package com.ragkb.embedding;

import com.ragkb.chunk.TokenCounter;

/**
 * 用服务端回报的 token 用量校准本地分词器。
 *
 * 为什么要做这件事:本地用 cl100k_base 估算,实际模型用的是自己的分词器,两者在中文上
 * 偏差可能达到一倍。偏差不会被任何代码发现,但它会让"每片不超过 N token""上下文预算
 * 不超过 M"这类约束在真实调用时失真 —— 你以为切到 300,实际可能喂了 500。
 *
 * 用法:取一段真实语料调用一次,把得到的倍率写进 {@link TokenCounter} 的注释与常量,
 * 之后所有阈值都按实际口径换算。这是一次性测量,不是运行时动作。
 */
public final class TokenCalibration {

    private TokenCalibration() {
    }

    /**
     * @param localTokens 本地分词器的计数
     * @param apiTokens   服务端回报的用量
     * @param ratio       apiTokens / localTokens,即"实际 token 是本地估算的几倍"
     */
    public record Result(int localTokens, int apiTokens, double ratio) {

        public String describe() {
            return String.format(java.util.Locale.ROOT,
                    "本地 %d token / 实际 %d token,倍率 %.3f —— 阈值应按 %.3f 倍收紧",
                    localTokens, apiTokens, ratio, ratio);
        }
    }

    /**
     * 用同一段文本同时走本地计数与服务端计数,得到倍率。
     *
     * @throws IllegalArgumentException 样本为空或本地计数为 0(无法算倍率)
     */
    public static Result measure(TokenCounter counter, EmbeddingClient client, String sample) {
        if (sample == null || sample.isBlank()) {
            throw new IllegalArgumentException("校准样本不能为空");
        }
        int localTokens = counter.count(sample);
        if (localTokens <= 0) {
            throw new IllegalArgumentException("本地计数为 0,无法计算倍率");
        }
        int apiTokens = client.embedDocuments(java.util.List.of(sample)).totalTokens();
        if (apiTokens <= 0) {
            // 用量为 0 说明服务端没回报 usage,这种情况下算出来的倍率是假的,不能写进文档
            throw new IllegalArgumentException("服务端未回报 token 用量,无法校准");
        }
        return new Result(localTokens, apiTokens, (double) apiTokens / localTokens);
    }
}
