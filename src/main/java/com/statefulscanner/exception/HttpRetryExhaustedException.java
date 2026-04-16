package com.statefulscanner.exception;

/**
 * Thrown when an HTTP request has exhausted all retry attempts.
 */
public class HttpRetryExhaustedException extends RuntimeException {

    public HttpRetryExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
