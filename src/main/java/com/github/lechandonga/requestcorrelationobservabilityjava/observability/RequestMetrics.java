package com.github.lechandonga.requestcorrelationobservabilityjava.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内请求统计（线程安全）：总量、错误数、慢请求数、在途请求数、耗时分布，
 * 以及最近若干条错误样本，供健康检查端点反映服务当前状态。
 */
@Component
public class RequestMetrics {

    /** 最近错误样本的保留条数。 */
    static final int MAX_RECENT_ERRORS = 20;

    private final long slowThresholdMs;

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong errorRequests = new AtomicLong();
    private final AtomicLong slowRequests = new AtomicLong();
    private final AtomicLong activeRequests = new AtomicLong();
    private final AtomicLong totalDurationMs = new AtomicLong();
    private final AtomicLong maxDurationMs = new AtomicLong();

    /**
     * 当前线程上“正在执行的过滤器派发数”，仅用于健康端点把统计请求自身排除在在途数之外。
     * 每次派发进入 +1、退出 -1，因此它反映的是当前线程此刻的执行深度，
     * 不会把其它线程上 REQUEST/ASYNC 派发的在途计数混进来。
     */
    private static final ThreadLocal<IntegerHolder> IN_FLIGHT_ON_THREAD =
            ThreadLocal.withInitial(IntegerHolder::new);

    private final Queue<ErrorSample> recentErrors = new ConcurrentLinkedQueue<>();

    public RequestMetrics(
            @Value("${observability.slow-threshold-ms:1000}") long slowThresholdMs) {
        this.slowThresholdMs = slowThresholdMs;
    }

    /** 每次过滤器派发进入时调用：当前线程执行深度 +1。 */
    public void dispatchEntered() {
        IN_FLIGHT_ON_THREAD.get().value++;
    }

    /** 每次过滤器派发退出时调用：当前线程执行深度 -1。 */
    public void dispatchExited() {
        IntegerHolder holder = IN_FLIGHT_ON_THREAD.get();
        holder.value = Math.max(0, holder.value - 1);
        if (holder.value == 0) {
            IN_FLIGHT_ON_THREAD.remove();
        }
    }

    /** 请求开始（仅初始 REQUEST 派发）：总数 +1、全局在途 +1。 */
    public void requestStarted() {
        totalRequests.incrementAndGet();
        activeRequests.incrementAndGet();
    }

    /** 请求正常结束（收口派发，可在别的线程）：在途 -1 并更新耗时/慢请求统计。 */
    public void requestSucceeded(long durationMs) {
        activeRequests.decrementAndGet();
        recordDuration(durationMs);
    }

    /** 请求异常结束（收口派发，可在别的线程）：在途 -1、错误 +1 并保留脱敏错误样本。 */
    public void requestFailed(long durationMs, String correlationId, String errorType) {
        activeRequests.decrementAndGet();
        recordDuration(durationMs);
        errorRequests.incrementAndGet();
        recentErrors.offer(new ErrorSample(correlationId, errorType, Instant.now().toString()));
        while (recentErrors.size() > MAX_RECENT_ERRORS) {
            recentErrors.poll();
        }
    }

    /** 当前总在途请求数。 */
    public long currentActiveRequests() {
        return activeRequests.get();
    }

    private static final class IntegerHolder {
        private int value;
    }

    private void recordDuration(long durationMs) {
        totalDurationMs.addAndGet(Math.max(durationMs, 0));
        maxDurationMs.accumulateAndGet(Math.max(durationMs, 0), Math::max);
        if (durationMs >= slowThresholdMs) {
            slowRequests.incrementAndGet();
        }
    }

    public long getSlowThresholdMs() {
        return slowThresholdMs;
    }

    /**
     * 生成当前统计快照。健康端点在“统计请求自身”的处理过程中调用，
     * 因此排除调用所在线程的在途计数，只反映其它正在处理的请求。
     */
    public Snapshot snapshot() {
        return snapshot(true);
    }

    /**
     * @param excludeCallingThread true 时排除调用所在线程的在途计数
     */
    public Snapshot snapshot(boolean excludeCallingThread) {
        long total = totalRequests.get();
        long errors = errorRequests.get();
        long durationSum = totalDurationMs.get();
        long selfOnThread = IN_FLIGHT_ON_THREAD.get().value;
        long active = excludeCallingThread
                ? Math.max(0, activeRequests.get() - selfOnThread)
                : activeRequests.get();
        double errorRate = total == 0 ? 0.0 : (double) errors / total;
        double avgDurationMs = total == 0 ? 0.0 : (double) durationSum / total;
        return new Snapshot(
                "UP",
                total,
                errors,
                slowRequests.get(),
                active,
                Math.round(errorRate * 10000) / 10000.0,
                Math.round(avgDurationMs * 100) / 100.0,
                maxDurationMs.get(),
                slowThresholdMs,
                List.copyOf(recentErrors));
    }

    /** 单条错误样本（只含可对外暴露的脱敏信息，不含堆栈/消息正文）。 */
    public record ErrorSample(String correlationId, String errorType, String timestamp) {
    }

    /** 统计快照。 */
    public record Snapshot(
            String status,
            long totalRequests,
            long errorRequests,
            long slowRequests,
            long activeRequests,
            double errorRate,
            double avgDurationMs,
            long maxDurationMs,
            long slowThresholdMs,
            List<ErrorSample> recentErrors) {
    }
}
