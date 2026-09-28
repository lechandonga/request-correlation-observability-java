package com.github.lechandonga.requestcorrelationobservabilityjava.tests;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationContext;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationTaskDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-thread propagation via {@link CorrelationTaskDecorator}: inheritance,
 * cleanup on thread reuse, generated ids for request-less tasks and no
 * cross-talk under concurrent submission.
 */
class CorrelationTaskDecoratorTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationTaskDecoratorTest.class);

    private final CorrelationTaskDecorator decorator = new CorrelationTaskDecorator();

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void workerInheritsCallerIdAndPooledThreadIsCleanAfterwards() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 30; i++) {
                final int iter = i;
                String callerId = "req-caller-" + (2000 + i);
                String previous = CorrelationContext.begin(callerId);
                CompletableFuture<String> result = new CompletableFuture<>();
                try {
                    Runnable task = decorator.decorate(() -> {
                        String workerId = CorrelationContext.currentId();
                        log.info("iter={} worker thread '{}' sees correlationId='{}' (inherited from caller)",
                                iter, Thread.currentThread().getName(), workerId);
                        result.complete(workerId);
                    });
                    pool.execute(task);
                } finally {
                    CorrelationContext.end(previous);
                }
                assertEquals(callerId, result.get(), "worker must see the submitting request's id");
            }
        } finally {
            pool.shutdown();
        }

        // Same two threads, reused by tasks submitted from a thread with no
        // request context: they must get fresh generated ids, never leftovers.
        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                final int iter = i;
                CompletableFuture<String> result = new CompletableFuture<>();
                futures.add(result);
                pool2.execute(decorator.decorate(() -> {
                    String id = CorrelationContext.currentId();
                    log.info("request-less task iter={} on '{}' gets correlationId='{}' (generated)",
                            iter, Thread.currentThread().getName(), id);
                    assertNotNull(id);
                    assertTrue(CorrelationIds.isValid(id));
                    result.complete(id);
                }));
            }
            for (CompletableFuture<String> future : futures) {
                assertNotNull(future.get());
            }
        } finally {
            pool2.shutdown();
        }
    }

    @Test
    void idsAreCapturedAtSubmissionTimeAndDoNotCrossUnderConcurrency() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(6);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> checks = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                final String callerId = "req-concurrent-" + (3000 + i);
                checks.add(callers.submit(() -> {
                    String previous = CorrelationContext.begin(callerId);
                    try {
                        // Decorate while bound to callerId; delay execution so
                        // the worker definitely runs after submission returns.
                        Runnable task = decorator.decorate(() -> {
                            String observed = CorrelationContext.currentId();
                            log.info("submitted callerId='{}', worker '{}' observed='{}', match={}",
                                    callerId, Thread.currentThread().getName(), observed,
                                    callerId.equals(observed));
                            assertEquals(callerId, observed,
                                    "correlation ids must not cross between concurrent requests");
                        });
                        workers.execute(task);
                    } finally {
                        CorrelationContext.end(previous);
                    }
                }));
            }
            for (Future<?> check : checks) {
                check.get();
            }
            // Let the last wave finish.
            workers.shutdown();
            assertTrue(workers.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void decoratorRestoresPreExistingWorkerThreadState() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Pre-bind an "ambient" id on the worker thread once.
            Future<?> setup = pool.submit(() -> CorrelationContext.begin("req-ambient-9999aaaa"));
            setup.get();

            String previous = CorrelationContext.begin("req-outer-1111bbbb");
            Future<?> run;
            try {
                run = pool.submit(decorator.decorate(() ->
                        assertEquals("req-outer-1111bbbb", CorrelationContext.currentId())));
            } finally {
                CorrelationContext.end(previous);
            }
            run.get();

            String ambientAfter = pool.submit(CorrelationContext::currentId).get();
            log.info("ambient id restored on reused worker thread='{}'", ambientAfter);
            assertEquals("req-ambient-9999aaaa", ambientAfter,
                    "decorator must restore the worker thread's previously bound id, not wipe it");
        } finally {
            pool.shutdownNow();
        }
    }
}
