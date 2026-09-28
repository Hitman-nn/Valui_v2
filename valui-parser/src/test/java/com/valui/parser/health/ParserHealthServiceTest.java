package com.valui.parser.health;

import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ParserHealthServiceTest {

    private CircuitBreakerRegistry cbRegistry;
    private ParserHealthService service;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        cbRegistry = CircuitBreakerRegistry.of(config);
        cbRegistry.circuitBreaker("fonbet-cb");
        cbRegistry.circuitBreaker("xbet-cb");

        List<BookmakerParser> parsers = List.of(
                stubParser(BookmakerType.FONBET),
                stubParser(BookmakerType.XBET)
        );
        service = new ParserHealthService(cbRegistry, new SimpleMeterRegistry(), parsers);
    }

    @Test
    void getCurrentState_returnsClosed_whenNoFailures() {
        Map<BookmakerType, ParserHealthService.CircuitBreakerInfo> state = service.getCurrentState();

        assertThat(state).containsKey(BookmakerType.FONBET);
        assertThat(state.get(BookmakerType.FONBET).state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(state.get(BookmakerType.FONBET).successRate()).isEqualTo(100f);
    }

    @Test
    void isAvailable_true_whenCbClosed() {
        assertThat(service.isAvailable(BookmakerType.FONBET)).isTrue();
    }

    @Test
    void isAvailable_false_whenCbOpen() {
        CircuitBreaker cb = cbRegistry.circuitBreaker("fonbet-cb");
        // Manually saturate with failures (10 calls, 10 failures = 100% > 50%)
        for (int i = 0; i < 10; i++) {
            cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("fail"));
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(service.isAvailable(BookmakerType.FONBET)).isFalse();
    }

    @Test
    void getFailureRate_returnsNegativeOne_whenNoCbRegistered() {
        assertThat(service.getFailureRate(BookmakerType.BETBOOM)).isEqualTo(-1f);
    }

    @Test
    void getFailureRate_returnsCorrectValue() {
        CircuitBreaker cb = cbRegistry.circuitBreaker("xbet-cb");
        // 3 errors, 7 successes = 30%
        for (int i = 0; i < 7; i++) cb.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        for (int i = 0; i < 3; i++) cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException());

        assertThat(service.getFailureRate(BookmakerType.XBET)).isEqualTo(30f);
    }

    @Test
    void getCurrentState_missingCb_notInResult() {
        Map<BookmakerType, ParserHealthService.CircuitBreakerInfo> state = service.getCurrentState();
        // BETBOOM has no CB registered in this test → not in map
        assertThat(state).doesNotContainKey(BookmakerType.BETBOOM);
    }

    // ── probeOpenCircuitBreakers: HALF_OPEN must skip the OPEN-state backoff ──────

    @Test
    void probeOpenCircuitBreakers_open_throttlesRepeatedProbes() {
        AtomicInteger calls = new AtomicInteger(0);
        List<BookmakerParser> parsers = List.of(countingParser(BookmakerType.FONBET, calls, true));
        ParserHealthService svc = new ParserHealthService(cbRegistry, new SimpleMeterRegistry(), parsers);
        CircuitBreaker cb = cbRegistry.circuitBreaker("fonbet-cb");
        cb.transitionToOpenState();

        // 12 ticks while OPEN and every probe fails — backoff (skip 1→5→10) must kick in
        // once accumulated failures cross the thresholds, so not every tick actually probes.
        for (int i = 0; i < 12; i++) svc.probeOpenCircuitBreakers();

        assertThat(calls.get()).isLessThan(12);
    }

    @Test
    void probeOpenCircuitBreakers_halfOpen_neverThrottles_evenWithPriorOpenFailures() {
        AtomicInteger calls = new AtomicInteger(0);
        List<BookmakerParser> parsers = List.of(countingParser(BookmakerType.FONBET, calls, true));
        ParserHealthService svc = new ParserHealthService(cbRegistry, new SimpleMeterRegistry(), parsers);
        CircuitBreaker cb = cbRegistry.circuitBreaker("fonbet-cb");
        cb.transitionToOpenState();

        // Build up a large accumulated failures count while OPEN (same setup as the test above).
        for (int i = 0; i < 12; i++) svc.probeOpenCircuitBreakers();
        int callsWhileOpen = calls.get();

        // Now HALF_OPEN — despite the high failures count carried over from the OPEN period,
        // every single tick must still probe: this is the 28.09 fix (previously the same
        // failures-based skip applied here too, stretching HALF_OPEN confirmation to 45-90 min).
        cb.transitionToHalfOpenState();
        calls.set(0);
        for (int i = 0; i < 5; i++) svc.probeOpenCircuitBreakers();

        assertThat(callsWhileOpen).isLessThan(12); // sanity: OPEN really did throttle
        assertThat(calls.get()).isEqualTo(5);       // HALF_OPEN: zero skips
    }

    // ── probeOpenCircuitBreakers: long backoff after a sustained hard block ───────

    @Test
    void probeOpenCircuitBreakers_sustainedFailure_escalatesToLongBackoff() {
        AtomicInteger calls = new AtomicInteger(0);
        List<BookmakerParser> parsers = List.of(countingParser(BookmakerType.FONBET, calls, true));
        ParserHealthService svc = new ParserHealthService(cbRegistry, new SimpleMeterRegistry(), parsers);
        CircuitBreaker cb = cbRegistry.circuitBreaker("fonbet-cb");
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState(); // HALF_OPEN never throttles — every tick is a real attempt

        // LONG_BACKOFF_THRESHOLD = 20: exactly 20 consecutive failed ticks should each still
        // probe (escalation arms only once the threshold is reached, on the 20th failure).
        for (int i = 0; i < 20; i++) svc.probeOpenCircuitBreakers();
        assertThat(calls.get()).isEqualTo(20);

        // 21st tick: backoff is now armed — must NOT probe again.
        svc.probeOpenCircuitBreakers();
        assertThat(calls.get()).isEqualTo(20);

        // A few more ticks while still within the backoff window — still nothing.
        for (int i = 0; i < 5; i++) svc.probeOpenCircuitBreakers();
        assertThat(calls.get()).isEqualTo(20);
    }

    @Test
    void probeOpenCircuitBreakers_afterLongBackoffWindowElapses_resumesProbing() {
        AtomicInteger calls = new AtomicInteger(0);
        List<BookmakerParser> parsers = List.of(countingParser(BookmakerType.FONBET, calls, true));
        ParserHealthService svc = new ParserHealthService(cbRegistry, new SimpleMeterRegistry(), parsers);
        CircuitBreaker cb = cbRegistry.circuitBreaker("fonbet-cb");
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState();

        for (int i = 0; i < 21; i++) svc.probeOpenCircuitBreakers(); // arms the backoff
        assertThat(calls.get()).isEqualTo(20);

        // Simulate the 45-minute backoff window having already elapsed.
        @SuppressWarnings("unchecked")
        Map<com.valui.common.domain.BookmakerType, Long> longBackoffUntilMs =
                (Map<com.valui.common.domain.BookmakerType, Long>)
                        org.springframework.test.util.ReflectionTestUtils.getField(svc, "longBackoffUntilMs");
        longBackoffUntilMs.put(BookmakerType.FONBET, System.currentTimeMillis() - 1_000L);

        svc.probeOpenCircuitBreakers();

        assertThat(calls.get()).isEqualTo(21); // resumed probing on the first tick past the window
    }

    // ── stub ─────────────────────────────────────────────────────────────────

    private static BookmakerParser stubParser(BookmakerType type) {
        return new BookmakerParser() {
            @Override public BookmakerType getBookmaker() { return type; }
            @Override public ParseResult<List<SportDto>> fetchSports() { return ParseResult.ok(List.of(), 0); }
            @Override public ParseResult<List<TournamentDto>> fetchTournaments(String s) { return ParseResult.ok(List.of(), 0); }
            @Override public ParseResult<List<ParsedMatchDto>> fetchMatches(String s) { return ParseResult.ok(List.of(), 0); }
        };
    }

    /** Counts fetchSports() invocations; optionally always throws (simulating a still-broken bookmaker). */
    private static BookmakerParser countingParser(BookmakerType type, AtomicInteger calls, boolean alwaysFail) {
        return new BookmakerParser() {
            @Override public BookmakerType getBookmaker() { return type; }
            @Override public ParseResult<List<SportDto>> fetchSports() {
                calls.incrementAndGet();
                if (alwaysFail) throw new RuntimeException("still broken");
                return ParseResult.ok(List.of(), 0);
            }
            @Override public ParseResult<List<TournamentDto>> fetchTournaments(String s) { return ParseResult.ok(List.of(), 0); }
            @Override public ParseResult<List<ParsedMatchDto>> fetchMatches(String s) { return ParseResult.ok(List.of(), 0); }
        };
    }
}
