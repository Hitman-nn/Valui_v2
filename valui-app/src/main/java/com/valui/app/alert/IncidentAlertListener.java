package com.valui.app.alert;

import com.valui.admin.auth.BruteForceAlertEvent;
import com.valui.monitor.stats.MonitorStormEvent;
import com.valui.notify.service.AdminNotificationService;
import com.valui.parser.health.BetBoomWsHighTimeoutRateEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sends Telegram alerts to the admin chat on serious incidents:
 *   B — circuit breaker opened (CLOSED→OPEN only; HALF_OPEN→OPEN is a probe failure, not a new incident)
 *   C — BetBoom WS high timeout rate
 *   D — admin login brute-force
 *
 * DLQ-final alerts are handled directly in DeadLetterPublisher.
 * Parser health (ParserUnavailableEvent/ParserRecoveredEvent) is covered by CB alerts,
 * which fire faster and avoid duplicate admin notifications.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentAlertListener {

    private final CircuitBreakerRegistry    registry;
    private final AdminNotificationService  adminNotificationService;

    // ── B: Circuit Breaker alerts ─────────────────────────────────────────────

    @PostConstruct
    public void subscribeToCbEvents() {
        registry.getAllCircuitBreakers().forEach(this::subscribe);
        registry.getEventPublisher().onEntryAdded(e -> subscribe(e.getAddedEntry()));
    }

    private void subscribe(CircuitBreaker cb) {
        cb.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.StateTransition transition = event.getStateTransition();
            String name = cb.getName().replace("-cb", "").toUpperCase();
            switch (transition) {
                case CLOSED_TO_OPEN ->
                    adminNotificationService.alertAdmin(
                        "⚠️ *Circuit Breaker OPEN*: `" + name + "`\n"
                        + "Парсер временно заблокирован — превышен порог ошибок");
                case HALF_OPEN_TO_CLOSED ->
                    adminNotificationService.alertAdmin(
                        "✅ *Circuit Breaker восстановлен*: `" + name + "`");
                default -> { /* остальные переходы не требуют алерта */ }
            }
        });
    }

    // ── C: BetBoom WS degradation alert ──────────────────────────────────────

    @EventListener
    public void onBetBoomWsHighTimeoutRate(BetBoomWsHighTimeoutRateEvent event) {
        adminNotificationService.alertAdmin(
            "⚠️ *BetBoom WS деградация*: " + event.getCount()
            + " таймаутов за " + event.getWindowMinutes() + " мин");
    }

    // ── D: Monitor storm alerts ──────────────────────────────────────────────

    @EventListener
    public void onMonitorStorm(MonitorStormEvent event) {
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
