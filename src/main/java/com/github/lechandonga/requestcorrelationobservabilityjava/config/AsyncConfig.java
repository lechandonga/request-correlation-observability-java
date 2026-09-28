package com.github.lechandonga.requestcorrelationobservabilityjava.config;

import com.github.lechandonga.requestcorrelationobservabilityjava.async.MdcCopyingTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步与定时任务配置。
 *
 * <p>{@link ThreadPoolTaskExecutor}（服务 {@code @Async} / 接口内部提交到线程池的工作）
 * 与 {@link ThreadPoolTaskScheduler}（{@code @Scheduled} 定时任务）都通过
 * {@link MdcCopyingTaskDecorator} 包装任务。注意：定时任务没有“发起请求”，其工作线程
 * MDC 默认为空，因此日志里不会携带请求标识——这是预期边界；只有“由请求触发”的异步工作
 * 才会继承发起方标识。</p>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /** 供 {@code @Async} 使用的主执行器。 */
    @Bean(name = "correlationTaskExecutor")
    public Executor correlationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("corr-async-");
        executor.setTaskDecorator(new MdcCopyingTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 供 {@code @Scheduled} 定时任务使用的调度器。 */
    @Bean(name = "correlationTaskScheduler")
    public ThreadPoolTaskScheduler correlationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("corr-sched-");
        // 每次调度执行前后都会恢复线程 MDC，避免上一次执行的标识残留
        scheduler.setTaskDecorator(new MdcCopyingTaskDecorator());
        scheduler.initialize();
        return scheduler;
    }
}
