package com.github.lechandonga.requestcorrelationobservabilityjava.config;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Defines the application's thread pools. Every pool uses
 * {@link CorrelationTaskDecorator}, so work handed off from a request thread
 * ({@code @Async}, {@code CompletableFuture#supplyAsync}, ...) keeps the
 * caller's correlation id, and pooled threads are restored to their prior
 * state after each task.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Well-known bean name: Spring Boot picks this up as the default executor
     * for {@code @Async} methods and for async request processing.
     */
    public static final String APPLICATION_TASK_EXECUTOR = "applicationTaskExecutor";

    @Bean(name = APPLICATION_TASK_EXECUTOR)
    public Executor applicationTaskExecutor(CorrelationTaskDecorator correlationTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("app-worker-");
        // Caller runs the task when the pool/queue is saturated: back-pressure
        // instead of silently dropping work (AbortPolicy would throw).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(correlationTaskDecorator);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
