package com.ragkb.health;

import com.ragkb.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查:分 live 与 ready 两个语义。
 *
 * <h2>为什么不能一个 /health 走天下</h2>
 * 第一版恒返回 UP —— 数据库挂了也是 UP,于是容器编排会认为"这个实例很健康",
 * 把流量继续打进来,而所有请求都在等连接池超时。探针的价值在于<b>区分</b>:
 * <ul>
 *   <li><b>live</b>:进程还活着吗?只要活着就 200。它对应"要不要重启这个容器" ——
 *       数据库挂了不该重启应用,重启也修不好。</li>
 *   <li><b>ready</b>:现在能接流量吗?要能连通数据库才 200。它对应"要不要把流量摘掉"。</li>
 * </ul>
 * 这两个判断混在一起,就会出现"库挂了 → 应用被反复重启 → 库还是挂着"的雪崩。
 *
 * <h2>为什么不检查模型服务</h2>
 * 模型 API 是计费的、且回包要几百毫秒以上:放进探针会让每次健康检查都产生费用与延迟。
 * 缺 API Key 只影响问答,不影响服务可用 —— 那属于"功能降级",不是"不健康"。
 * 这一条在启动日志里告警,而不是让探针变红。
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 存活探针:进程在跑就 200。不碰数据库,避免"库抖动导致容器被重启"。 */
    @GetMapping("/health/live")
    public ApiResponse<Map<String, Object>> live() {
        return ApiResponse.ok(body("UP", null));
    }

    /**
     * 就绪探针:能连上数据库才算就绪。
     *
     * <p>{@code /health} 保留为它的别名 —— 旧脚本与演示文档用的都是这个路径,
     * 改语义而不改路径,比留着一个恒 UP 的假探针更安全。
     */
    @GetMapping({"/health", "/health/ready"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> ready() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(ApiResponse.ok(body("UP", "UP")));
        } catch (RuntimeException e) {
            log.warn("readiness probe failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(503, "数据库不可用"));
        }
    }

    private static Map<String, Object> body(String status, String db) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (db != null) {
            body.put("db", db);
        }
        body.put("time", LocalDateTime.now().toString());
        return body;
    }
}
