package com.ragkb.chunk;

/**
 * 切片参数。
 *
 * 默认值不是抄来的,是从 {@code samples/} 下三种语料的实测分位数推出来的:
 * PDF 段落 token 中位 102 / p90 162 / 最大 184,DOCX 中位 101 / p90 138 / 最大 160,
 * Markdown 中位 18 / p90 44 / 最大 66。
 * <ul>
 *   <li>{@code targetTokens = 300}:约为 PDF 段落的 3 倍,一片放 2~3 个自然段;
 *       Markdown 段落很短,会靠"遇到新标题就封口"限制,所以实际片长会小于 target ——
 *       target 是引导值,不是必须达到的值。</li>
 *   <li>{@code maxTokens = 400}:必须大于语料最长段落(184),否则最长那段一定被切坏;
 *       留出余量是因为合并后可能略超 target。</li>
 *   <li>{@code overlapTokens = 48}:约 target 的 16%,用来救"答案恰好跨两片"的情况。</li>
 * </ul>
 *
 * @param targetTokens  目标大小,合并时接近它就封口
 * @param maxTokens     单片硬上限,超过必须切
 * @param overlapTokens 相邻片的重叠量
 */
public record ChunkingProperties(int targetTokens, int maxTokens, int overlapTokens) {

    public ChunkingProperties {
        if (targetTokens <= 0) {
            throw new IllegalArgumentException("targetTokens 必须为正: " + targetTokens);
        }
        if (maxTokens < targetTokens) {
            throw new IllegalArgumentException(
                    "maxTokens(" + maxTokens + ") 不能小于 targetTokens(" + targetTokens + "),否则每片都超上限");
        }
        if (overlapTokens < 0) {
            throw new IllegalArgumentException("overlapTokens 不能为负: " + overlapTokens);
        }
        if (overlapTokens >= targetTokens) {
            throw new IllegalArgumentException(
                    "overlapTokens(" + overlapTokens + ") 必须小于 targetTokens(" + targetTokens
                            + "),否则切不动");
        }
    }

    public static ChunkingProperties defaults() {
        return new ChunkingProperties(300, 400, 48);
    }
}
