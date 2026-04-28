package com.valui.notify.consumer;

import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.domain.UserStatus;
import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.SportEventDetectedMessage;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.common.domain.NotificationChannel;
import com.valui.notify.formatter.NotificationFormatter;
import com.valui.common.entity.NotificationLogEntity;
import com.valui.notify.log.NotificationLogService;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.DetectedEventRepository;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SportEventConsumer {

    private final ControllerRepository    controllerRepo;
    private final UserRepository          userRepo;
    private final DetectedEventRepository detectedEventRepo;
    private final NotificationLogService  notificationLogService;
    private final NotificationFormatter   formatter;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(
            topics   = KafkaTopics.SPORT_EVENTS_DETECTED,
            groupId  = "valui-notify-group"
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

        // Look up the internal detected-event UUID for the notification log FK
        UUID detectedEventId = detectedEventRepo
                .findByControllerIdAndEventExternalId(controllerId, event.externalEventId())
                .map(e -> e.getId())
                .orElse(null);

        NotificationLogEntity logEntry = notificationLogService.createPending(
                userId, detectedEventId, NotificationChannel.TELEGRAM);

        String messageText = formatter.buildTelegramMessage(event, controller);

        UserNotificationRequestMessage request = new UserNotificationRequestMessage(
                logEntry.getId().toString(),
                event.userId(),
                event.telegramId(),
                NotificationChannel.TELEGRAM.name(),
                messageText,
                event.eventId()
        );

        kafkaTemplate.send(KafkaTopics.USER_NOTIFICATIONS_PENDING, event.userId(), request)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish notification request [logId={}]: {}",
                                logEntry.getId(), ex.getMessage());
                    } else {
                        log.debug("Queued notification [logId={} chatId={} topic={}]",
                                logEntry.getId(), event.telegramId(), KafkaTopics.USER_NOTIFICATIONS_PENDING);
                    }
                });
    }

    private static boolean passesFilterRule(String filterRule, String title) {
        if (filterRule == null || filterRule.isBlank()) return true;
        if (title == null) return false;
        try {
            return Pattern.compile(filterRule, Pattern.CASE_INSENSITIVE).matcher(title).find();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid filterRule regex '{}': {}", filterRule, e.getMessage());
            return true; // broken filter → don't silently block events
        }
    }
}
