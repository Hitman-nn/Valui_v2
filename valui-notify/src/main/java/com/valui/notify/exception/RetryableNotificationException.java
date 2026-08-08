package com.valui.notify.exception;

/**
 * Wraps a notification send failure with retry metadata.
 *
 * {@code retryable = false} → DeadLetterPublisher routes straight to dlq.final
 *   (e.g. bot blocked by user, malformed request)
 * {@code retryable = true}  → standard retry ladder applies
 * {@code retryAfterMs}      → hint from Telegram's 429 {@code Retry-After} response.
 *   NOT currently consumed by any retry-tier sleep — the ladder always uses its fixed
 *   1s/5s/30s delays regardless of this value. Logged by DeadLetterPublisher so a chat being
 *   429'd faster than the fixed ladder backs off is at least visible, even though nothing acts
 *   on it yet.
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
