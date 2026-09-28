package com.github.lechandonga.requestcorrelationobservabilityjava.observability;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregated, in-memory request statistics. All state is lock-free so the
 * request filter can update it on every call under concurrency.
 *
 * <p>Tracked counters: total / success / error / slow requests, total latency
 * (for an average), and the number of errors grouped by failure reason.
 */
@Component
public class RequestStats {

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong errorRequests = new AtomicLong();
    private final AtomicLong slowRequests = new AtomicLong();
    private final AtomicLong totalDurationMillis = new AtomicLong();
    private final ConcurrentMap<String, AtomicLong> errorsByReason = new ConcurrentHashMap<>();

    /**
     * Record one completed request.
     *
     * @param durationMillis wall-clock duration of the request
     * @param slow           true if the duration crossed the slow threshold
     * @param errorReason    non-null failure reason for failed requests,
     *                       null for successful ones
     */
    public void record(long durationMillis, boolean slow, String errorReason) {
        totalRequests.incrementAndGet();
        totalDurationMillis.addAndGet(Math.max(0L, durationMillis));
        if (slow) {
            slowRequests.incrementAndGet();
        }
        if (errorReason != null) {
            errorRequests.incrementAndGet();
            errorsByReason.computeIfAbsent(errorReason, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /** @return immutable-ish snapshot of the current counters */
    public Map<String, Object> snapshot() {
        long total = totalRequests.get();
        long errors = errorRequests.get();
        long slow = slowRequests.get();
        long durationTotal = totalDurationMillis.get();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRequests", total);
        result.put("successfulRequests", total - errors);
        result.put("errorRequests", errors);
        result.put("slowRequests", slow);
        result.put("averageDurationMillis", total == 0 ? 0 : durationTotal / total);
        result.put("errorsByReason", new LinkedHashMap<>(toMapOfPrimitives()));
        return result;
    }

    private Map<String, Long> toMapOfPrimitives() {
        Map<String, Long> copy = new LinkedHashMap<>();
        errorsByReason.forEach((reason, count) -> copy.put(reason, count.get()));
        return copy;
    }
}
