package com.ragkb.generation;

/**
 * 对话模型所需的配置。
 *
 * <h2>为什么温度默认 0.2 而不是 0</h2>
 * 这一层要的是"照材料说,不要发挥"。温度 0 在多数实现里是贪心解码,遇到同分候选时
 * 会稳定选同一个词,长文本里容易陷入重复;0.2 留出极小的随机性,足以避开这种退化,
 * 又不至于让答案漂移。真要把"可复现"放在第一位,再降到 0 —— 但要接受重复输出的风险。
 *
 * @param apiKey         API Key;为空时客户端在真正调用时才报错,便于离线构造对象与跑单测
 * @param model          模型名
 * @param endpoint       OpenAI 兼容的 chat/completions 地址;专属实例通过环境变量覆盖
 * @param temperature    采样温度
 * @param maxTokens      单次回答的最大 token 数。答案要短:长回答更容易溢出材料范围
 * @param timeoutSeconds 单次请求超时
 * @param maxAttempts    失败重试总次数(含首次)
 */
public record ChatProperties(
        String apiKey,
        String model,
        String endpoint,
        double temperature,
        int maxTokens,
        int timeoutSeconds,
        int maxAttempts) {

    public static final String DEFAULT_MODEL = "qwen-plus";
    public static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    public ChatProperties {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("chat model 不能为空");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("chat endpoint 不能为空");
        }
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature 应在 0~2: " + temperature);
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须为正: " + maxTokens);
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须为正: " + timeoutSeconds);
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts 必须为正: " + maxAttempts);
        }
    }

    public static ChatProperties defaults() {
        return new ChatProperties("", DEFAULT_MODEL, DEFAULT_ENDPOINT, 0.2, 800, 60, 3);
    }

    public ChatProperties withApiKey(String key) {
        return new ChatProperties(key, model, endpoint, temperature, maxTokens, timeoutSeconds, maxAttempts);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
