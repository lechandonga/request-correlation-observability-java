package com.github.lechandonga.requestcorrelationobservabilityjava.web;

/**
 * Safe error payload returned to clients (no stack traces / internal details).
 */
public record ApiError(int status, String error, String message, String correlationId) {
}
