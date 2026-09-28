package com.github.lechandonga.requestcorrelationobservabilityjava.web;

import com.github.lechandonga.requestcorrelationobservabilityjava.observability.RequestStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight liveness/readiness endpoint that also reports the accumulated
 * request statistics. Deliberately framework-agnostic (no actuator
 * dependency) and free of sensitive internals.
 */
@RestController
public class HealthController {

    private static final long START_MILLIS = System.currentTimeMillis();

    private final RequestStats requestStats;

    public HealthController(RequestStats requestStats) {
        this.requestStats = requestStats;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        body.put("startedAtMillis", START_MILLIS);
        body.put("statistics", requestStats.snapshot());
        return body;
    }
}
