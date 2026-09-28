package com.github.lechandonga.requestcorrelationobservabilityjava.correlation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.requestcorrelationobservabilityjava.observability.RequestStats;
import com.github.lechandonga.requestcorrelationobservabilityjava.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-request correlation boundary.
 *
 * <ol>
 *     <li>resolves the correlation id: reuse a legal inbound
 *         {@code X-Correlation-Id} (fallback {@code X-Request-Id}), otherwise
 *         generate one — the decision and its reason are logged;</li>
 *     <li>binds the id to the request thread (see {@link CorrelationContext}),
 *         so all downstream logging is tagged, and echoes it back in the
 *         response header {@code X-Correlation-Id};</li>
 *     <li>times the request, flags slow ones, records statistics and, on an
 *         unhandled error, returns a generic body — never the stack trace.</li>
 * </ol>
 *
 * <p>Highest precedence so that any later filter / interceptor / controller
 * code already runs inside the correlation scope.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);

    /**
     * Request attribute under which the exception handler publishes the
     * distinguishable failure reason (exception type), so the filter can
     * record it even though the exception never propagates out of the
     * dispatcher.
     */
    public static final String ERROR_REASON_ATTRIBUTE = "correlation.errorReason";

    private final RequestStats requestStats;
    private final ObjectMapper objectMapper;
    private final long slowThresholdMillis;

    public CorrelationFilter(RequestStats requestStats,
                             ObjectMapper objectMapper,
                             @Value("${observability.slow-threshold-ms:1000}") long slowThresholdMillis) {
        this.requestStats = requestStats;
        this.objectMapper = objectMapper;
        this.slowThresholdMillis = slowThresholdMillis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CorrelationDecision decision = resolve(request.getHeader(CorrelationIds.HEADER_NAME),
                request.getHeader(CorrelationIds.ALTERNATE_HEADER_NAME));
        String correlationId = decision.correlationId();

        String previous = CorrelationContext.begin(correlationId);
        long startNanos = System.nanoTime();
        // Echoed before downstream work so even a failure response carries it.
        response.setHeader(CorrelationIds.HEADER_NAME, correlationId);

        log.info("Request received: method={}, uri={}, correlationId={}, decision={} ({})",
                request.getMethod(), request.getRequestURI(), correlationId,
                decision.generated() ? "generated" : "reused", decision.reason());

        boolean thrown = false;
        String failureReason = null;
        try {
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            thrown = true;
            failureReason = ex.getClass().getSimpleName();
            log.error("Request failed with exception: correlationId={}, reason={}, message={}",
                    correlationId, failureReason, ex.getMessage(), ex);
            writeSafeError(response, correlationId);
        } finally {
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = response.getStatus();
            boolean slow = durationMillis >= slowThresholdMillis;

            String statsReason = failureReason;
            if (statsReason == null) {
                statsReason = (String) request.getAttribute(ERROR_REASON_ATTRIBUTE);
            }
            if (statsReason == null && status >= HttpStatus.BAD_REQUEST.value()) {
                statsReason = "HTTP_" + status;
            }
            requestStats.record(durationMillis, slow, statsReason);

            if (thrown || status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                log.error("Request completed: correlationId={}, status={}, durationMs={}, outcome=server_error, reason={}",
                        correlationId, status, durationMillis,
                        statsReason != null ? statsReason : "HTTP_" + status);
            } else if (status >= HttpStatus.BAD_REQUEST.value()) {
                log.warn("Request completed: correlationId={}, status={}, durationMs={}, outcome=client_error, reason={}",
                        correlationId, status, durationMillis,
                        statsReason != null ? statsReason : "HTTP_" + status);
            } else if (slow) {
                log.warn("Slow request detected: correlationId={}, status={}, durationMs={}, thresholdMs={}",
                        correlationId, status, durationMillis, slowThresholdMillis);
            } else {
                log.info("Request completed: correlationId={}, status={}, durationMs={}, outcome=success",
                        correlationId, status, durationMillis);
            }

            // Restore the worker thread to its prior state — no id may leak
            // across pooled request threads / async dispatches.
            CorrelationContext.end(previous);
        }
    }

    /**
     * Apply the reuse-or-generate rule and produce a loggable reason.
     */
    public static CorrelationDecision resolve(String primaryHeader, String alternateHeader) {
        if (primaryHeader != null && !primaryHeader.isBlank()) {
            return decide(primaryHeader, CorrelationIds.HEADER_NAME);
        }
        if (alternateHeader != null && !alternateHeader.isBlank()) {
            CorrelationDecision decision = decide(alternateHeader, CorrelationIds.ALTERNATE_HEADER_NAME);
            if (!decision.generated()) {
                return new CorrelationDecision(decision.correlationId(), false,
                        "reused from fallback header " + CorrelationIds.ALTERNATE_HEADER_NAME);
            }
            return decision;
        }
        String generated = CorrelationIds.generate();
        return new CorrelationDecision(generated, true,
                "no " + CorrelationIds.HEADER_NAME + " header present");
    }

    private static CorrelationDecision decide(String rawValue, String headerName) {
        String candidate = rawValue.trim();
        if (CorrelationIds.isValid(candidate)) {
            return new CorrelationDecision(candidate, false,
                    "legal value in " + headerName);
        }
        String generated = CorrelationIds.generate();
        return new CorrelationDecision(generated, true,
                "illegal value in " + headerName + " (allowed: " + CorrelationIds.MIN_LENGTH
                        + ".." + CorrelationIds.MAX_LENGTH + " chars of [A-Za-z0-9._-])");
    }

    /**
     * Generic error body: fixed message + correlation id only. No exception
     * type, message or stack trace leaves the service.
     */
    private void writeSafeError(HttpServletResponse response, String correlationId) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        ApiError body = new ApiError(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "The request could not be completed. Please quote the correlation id when contacting support.",
                correlationId);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
