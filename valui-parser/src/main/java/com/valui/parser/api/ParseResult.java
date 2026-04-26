package com.valui.parser.api;

import java.time.Instant;

public record ParseResult<T>(
        T data,
        boolean success,
        String errorMessage,
        Instant fetchedAt,
        long latencyMs
) {
    public static <T> ParseResult<T> ok(T data, long latencyMs) {
        return new ParseResult<>(data, true, null, Instant.now(), latencyMs);
    }

    public static <T> ParseResult<T> error(String message) {
        return new ParseResult<>(null, false, message, Instant.now(), 0L);
    }
}
