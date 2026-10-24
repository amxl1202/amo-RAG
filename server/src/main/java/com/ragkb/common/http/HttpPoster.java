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
     * 流式 POST:每读到一行就回调一次,用于 SSE 这类"响应体不是一次给完"的接口。
     *
     * <h2>为什么放在同一个接口里,而且是 default 方法</h2>
     * 它是传输层的另一种形态,不是另一种传输层。写成 default 有两个好处:
     * 保持 {@link FunctionalInterface}(lambda 仍可当实现用,现有测试不用改),
     * 以及让"不支持流式"的实现直接抛异常,而不是静默返回空流 ——
     * 静默会变成"答案一个字都没推,但接口 200"。
     *
     * @param onLine 每行回调(不含换行符);空行与 {@code data:} 前缀由实现方按需处理
     * @return 状态码与<b>非 2xx 时</b>的错误体;成功时 body 为空,内容已经通过回调流出
     */
    default Response postStreaming(String url, Map<String, String> headers, String jsonBody,
                                   java.util.function.Consumer<String> onLine) throws Exception {
        throw new UnsupportedOperationException("该 HttpPoster 实现不支持流式请求");
    }

    /**
     * @param status HTTP 状态码
     * @param body   响应体原文
     */
    record Response(int status, String body) {
    }
}
