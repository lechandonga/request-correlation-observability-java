package com.github.lechandonga.requestcorrelationobservabilityjava.tests;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationContext;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Thread-local binding semantics, including the thread-reuse case that most
 * often causes correlation-id leaks.
 */
class CorrelationContextTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationContextTest.class);

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void beginBindsIdAndEndRestoresPreviousState() {
        String idA = CorrelationIds.generate();
        String idB = CorrelationIds.generate();

        String before = CorrelationContext.begin(idA);
        assertNull(before, "fresh thread must have no previous id");
        assertEquals(idA, CorrelationContext.currentId());
        assertEquals(idA, MDC.get(CorrelationContext.MDC_KEY));
        log.info("begin idA='{}' -> currentId='{}', mdc='{}'", idA,
                CorrelationContext.currentId(), MDC.get(CorrelationContext.MDC_KEY));

        String previous = CorrelationContext.begin(idB);
        assertEquals(idA, previous, "nested begin must return the outer id");
        assertEquals(idB, CorrelationContext.currentId());
        log.info("begin idB='{}' -> previous='{}', currentId='{}'", idB, previous,
                CorrelationContext.currentId());

        CorrelationContext.end(previous);
        assertEquals(idA, CorrelationContext.currentId(), "end must restore the outer id");
        assertEquals(idA, MDC.get(CorrelationContext.MDC_KEY));
        log.info("end(idA) restored -> currentId='{}'", CorrelationContext.currentId());

        CorrelationContext.end(before);
        assertNull(CorrelationContext.currentId(), "restoring null previous must clear the id");
        assertNull(MDC.get(CorrelationContext.MDC_KEY), "MDC must be cleared as well");
        log.info("end(null) -> currentId='{}', mdc='{}'", CorrelationContext.currentId(),
                MDC.get(CorrelationContext.MDC_KEY));
    }

    @Test
    void pooledThreadReuseDoesNotLeakPreviousId() throws Exception {
        // One single worker = guaranteed thread reuse across many "requests".
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 50; i++) {
                final int iter = i;
                String expected = "req-reused-" + (1000 + i);
                String observed = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    String previous = CorrelationContext.begin(expected);
                    try {
                        String inside = CorrelationContext.currentId();
                        log.info("iter={} bound id='{}' on thread='{}', previous='{}'",
                                iter, inside, Thread.currentThread().getName(), previous);
                        assertEquals(expected, inside);
                        return inside;
                    } finally {
                        CorrelationContext.end(previous);
                    }
                }, pool).get();
                assertEquals(expected, observed);
            }

            // After all tasks the pooled thread must be clean.
            String residual = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                String leftover = CorrelationContext.currentId();
                log.info("pooled thread residual id after 50 tasks='{}'", leftover);
                return leftover;
            }, pool).get();
            assertNull(residual, "reused pooled thread must not retain any correlation id");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void contextIsIsolatedBetweenThreads() throws Exception {
        String idA = "req-isolated-aaaa1111";
        String idB = "req-isolated-bbbb2222";
        CorrelationContext.begin(idA);

        String onOtherThread = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            String current = CorrelationContext.currentId();
            log.info("other thread sees currentId='{}' while main thread has '{}'", current, idA);
            return current;
        }).get();

        assertNull(onOtherThread, "thread-local must not leak onto other threads");
        assertEquals(idA, CorrelationContext.currentId(), "main thread id must be untouched");
        log.info("main thread still has id='{}' after concurrent read", CorrelationContext.currentId());
    }
}
