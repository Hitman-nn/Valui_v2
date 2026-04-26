package com.valui.parser.http;

import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryTest {

    private Retry retry;

    @BeforeEach
    void setUp() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(IOException.class)
                .ignoreExceptions(IllegalArgumentException.class)
                .build();
        retry = RetryRegistry.of(config).retry("test-retry");
    }

    @Test
    void retries_twice_then_propagates_exception() {
        AtomicInteger calls = new AtomicInteger(0);
        // CheckedSupplier allows declaring checked exceptions in the lambda body
        CheckedSupplier<String> decorated = Retry.decorateCheckedSupplier(retry, () -> {
            calls.incrementAndGet();
            throw new IOException("network error");
        });

        assertThatThrownBy(decorated::get).isInstanceOf(IOException.class);
        assertThat(calls.get()).isEqualTo(2); // 1 initial + 1 retry
    }

    @Test
    void succeeds_on_second_attempt() throws Throwable {
        AtomicInteger calls = new AtomicInteger(0);
        CheckedSupplier<String> decorated = Retry.decorateCheckedSupplier(retry, () -> {
            if (calls.incrementAndGet() == 1) throw new IOException("transient");
            return "ok";
        });

        String result = decorated.get();
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    void does_not_retry_ignored_exception() {
        AtomicInteger calls = new AtomicInteger(0);
        // IllegalArgumentException is unchecked — plain Supplier is fine
        Supplier<String> decorated = Retry.decorateSupplier(retry, () -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("bad arg");
        });

        assertThatThrownBy(decorated::get).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void does_not_retry_non_configured_exception() {
        AtomicInteger calls = new AtomicInteger(0);
        Supplier<String> decorated = Retry.decorateSupplier(retry, () -> {
            calls.incrementAndGet();
            throw new RuntimeException("other error");
        });

        assertThatThrownBy(decorated::get).isInstanceOf(RuntimeException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void metrics_track_retry_calls() {
        AtomicInteger calls = new AtomicInteger(0);
        CheckedSupplier<String> decorated = Retry.decorateCheckedSupplier(retry, () -> {
            calls.incrementAndGet();
            throw new IOException("always fails");
        });

        for (int i = 0; i < 3; i++) {
            try { decorated.get(); } catch (Throwable ignored) {}
        }

        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(3);
    }
}
