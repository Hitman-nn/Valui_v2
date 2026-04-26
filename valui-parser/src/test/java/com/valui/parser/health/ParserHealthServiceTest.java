package com.valui.parser.health;

import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.MatchDto;
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

    // ── stub ─────────────────────────────────────────────────────────────────

    private static BookmakerParser stubParser(BookmakerType type) {
        return new BookmakerParser() {
            @Override public BookmakerType getBookmaker() { return type; }
            @Override public ParseResult<List<SportDto>> fetchSports() { return ParseResult.ok(List.of(), 0); }
            @Override public ParseResult<List<TournamentDto>> fetchTournaments(String s) { return ParseResult.ok(List.of(), 0); }
            @Override public ParseResult<List<MatchDto>> fetchMatches(String s) { return ParseResult.ok(List.of(), 0); }
        };
    }
}
