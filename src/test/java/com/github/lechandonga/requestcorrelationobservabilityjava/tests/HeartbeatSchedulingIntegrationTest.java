package com.github.lechandonga.requestcorrelationobservabilityjava.tests;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Periodic {@code @Scheduled} task: every run happens outside any request, so
 * the decorator must generate a fresh id per run and the single scheduler
 * thread must not keep a previous run's id.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "observability.heartbeat.enabled=true",
        "observability.heartbeat.interval-ms=100"
})
class HeartbeatSchedulingIntegrationTest {

    @Test
    void everyHeartbeatTickGetsItsOwnCorrelationId() throws Exception {
        Logger heartbeatLogger = (Logger) LoggerFactory.getLogger(
                "com.github.lechandonga.requestcorrelationobservabilityjava.scheduling.HeartbeatTask");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        heartbeatLogger.addAppender(appender);

        // Wait for at least 3 ticks (100ms interval).
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && appender.list.size() < 3) {
            Thread.sleep(50);
        }

        List<ILoggingEvent> ticks = appender.list;
        Set<String> ids = new HashSet<>();
        for (ILoggingEvent tick : ticks) {
            String id = tick.getMDCPropertyMap().get("correlationId");
            ids.add(id);
            assertTrue(id != null && id.matches("[A-Za-z0-9._-]{8,128}"),
                    "heartbeat tick must have a legal generated id, got: " + id);
            assertTrue(tick.getThreadName().startsWith("app-scheduler-"),
                    "tick must run on the scheduler thread: " + tick.getThreadName());
            System.out.println("[HeartbeatSchedulingIntegrationTest] tick on "
                    + tick.getThreadName() + " correlationId=" + id);
        }
        assertTrue(ticks.size() >= 3, "expected at least 3 heartbeat ticks, got " + ticks.size());
        assertTrue(ids.size() == ticks.size(),
                "each scheduled run must get a distinct generated id: " + ids);
    }
}
