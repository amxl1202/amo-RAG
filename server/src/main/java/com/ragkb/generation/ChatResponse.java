package com.ragkb.generation;

/**
 * 一次模型调用的结果。
 *
 * <h2>为什么把 usage 一并带回来</h2>
 * 生成层的成本不像检索那样可以忽略:一次问答的 token 里有材料段,而材料段的长短由检索结果决定。
 * 不记录 usage 就永远不知道"多放一条证据"贵多少,也没法回答"拒答省下的那一次调用值多少钱"。
 * 这两个数在评估报告里都要出现,所以从客户端一路带到调用方。
 *
 * @param content          模型返回的正文(已去除首尾空白)
 * @param model            实际使用的模型名,便于日志里区分
 * @param promptTokens     输入 token 数;接口未返回时为 0(不要用本地估算填空,口径会混)
 * @param completionTokens 输出 token 数;接口未返回时为 0
 * @param latencyMillis    含网络往返的耗时
 */
public record ChatResponse(
        String content,
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMillis) {

    public ChatResponse {
        content = content == null ? "" : content.strip();
    }
}
