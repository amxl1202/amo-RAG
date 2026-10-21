package com.ragkb.common;

import com.ragkb.chunk.TokenCounter;
import com.ragkb.generation.AnswerGenerator;
import com.ragkb.generation.ChatModel;
import com.ragkb.generation.DashScopeChatModel;
import com.ragkb.generation.EvidenceEvaluator;
import com.ragkb.generation.PromptBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 生成链路的装配:提示词拼装 → 模型 → 答案。
 *
 * 与检索的装配分开成两个类,是因为两条链路的生命周期不同:检索组件是单例、无状态、
 * 随应用常驻;生成组件里有一个会打外部接口几十秒的客户端,排查时希望它单独可见。
 * 放在同一个配置类里,日志与 bean 列表都会混在一起。
 */
@Configuration
@EnableConfigurationProperties(GenerationProperties.class)
public class GenerationConfig {

    /** 对话模型。传输层可注入,便于没有 Key、没有网络时构造与测试。 */
    @Bean
    public ChatModel chatModel(AiProperties properties) {
        return new DashScopeChatModel(properties.toChatProperties());
    }

    /** 提示词拼装。预算来自配置,不写死在代码里 —— 改预算不该改代码。 */
    @Bean
    public PromptBuilder promptBuilder(TokenCounter tokenCounter, GenerationProperties properties) {
        return new PromptBuilder(tokenCounter, properties.toPromptLimits());
    }

    @Bean
    public AnswerGenerator answerGenerator(ChatModel chatModel) {
        return new AnswerGenerator(chatModel);
    }

    /** 两阶段拒答。阈值来自配置:改阈值等于改行为,必须能一眼看到、也必须随评估一起改。 */
    @Bean
    public EvidenceEvaluator evidenceEvaluator(GenerationProperties properties) {
        return new EvidenceEvaluator(properties.toThresholds());
    }
}
