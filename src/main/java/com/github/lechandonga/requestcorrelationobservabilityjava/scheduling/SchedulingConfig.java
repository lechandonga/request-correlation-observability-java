package com.github.lechandonga.requestcorrelationobservabilityjava.scheduling;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationContext;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler pool. Every scheduled run goes through the same
 * {@link CorrelationTaskDecorator} as request worker pools.
 *
 * <p><b>Boundary note:</b> {@code TaskDecorator} in Spring only decorates the
 * {@code execute(Runnable)} path, not {@code schedule(...)}. Scheduled work
 * ({@code @Scheduled}, one-shot schedules) is therefore routed through a
 * {@link ThreadPoolTaskScheduler} subclass whose underlying executor decorates
 * at the schedule submission points. A timer run is not initiated by any
 * request, so the decorator generates a fresh id per run; the scheduler
 * thread is reset after every run by the decorator's finally-restore.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    public static final String TASK_SCHEDULER = "taskScheduler";

    @Bean(name = TASK_SCHEDULER)
    public ThreadPoolTaskScheduler taskScheduler(CorrelationTaskDecorator correlationTaskDecorator) {
        ThreadPoolTaskScheduler scheduler = new CorrelationAwareTaskScheduler(correlationTaskDecorator);
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("app-scheduler-");
        // Also covers the execute(Runnable) path.
        scheduler.setTaskDecorator(correlationTaskDecorator);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Task scheduler whose underlying pool applies the correlation decorator
     * to every scheduled task at submission time.
     */
    static final class CorrelationAwareTaskScheduler extends ThreadPoolTaskScheduler {

        private final CorrelationTaskDecorator decorator;

        CorrelationAwareTaskScheduler(CorrelationTaskDecorator decorator) {
            this.decorator = decorator;
        }

        @Override
        protected ScheduledExecutorService createExecutor(int poolSize,
                                                          ThreadFactory threadFactory,
                                                          RejectedExecutionHandler rejectedExecutionHandler) {
            return new CorrelationAwareScheduledExecutor(poolSize, threadFactory, decorator);
        }
    }

    /**
     * {@link ScheduledThreadPoolExecutor} that applies the correlation
     * decorator to every task at the schedule submission points.
     */
    static final class CorrelationAwareScheduledExecutor extends ScheduledThreadPoolExecutor {

        private final CorrelationTaskDecorator decorator;

        CorrelationAwareScheduledExecutor(int corePoolSize,
                                          ThreadFactory threadFactory,
                                          CorrelationTaskDecorator decorator) {
            super(corePoolSize, threadFactory);
            this.decorator = decorator;
            setRemoveOnCancelPolicy(true);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return super.schedule(decorator.decorate(command), delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                      long initialDelay, long period, TimeUnit unit) {
            return super.scheduleAtFixedRate(perTick(command), initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                         long initialDelay, long delay, TimeUnit unit) {
            return super.scheduleWithFixedDelay(perTick(command), initialDelay, delay, unit);
        }

        @Override
        public void execute(Runnable command) {
            super.execute(decorator.decorate(command));
        }

        /**
         * Wrap a <em>periodic</em> task so that correlation state is resolved
         * on every trigger, not once at registration.
         *
         * <p>{@link CorrelationTaskDecorator#decorate(Runnable)} captures the
         * id at submission time; for a periodic registration that happens on a
         * request thread it would freeze the caller's id for every future
         * trigger, and on a bean-init thread it would freeze a single
         * generated id. Here each tick instead generates a fresh id (a
         * scheduled tick has no inbound request by definition) and restores
         * the scheduler thread's prior state afterwards.
         */
        private Runnable perTick(Runnable command) {
            return () -> {
                String tickId = CorrelationIds.generate();
                String previous = CorrelationContext.begin(tickId);
                try {
                    command.run();
                } finally {
                    CorrelationContext.end(previous);
                }
            };
        }
    }
}
