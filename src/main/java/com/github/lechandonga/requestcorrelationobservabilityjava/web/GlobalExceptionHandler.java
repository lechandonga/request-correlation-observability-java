package com.github.lechandonga.requestcorrelationobservabilityjava.web;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationContext;
import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Central translation of exceptions into safe {@link ApiError} responses.
 *
 * <p>Rules:
 * <ul>
 *     <li>the response never contains an exception class, message or stack
 *         trace — only an HTTP status, a generic message and the correlation
 *         id, which support can use to find the detailed server log;</li>
 *     <li>the server log keeps the full exception plus a distinguishable
 *         reason (exception type) and the correlation id;</li>
 *     <li>the failure reason is also published on the request so the
 *         {@link CorrelationFilter} can count it by reason, even though the
 *         exception does not propagate out of the dispatcher;</li>
 *     <li>client mistakes (4xx) are logged at WARN, unexpected failures (5xx)
 *         at ERROR.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        // Business messages are curated to be client-safe.
        log.warn("Business failure: correlationId={}, reason=BusinessException, message={}",
                CorrelationContext.currentId(), ex.getMessage());
        markFailure(request, "BusinessException");
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex,
                                                          HttpServletRequest request) {
        log.warn("Bad request: correlationId={}, reason=IllegalArgumentException, message={}",
                CorrelationContext.currentId(), ex.getMessage());
        markFailure(request, "IllegalArgumentException");
        return build(HttpStatus.BAD_REQUEST, "The request was rejected as invalid.");
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException ex,
                                                   HttpServletRequest request) {
        log.warn("No handler: correlationId={}, reason=NoHandlerFoundException, path={}",
                CorrelationContext.currentId(), ex.getRequestURL());
        markFailure(request, "NoHandlerFoundException");
        return build(HttpStatus.NOT_FOUND, "The requested resource does not exist.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception: correlationId={}, reason={}",
                CorrelationContext.currentId(), ex.getClass().getName(), ex);
        markFailure(request, ex.getClass().getSimpleName());
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "The request could not be completed. Please quote the correlation id when contacting support.");
    }

    private void markFailure(HttpServletRequest request, String reason) {
        request.setAttribute(CorrelationFilter.ERROR_REASON_ATTRIBUTE, reason);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String safeMessage) {
        return ResponseEntity
                .status(status)
                .body(new ApiError(status.value(), status.getReasonPhrase(),
                        safeMessage, CorrelationContext.currentId()));
    }
}
