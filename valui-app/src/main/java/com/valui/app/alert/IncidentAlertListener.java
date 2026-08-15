package com.valui.app.alert;

import com.valui.admin.auth.BruteForceAlertEvent;
import com.valui.common.domain.BookmakerType;
import com.valui.monitor.stats.MonitorStormEvent;
import com.valui.notify.service.AdminNotificationService;
import com.valui.parser.health.BetBoomWsHighTimeoutRateEvent;
import com.valui.parser.health.ParserHealthChecker;
import com.valui.parser.health.ParserIncidentStateStore;
import com.valui.parser.health.ParserRecoveredEvent;
import com.valui.parser.health.ParserUnavailableEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends Telegram alerts to the admin chat on serious incidents:
 *   B — circuit breaker opened (CLOSED→OPEN only; HALF_OPEN→OPEN is a probe failure, not a new incident)
 *   C — BetBoom WS high timeout rate
 *   D — admin login brute-force
 *
 * DLQ-final alerts are handled directly in DeadLetterPublisher.
 * Parser health in the normal case is covered by the CB alerts above (B), which fire faster than
 * ParserHealthChecker's active-probe backstop. But a restart mid-incident silently drops the "B"
 * recovery signal — see {@link #onParserRecoveredReconciled} below — so ParserHealthChecker's
 * events are also handled, filtered to just the cases (B) can't cover to avoid double-alerting.
 *
 * <p>B alerts are per-breaker cooldown-throttled ({@link #ALERT_COOLDOWN}) and can be disabled
 * wholesale via {@code valui.alerts.parser-incidents.admin-enabled} — without the cooldown, a
 * genuinely flapping bookmaker (repeated CLOSED→OPEN→HALF_OPEN→CLOSED, once per
 * wait-duration-in-open-state) would fire one Telegram message per transition, unbounded.
 *
 * <p>{@link #handleTransition} additionally gates through {@link ParserIncidentStateStore} —
 * the cooldown alone isn't enough, because it only throttles *repeats*, not a genuinely separate
 * detector reporting the same real-world transition. For a low-traffic bookmaker,
 * {@link ParserHealthChecker}'s active probe can confirm a recovery (via
 * {@link #onParserRecoveredReconciled}) well before the CB itself accumulates enough real
 * HALF_OPEN trial traffic to transition on its own — and once it eventually does, more than
 * {@link #ALERT_COOLDOWN} later, {@link #handleTransition} would otherwise alert a second time
 * for the same incident (this is exactly what caused a real duplicate: one "recovered" message
 * with downtime duration, a later one without, because {@link #openedAt} had already been
 * consumed by the first).
 *
 * <p>The actual Telegram send in {@link #sendCbAlert} is dispatched via
 * {@link #sendAsync}/{@code @Async}, not called inline from the CB callback: that callback runs
 * synchronously on resilience4j's own state-transition thread, and a blocking network call
 * sitting there is a real risk to the breaker's own event dispatch (see
 * {@link BookmakerIncidentNotifier}'s javadoc for a production incident this exact pattern
 * caused elsewhere in this class's sibling).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentAlertListener {

    /** Minimum gap between two CB alerts (OPEN or RECOVERED) for the *same* breaker. */
    private static final Duration ALERT_COOLDOWN = Duration.ofMinutes(3);

    // Sentinel for "never alerted yet" — see GcPauseWatchdog for why not 0.
    private static final long NEVER_ALERTED = Long.MIN_VALUE / 2;

    private final CircuitBreakerRegistry    registry;
    private final AdminNotificationService  adminNotificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ParserIncidentStateStore  incidentStore;

    @Value("${valui.alerts.parser-incidents.admin-enabled:true}")
    private boolean adminAlertsEnabled;

    // cb name → last Telegram send (cooldown gate)
    private final ConcurrentHashMap<String, AtomicLong> lastAlertAtMs = new ConcurrentHashMap<>();
    // cb name → when it opened, so the recovery alert can report how long it was down.
    // Tracked unconditionally (cheap) even if the OPEN alert itself got cooldown-suppressed.
    private final ConcurrentHashMap<String, Instant> openedAt = new ConcurrentHashMap<>();

    // ── B: Circuit Breaker alerts ─────────────────────────────────────────────

    @PostConstruct
    public void subscribeToCbEvents() {
        registry.getAllCircuitBreakers().forEach(this::subscribe);
        registry.getEventPublisher().onEntryAdded(e -> subscribe(e.getAddedEntry()));
    }

    private void subscribe(CircuitBreaker cb) {
        cb.getEventPublisher().onStateTransition(event ->
            handleTransition(cb.getName(), event.getStateTransition(), System.currentTimeMillis()));
    }

    /**
     * The actual cooldown/toggle decision, split out from the CB event callback above so it's
     * testable deterministically (explicit {@code nowMs}) instead of racing the system clock —
     * same pattern as {@code GcPauseWatchdog.handlePause}.
     */
    void handleTransition(String cbName, CircuitBreaker.StateTransition transition, long nowMs) {
        String display = cbName.replace("-cb", "").toUpperCase();
        BookmakerType bm = toBookmaker(cbName);
        switch (transition) {
            case CLOSED_TO_OPEN -> {
                // bm == null: a non-parser CB (shouldn't exist today, but handleTransition isn't
                // bookmaker-specific by design) — nothing to dedup against, always alert.
                if (bm != null && !incidentStore.markOpen(bm)) return;
                openedAt.put(cbName, Instant.now());
                sendCbAlert(cbName, nowMs,
                    "⚠️ *Circuit Breaker OPEN*: `" + display + "`\n"
                    + "Парсер временно заблокирован — превышен порог ошибок");
            }
            case HALF_OPEN_TO_CLOSED -> {
                // See class javadoc: without this gate, a slow real CB transition arriving after
                // ParserHealthChecker already reconciled the same recovery re-alerts a second time.
                if (bm != null && !incidentStore.markClosed(bm)) return;
                Instant opened = openedAt.remove(cbName);
                String suffix = opened != null
                    ? " (была недоступна " + formatDuration(Duration.between(opened, Instant.now())) + ")"
                    : "";
                sendCbAlert(cbName, nowMs, "✅ *Circuit Breaker восстановлен*: `" + display + "`" + suffix);
            }
            default -> { /* остальные переходы не требуют алерта */ }
        }
    }

    private static BookmakerType toBookmaker(String cbName) {
        try {
            return BookmakerType.valueOf(cbName.replace("-cb", "").toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void sendCbAlert(String cbName, long nowMs, String text) {
        if (!adminAlertsEnabled) return;
        if (!tryClaim(cbName, nowMs)) {
            log.debug("[CB-ALERT] Suppressed (cooldown): {}", cbName);
            return;
        }
        // Publish, don't call adminNotificationService.alertAdmin(text) inline — see class
        // javadoc. sendAsync below is the @Async entry point that actually performs the send.
        eventPublisher.publishEvent(new CbAlertRequest(text));
    }

    /** Local event type — just a carrier to cross from the CB callback thread onto an async one
     *  via sendAsync below. Not meant to be observed by any other listener. Package-private
     *  (not private) so the test can assert on published content. */
    record CbAlertRequest(String text) {}

    @Async
    @EventListener
    void sendAsync(CbAlertRequest request) {
        adminNotificationService.alertAdmin(request.text());
    }

    /** CAS, not a plain check-then-set: concurrent state-transition callbacks for the same
     *  breaker must not both pass the cooldown check before either updates the timestamp. */
    boolean tryClaim(String cbName, long nowMs) {
        AtomicLong ts = lastAlertAtMs.computeIfAbsent(cbName, k -> new AtomicLong(NEVER_ALERTED));
        long previous = ts.get();
        if (nowMs - previous < ALERT_COOLDOWN.toMillis()) return false;
        return ts.compareAndSet(previous, nowMs);
    }

    private static String formatDuration(Duration d) {
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) return h + "h" + m + "m";
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }

    // ── B (reconciled): restart-survives parser recovery/outage detection ─────

    /**
     * Catches the case {@link #handleTransition} structurally cannot: a restart while a breaker
     * is OPEN/HALF_OPEN. A freshly created CircuitBreaker boots CLOSED and never re-emits
     * CLOSED_TO_OPEN/HALF_OPEN_TO_CLOSED for state that existed before the restart, so the direct
     * CB subscription above goes silent for that incident. {@link ParserHealthChecker}'s active
     * probe reads the same persisted {@code ParserIncidentStateStore} the CB path writes to, so
     * it is the one detector that can still confirm and announce what actually happened.
     *
     * <p>Filtered to {@code event.getSource() instanceof ParserHealthChecker}: in the normal
     * (non-restart) case, BookmakerIncidentNotifier's CB subscription publishes this same event
     * type too, and {@link #handleTransition} above already alerts admin for that one directly —
     * without this filter, both would fire for a single real transition.
     */
    @EventListener
    public void onParserUnavailableReconciled(ParserUnavailableEvent event) {
        if (!(event.getSource() instanceof ParserHealthChecker)) return;
        sendCbAlert(cbNameOf(event.getBookmaker()), System.currentTimeMillis(),
            "⚠️ *Circuit Breaker OPEN*: `" + event.getBookmaker() + "`\n"
            + "Парсер временно заблокирован — превышен порог ошибок (обнаружено активной проверкой)");
    }

    /** See {@link #onParserUnavailableReconciled} — same restart gap, recovery side. */
    @EventListener
    public void onParserRecoveredReconciled(ParserRecoveredEvent event) {
        if (!(event.getSource() instanceof ParserHealthChecker)) return;
        String cbName = cbNameOf(event.getBookmaker());
        Instant opened = openedAt.remove(cbName);
        String suffix = opened != null
            ? " (была недоступна " + formatDuration(Duration.between(opened, Instant.now())) + ")"
            : "";
        sendCbAlert(cbName, System.currentTimeMillis(),
            "✅ *Circuit Breaker восстановлен*: `" + event.getBookmaker() + "`" + suffix);
    }

    private static String cbNameOf(BookmakerType bookmaker) {
        return bookmaker.name().toLowerCase() + "-cb";
    }

    // ── C: BetBoom WS degradation alert ──────────────────────────────────────

    @EventListener
    public void onBetBoomWsHighTimeoutRate(BetBoomWsHighTimeoutRateEvent event) {
        adminNotificationService.alertAdmin(
            "⚠️ *BetBoom WS деградация*: " + event.getCount()
            + " таймаутов за " + event.getWindowMinutes() + " мин");
    }

    // ── D: Monitor storm alerts ──────────────────────────────────────────────

    /**
     * Off by default ({@code valui.alerts.monitor-storm-enabled}): in practice this fires
     * alongside a per-bookmaker CB alert that already pinged admin about the same incident, so
     * the Telegram send added little the WARN line in {@code MonitorSummaryLogger} didn't already
     * cover — that log line keeps firing regardless of this toggle.
     */
    @Value("${valui.alerts.monitor-storm-enabled:false}")
    private boolean monitorStormAlertsEnabled;

    @EventListener
    public void onMonitorStorm(MonitorStormEvent event) {
        if (!monitorStormAlertsEnabled) return;
        adminNotificationService.alertAdmin(String.format(
            "⚠️ *Шторм монитора*: ошибок=%d/%d (%.1f%%) в %d окнах подряд",
            event.getErrors(), event.getTotal(), event.getRate(), event.getConsecutiveWindows()));
    }

    // ── E: Admin login brute-force alerts ────────────────────────────────────

    @EventListener
    public void onBruteForce(BruteForceAlertEvent event) {
        adminNotificationService.alertAdmin(
            "🚨 *Подозрительная активность*: `" + event.getPath() + "`\n"
            + "IP: `" + event.getIp() + "` — " + event.getAttempts() + " попыток за 60 с");
    }
}
