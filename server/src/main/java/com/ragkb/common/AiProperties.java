package com.ragkb.common;

import com.ragkb.embedding.EmbeddingProperties;
import com.ragkb.generation.ChatProperties;
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

    /**
     * 对话模型名。
     *
     * 与向量模型共用同一个 Key —— 百炼的专属实例里两者是一套凭据,
     * 拆成两份配置只会让"换 Key 时改了一半"这种事故有发生的可能。
     */
    private String chatModel = ChatProperties.DEFAULT_MODEL;

    /** OpenAI 兼容的 chat/completions 地址。专属实例通过环境变量覆盖,不写进仓库。 */
    private String chatEndpoint = ChatProperties.DEFAULT_ENDPOINT;

    /** 采样温度。生成层要"照材料说",温度不能高;但也不取 0,原因见 ChatProperties。 */
    private double chatTemperature = 0.2;

    /** 单次回答的 token 上限。 */
    private int chatMaxTokens = 800;

    /** 对话请求超时(秒)。比向量化宽松:生成是长输出,首字之后还要持续接收。 */
    private int chatTimeoutSeconds = 60;

    /** 对话请求失败重试总次数(含首次)。 */
    private int chatMaxAttempts = 3;

    public EmbeddingProperties toEmbeddingProperties() {
        return new EmbeddingProperties(apiKey, model, dimension, batchSize,
                endpoint, timeoutSeconds, maxAttempts);
    }

    public ChatProperties toChatProperties() {
        return new ChatProperties(apiKey, chatModel, chatEndpoint, chatTemperature,
                chatMaxTokens, chatTimeoutSeconds, chatMaxAttempts);
    }
}
