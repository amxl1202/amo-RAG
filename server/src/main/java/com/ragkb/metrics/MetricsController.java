package com.ragkb.metrics;

import com.ragkb.common.ApiResponse;
import com.ragkb.metrics.mapper.MetricsMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营向的统计接口。
 *
 * <p>路径不在白名单里,所以它和业务接口一样需要登录 —— 统计数据能反推使用量,
 * 不该匿名可读。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    /** 最多看 90 天:再长就不是"近期画像"而是历史归档,该走离线分析。 */
    private static final int MAX_DAYS = 90;

    private final MetricsMapper mapper;
    private final MetricsProperties properties;

    public MetricsController(MetricsMapper mapper, MetricsProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @GetMapping("/summary")
    public ApiResponse<MetricsSummary> summary(@RequestParam(defaultValue = "7") int days) {
        int window = Math.max(1, Math.min(days, MAX_DAYS));
        return ApiResponse.ok(MetricsSummary.from(mapper.summarize(window), properties, window));
    }
}
