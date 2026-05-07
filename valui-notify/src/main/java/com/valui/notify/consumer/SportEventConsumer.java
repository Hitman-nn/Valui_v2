package com.valui.notify.consumer;

import com.valui.common.domain.ControllerType;
import com.valui.common.domain.NotificationChannel;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.NotificationLogEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.SportEventDetectedMessage;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.betting.cache.BetNotifCacheService;
import com.valui.betting.cache.BetNotifData;
import com.valui.common.entity.GlobalFilterEntity;
import com.valui.notify.dedup.TitleDedupCacheService;
import com.valui.notify.dedup.TitleDedupEntry;
import com.valui.notify.formatter.NotificationFormatter;
import com.valui.notify.log.NotificationLogService;
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.api.UserPortService;
import com.valui.user.quickadd.QuickAddCacheService;
import com.valui.user.quickadd.QuickAddData;
import com.valui.user.service.GlobalFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Consumes {@code sport.events.detected} and, for each event that passes all
 * eligibility checks, creates a notification log row (PENDING) and forwards
 * the formatted message to {@code user.notifications.pending}.
 *
 * Checks applied in order:
 *   1. Controller exists and is active
 *   2. User is ACTIVE and not banned
 *   3. Controller is not muted
 *   4. filterRule regex matches event title (if rule is set)
 *
 * Inline-button enrichment:
 *   SPORT controller   → quick-add data stored in Redis + quickAddKey in message ("➕ Следить за турниром")
 *   TOURNAMENT/MATCH   → bet data stored in Redis + betKey in message ("💸 Поставил")
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SportEventConsumer {

    private final ControllerPortService        controllerPort;
    private final UserPortService              userPort;
    private final DetectedEventPortService     detectedEventPort;
    private final GlobalFilterService          globalFilterService;
    private final NotificationLogService       notificationLogService;
    private final NotificationFormatter        formatter;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final QuickAddCacheService         quickAddCacheService;
    private final BetNotifCacheService         betNotifCacheService;
    private final TitleDedupCacheService       titleDedupCache;

    @KafkaListener(
            topics  = KafkaTopics.SPORT_EVENTS_DETECTED,
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onSportEventDetected(SportEventDetectedMessage event) {
        process(event);
    }

    private void process(SportEventDetectedMessage event) {
        UUID controllerId = UUID.fromString(event.controllerId());

        ControllerEntity controller = controllerPort.findById(controllerId).orElse(null);
        if (controller == null) {
            log.debug("Controller {} not found, dropping event {}", controllerId, event.externalEventId());
            return;
        }
        if (!Boolean.TRUE.equals(controller.getIsActive())) {
            log.debug("Controller {} inactive, dropping", controllerId);
            return;
        }
        if (Boolean.TRUE.equals(controller.getIsMuted())) {
            log.debug("Controller {} muted, dropping", controllerId);
            return;
        }

        UUID userId = UUID.fromString(event.userId());
        UserEntity user = userPort.findById(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            log.debug("User {} absent or inactive, dropping", userId);
            return;
        }

        if (!passesFilterRule(controller.getFilterRule(), event.title())) {
            log.debug("Event '{}' blocked by filterRule '{}' on controller {}",
                    event.title(), controller.getFilterRule(), controllerId);
            return;
        }

        // Check global exclusion filters (cached, TTL 30 s)
        for (GlobalFilterEntity gf : globalFilterService.findByUserId(userId)) {
            if (!passesFilterRule(gf.getFilterRule(), event.title())) {
                log.debug("Event '{}' blocked by global filter for user {}", event.title(), userId);
                return;
            }
        }

        // Use chatId (subscription target) for delivery; fall back to telegramId for legacy rows
        Long targetChatId = event.chatId() != null ? event.chatId() : event.telegramId();

        // ── Title-based deduplication ────────────────────────────────────────
        // Same match may appear under a different event ID within the TTL window
        // (e.g., BetBoom pre-match → live transition). Instead of sending a second
        // notification, edit the already-sent message with the updated URL/text.
        String dedupKey = titleDedupCache.computeKey(
                targetChatId, event.bookmaker(), event.url(), event.title());

        TitleDedupEntry existing = titleDedupCache.find(dedupKey).orElse(null);
        if (existing != null) {
            log.debug("[DEDUP] Hit for chatId={} — editing message {}", targetChatId, existing.telegramMessageId());
            sendEditRequest(event, controller, targetChatId, existing);
            return;
        }

        // ── Normal first-send path ────────────────────────────────────────────

        UUID detectedEventId = detectedEventPort
                .findIdByControllerIdAndExternalId(controllerId, event.externalEventId())
                .orElse(null);

        NotificationLogEntity logEntry;
        try {
            logEntry = notificationLogService.createPending(
                    userId, detectedEventId, NotificationChannel.TELEGRAM, targetChatId);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // Unique constraint (event_id, chat_id, channel) violated — Kafka message was
            // replayed (consumer rebalance / restart). Notification already queued, skip.
            log.debug("Duplicate notification suppressed [controllerId={} externalEventId={} chatId={}]",
                    controllerId, event.externalEventId(), targetChatId);
            return;
        }

        String messageText = formatter.buildTelegramMessage(event, controller);

        // Determine inline button metadata based on controller type
        String quickAddKey = null;
        String eventUrl    = null;
        String betKey      = null;

        if (ControllerType.SPORT == controller.getType() && hasUrl(event.url())) {
            // SPORT controller → "➕ Следить за турниром" button only; no bet on a tournament
            quickAddKey = logEntry.getId().toString();
            quickAddCacheService.store(quickAddKey,
                    new QuickAddData(event.url(), event.bookmaker(), event.title()));
            log.debug("[QUICK-ADD] Cached tournament data for notifLogId={}", quickAddKey);
        } else {
            // TOURNAMENT / MATCH → "💸 Поставил" button; no URL button
            betKey = logEntry.getId().toString();
            betNotifCacheService.store(betKey,
                    new BetNotifData(event.title(), event.url(), event.bookmaker()));
        }

        UserNotificationRequestMessage request = new UserNotificationRequestMessage(
                logEntry.getId().toString(),
                event.userId(),
                targetChatId,
                NotificationChannel.TELEGRAM.name(),
                messageText,
                event.eventId(),
                quickAddKey,
                eventUrl,
                betKey,
                dedupKey,   // NotificationDispatcher will store this in the dedup cache after send
                null        // editMessageId = null → normal send
        );

        final boolean hasQuickAdd = quickAddKey != null;
        kafkaTemplate.send(KafkaTopics.USER_NOTIFICATIONS_PENDING, event.userId(), request)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish notification request [logId={}]: {}",
                                logEntry.getId(), ex.getMessage());
                    } else {
                        log.debug("Queued notification [logId={} chatId={} quickAdd={}]",
                                logEntry.getId(), targetChatId, hasQuickAdd);
                    }
                });
    }

    /**
     * Updates the cached bet/quickAdd Redis entries with the new event URL and
     * sends an edit-request to the Kafka pipeline so NotificationDispatcher rewrites
     * the already-sent Telegram message.
     */
    private void sendEditRequest(SportEventDetectedMessage event, ControllerEntity controller,
                                 Long targetChatId, TitleDedupEntry existing) {
        // Update the Redis cache entries so keyboard callbacks return the new URL.
        // Edge case: if the Kafka send below fails after these stores, the callback
        // URLs in Redis are already updated but the Telegram message still shows old text.
        // Acceptable: the next dedup-window event will re-attempt the edit.
        if (existing.betKey() != null) {
            betNotifCacheService.store(existing.betKey(),
                    new BetNotifData(event.title(), event.url(), event.bookmaker()));
        }
        if (existing.quickAddKey() != null && hasUrl(event.url())) {
            quickAddCacheService.store(existing.quickAddKey(),
                    new QuickAddData(event.url(), event.bookmaker(), event.title()));
        }

        String newText = formatter.buildTelegramMessage(event, controller);

        UserNotificationRequestMessage editRequest = new UserNotificationRequestMessage(
                null,                                   // no log entry for edits
                event.userId(),
                targetChatId,
                NotificationChannel.TELEGRAM.name(),
                newText,
                event.eventId(),
                existing.quickAddKey(),
                null,
                existing.betKey(),
                null,                                   // dedupKey not needed for edits
                existing.telegramMessageId()            // tells dispatcher to edit, not send
        );

        kafkaTemplate.send(KafkaTopics.USER_NOTIFICATIONS_PENDING, event.userId(), editRequest)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("[DEDUP] Failed to queue edit for chatId={} messageId={}: {}",
                                targetChatId, existing.telegramMessageId(), ex.getMessage());
                    } else {
                        log.debug("[DEDUP] Edit queued for chatId={} messageId={}",
                                targetChatId, existing.telegramMessageId());
                    }
                });
    }

    private static boolean passesFilterRule(String filterRule, String title) {
        if (filterRule == null || filterRule.isBlank()) return true;
        if (title == null) return false;
        try {
            return Pattern.compile(filterRule, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(title).find();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid filterRule regex '{}': {}", filterRule, e.getMessage());
            return true;
        }
    }

    private static boolean hasUrl(String url) {
        return url != null && !url.isBlank();
    }
}
