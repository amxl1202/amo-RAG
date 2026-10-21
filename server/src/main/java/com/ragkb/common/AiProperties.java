package com.ragkb.common;

import com.ragkb.embedding.EmbeddingProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量化配置的绑定层。
 *
 * 为什么不让 {@link EmbeddingProperties} 自己带上 {@code @ConfigurationProperties}:
 * 那个 record 属于"脱离框架也能跑"的核心层,命令行工具和单元测试都直接 new 它。
 * 把 Spring 注解钉上去,等于让核心层依赖容器。所以这里做一层可变的配置持有者,
 * 由 {@link #toEmbeddingProperties()} 转成核心层认识的 record。
 *
 * 这个类的存在同时收掉了一个审核提出的问题:此前 yml 里的 {@code app.ai.dashscope.*}
 * 没有任何代码读取,是一份"看起来在生效"的死配置。
 */
@Data
@ConfigurationProperties(prefix = "app.ai.dashscope")
public class AiProperties {

    /** API Key。为空时客户端在真正调用时才报错,便于无网络环境构造对象与跑单测。 */
    private String apiKey = "";

    /** 向量模型名。同时决定入库时写进 chunk_embeddings.model 的值。 */
    private String model = EmbeddingProperties.DEFAULT_MODEL;

    /** 向量维度,必须与 schema.sql 的 vector(N) 一致。 */
    private int dimension = 512;

    /** 单次请求最多带几条文本。 */
    private int batchSize = 10;

    /** 接口地址。专属实例(百炼 MaaS)通过环境变量覆盖,不写进仓库。 */
    private String endpoint = EmbeddingProperties.DEFAULT_ENDPOINT;

    /** 单次请求超时(秒)。 */
    private int timeoutSeconds = 30;

    /** 失败重试总次数(含首次)。 */
    private int maxAttempts = 3;

    public EmbeddingProperties toEmbeddingProperties() {
        return new EmbeddingProperties(apiKey, model, dimension, batchSize,
                endpoint, timeoutSeconds, maxAttempts);
    }
}
