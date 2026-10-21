package com.ragkb.embedding;

/**
 * 向量化所需的配置。
 *
 * 维度固定为 512 不是随手取的:向量表的列是 {@code vector(512)},改维度要重建表并重刷全量数据。
 * 所以"换模型"在这套设计里是一次真正的迁移,不是改个配置 —— 这也正是把向量单独放一张表
 * 的理由:至少切片本身不用重切。
 *
 * @param apiKey         DashScope 的 API Key;为空时客户端在真正调用时才报错,便于无网络环境下构造
 * @param model          模型名;向量表里也记一份,便于换了模型之后能分辨哪些行是旧模型算的
 * @param dimension      向量维度,必须与向量表 DDL 里的 vector(N) 一致
 * @param batchSize      单次请求最多带几条文本。接口对批量有上限,超了会被整体拒绝
 * @param endpoint       接口地址
 * @param timeoutSeconds 单次请求超时
 * @param maxAttempts    失败重试总次数(含首次)
 */
public record EmbeddingProperties(
        String apiKey,
        String model,
        int dimension,
        int batchSize,
        String endpoint,
        int timeoutSeconds,
        int maxAttempts) {

    public static final String DEFAULT_MODEL = "text-embedding-v3";
    public static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    public EmbeddingProperties {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("embedding model 不能为空");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension 必须为正: " + dimension);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正: " + batchSize);
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须为正: " + timeoutSeconds);
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts 必须为正: " + maxAttempts);
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint 不能为空");
        }
    }

    public static EmbeddingProperties defaults() {
        return new EmbeddingProperties("", DEFAULT_MODEL, 512, 10, DEFAULT_ENDPOINT, 30, 3);
    }

    public EmbeddingProperties withApiKey(String key) {
        return new EmbeddingProperties(key, model, dimension, batchSize, endpoint, timeoutSeconds, maxAttempts);
    }

    /**
     * 从<b>进程环境变量</b>读取配置。
     *
     * 注意这里读的是 {@link System#getenv},<b>看不见 .env 文件</b>:spring-dotenv 只把 .env
     * 注入 Spring 的属性占位符解析,不会写进进程环境(JVM 启动后进程环境本就不可变)。
     * 所以这个方法只服务命令行工具与测试;Spring 容器内的装配应该走
     * {@code @ConfigurationProperties(prefix = "app.ai.dashscope")},让 .env 里的值通过占位符生效。
     *
     * 没有 Key 时不报错 —— 构造配置和执行调用是两件事,单元测试与离线工具都应该能在没有 Key
     * 的情况下构造出对象。
     */
    public static EmbeddingProperties fromEnvironment() {
        EmbeddingProperties base = defaults();
        return new EmbeddingProperties(
                env("DASHSCOPE_API_KEY", ""),
                env("EMBEDDING_MODEL", base.model()),
                intEnv("EMBEDDING_DIMENSION", base.dimension()),
                intEnv("EMBEDDING_BATCH_SIZE", base.batchSize()),
                env("EMBEDDING_ENDPOINT", base.endpoint()),
                intEnv("EMBEDDING_TIMEOUT_SECONDS", base.timeoutSeconds()),
                intEnv("EMBEDDING_MAX_ATTEMPTS", base.maxAttempts()));
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
