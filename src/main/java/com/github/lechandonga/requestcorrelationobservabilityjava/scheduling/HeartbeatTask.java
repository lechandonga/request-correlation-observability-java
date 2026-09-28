package com.github.lechandonga.requestcorrelationobservabilityjava.scheduling;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic heartbeat used to demonstrate correlation behaviour on the
 * (single, reused) scheduler thread.
 *
 * <p>Disabled by default; enable with
 * {@code observability.heartbeat.enabled=true}. Each run logs the correlation
 * id bound by {@code CorrelationTaskDecorator}; because there is no inbound
 * request the decorator generates a fresh id per run.
 */
@Component
@ConditionalOnProperty(name = "observability.heartbeat.enabled", havingValue = "true")
public class HeartbeatTask {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatTask.class);

    @Value("${observability.heartbeat.interval-ms:60000}")
    private long intervalMs;

    @Scheduled(fixedDelayString = "#{${observability.heartbeat.interval-ms:60000}}")
    public void heartbeat() {
        log.info("Heartbeat tick on thread '{}', intervalMs={}, correlationId={}",
                Thread.currentThread().getName(), intervalMs, CorrelationContext.currentId());
    }
}
