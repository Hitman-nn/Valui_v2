package com.valui.app.alert;

import com.valui.notify.service.AdminNotificationService;
import com.valui.parser.health.ParserRecoveredEvent;
import com.valui.parser.health.ParserUnavailableEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sends Telegram alerts to the admin chat on serious incidents:
 *   A — parser unavailable (ParserUnavailableEvent, consecutive >= threshold)
 *   B — circuit breaker opened (CLOSED→OPEN only; HALF_OPEN→OPEN is a probe failure, not a new incident)
 *
 * DLQ-final alerts (C) are handled directly in DeadLetterPublisher.
 * Recovery notifications are sent for both A and B.
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

    // ── A: Parser health alerts ───────────────────────────────────────────────

    @EventListener
    public void onParserUnavailable(ParserUnavailableEvent event) {
        adminNotificationService.alertAdmin(
            "🔴 *Парсер недоступен*: `" + event.getBookmaker() + "`\n"
            + "consecutive=" + event.getConsecutiveFailures());
    }

    @EventListener
    public void onParserRecovered(ParserRecoveredEvent event) {
        adminNotificationService.alertAdmin(
            "✅ *Парсер восстановлен*: `" + event.getBookmaker() + "`");
    }
}
