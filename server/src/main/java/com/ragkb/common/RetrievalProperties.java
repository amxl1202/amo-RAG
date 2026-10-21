package com.ragkb.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索层配置。
 */
@Data
@ConfigurationProperties(prefix = "app.retrieval")
public class RetrievalProperties {

    /**
     * 单通道超时(毫秒)。
     *
     * 取 800 的依据:两路都是本地/近端调用(向量接口 + 同库 SQL),正常情况下远低于这个值;
     * 一旦某一路超过它,继续等下去只会拖慢整个请求,而降级后另一路的结果仍然可用。
     * 这个数字应当按实测的 p99 调整,不是拍死的。
     */
    private long timeoutMs = 800;

    /** 关键词通道模式:ILIKE 或 TRGM。 */
    private String keywordMode = "TRGM";

    /** 每路各取多少条候选,再交给融合。太少会让名次失去区分度。 */
    private int channelLimit = 20;
}
