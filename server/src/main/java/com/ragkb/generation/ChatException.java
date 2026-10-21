package com.ragkb.generation;

/**
 * 对话生成失败。单独一个类型,是为了让上层能区分"外部依赖挂了"和"代码逻辑错了":
 * 前者应该重试、降级成"服务繁忙",后者应该改代码。
 */
public class ChatException extends RuntimeException {

    private final boolean retryable;

    public ChatException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ChatException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** 重试有没有意义:网络异常与 5xx/429 值得重试,4xx(参数/鉴权错)重试只是浪费时间。 */
    public boolean retryable() {
        return retryable;
    }
}
