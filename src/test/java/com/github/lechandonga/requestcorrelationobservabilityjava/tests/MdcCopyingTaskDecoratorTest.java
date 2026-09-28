package com.github.lechandonga.requestcorrelationobservabilityjava.tests;

import com.github.lechandonga.requestcorrelationobservabilityjava.async.MdcCopyingTaskDecorator;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨线程 MDC 传递与线程复用清理测试。
 */
class MdcCopyingTaskDecoratorTest {

    private static final Logger log = LoggerFactory.getLogger(MdcCopyingTaskDecoratorTest.class);

    private final ExecutorService pool = Executors.newFixedThreadPool(1);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        MDC.clear();
    }

    @Test
    void workerInheritsCallerCorrelationId() throws Exception {
        String cid = CorrelationIds.generate();
        MDC.put(CorrelationIds.MDC_KEY, cid);

        MdcCopyingTaskDecorator decorator = new MdcCopyingTaskDecorator();
        AtomicReference<String> seenInWorker = new AtomicReference<>();
        Future<?> future = pool.submit(decorator.decorate(
                () -> seenInWorker.set(MDC.get(CorrelationIds.MDC_KEY))));
        future.get();

        log.info("test=inherit callerCorrelationId={} workerCorrelationId={} "
                + "reason=decorator snapshots caller MDC at submission",
                cid, seenInWorker.get());
        assertEquals(cid, seenInWorker.get(), "工作线程必须看到提交方的标识");

        // 提交方线程自身的 MDC 不受任务执行影响
        assertEquals(cid, MDC.get(CorrelationIds.MDC_KEY));
    }

    @Test
    void reusedThreadDoesNotKeepPreviousCorrelationId() throws Exception {
        MdcCopyingTaskDecorator decorator = new MdcCopyingTaskDecorator();

        // 第一个任务：带标识 A
        String cidA = "trace-AAAAAAA1";
        MDC.put(CorrelationIds.MDC_KEY, cidA);
        AtomicReference<String> firstSeen = new AtomicReference<>();
        pool.submit(decorator.decorate(() -> firstSeen.set(MDC.get(CorrelationIds.MDC_KEY)))).get();
        log.info("test=reuse.task1 expected={} actual={} reason=first task carries cidA",
                cidA, firstSeen.get());
        assertEquals(cidA, firstSeen.get());

        // 第二个任务：提交方 MDC 为空。同一工作线程被复用，绝不能残留 cidA
        MDC.clear();
        AtomicReference<String> secondSeen = new AtomicReference<>("unset");
        pool.submit(decorator.decorate(
                () -> secondSeen.set(MDC.get(CorrelationIds.MDC_KEY)))).get();
        log.info("test=reuse.task2 expected=null actual={} reason=decorator restores worker MDC "
                + "after each task, reused thread must not leak cidA", secondSeen.get());
        assertNull(secondSeen.get(), "线程复用后第二个任务不得看到上一个请求的标识");
    }

    @Test
    void workerMdcIsCleanedEvenWhenTaskThrows() throws Exception {
        MdcCopyingTaskDecorator decorator = new MdcCopyingTaskDecorator();
        String cid = "trace-THROW01";
        MDC.put(CorrelationIds.MDC_KEY, cid);

        AtomicReference<String> duringTask = new AtomicReference<>();
        try {
            pool.submit(decorator.decorate(() -> {
                duringTask.set(MDC.get(CorrelationIds.MDC_KEY));
                throw new IllegalStateException("boom");
            })).get();
        } catch (Exception expected) {
            // 预期异常，继续验证清理
        }
        log.info("test=cleanupOnError duringTaskCorrelationId={} reason=finally restores MDC "
                + "even when task throws", duringTask.get());
        assertEquals(cid, duringTask.get());

        // 异常任务之后再跑一个无上下文任务，验证工作线程已被清空
        MDC.clear();
        AtomicReference<String> after = new AtomicReference<>("unset");
        pool.submit(decorator.decorate(() -> after.set(MDC.get(CorrelationIds.MDC_KEY)))).get();
        log.info("test=cleanupOnError.followup expected=null actual={}", after.get());
        assertNull(after.get());
    }

    @Test
    void concurrentTasksWithDifferentIdsDoNotMix() throws Exception {
        // 用 2 个线程跑大量带不同标识的任务，校验任务内读到的永远是自己的标识
        ExecutorService multi = Executors.newFixedThreadPool(2);
        try {
            MdcCopyingTaskDecorator decorator = new MdcCopyingTaskDecorator();
            int taskCount = 200;
            String[] seenById = new String[taskCount];
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                final int index = i;
                String cid = String.format("trace-concurrent-%08d", index);
                MDC.put(CorrelationIds.MDC_KEY, cid);
                futures.add(multi.submit(decorator.decorate(() -> {
                    seenById[index] = MDC.get(CorrelationIds.MDC_KEY);
                    try {
                        // 制造交错，迫使线程复用
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })));
                MDC.clear();
            }
            for (Future<?> f : futures) {
                f.get();
            }
            int matched = 0;
            for (int i = 0; i < taskCount; i++) {
                String expected = String.format("trace-concurrent-%08d", i);
                assertEquals(expected, seenById[i],
                        "并发任务标识不得串号 index=" + i);
                matched++;
            }
            log.info("test=concurrentIsolation tasks={} matched={} reason=each task sees its own "
                    + "correlation id despite thread reuse", taskCount, matched);
            assertEquals(taskCount, matched);
        } finally {
            multi.shutdownNow();
            MDC.clear();
        }
    }
}
