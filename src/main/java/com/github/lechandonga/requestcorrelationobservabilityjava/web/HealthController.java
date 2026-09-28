package com.github.lechandonga.requestcorrelationobservabilityjava.web;

import com.github.lechandonga.requestcorrelationobservabilityjava.observability.RequestMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查入口：
 * <ul>
 *     <li>{@code GET /health}：轻量存活状态；</li>
 *     <li>{@code GET /health/metrics}：服务当前状态与请求统计（含慢请求阈值、错误率等）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private final RequestMetrics metrics;

    public HealthController(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping("/metrics")
    public RequestMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }
}
