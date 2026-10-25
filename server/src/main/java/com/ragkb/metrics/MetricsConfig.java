package com.ragkb.metrics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 埋点相关的装配。
 *
 * <p>执行器单独给一条虚拟线程池,而不是复用流式问答那个:
 * 两者的失败语义不同(埋点失败可以丢,流式失败必须告诉用户),
 * 共用池会让"埋点把池子占满"变成"问答变慢",而这条因果链很难查。
 */
@Configuration
@EnableConfigurationProperties(MetricsProperties.class)
public class MetricsConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService qaRecordExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
