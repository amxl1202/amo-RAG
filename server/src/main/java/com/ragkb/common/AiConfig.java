package com.ragkb.common;

import com.ragkb.embedding.DashScopeEmbeddingClient;
import com.ragkb.embedding.EmbeddingClient;
import com.ragkb.retrieval.HybridRetriever;
import com.ragkb.retrieval.JdbcKeywordRetriever;
import com.ragkb.retrieval.RrfFuser;
import com.ragkb.retrieval.VectorRetriever;
import com.ragkb.vector.JdbcVectorStoreRepository;
import com.ragkb.vector.VectorStoreRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ExecutorService;

/**
 * 检索链路的装配。
 *
 * 这些组件此前都写好了但没有任何地方把它们接起来 —— 组件齐全、链路不通,
 * 表现是"代码都在,但接口调不动"。这个配置类就是那条线。
 *
 * 装配顺序刻意按依赖方向写:配置 → 向量化客户端 → 向量仓库 → 两条通道 → 融合。
 * 读代码时可以顺着往下看,不用在文件之间来回跳。
 */
@Configuration
@EnableConfigurationProperties({AiProperties.class, RetrievalProperties.class})
public class AiConfig {

    /** 向量化客户端。HTTP 传输层可注入,便于在没有网络/没有 Key 时测试。 */
    @Bean
    public EmbeddingClient embeddingClient(AiProperties properties) {
        return new DashScopeEmbeddingClient(properties.toEmbeddingProperties());
    }

    /**
     * 向量仓库。
     *
     * 绑定当前模型名:检索只在本模型的向量内进行。换模型重刷期间旧向量仍在库里,
     * 而余弦相似度跨模型没有可比性,混在一起排序会让结果失真。
     */
    @Bean
    public VectorStoreRepository vectorStoreRepository(JdbcTemplate jdbcTemplate, AiProperties properties) {
        return new JdbcVectorStoreRepository(jdbcTemplate, properties.getModel());
    }

    @Bean
    public VectorRetriever vectorRetriever(EmbeddingClient embeddingClient, VectorStoreRepository repository) {
        return new VectorRetriever(embeddingClient, repository);
    }

    /** 关键词通道。模式由配置决定,默认 TRGM(候选集是 ILIKE 的超集)。 */
    @Bean
    public JdbcKeywordRetriever keywordRetriever(JdbcTemplate jdbcTemplate, RetrievalProperties properties) {
        JdbcKeywordRetriever.Mode mode = "ILIKE".equalsIgnoreCase(properties.getKeywordMode())
                ? JdbcKeywordRetriever.Mode.ILIKE
                : JdbcKeywordRetriever.Mode.TRGM;
        return new JdbcKeywordRetriever(jdbcTemplate, mode);
    }

    /**
     * 检索线程池。
     *
     * 单独声明成 bean 是为了让容器负责关闭 —— 线程池漏关在开发期看不出来,
     * 在反复重启的测试环境里会攒下一堆空闲线程。
     */
    @Bean(name = "retrievalExecutor", destroyMethod = "shutdown")
    public ExecutorService retrievalExecutor() {
        return HybridRetriever.defaultExecutor();
    }

    /** 双通道检索:并行取两路,任一路超时或异常时降级为空,不影响另一路。 */
    @Bean
    public HybridRetriever hybridRetriever(VectorRetriever vectorRetriever,
                                           JdbcKeywordRetriever keywordRetriever,
                                           ExecutorService retrievalExecutor,
                                           RetrievalProperties properties) {
        return new HybridRetriever(vectorRetriever, keywordRetriever,
                retrievalExecutor, properties.getTimeoutMs());
    }

    @Bean
    public RrfFuser rrfFuser() {
        return new RrfFuser();
    }
}
