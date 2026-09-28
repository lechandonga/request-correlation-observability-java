package com.github.lechandonga.requestcorrelationobservabilityjava.async;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性定时任务。
 *
 * <p>定时任务不由任何请求触发，工作线程 MDC 为空，因此日志中 correlation id 为空——
 * 这是预期的跨线程边界。任务结束后 {@link MdcCopyingTaskDecorator} 会恢复 MDC，
 * 同一调度线程执行下一次任务时也不会残留任何标识。</p>
 */
@Component
public class ScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTasks.class);

    /** 周期较长（演示用），默认每 60 秒一次，可用配置覆盖。 */
    @Scheduled(fixedDelayString = "${observability.heartbeat.fixed-delay-ms:60000}",
            scheduler = "correlationTaskScheduler")
    public void heartbeat() {
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        log.info("scheduled heartbeat tick correlationId={} (expected null, no request context)",
                correlationId);
    }
}
