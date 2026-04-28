package com.valui.notify.exception;

/**
 * Wraps a notification send failure with retry metadata.
 *
 * {@code retryable = false} → DeadLetterPublisher routes straight to dlq.final
 *   (e.g. bot blocked by user, malformed request)
 * {@code retryable = true}  → standard retry ladder applies
 * {@code retryAfterMs}      → hint from Telegram 429 response; consumer sleeps
 *   for this duration instead of the standard backoff when > 0
 */
public class RetryableNotificationException extends RuntimeException {

    private final boolean retryable;
    private final long retryAfterMs;

    public RetryableNotificationException(String message, Throwable cause,
                                          boolean retryable, long retryAfterMs) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfterMs = retryAfterMs;
    }

    public boolean isRetryable() { return retryable; }
    public long retryAfterMs()   { return retryAfterMs; }
}
