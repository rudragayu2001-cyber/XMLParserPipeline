package org.xmlpipeline.exception;

/**
 * Non-retryable fetch failure (HTTP 404, other 4xx client errors).
 * The task is marked failed immediately without further retry attempts.
 */
public class FetchException extends RuntimeException {
    public FetchException(String message) {
        super(message);
    }
}
