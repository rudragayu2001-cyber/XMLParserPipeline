package org.xmlpipeline.exception;

/**
 * Retryable fetch failure (network timeout, connection refused, DNS failure, HTTP 5xx).
 * Thrown only after all retry attempts are exhausted inside FetcherService.
 */
public class RetryableFetchException extends RuntimeException {
    public RetryableFetchException(String message) {
        super(message);
    }

    public RetryableFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
