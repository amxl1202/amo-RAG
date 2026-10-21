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

    public com.ragkb.generation.PromptBuilder.Limits toPromptLimits() {
        return new com.ragkb.generation.PromptBuilder.Limits(maxEvidence, maxContextTokens);
    }
}
