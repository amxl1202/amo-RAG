package com.ragkb.embedding;

import java.util.Map;

/**
 * HTTP 发送能力的最小抽象。
 *
 * 抽出这一层只有一个目的:<b>让向量化客户端能在没有网络、没有 API Key 的情况下被测试</b>。
 * 否则重试、乱序重排、维度校验这些最容易错的逻辑,只能靠真的打接口来验证 ——
 * 既慢又不稳定,而且没法构造 5xx 与超时。
 */
@FunctionalInterface
public interface HttpPoster {

    Response post(String url, Map<String, String> headers, String jsonBody) throws Exception;

    /**
     * @param status HTTP 状态码
     * @param body   响应体原文
     */
    record Response(int status, String body) {
    }
}
