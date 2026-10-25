package com.ragkb.common;

import com.ragkb.chunk.TokenCounter;
import com.ragkb.document.DocumentEntity;
import com.ragkb.document.mapper.DocumentMapper;
import com.ragkb.generation.AnswerGenerator;
import com.ragkb.generation.ChatModel;
import com.ragkb.generation.DashScopeChatModel;
import com.ragkb.generation.EvidenceEvaluator;
import com.ragkb.generation.PromptBuilder;
import com.ragkb.qa.DocumentTitles;
import com.ragkb.qa.QaService;
import com.ragkb.retrieval.HybridRetriever;
import com.ragkb.retrieval.RrfFuser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成链路的装配:提示词拼装 → 模型 → 答案 → 证据评估 → 编排。
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

    /**
     * 文档标题查询。
     *
     * <p>缓存是安全的:当前没有重命名接口,标题随上传写入后不再变化。
     * 如果将来加了重命名,这里必须改成带失效的缓存 —— 否则用户会看到旧标题,
     * 而且只在"改名后第一次提问"时出现,极难复现。
     */
    @Bean
    public DocumentTitles documentTitles(DocumentMapper documentMapper) {
        Map<String, String> cache = new ConcurrentHashMap<>();
        return (documentId, tenantId) -> cache.computeIfAbsent(tenantId + ":" + documentId, key -> {
            DocumentEntity entity = documentMapper.selectById(documentId);
            // 归属不符时不要泄漏标题 —— 直接给一个中性的占位
            if (entity == null || entity.getTenantId() == null || entity.getTenantId() != tenantId) {
                return "文档#" + documentId;
            }
            return entity.getOriginalName();
        });
    }

    /**
     * 流式问答的执行器:一条虚拟线程服务一条流。
     *
     * <p>每条流从头到尾都在等 IO(等模型吐字),用平台线程池要么把池子占满、要么限制并发数;
     * 虚拟线程的成本近似对象,可以"一条流一条线程",写起来还是阻塞式代码。
     * 这是 Java 21 之后这类场景的标准解法 —— 需要在 Java 25 + Boot 4 上才这么写,降版本就没有。
     */
    @Bean(destroyMethod = "close")
    public java.util.concurrent.ExecutorService qaStreamExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 编排。跑批工具用同一套装配方式手工 new,保证"评估跑的"与"线上跑的"是同一段逻辑。 */
    @Bean
    public QaService qaService(HybridRetriever hybridRetriever, RrfFuser rrfFuser,
                               PromptBuilder promptBuilder, AnswerGenerator answerGenerator,
                               EvidenceEvaluator evidenceEvaluator, ChatModel chatModel,
                               DocumentTitles documentTitles, RetrievalProperties retrievalProperties) {
        return new QaService(hybridRetriever, rrfFuser, promptBuilder, answerGenerator,
                evidenceEvaluator, chatModel, documentTitles, retrievalProperties.getChannelLimit());
    }
}
