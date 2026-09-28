package com.github.lechandonga.requestcorrelationobservabilityjava.web;

/**
 * Example business failure carrying a client-safe message.
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
