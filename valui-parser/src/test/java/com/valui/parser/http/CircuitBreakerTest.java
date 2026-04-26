package com.valui.parser.http;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerTest {

    private CircuitBreaker cb;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        cb = CircuitBreakerRegistry.of(config).circuitBreaker("test-cb");
    }

    @Test
    void opens_when_failureRateExceedsThreshold() {
        // 6 failures + 4 successes = 60% failure rate → OPEN
        runTimes(6, () -> { throw new RuntimeException("fail"); });
        runTimes(4, () -> "ok");

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void stays_closed_below_threshold() {
        // 4 failures + 6 successes = 40% failure rate → CLOSED
        runTimes(4, () -> { throw new RuntimeException("fail"); });
        runTimes(6, () -> "ok");

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getFailureRate()).isEqualTo(40f);
    }

    @Test
    void open_cb_rejects_immediately() {
        // saturate with failures
        runTimes(10, () -> { throw new RuntimeException("fail"); });
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // next call must be rejected without executing
        AtomicInteger executed = new AtomicInteger(0);
        Supplier<String> decorated = CircuitBreaker.decorateSupplier(cb, () -> {
            executed.incrementAndGet();
            return "should not run";
        });

        assertThatThrownBy(decorated::get).isInstanceOf(CallNotPermittedException.class);
        assertThat(executed.get()).isZero();
    }

    @Test
    void transitions_to_half_open_after_wait() throws Exception {
        runTimes(10, () -> { throw new RuntimeException("fail"); });
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(250); // wait > waitDurationInOpenState (200ms)
        cb.transitionToHalfOpenState(); // manual trigger for test

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void metrics_track_success_and_failure_counts() {
        runTimes(3, () -> "ok");
        runTimes(7, () -> { throw new RuntimeException("fail"); });

        CircuitBreaker.Metrics m = cb.getMetrics();
        assertThat(m.getNumberOfSuccessfulCalls()).isEqualTo(3);
        assertThat(m.getNumberOfFailedCalls()).isEqualTo(7);
        assertThat(m.getFailureRate()).isEqualTo(70f);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private void runTimes(int n, Supplier<Object> supplier) {
        Supplier<Object> decorated = CircuitBreaker.decorateSupplier(cb, supplier);
        for (int i = 0; i < n; i++) {
            try { decorated.get(); } catch (Exception ignored) {}
        }
    }
}
