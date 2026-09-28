package com.github.lechandonga.requestcorrelationobservabilityjava.correlation;

import com.github.lechandonga.requestcorrelationobservabilityjava.observability.RequestMetrics;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求入口过滤器，保证每个请求都有唯一链路标识并贯穿整条处理链：
 * <ol>
 *     <li>从请求头读取标识，合法则沿用、缺失/非法则生成，并在日志打印标识与判定依据；</li>
 *     <li>将标识写入 MDC（供所有日志使用）与响应头（对外回传）；</li>
 *     <li>统计请求耗时、错误与慢请求；</li>
 *     <li>每次派发结束后无条件清理当前线程 MDC，防止容器线程复用时标识串号。</li>
 * </ol>
 *
 * <p>对 {@code @Async} 等异步请求，容器会先做 REQUEST 初始派发，异步完成后再做 ASYNC 派发。
 * 初始派发只解析标识并暂存到请求属性；计时与统计在最终 ASYNC 派发时收口，
 * 从而保证在途计数与耗时统计成对、不漏。</p>
 */
public class CorrelationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);

    private static final String ATTR_CORRELATION_ID = CorrelationFilter.class.getName() + ".cid";
    private static final String ATTR_START_NANOS = CorrelationFilter.class.getName() + ".start";

    private final RequestMetrics metrics;

    public CorrelationFilter(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // 需要在 ASYNC 派发时做统计收口，因此异步派发也要执行本过滤器
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 每次派发都标记当前线程在途深度，供健康端点排除统计请求自身
        metrics.dispatchEntered();
        try {
            doDispatch(request, response, filterChain);
        } finally {
            metrics.dispatchExited();
        }
    }

    private void doDispatch(HttpServletRequest request, HttpServletResponse response,
                            FilterChain filterChain) throws ServletException, IOException {
        boolean asyncDispatch = request.getDispatcherType() == DispatcherType.ASYNC;

        String correlationId;
        long startNanos;
        if (asyncDispatch) {
            // 最终异步派发：标识与起始时间来自初始派发时暂存的请求属性
            correlationId = (String) request.getAttribute(ATTR_CORRELATION_ID);
            Number startAttr = (Number) request.getAttribute(ATTR_START_NANOS);
            startNanos = startAttr == null ? System.nanoTime() : startAttr.longValue();
            MDC.put(CorrelationIds.MDC_KEY, correlationId);
        } else {
            // 初始 REQUEST 派发：沿用或生成标识
            CorrelationIds.Resolved resolved =
                    CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
            correlationId = resolved.correlationId();
            startNanos = System.nanoTime();

            request.setAttribute(ATTR_CORRELATION_ID, correlationId);
            request.setAttribute(ATTR_START_NANOS, startNanos);
            MDC.put(CorrelationIds.MDC_KEY, correlationId);
            response.setHeader(CorrelationIds.HEADER_NAME, correlationId);
            metrics.requestStarted();

            // 日志中同时输出请求标识与“沿用/生成”的判定依据，便于核验归一化行为
            log.info("request received correlationId={} generated={} method={} uri={} reason={}",
                    correlationId, resolved.generated(), request.getMethod(),
                    request.getRequestURI(), resolved.reason());
        }

        boolean success = false;
        try {
            filterChain.doFilter(request, response);
            success = true;
        } catch (Exception ex) {
            // 初始派发已进入异步模式时异常会在 ASYNC 派发阶段暴露，这里只在“本次派发即收口”时统计
            if (asyncDispatch || !request.isAsyncStarted()) {
                long durationMs = elapsedMillis(startNanos);
                metrics.requestFailed(durationMs, correlationId, ex.getClass().getSimpleName());
                // 带标识与可区分的失败原因记录错误日志，但不记录可能含敏感内容的消息正文
                log.error("request failed correlationId={} durationMs={} causeType={}",
                        correlationId, durationMs, ex.getClass().getSimpleName(), ex);
            }
            throw ex;
        } finally {
            // 初始派发且异步尚未结束：本次不收口，等 ASYNC 派发再统计
            boolean completesHere = asyncDispatch || !request.isAsyncStarted();
            if (success && completesHere) {
                long durationMs = elapsedMillis(startNanos);
                int status = response.getStatus();
                if (status >= 500) {
                    // 经 GlobalExceptionHandler 处理的异常不会抛出过滤器，按 5xx 状态计入错误
                    metrics.requestFailed(durationMs, correlationId, "HTTP_" + status);
                } else {
                    metrics.requestSucceeded(durationMs);
                }
                if (durationMs >= metrics.getSlowThresholdMs()) {
                    log.warn("slow request correlationId={} durationMs={} thresholdMs={} status={}",
                            correlationId, durationMs, metrics.getSlowThresholdMs(), status);
                } else {
                    log.info("request completed correlationId={} durationMs={} status={}",
                            correlationId, durationMs, status);
                }
            }
            // 无条件清理当前线程 MDC，防止容器线程复用导致下一个请求继承旧标识
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
