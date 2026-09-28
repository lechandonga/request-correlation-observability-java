package com.github.lechandonga.requestcorrelationobservabilityjava.tests;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks over real HTTP: header reuse/normalisation, concurrent
 * isolation, async propagation, safe error bodies, slow-request detection,
 * health statistics and MDC-tagged server logs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "observability.slow-threshold-ms=80",
        "observability.heartbeat.enabled=false"
})
class CorrelationWebIntegrationTest {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CorrelationWebIntegrationTest.class);
    private static final String CORR_HEADER = "X-Correlation-Id";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ------------------------------------------------------------------ reuse

    @Test
    void legalInboundIdIsReusedInHeaderAndBody() throws Exception {
        String inbound = "trace-http-legal-0001";
        ResponseEntity<String> response = getWithHeader("/api/demo/ping", CORR_HEADER, inbound);

        assertEquals(inbound, response.getHeaders().getFirst(CORR_HEADER),
                "response header must echo the reused id");
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals(inbound, body.get("correlationId").asText(),
                "response body must carry the same id");
        log.info("reuse check: sent='{}', header='{}', body='{}' -> reused",
                inbound, response.getHeaders().getFirst(CORR_HEADER),
                body.get("correlationId").asText());
    }

    @Test
    void missingOrIllegalIdIsNormalizedToGeneratedId() throws Exception {
        // 1) no header at all
        ResponseEntity<String> missing = restTemplate.getForEntity(url("/api/demo/ping"), String.class);
        String generated = missing.getHeaders().getFirst(CORR_HEADER);
        assertNotNull(generated);
        assertTrue(generated.startsWith("req-"), "generated id expected, got: " + generated);
        assertTrue(generated.length() >= 8 + 4 && generated.length() <= 128);
        log.info("missing header -> generated='{}' (basis: no {} header present)", generated, CORR_HEADER);

        // 2) illegal values
        String[] illegal = {"short", "has space", "x".repeat(129)};
        for (String bad : illegal) {
            ResponseEntity<String> response = getWithHeader("/api/demo/ping", CORR_HEADER, bad);
            String normalized = response.getHeaders().getFirst(CORR_HEADER);
            assertNotNull(normalized);
            assertFalse(normalized.equals(bad), "illegal id must not be echoed back: " + bad);
            assertTrue(normalized.matches("[A-Za-z0-9._-]{8,128}"),
                    "normalized id must be legal: " + normalized);
            log.info("illegal header='{}' -> normalized='{}' (basis: illegal value replaced by generated id)",
                    bad, normalized);
        }
    }

    @Test
    void aliasHeaderAndDistinctRequestsNeverShareIds() {
        ResponseEntity<String> aliased =
                getWithHeader("/api/demo/ping", "X-Request-Id", "trace-alias-http-001");
        assertEquals("trace-alias-http-001", aliased.getHeaders().getFirst(CORR_HEADER));
        log.info("alias header -> reused='{}'", aliased.getHeaders().getFirst(CORR_HEADER));

        String first = restTemplate.getForEntity(url("/api/demo/ping"), String.class)
                .getHeaders().getFirst(CORR_HEADER);
        String second = restTemplate.getForEntity(url("/api/demo/ping"), String.class)
                .getHeaders().getFirst(CORR_HEADER);
        assertNotNull(first);
        assertFalse(first.equals(second), "two requests must not share a generated id");
        log.info("two anonymous requests got distinct ids: '{}' vs '{}'", first, second);
    }

    // ------------------------------------------------------------ concurrency

    @Test
    void concurrentRequestsDoNotCrossCorrelationIds() throws Exception {
        int threads = 24;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Map<String, String> mismatches = new ConcurrentHashMap<>();
        AtomicInteger completed = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String myId = "trace-conc-" + Thread.currentThread().getId()
                                + "-" + System.nanoTime() + "-" + i;
                        ResponseEntity<String> response =
                                getWithHeader("/api/demo/ping", CORR_HEADER, myId);
                        String echoed = response.getHeaders().getFirst(CORR_HEADER);
                        completed.incrementAndGet();
                        if (!myId.equals(echoed)) {
                            mismatches.put(myId, echoed);
                        }
                    }
                } catch (Exception e) {
                    mismatches.put("exception", e.toString());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("concurrent check: {}/{} ok, mismatches={}", completed.get(),
                threads * perThread, mismatches);
        assertEquals(0, mismatches.size(),
                "correlation ids must never cross between concurrent requests: " + mismatches);
        assertEquals(threads * perThread, completed.get());
    }

    // ------------------------------------------------------------------ async

    @Test
    void asyncWorkerInheritsRequestCorrelationId() throws Exception {
        String inbound = "trace-async-worker-0001";
        ResponseEntity<String> response = getWithHeader("/api/demo/async", CORR_HEADER, inbound);
        JsonNode body = objectMapper.readTree(response.getBody());

        assertEquals(inbound, response.getHeaders().getFirst(CORR_HEADER));
        assertEquals(inbound, body.get("correlationId").asText());
        assertTrue(body.get("propagated").asBoolean(),
                "worker thread must observe the caller's correlation id");
        assertFalse(body.get("thread").asText().startsWith("http"),
                "work must actually have run on a worker thread: " + body.get("thread").asText());
        log.info("async check: id='{}' propagated to worker thread='{}'",
                inbound, body.get("thread").asText());
    }

    @Test
    void workerThreadReuseDoesNotKeepOldId() throws Exception {
        // Far more tasks than the two pooled worker threads (max 4), and each
        // task asserts the id it sees. The decorator's finally-restore is what
        // keeps this green.
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add("trace-reuse-" + (5000 + i));
        }
        for (String inbound : ids) {
            ResponseEntity<String> response = getWithHeader("/api/demo/async", CORR_HEADER, inbound);
            JsonNode body = objectMapper.readTree(response.getBody());
            assertEquals(inbound, body.get("correlationId").asText(),
                    "reused worker thread saw a stale correlation id");
            assertTrue(body.get("propagated").asBoolean());
        }
        log.info("worker-reuse check: 20 sequential async calls on a 2-thread pool, none leaked");
    }

    @Test
    void fireAndForgetAndSchedulerTasksCarryCallerId() throws Exception {
        Logger packageLogger = (Logger) LoggerFactory.getLogger(
                "com.github.lechandonga.requestcorrelationobservabilityjava");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        packageLogger.addAppender(appender);
        try {
            String workerCallId = "trace-faf-0001-aaaa";
            String scheduledCallId = "trace-sched-call-001";

            assertEquals(200, postWithHeader("/api/demo/fire-and-forget", CORR_HEADER, workerCallId)
                    .getStatusCode().value());
            postWithHeader("/api/demo/scheduled", CORR_HEADER, scheduledCallId);

            String workerLine = waitForLog(appender, "app-worker-", workerCallId, 3000);
            String schedulerLine = waitForLog(appender, "app-scheduler-", scheduledCallId, 3000);
            assertNotNull(workerLine, "fire-and-forget worker log must carry the caller id");
            assertNotNull(schedulerLine, "scheduler log must carry the caller id");
            log.info("cross-pool propagation verified: worker='{}' | scheduler='{}'",
                    workerLine, schedulerLine);
        } finally {
            packageLogger.detachAppender(appender);
        }
    }

    // ------------------------------------------------------------------ errors

    @Test
    void businessErrorReturnsSafeTaggedBody() throws Exception {
        ResponseEntity<String> response =
                getWithHeader("/api/demo/error?kind=business", CORR_HEADER, "trace-error-biz-001");
        assertEquals(422, response.getStatusCode().value());
        assertEquals("trace-error-biz-001", response.getHeaders().getFirst(CORR_HEADER));
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals(422, body.get("status").asInt());
        assertEquals("trace-error-biz-001", body.get("correlationId").asText());
        assertFalse(response.getBody().contains("BusinessException"),
                "exception class must not leak to the client");
        log.info("business error body is safe and tagged: status={}, correlationId={}, message='{}'",
                body.get("status").asInt(), body.get("correlationId").asText(),
                body.get("message").asText());
    }

    @Test
    void unexpectedErrorDoesNotLeakInternals() throws Exception {
        ResponseEntity<String> response =
                getWithHeader("/api/demo/error?kind=boom", CORR_HEADER, "trace-error-500-001");
        assertEquals(500, response.getStatusCode().value());
        assertEquals("trace-error-500-001", response.getHeaders().getFirst(CORR_HEADER));
        String responseBody = response.getBody();
        assertFalse(responseBody.contains("IllegalStateException"), "exception type must not leak");
        assertFalse(responseBody.contains("internal detail"), "exception message must not leak");
        assertFalse(responseBody.contains("at com.github"), "stack trace must not leak");
        JsonNode body = objectMapper.readTree(responseBody);
        assertEquals("trace-error-500-001", body.get("correlationId").asText());
        log.info("unexpected error body is generic, correlationId='{}'",
                body.get("correlationId").asText());
    }

    @Test
    void unknownRouteReturnsTagged404() throws Exception {
        ResponseEntity<String> response =
                getWithHeader("/no/such/route", CORR_HEADER, "trace-404-0001aaaa");
        assertEquals(404, response.getStatusCode().value());
        assertEquals("trace-404-0001aaaa", response.getHeaders().getFirst(CORR_HEADER));
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("trace-404-0001aaaa", body.get("correlationId").asText());
        log.info("404 body tagged with correlationId='{}'",
                body.get("correlationId").asText());
    }

    // ------------------------------------------------------------ slow/health

    @Test
    void slowRequestIsFlaggedAndReflectedInHealthStats() throws Exception {
        ResponseEntity<String> slow =
                getWithHeader("/api/demo/slow?millis=200", CORR_HEADER, "trace-slow-0001aaa");
        assertEquals(200, slow.getStatusCode().value());

        JsonNode health = objectMapper.readTree(
                restTemplate.getForEntity(url("/health"), String.class).getBody());
        log.info("health snapshot: {}", health);

        assertEquals("UP", health.get("status").asText());
        assertTrue(health.get("statistics").get("slowRequests").asInt() >= 1,
                "slow request must be counted");
        assertTrue(health.get("statistics").get("totalRequests").asInt() >= 2);
        assertTrue(health.get("statistics").has("averageDurationMillis"));
        assertTrue(health.get("statistics").has("errorsByReason"));
    }

    @Test
    void errorCountersAreExposedByReason() throws Exception {
        getWithHeader("/api/demo/error?kind=boom", CORR_HEADER, "trace-stats-0001aaaa");
        JsonNode health = objectMapper.readTree(
                restTemplate.getForEntity(url("/health"), String.class).getBody());
        int errors = health.get("statistics").get("errorRequests").asInt();
        assertTrue(errors >= 1, "error must be counted");
        JsonNode byReason = health.get("statistics").get("errorsByReason");
        assertTrue(byReason.has("IllegalStateException"),
                "errors must be grouped by distinguishable reason, got: " + byReason);
        log.info("errorsByReason={}", byReason);
    }

    // ------------------------------------------------------------- MDC logging

    @Test
    void serverLogLinesCarryCorrelationIdAndSlowWarningIsEmitted() {
        Logger packageLogger = (Logger) LoggerFactory.getLogger(
                "com.github.lechandonga.requestcorrelationobservabilityjava");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        packageLogger.addAppender(appender);
        try {
            String inbound = "trace-log-mdc-0001aa";
            getWithHeader("/api/demo/slow?millis=150", CORR_HEADER, inbound);

            boolean sawTaggedLine = false;
            boolean sawDecisionLog = false;
            boolean sawSlowWarning = false;
            for (ILoggingEvent event : appender.list) {
                String mdcId = event.getMDCPropertyMap().get("correlationId");
                if (inbound.equals(mdcId)) {
                    sawTaggedLine = true;
                }
                if (event.getFormattedMessage().contains("Request received")
                        && event.getFormattedMessage().contains(inbound)
                        && event.getFormattedMessage().contains("reused")) {
                    sawDecisionLog = true;
                }
                if (event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("Slow request detected")
                        && event.getFormattedMessage().contains(inbound)) {
                    sawSlowWarning = true;
                }
            }
            log.info("log check for '{}': taggedLine={}, decisionLog={}, slowWarning={}",
                    inbound, sawTaggedLine, sawDecisionLog, sawSlowWarning);
            assertTrue(sawTaggedLine, "at least one server log line must carry the MDC id");
            assertTrue(sawDecisionLog, "filter must log the decision and its basis");
            assertTrue(sawSlowWarning, "slow request must emit a WARN with the correlation id");
        } finally {
            packageLogger.detachAppender(appender);
        }
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<String> getWithHeader(String path, String headerName, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(headerName, value);
        return restTemplate.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> postWithHeader(String path, String headerName, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(headerName, value);
        return restTemplate.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    /**
     * Poll the in-memory log appender until a line on the given thread prefix
     * carries the expected MDC correlation id.
     */
    private String waitForLog(ListAppender<ILoggingEvent> appender,
                              String threadPrefix, String expectedCorrelationId,
                              long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (ILoggingEvent event : new ArrayList<>(appender.list)) {
                String mdcId = event.getMDCPropertyMap().get("correlationId");
                if (expectedCorrelationId.equals(mdcId)
                        && event.getThreadName().startsWith(threadPrefix)) {
                    return "[" + event.getThreadName() + "] " + event.getFormattedMessage();
                }
            }
            Thread.sleep(20);
        }
        return null;
    }
}
