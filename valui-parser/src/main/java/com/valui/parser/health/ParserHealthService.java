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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParserHealthService {

    private final CircuitBreakerRegistry cbRegistry;
    private final MeterRegistry meterRegistry;
    private final List<BookmakerParser> parsers;

    private final Map<BookmakerType, AtomicInteger> probeFailures = new ConcurrentHashMap<>();

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
                .map(cb -> cb.getState() == CircuitBreaker.State.CLOSED)
                .orElse(true);
    }

    /** Latest failure rate for a bookmaker (0-100). -1 if unknown. */
    public float getFailureRate(BookmakerType type) {
        return findCb(type)
                .map(cb -> cb.getMetrics().getFailureRate())
                .orElse(-1f);
    }

    /**
     * Periodically sends a probe call through each non-CLOSED circuit breaker so that
     * Resilience4j can evaluate whether {@code waitDurationInOpenState} has elapsed
     * and auto-transition to HALF_OPEN.
     *
     * Without this, ControllerTask skips tasks when the CB is OPEN (to avoid
     * saturating the error budget) which means NO calls go through the CB proxy —
     * leaving it stuck OPEN indefinitely even after the bookmaker recovers.
     * {@code automatic-transition-from-open-to-half-open-enabled: true} handles the
     * OPEN→HALF_OPEN timer, but that timer only fires if the CB instance receives a
     * wakeup call in some Resilience4j builds. This probe is the backstop.
     *
     * <p><b>28.09 finding — HALF_OPEN must NOT share OPEN's backoff.</b> A real xbet-cb
     * incident (28.09, 00:22–02:52) took ~2.5h to fully recover even though
     * {@code wait-duration-in-open-state} is only 2m: {@link #isAvailable} blocks regular
     * controller traffic for BOTH OPEN and HALF_OPEN, so this probe's own calls are the
     * ONLY calls ever reaching the CB while it's non-CLOSED — including the
     * {@code permitted-number-of-calls-in-half-open-state=3} calls Resilience4j needs to
     * decide CLOSED vs back-to-OPEN. The failures-based backoff below (skip 1→5→10 ticks,
     * i.e. 3min→15min→30min) is right for OPEN — don't hammer a confirmed-broken bookmaker
     * — but applying the SAME backoff to HALF_OPEN meant those 3 decisive calls got spread
     * 15-30 minutes apart, stretching what should be a quick verdict into ~45-90 minutes
     * per HALF_OPEN attempt. HALF_OPEN is already self-limiting (Resilience4j caps it to
     * those 3 calls regardless of how fast they arrive), so there's no hammering risk in
     * probing it every tick — this now skips the backoff entirely once the CB has actually
     * reached HALF_OPEN, cutting confirmation time to ~this method's own 3-minute cadence.
     */
    @Scheduled(fixedRate = 3, timeUnit = TimeUnit.MINUTES, initialDelay = 3)
    public void probeOpenCircuitBreakers() {
        parsers.forEach(parser -> {
            BookmakerType bk = parser.getBookmaker();
            Optional<CircuitBreaker> cbOpt = findCb(bk);
            CircuitBreaker.State state = cbOpt.map(CircuitBreaker::getState).orElse(CircuitBreaker.State.CLOSED);
            if (state == CircuitBreaker.State.CLOSED) {
                probeFailures.remove(bk);
                return;
            }
            if (state != CircuitBreaker.State.HALF_OPEN) {
                int failures = probeFailures
                        .computeIfAbsent(bk, k -> new AtomicInteger(0)).get();
                int skip = failures < 10 ? 1 : failures < 30 ? 5 : 10;
                if (failures > 0 && failures % skip != 0) {
                    probeFailures.get(bk).incrementAndGet();
                    return;
                }
            }
            log.debug("[CB-PROBE] {} {} — probing via fetchSports()", bk, state);
            try {
                parser.fetchSports();
                probeFailures.remove(bk);
            } catch (Exception e) {
                probeFailures.computeIfAbsent(bk, k -> new AtomicInteger(0)).incrementAndGet();
                log.debug("[CB-PROBE] {} probe exception: {}", bk, e.getMessage());
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
