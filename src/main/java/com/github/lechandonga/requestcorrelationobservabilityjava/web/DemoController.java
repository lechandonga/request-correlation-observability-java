package com.github.lechandonga.requestcorrelationobservabilityjava.web;

import com.github.lechandonga.requestcorrelationobservabilityjava.config.AsyncConfig;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Demo endpoints exercising every correlation path:
 *
 * <ul>
 *     <li>{@code GET  /api/demo/ping}           - plain synchronous call;</li>
 *     <li>{@code GET  /api/demo/async}          - work executed on the
 *         decorated worker pool, id captured inside the worker thread;</li>
 *     <li>{@code POST /api/demo/fire-and-forget}- task submitted and left
 *         running asynchronously;</li>
 *     <li>{@code POST /api/demo/scheduled}      - one-shot on the scheduler
 *         pool (shows propagation to a different pool as well);</li>
 *     <li>{@code GET  /api/demo/slow}           - configurable delay to trip
 *         the slow-request threshold;</li>
 *     <li>{@code GET  /api/demo/error}          - failure paths
 *         (business / illegal-argument / unexpected).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final Executor workerPool;
    private final ThreadPoolTaskScheduler taskScheduler;

    public DemoController(@Qualifier(AsyncConfig.APPLICATION_TASK_EXECUTOR) Executor workerPool,
                          @Qualifier("taskScheduler") ThreadPoolTaskScheduler taskScheduler) {
        this.workerPool = workerPool;
        this.taskScheduler = taskScheduler;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        log.info("ping handled synchronously");
        return payload("sync", CorrelationContext.currentId());
    }

    @GetMapping("/async")
    public Map<String, Object> asyncWork() throws InterruptedException, ExecutionException {
        String callerId = CorrelationContext.currentId();
        log.info("About to submit work to the worker pool, callerCorrelationId={}", callerId);

        Future<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
            String workerId = CorrelationContext.currentId();
            log.info("Worker thread '{}' is running the async task, workerCorrelationId={}",
                    Thread.currentThread().getName(), workerId);
            Map<String, Object> result = new LinkedHashMap<>(payload("async-worker", workerId));
            result.put("propagated", callerId.equals(workerId));
            return result;
        }, workerPool);

        return future.get();
    }

    @PostMapping("/fire-and-forget")
    public Map<String, Object> fireAndForget() {
        String callerId = CorrelationContext.currentId();
        workerPool.execute(() -> log.info("Fire-and-forget task on thread '{}', correlationId={}",
                Thread.currentThread().getName(), CorrelationContext.currentId()));
        log.info("Fire-and-forget task submitted with correlationId={}", callerId);
        return payload("fire-and-forget", callerId);
    }

    @PostMapping("/scheduled")
    public Map<String, Object> scheduleOneShot() {
        String callerId = CorrelationContext.currentId();
        taskScheduler.schedule(
                () -> log.info("One-shot scheduled task on thread '{}', correlationId={}",
                        Thread.currentThread().getName(), CorrelationContext.currentId()),
                Instant.now().plusMillis(50));
        log.info("One-shot scheduled task submitted with correlationId={}", callerId);
        return payload("scheduled", callerId);
    }

    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "150") long millis)
            throws InterruptedException {
        if (millis < 0 || millis > 10_000) {
            throw new IllegalArgumentException("millis must be between 0 and 10000");
        }
        log.info("Sleeping for {}ms to simulate slow work", millis);
        Thread.sleep(millis);
        return payload("slow", CorrelationContext.currentId());
    }

    @GetMapping("/error")
    public Map<String, Object> error(@RequestParam(defaultValue = "business") String kind) {
        log.info("Simulating failure of kind={}", kind);
        switch (kind) {
            case "business" -> throw new BusinessException("The requested operation is not allowed.");
            case "illegal" -> throw new IllegalArgumentException("invalid kind");
            case "boom" -> throw new IllegalStateException("internal detail that must not leak");
            default -> throw new IllegalArgumentException("unknown kind: " + kind);
        }
    }

    private static Map<String, Object> payload(String mode, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("thread", Thread.currentThread().getName());
        body.put("correlationId", correlationId);
        return body;
    }
}
