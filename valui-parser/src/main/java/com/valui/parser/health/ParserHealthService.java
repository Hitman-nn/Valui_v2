package com.valui.parser.health;

import com.valui.common.domain.BookmakerType;
import com.valui.parser.api.BookmakerParser;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParserHealthService {

    private final CircuitBreakerRegistry cbRegistry;
    private final MeterRegistry meterRegistry;
    private final List<BookmakerParser> parsers;

    public record CircuitBreakerInfo(
            CircuitBreaker.State state,
            float successRate,
            long numberOfSuccessfulCalls,
            long numberOfFailedCalls,
            long numberOfNotPermittedCalls
    ) {}

    @PostConstruct
    void bindMetrics() {
        // Proactively create CB instances from config so they exist at startup.
        // Without this, @CircuitBreaker proxies create CBs lazily on first invocation,
        // leaving the registry empty when bindTo() runs.
        parsers.forEach(p -> cbRegistry.circuitBreaker(p.getBookmaker().name().toLowerCase() + "-cb"));

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(cbRegistry).bindTo(meterRegistry);
        List<String> cbNames = cbRegistry.getAllCircuitBreakers().stream()
                .map(CircuitBreaker::getName)
                .sorted()
                .toList();
        log.info("Resilience4j CB metrics bound to Micrometer: {}", cbNames);
    }

    /** Returns CB state for every registered parser. */
    public Map<BookmakerType, CircuitBreakerInfo> getCurrentState() {
        Map<BookmakerType, CircuitBreakerInfo> result = new LinkedHashMap<>();
        parsers.forEach(p -> findCb(p.getBookmaker())
                .ifPresent(cb -> result.put(p.getBookmaker(), toInfo(cb))));
        return result;
    }

    /**
     * True if the CB is not OPEN / FORCED_OPEN.
     * Use this (not parser.isAvailable()) in the monitor scheduler for CB-aware routing.
     */
    public boolean isAvailable(BookmakerType type) {
        return findCb(type)
                .map(cb -> cb.getState() != CircuitBreaker.State.OPEN &&
                           cb.getState() != CircuitBreaker.State.FORCED_OPEN)
                .orElse(true); // no CB registered → assume available
    }

    /** Latest failure rate for a bookmaker (0-100). -1 if unknown. */
    public float getFailureRate(BookmakerType type) {
        return findCb(type)
                .map(cb -> cb.getMetrics().getFailureRate())
                .orElse(-1f);
    }

    /**
     * Periodically sends a probe call through each OPEN circuit breaker so that
     * Resilience4j can evaluate whether {@code waitDurationInOpenState} has elapsed
     * and auto-transition to HALF_OPEN.
     *
     * Without this, ControllerTask skips tasks when the CB is OPEN (to avoid
     * saturating the error budget) which means NO calls go through the CB proxy —
     * leaving it stuck OPEN indefinitely even after the bookmaker recovers.
     * {@code automatic-transition-from-open-to-half-open-enabled: true} handles the
     * OPEN→HALF_OPEN timer, but that timer only fires if the CB instance receives a
     * wakeup call in some Resilience4j builds. This probe is the backstop.
     */
    @Scheduled(fixedRate = 3, timeUnit = TimeUnit.MINUTES, initialDelay = 3)
    public void probeOpenCircuitBreakers() {
        parsers.forEach(parser -> {
            if (!isAvailable(parser.getBookmaker())) {
                BookmakerType bk = parser.getBookmaker();
                log.debug("[CB-PROBE] {} OPEN — probing via fetchSports()", bk);
                try {
                    parser.fetchSports();
                } catch (Exception e) {
                    log.debug("[CB-PROBE] {} probe exception: {}", bk, e.getMessage());
                }
            }
        });
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Optional<CircuitBreaker> findCb(BookmakerType type) {
        String name = type.name().toLowerCase() + "-cb";
        return cbRegistry.find(name);
    }

    private static CircuitBreakerInfo toInfo(CircuitBreaker cb) {
        CircuitBreaker.Metrics m = cb.getMetrics();
        float rate = m.getFailureRate();
        float successRate = rate >= 0 ? (100f - rate) : 100f;
        return new CircuitBreakerInfo(
                cb.getState(),
                successRate,
                m.getNumberOfSuccessfulCalls(),
                m.getNumberOfFailedCalls(),
                m.getNumberOfNotPermittedCalls()
        );
    }
}
