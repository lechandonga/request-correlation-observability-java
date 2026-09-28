package com.github.lechandonga.requestcorrelationobservabilityjava.web;

/**
 * 对外统一的脱敏错误响应体。
 *
 * <p>只包含通用错误码与固定文案，<b>不</b>包含异常消息、类名、堆栈等内部信息；
 * {@code correlationId} 便于调用方拿回来与服务端日志对账。</p>
 *
 * @param correlationId 本次请求的链路标识
 * @param status        HTTP 状态码
 * @param error         通用错误码（如 INTERNAL_ERROR），不泄露内部实现
 * @param message       固定、安全的提示文案
 * @param timestamp     错误发生时间（ISO-8601）
 */
public record ApiError(
        String correlationId,
        int status,
        String error,
        String message,
        String timestamp) {
}
