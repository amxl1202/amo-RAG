package com.ragkb.common.http;

import java.util.Map;

/**
 * HTTP 发送能力的最小抽象(只支持 POST,够用即可)。
 *
 * 抽出这一层只有一个目的:<b>让调用外部模型接口的代码能在没有网络、没有 API Key 的情况下被测试</b>。
 * 否则重试分级、响应乱序重排、维度校验这些最容易错的逻辑,只能靠真的打接口来验证 ——
 * 既慢又不稳定,而且没法构造 5xx 与超时。
 *
 * 放在 {@code common.http} 而不是某一种客户端内部:向量化和对话生成都要用它,
 * 归属任何一方都会让另一方反向依赖。传输层不感知业务,业务层才谈得上复用。
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
