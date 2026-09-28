package com.github.lechandonga.requestcorrelationobservabilityjava.correlation;

/**
 * Result of resolving a correlation id for an incoming request.
 *
 * @param correlationId the id that will be used
 * @param generated     true if the service generated the id
 * @param reason        human-readable reason for the decision
 */
public record CorrelationDecision(String correlationId, boolean generated, String reason) {
}
