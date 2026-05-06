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
import com.valui.notify.formatter.NotificationFormatter;
import com.valui.notify.log.NotificationLogService;
import com.valui.user.quickadd.QuickAddCacheService;
import com.valui.user.quickadd.QuickAddData;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.DetectedEventRepository;
import com.valui.user.repository.GlobalFilterRepository;
import com.valui.user.repository.UserRepository;
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

    private final ControllerRepository    controllerRepo;
    private final UserRepository          userRepo;
    private final DetectedEventRepository detectedEventRepo;
    private final GlobalFilterRepository  globalFilterRepo;
    private final NotificationLogService  notificationLogService;
    private final NotificationFormatter   formatter;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final QuickAddCacheService    quickAddCacheService;
    private final BetNotifCacheService    betNotifCacheService;

    @KafkaListener(
            topics  = KafkaTopics.SPORT_EVENTS_DETECTED,
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onSportEventDetected(SportEventDetectedMessage event) {
        try {
            process(event);
        } catch (Exception e) {
            log.error("Failed to process sport event [controllerId={} externalEventId={}]: {}",
                    event.controllerId(), event.externalEventId(), e.getMessage(), e);
        }
    }

    private void process(SportEventDetectedMessage event) {
        UUID controllerId = UUID.fromString(event.controllerId());

        ControllerEntity controller = controllerRepo.findById(controllerId).orElse(null);
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
        UserEntity user = userRepo.findById(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            log.debug("User {} absent or inactive, dropping", userId);
            return;
        }

        if (!passesFilterRule(controller.getFilterRule(), event.title())) {
            log.debug("Event '{}' blocked by filterRule '{}' on controller {}",
                    event.title(), controller.getFilterRule(), controllerId);
            return;
        }

        // Check global exclusion filters (blacklist: event blocked if any forbidden word is found)
        for (GlobalFilterEntity gf : globalFilterRepo.findAllByUserIdOrderByCreatedAtAsc(userId)) {
            if (!passesFilterRule(gf.getFilterRule(), event.title())) {
                log.debug("Event '{}' blocked by global filter for user {}", event.title(), userId);
                return;
            }
        }

        UUID detectedEventId = detectedEventRepo
                .findByControllerIdAndEventExternalId(controllerId, event.externalEventId())
                .map(e -> e.getId())
                .orElse(null);

        // Use chatId (subscription target) for delivery; fall back to telegramId for legacy rows
        Long targetChatId = event.chatId() != null ? event.chatId() : event.telegramId();

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
                betKey
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
