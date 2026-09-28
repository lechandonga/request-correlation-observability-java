package com.github.lechandonga.requestcorrelationobservabilityjava.correlation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * Propagates the correlation id from the submitting thread onto the worker
 * thread.
 *
 * <p>Semantics:
 * <ul>
 *     <li>the id is captured at <em>submission</em> time (on the caller's
 *         thread), so multiple queued tasks cannot influence each other;</li>
 *     <li>before the task runs the id is bound on the worker thread, and in
 *         {@code finally} the worker thread's previous state is restored —
 *         thread-pool reuse therefore never leaks an id into the next task;</li>
 *     <li>a task not submitted from a request (e.g. a bare scheduled run)
 *         gets its own generated id instead of running untagged.</li>
 * </ul>
 */
@Component
public class CorrelationTaskDecorator implements TaskDecorator {

    private static final Logger log = LoggerFactory.getLogger(CorrelationTaskDecorator.class);

    @Override
    public Runnable decorate(Runnable runnable) {
        String capturedId = CorrelationContext.currentId();
        boolean inherited = capturedId != null;
        String taskId = inherited ? capturedId : CorrelationIds.generate();
        return () -> {
            String previous = CorrelationContext.begin(taskId);
            log.debug("Async task starting on thread '{}', correlationId={} ({})",
                    Thread.currentThread().getName(), taskId,
                    inherited ? "inherited from submitting request"
                              : "generated: task is not bound to a request");
            try {
                runnable.run();
            } finally {
                CorrelationContext.end(previous);
            }
        };
    }
}
