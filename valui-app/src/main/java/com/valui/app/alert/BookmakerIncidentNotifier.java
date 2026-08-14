package com.valui.app.alert;

import com.valui.bot.listener.ParserAvailabilityRegistry;
import com.valui.common.domain.BookmakerType;
import com.valui.parser.health.ParserRecoveredEvent;
import com.valui.parser.health.ParserUnavailableEvent;
import com.valui.user.repository.ControllerSubscriptionRepository;
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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notifies affected Telegram chats when a bookmaker parser becomes unavailable or recovers.
 *
 * <p>Two independent triggers feed the same notify logic below, both as {@link ParserUnavailableEvent}/
 * {@link ParserRecoveredEvent}:
 * <ul>
 *   <li>Circuit breaker state transitions — fast (seconds), driven by real traffic through
 *       {@code ControllerTaskExecutor}. Same signal {@link IncidentAlertListener} uses for the
 *       admin alert, so users now hear about an incident on the same timescale admin does,
 *       instead of waiting on the slow poll below.
 *   <li>The same events from {@code ParserHealthChecker}'s independent ~5-minute poll — kept as
 *       a backstop for a bookmaker with too little real traffic to ever trip its breaker.
 * </ul>
 * The CB-transition handler below only <em>publishes</em> the event, deliberately not calling
 * {@link #notifyUnavailable}/{@link #notifyRecovered} directly: that callback runs synchronously
 * on resilience4j's own state-transition thread (the same thread that just executed the
 * failing/succeeding parser call). Doing the real work there — a blocking DB query plus one
 * blocking Telegram send per chat — caused a real production incident: a transient DB hiccup
 * during the query threw back out of the listener into
 * {@code CircuitBreakerStateMachine.publishStateTransitionEvent}, logged as "Failed to handle
 * event STATE_TRANSITION". Publishing routes both triggers through the identical {@code @Async}
 * {@code @EventListener} entry point below, off that thread — same fix on both ends, same
 * dedup/claim logic in {@link #notifiedChats} either way.
 *
 * One message per chat per incident. Uses ParserAvailabilityRegistry so the bot shows ⚠️ on the
 * BK selection keyboard and returns a toast instead of processing the selection — that always
 * stays live regardless of the {@code users-enabled} toggle below, since it's a UI indicator,
 * not a notification.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookmakerIncidentNotifier {

    private final AbsSender                       bot;
    private final ControllerSubscriptionRepository subscriptionRepo;
    private final ParserAvailabilityRegistry       availabilityRegistry;
    private final CircuitBreakerRegistry           cbRegistry;
    private final ApplicationEventPublisher        eventPublisher;

    @Value("${valui.alerts.parser-incidents.users-enabled:true}")
    private boolean usersAlertsEnabled;

    // bm → set of chatIds that received "unavailable" message for the current incident
    private final ConcurrentHashMap<BookmakerType, Set<Long>> notifiedChats = new ConcurrentHashMap<>();

    // ── fast trigger: circuit breaker state (mirrors IncidentAlertListener) ────

    @PostConstruct
    void subscribeToCbEvents() {
        cbRegistry.getAllCircuitBreakers().forEach(this::subscribe);
        cbRegistry.getEventPublisher().onEntryAdded(e -> subscribe(e.getAddedEntry()));
    }

    private void subscribe(CircuitBreaker cb) {
        cb.getEventPublisher().onStateTransition(event -> {
            BookmakerType bm = toBookmaker(cb.getName());
            if (bm == null) return; // non-parser CB, if one is ever registered — nothing to notify
            // Publish only — see class javadoc for why this must not call notifyUnavailable/
            // notifyRecovered directly from this thread.
            switch (event.getStateTransition()) {
                case CLOSED_TO_OPEN -> eventPublisher.publishEvent(
                        new ParserUnavailableEvent(this, bm, (int) cb.getMetrics().getNumberOfFailedCalls()));
                case HALF_OPEN_TO_CLOSED -> eventPublisher.publishEvent(new ParserRecoveredEvent(this, bm));
                default -> { /* остальные переходы не требуют уведомления чатов */ }
            }
        });
    }

    private static BookmakerType toBookmaker(String cbName) {
        try {
            return BookmakerType.valueOf(cbName.replace("-cb", "").toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── slow trigger: independent health-poll backstop ─────────────────────────

    @Async
    @EventListener
    public void onParserUnavailable(ParserUnavailableEvent event) {
        notifyUnavailable(event.getBookmaker());
    }

    @Async
    @EventListener
    public void onParserRecovered(ParserRecoveredEvent event) {
        notifyRecovered(event.getBookmaker());
    }

    // ── shared notify logic ─────────────────────────────────────────────────────

    private void notifyUnavailable(BookmakerType bm) {
        availabilityRegistry.markUnavailable(bm);
        if (!usersAlertsEnabled) return;

        // Atomic claim: only the first trigger/caller for this incident proceeds to notify chats.
        // Repeat alerts from the slow poller (consecutive=12,24) update the registry but skip
        // chat notifications, same as any call arriving after the incident is already claimed.
        Set<Long> slot = Collections.newSetFromMap(new ConcurrentHashMap<>());
        if (notifiedChats.putIfAbsent(bm, slot) != null) return;

        // Spring Data repository methods are @Transactional by default — no wrapper needed here
        List<Long> chatIds = subscriptionRepo.findActiveChatIdsByBookmaker(bm);
        log.info("[BK-INCIDENT] {} unavailable — notifying {} chats", bm, chatIds.size());

        String text = "⚠️ *" + bm.name() + "* временно недоступна. Мониторинг может задерживаться.";
        for (Long chatId : chatIds) {
            try {
                bot.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .parseMode("Markdown")
                        .build());
                slot.add(chatId);
            } catch (TelegramApiException e) {
                log.warn("[BK-INCIDENT] Failed to notify chatId={} bm={}: {}", chatId, bm, e.getMessage());
            }
        }
    }

    private void notifyRecovered(BookmakerType bm) {
        availabilityRegistry.markAvailable(bm);
        if (!usersAlertsEnabled) return;

        Set<Long> chats = notifiedChats.remove(bm);
        if (chats == null || chats.isEmpty()) return;

        log.info("[BK-INCIDENT] {} recovered — notifying {} chats", bm, chats.size());
        String text = "✅ *" + bm.name() + "* снова доступна. Мониторинг возобновлён.";
        for (Long chatId : chats) {
            try {
                bot.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .parseMode("Markdown")
                        .build());
            } catch (TelegramApiException e) {
                log.warn("[BK-INCIDENT] Failed to send recovery to chatId={} bm={}: {}", chatId, bm, e.getMessage());
            }
        }
    }
}
