package com.ragkb.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 数据库不可达时跳过依赖真库的测试类。
 *
 * <h2>为什么需要它,而不是在测试里用 Assumptions</h2>
 * 对本项目其余真库测试,{@code Assumptions} 够用 —— 它们自己建 {@code JdbcTemplate},
 * 连不上就在用例里 skip。但 {@code @SpringBootTest} 的容器在<b>用例执行之前</b>就加载了,
 * 连不上库时整个类会变成 7 个 Error,构建直接红。
 *
 * <h2>为什么条件是"端口能连通"而不是"能查到表"</h2>
 * 这个判断只为一个目的:区分"没开 Docker"(该跳过)与"库起了但代码有问题"(该红)。
 * 端口通了之后若 schema 或 SQL 有问题,容器照样会失败、构建照样红 —— 那种红是正确的。
 * 反过来,如果把条件写成"能查到某张表",schema 缺失也会被 skip 掉,那就把真缺陷藏起来了。
 *
 * <h2>为什么用 ExecutionCondition 而不是 @EnabledIf</h2>
 * JUnit 的容器级判断在 Spring 扩展之前求值,所以它能在上下文加载之前拦住这个类。
 * 端口来自 {@code DB_PORT}(默认 5433),与 {@code dev-db.sh} 暴露的端口一致。
 */
public class DatabaseAvailableCondition implements ExecutionCondition {

    private static final String DEFAULT_PORT = "5433";
    private static final int TIMEOUT_MILLIS = 800;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        int port = Integer.parseInt(System.getenv().getOrDefault("DB_HOST_PORT", DEFAULT_PORT));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MILLIS);
            return ConditionEvaluationResult.enabled("数据库端口 " + port + " 可连通");
        } catch (Exception e) {
            return ConditionEvaluationResult.disabled(
                    "数据库端口 " + port + " 连不上,跳过依赖真库的测试(先跑 scripts/dev-db.sh)");
        }
    }
}
