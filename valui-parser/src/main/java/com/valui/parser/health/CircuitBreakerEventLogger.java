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
 * OPEN → WARN (failure threshold exceeded, parser calls will short-circuit)
 * HALF_OPEN → INFO (probe requests allowed)
 * CLOSED → INFO (fully recovered)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventLogger {

    private final CircuitBreakerRegistry registry;
    private final ConcurrentHashMap<String, Instant> openedAt = new ConcurrentHashMap<>();

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
                        log.warn("[CB] {} OPEN — failure threshold exceeded (CLOSED→OPEN)", name);
                    } else {
                        // HALF_OPEN→OPEN: probe failed, re-opening; preserve original openedAt for duration tracking
                        openedAt.putIfAbsent(name, Instant.now());
                        log.warn("[CB] {} OPEN — probe failed, still unavailable ({}→OPEN)", name, from);
                    }
                }
                case HALF_OPEN -> log.info("[CB] {} HALF_OPEN — testing recovery", name);
                case CLOSED -> {
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
