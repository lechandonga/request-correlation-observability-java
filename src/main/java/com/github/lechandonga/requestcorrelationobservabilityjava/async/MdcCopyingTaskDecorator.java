package com.github.lechandonga.requestcorrelationobservabilityjava.async;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 跨线程传递 MDC（含 correlation id）的任务装饰器。
 *
 * <p>关键时序：</p>
 * <ol>
 *     <li><b>提交时</b>（调用方线程）快照调用方 MDC；</li>
 *     <li><b>执行前</b>（工作线程）保存工作线程原有 MDC，再写入调用方快照；</li>
 *     <li><b>执行后</b>无论成功失败都恢复工作线程原有 MDC（原本为空则清空），
 *     从而保证线程被池化复用时不残留上一个任务的标识。</li>
 * </ol>
 */
public class MdcCopyingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 此处在调用方线程执行：拿到提交者的 MDC 快照
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            // 此处与 runnable 在同一个工作线程执行
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            try {
                if (callerContext != null) {
                    MDC.setContextMap(callerContext);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                // 恢复工作线程进入任务之前的状态，避免线程复用串号
                if (previousContext != null) {
                    MDC.setContextMap(previousContext);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
