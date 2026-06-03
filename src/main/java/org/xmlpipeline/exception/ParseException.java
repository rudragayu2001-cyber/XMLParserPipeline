package org.xmlpipeline.exception;

/**
 * Non-retryable parse failure (unrecoverable XML syntax error).
 * The task is marked failed immediately; no retry makes sense for malformed content.
 */
public class ParseException extends RuntimeException {
    public ParseException(String message) {
        super(message);
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
