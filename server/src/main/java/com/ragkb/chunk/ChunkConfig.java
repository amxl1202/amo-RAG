package com.ragkb.chunk;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 切片参数的绑定入口。
 *
 * <p>{@code @ConfigurationProperties} 标在 record 上只是"声明它可以被绑定",
 * 还需要 {@code @EnableConfigurationProperties} 才会真正注册成 Bean ——
 * 少了这一行,启动时报 "No qualifying bean of type ChunkingProperties"。
 * 这也解释了为什么之前 yml 里写 app.chunking.* 完全没效果:
 * 绑定从未发生,splitter 一直用代码里的默认值。
 */
@Configuration
@EnableConfigurationProperties(ChunkingProperties.class)
public class ChunkConfig {
}
