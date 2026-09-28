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

import java.time.Duration;
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

    // 28.09 finding: a genuine hard block (1xbet.kz fully blackholing the proxy IP at the
    // network level, not just serving a captcha) can last hours — three such incidents this
    // week ran ~2.5h each, one is still ongoing as of this fix. Probing every 3 minutes
    // (unthrottled while HALF_OPEN, per the earlier 28.09 fix) is exactly right for a
    // transient blip, but for a SUSTAINED hard block it just means hammering an already-banned
    // IP every 3 minutes for hours — plausibly making the ban worse if the remote side resets
    // its own ban timer on continued detected activity, with zero benefit since the connection
    // isn't even completing. LONG_BACKOFF_THRESHOLD consecutive non-CLOSED ticks (each tick is
    // exactly one @Scheduled invocation, so this is wall-clock, not attempt count) escalates
    // to a much longer, quiet LONG_BACKOFF_DURATION pause before trying again — after which a
    // single fresh probe either finds it recovered or restarts the same escalation from zero.
    private static final int      LONG_BACKOFF_THRESHOLD = 20;                    // ≈ 60 min at the 3-min tick rate
    private static final Duration LONG_BACKOFF_DURATION  = Duration.ofMinutes(45);
    private final Map<BookmakerType, Long> longBackoffUntilMs = new ConcurrentHashMap<>();

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
                longBackoffUntilMs.remove(bk);
                return;
            }

            long now = System.currentTimeMillis();
            Long backoffUntil = longBackoffUntilMs.get(bk);
            if (backoffUntil != null) {
                if (now < backoffUntil) {
                    log.debug("[CB-PROBE] {} in long backoff (sustained failure) — skipping until {}",
                            bk, java.time.Instant.ofEpochMilli(backoffUntil));
                    return;
                }
                // Backoff window elapsed — one fresh attempt, clean slate either way.
                longBackoffUntilMs.remove(bk);
                probeFailures.remove(bk);
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
                int n = probeFailures.computeIfAbsent(bk, k -> new AtomicInteger(0)).incrementAndGet();
                log.debug("[CB-PROBE] {} probe exception: {}", bk, e.getMessage());
                if (n >= LONG_BACKOFF_THRESHOLD) {
                    longBackoffUntilMs.put(bk, now + LONG_BACKOFF_DURATION.toMillis());
                    log.warn("[CB-PROBE] {} failed {} consecutive probe ticks (~{} min) — looks like a " +
                            "sustained/hard block rather than a transient blip; backing off entirely for " +
                            "{} instead of continuing to probe every cycle",
                            bk, n, n * 3, LONG_BACKOFF_DURATION);
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
