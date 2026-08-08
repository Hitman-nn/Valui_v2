package com.valui.parser.util;

/**
 * Shared helper for logging exception messages in circuit-breaker fallback methods.
 *
 * <p>{@code Throwable.getMessage()} is frequently {@code null} for common exception types
 * (NullPointerException, several IOException subclasses, Resilience4j's own wrappers) — logging
 * it directly produces a useless line like {@code "olimp fetchMatches fallback: null"} with zero
 * diagnostic value. This falls back to the cause's class name + message, and finally to a fixed
 * placeholder, so a fallback log line always carries at least the exception type.
 *
 * <p>Originally written ad hoc inside {@code XBetParser}; promoted to a shared utility since the
 * same null-message problem affects every bookmaker parser's fallback logging identically.
 */
public final class ExceptionDescriptions {

    private ExceptionDescriptions() {}

    public static String describe(Throwable t) {
        if (t == null) return "(no exception)";
        if (t.getMessage() != null) return t.getMessage();
        Throwable cause = t.getCause();
        if (cause != null) return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return t.getClass().getSimpleName() + " (no message)";
    }
}
