package com.github.lechandonga.requestcorrelationobservabilityjava.web;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 演示端点：同步处理、线程池异步处理、慢请求、异常与参数错误。
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final Executor taskExecutor;
    private final ThreadPoolTaskScheduler taskScheduler;

    public DemoController(@Qualifier("correlationTaskExecutor") Executor taskExecutor,
                          @Qualifier("correlationTaskScheduler")
                          ThreadPoolTaskScheduler taskScheduler) {
        this.taskExecutor = taskExecutor;
        this.taskScheduler = taskScheduler;
    }

    /** 同步端点：响应体回显当前线程与链路标识。 */
    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(name = "name", defaultValue = "world") String name) {
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        log.info("handling hello correlationId={} name={}", correlationId, name);
        return Map.of(
                "message", "hello, " + name,
                "correlationId", correlationId,
                "thread", Thread.currentThread().getName());
    }

    /**
     * 异步端点：把工作提交到线程池。工作线程日志仍带发起方标识；
     * 响应中同时给出提交线程与工作线程看到的标识，便于验证传递正确。
     */
    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> asyncWork() throws Exception {
        String requestCorrelationId = MDC.get(CorrelationIds.MDC_KEY);
        String requestThread = Thread.currentThread().getName();
        log.info("submitting async work correlationId={} fromThread={}",
                requestCorrelationId, requestThread);

        return CompletableFuture.supplyAsync(() -> {
            // 装饰器已把提交方的 MDC 搬到该工作线程
            String asyncCorrelationId = MDC.get(CorrelationIds.MDC_KEY);
            log.info("executing async work correlationId={} workerThread={}",
                    asyncCorrelationId, Thread.currentThread().getName());
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("finished async work correlationId={} workerThread={}",
                    asyncCorrelationId, Thread.currentThread().getName());
            return Map.<String, Object>of(
                    "message", "async work done",
                    "requestCorrelationId", requestCorrelationId,
                    "asyncCorrelationId", String.valueOf(asyncCorrelationId),
                    "requestThread", requestThread,
                    "workerThread", Thread.currentThread().getName());
        }, taskExecutor);
    }

    /** 慢请求端点：故意睡眠以触发慢请求识别。 */
    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(name = "delayMs", defaultValue = "1500") long delayMs)
            throws InterruptedException {
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        log.info("handling slow correlationId={} delayMs={}", correlationId, delayMs);
        Thread.sleep(delayMs);
        return Map.of(
                "message", "slow work done",
                "correlationId", correlationId,
                "delayMs", delayMs);
    }

    /** 异常端点：抛出未处理异常，验证错误链路与脱敏响应。 */
    @GetMapping("/error")
    public Map<String, Object> error() {
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        log.info("about to fail correlationId={}", correlationId);
        throw new IllegalStateException("simulated failure with internal detail");
    }

    /** 参数错误端点：非法入参走 400 归一化。 */
    @GetMapping("/divide")
    public Map<String, Object> divide(@RequestParam("a") int a, @RequestParam("b") int b) {
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        log.info("divide correlationId={} a={} b={}", correlationId, a, b);
        if (b == 0) {
            throw new IllegalArgumentException("divisor must not be zero");
        }
        return Map.of(
                "result", a / b,
                "correlationId", correlationId);
    }

    /**
     * 由请求触发、提交到定时调度器的一次性任务：验证调度器线程同样继承发起方标识，
     * 且执行后工作线程 MDC 被恢复为空。
     */
    @GetMapping("/scheduled")
    public Map<String, Object> scheduledOneShot() {
        String requestCorrelationId = MDC.get(CorrelationIds.MDC_KEY);
        log.info("scheduling one-shot task correlationId={}", requestCorrelationId);
        taskScheduler.schedule(() -> {
            String workerCorrelationId = MDC.get(CorrelationIds.MDC_KEY);
            log.info("running scheduled one-shot correlationId={} workerThread={}",
                    workerCorrelationId, Thread.currentThread().getName());
        }, Instant.now().plus(Duration.ofMillis(50)));
        return Map.of(
                "message", "one-shot scheduled task submitted",
                "correlationId", requestCorrelationId);
    }
}
