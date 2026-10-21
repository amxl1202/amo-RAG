package com.ragkb.embedding;

/**
 * 向量化失败。单独一个类型,是为了让上层能区分"外部依赖挂了"和"代码逻辑错了":
 * 前者应该重试或降级,后者应该改代码。
 */
public class EmbeddingException extends RuntimeException {

    private final boolean retryable;

    public EmbeddingException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public EmbeddingException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** 重试有没有意义:网络异常与服务端 5xx 值得重试,4xx(参数/鉴权错)重试只是浪费时间。 */
    public boolean retryable() {
        return retryable;
    }
}
