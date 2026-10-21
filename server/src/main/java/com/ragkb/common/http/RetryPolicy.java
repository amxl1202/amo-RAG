package com.ragkb.common.http;

/**
 * 调用外部模型接口时的重试判据。
 *
 * <h2>为什么要单独抽出来</h2>
 * 向量化与对话生成的重试策略是同一套:429(限流)与 5xx(服务端故障)值得退避重试,
 * 其余 4xx(参数错、Key 错、模型名错)重试只是浪费时间,还会把真正的错误原因埋在重试日志里。
 * 这套判据写在两个客户端里就会各自演化 —— 一处改了 429、另一处忘了改,行为就分叉了。
 *
 * 抽成两个静态方法而不是一个"带重试的执行器",是因为两个客户端的重试循环体差别不小:
 * 向量化要在成功后做乱序重排与维度校验,生成要处理空 choices 与拒答串。
 * 用回调把循环体塞进公共层,读起来反而更绕。
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    /** 429 是限流,值得退避;5xx 是服务端问题,也值得;4xx 其余是请求本身有问题,重试无用。 */
    public static boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    /**
     * 线性退避等待。
     *
     * @return true 表示可以继续重试;false 表示等待期间被中断,调用方应当直接放弃
     */
    public static boolean sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(200L * attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
