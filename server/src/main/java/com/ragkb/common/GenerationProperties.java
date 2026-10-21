package com.ragkb.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 生成策略配置(与"哪家供应商"无关的部分)。
 *
 * 单独一份而不并进 {@link AiProperties}:那一份是供应商凭据与端点,换实例时整体替换;
 * 这一份是提示词预算,换语料才需要动。两者的变更理由不同,放一起会让人在改预算时
 * 顺手动了端点。
 */
@Data
@ConfigurationProperties(prefix = "app.generation")
public class GenerationProperties {

    /** 材料段最多放几条证据;超出部分进 droppedCount,不进提示词。 */
    private int maxEvidence = 5;

    /** 材料段的 token 上限(本地 cl100k 口径,偏高)。 */
    private int maxContextTokens = 2000;

    /**
     * 阶段一拒答:向量通道 top-1 余弦相似度下限。
     *
     * 这个数必须由评估集反推,不能拍。改它等于改"什么时候拒答",必须重跑
     * {@code docs/eval-baseline.md} 里的拒答准确率与误拒率。
     */
    private double minVectorSimilarity = 0.45;

    /**
     * 阶段一拒答:关键词通道对问题词的覆盖率下限(0~1)。
     *
     * 与相似度是"或"的关系 —— 中文技术语料里专有名词靠字面命中,
     * 只看相似度会把这类问题误拒。
     */
    private double minKeywordCoverage = 0.5;

    public com.ragkb.generation.PromptBuilder.Limits toPromptLimits() {
        return new com.ragkb.generation.PromptBuilder.Limits(maxEvidence, maxContextTokens);
    }

    public com.ragkb.generation.EvidenceEvaluator.Thresholds toThresholds() {
        return new com.ragkb.generation.EvidenceEvaluator.Thresholds(minVectorSimilarity, minKeywordCoverage);
    }
}
