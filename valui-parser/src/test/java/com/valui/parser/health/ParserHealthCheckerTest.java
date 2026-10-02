package com.valui.parser.health;

import com.valui.common.domain.BookmakerType;
import com.valui.parser.api.BookmakerParser;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("ParserHealthChecker — recovery waits for the circuit breaker")
class ParserHealthCheckerTest {

    private final BookmakerParser parser = mock(BookmakerParser.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ParserIncidentStateStore store = mock(ParserIncidentStateStore.class);
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    private CircuitBreaker cb;
    private ParserHealthChecker checker;

    @BeforeEach
    void setUp() {
        given(parser.getBookmaker()).willReturn(BookmakerType.XBET);
        given(parser.isAvailable()).willReturn(true);
        given(store.isOpen(BookmakerType.XBET)).willReturn(true);
        given(store.markClosed(BookmakerType.XBET)).willReturn(true);
        cb = registry.circuitBreaker("xbet-cb");
        checker = new ParserHealthChecker(List.of(parser), publisher, store, registry);
    }

    @Test
    @DisplayName("REGRESSION 02.10: direct probe succeeds while CB is HALF_OPEN — no premature 'recovered'")
    void probeOk_cbHalfOpen_incidentStaysOpen() {
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState();

        checker.checkAll();
        checker.checkAll();
        checker.checkAll();

        verify(store, never()).markClosed(any());
        verify(publisher, never()).publishEvent(any(ParserRecoveredEvent.class));
    }

    @Test
    @DisplayName("Once the CB is CLOSED the incident closes as before")
    void probeOk_cbClosed_recoveryPublished() {
        checker.checkAll();
        checker.checkAll();

        verify(store).markClosed(BookmakerType.XBET);
        verify(publisher).publishEvent(any(ParserRecoveredEvent.class));
    }
}
