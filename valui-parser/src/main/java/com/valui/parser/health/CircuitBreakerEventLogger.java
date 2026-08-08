package com.valui.parser.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs Resilience4j circuit breaker state transitions for all parser circuit breakers.
 * CLOSED→OPEN → WARN (failure threshold exceeded, parser calls will short-circuit)
 * HALF_OPEN→OPEN → INFO (probe failed, still unavailable — not a new incident)
 * HALF_OPEN → INFO (probe requests allowed)
 * CLOSED → INFO (fully recovered)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventLogger {

    private static final int QUIET_AFTER_CYCLES = 5;

    private final CircuitBreakerRegistry registry;
    private final ConcurrentHashMap<String, Instant> openedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> reopenCycles = new ConcurrentHashMap<>();

    @PostConstruct
    public void registerListeners() {
        registry.getAllCircuitBreakers().forEach(this::subscribe);
        registry.getEventPublisher().onEntryAdded(e -> subscribe(e.getAddedEntry()));
    }

    private void subscribe(CircuitBreaker cb) {
        cb.getEventPublisher().onStateTransition(event -> {
            String name = cb.getName();
            CircuitBreaker.State to = event.getStateTransition().getToState();
            CircuitBreaker.State from = event.getStateTransition().getFromState();
            switch (to) {
                case OPEN -> {
                    if (from == CircuitBreaker.State.CLOSED) {
                        openedAt.put(name, Instant.now());
                        reopenCycles.put(name, 0);
                        CircuitBreaker.Metrics m = cb.getMetrics();
                        log.warn("[CB] {} OPEN — failure threshold exceeded (CLOSED→OPEN) " +
                                "failureRate={}% failedCalls={} totalCalls={}",
                                name, m.getFailureRate(), m.getNumberOfFailedCalls(), m.getNumberOfBufferedCalls());
                    } else {
                        openedAt.putIfAbsent(name, Instant.now());
                        int cycles = reopenCycles.merge(name, 1, Integer::sum);
                        if (cycles <= QUIET_AFTER_CYCLES) {
                            log.info("[CB] {} OPEN — probe failed, still unavailable ({}→OPEN)", name, from);
                        } else if (cycles % 10 == 0) {
                            log.info("[CB] {} OPEN — still unavailable (cycle {}, {}→OPEN)", name, cycles, from);
                        } else {
                            log.debug("[CB] {} OPEN — probe failed (cycle {}, {}→OPEN)", name, cycles, from);
                        }
                    }
                }
                case HALF_OPEN -> {
                    int cycles = reopenCycles.getOrDefault(name, 0);
                    if (cycles <= QUIET_AFTER_CYCLES) {
                        log.info("[CB] {} HALF_OPEN — testing recovery", name);
                    } else {
                        log.debug("[CB] {} HALF_OPEN — testing recovery (cycle {})", name, cycles);
                    }
                }
                case CLOSED -> {
                    reopenCycles.remove(name);
                    Instant opened = openedAt.remove(name);
                    String duration = opened != null
                            ? " (open for " + formatDuration(Duration.between(opened, Instant.now())) + ")"
                            : "";
                    log.info("[CB] {} CLOSED — recovered ({}→CLOSED{})", name, from, duration);
                }
                default -> log.debug("[CB] {} {}→{}", name, from, to);
            }
        });
    }

    private static String formatDuration(Duration d) {
        return HealthFormatUtils.formatDuration(d);
    }
}
