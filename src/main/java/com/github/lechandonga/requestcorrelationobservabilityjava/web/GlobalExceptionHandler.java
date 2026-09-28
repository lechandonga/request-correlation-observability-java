package com.github.lechandonga.requestcorrelationobservabilityjava.web;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationIds;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 全局异常处理：把任何未捕获异常归一化为 {@link ApiError}。
 *
 * <p>安全原则：响应中不回传异常 message、异常类型与堆栈，只给固定文案；
 * 可区分的失败原因（异常类型）只写进服务端日志，且日志带有 correlation id。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务参数非法：400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex,
                                                     HttpServletRequest request) {
        log.warn("request rejected correlationId={} uri={} causeType={} cause={}",
                currentCorrelationId(), request.getRequestURI(),
                ex.getClass().getSimpleName(), ex.getClass().getSimpleName(), ex);
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Bad Request");
    }

    /** 兜底：任何其它异常一律 500 + 固定文案，细节仅记录在服务端日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled exception correlationId={} uri={} causeType={}",
                currentCorrelationId(), request.getRequestURI(), ex.getClass().getSimpleName(),
                ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal Server Error");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String errorCode, String message) {
        ApiError body = new ApiError(
                currentCorrelationId(),
                status.value(),
                errorCode,
                message,
                Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }

    private String currentCorrelationId() {
        return MDC.get(CorrelationIds.MDC_KEY);
    }
}
