package com.valui.common.kafka;

/**
 * Kafka message published to {@code user.notifications.pending}.
 * Key = userId guarantees ordering for a single user's notifications.
 * Mirrors UserNotificationRequest.avsc.
 *
 * Inline-button fields (both nullable):
 *   quickAddKey — Redis cache key for "➕ Следить за турниром" button (SPORT controller events).
 *                 Populated by SportEventConsumer; consumed by QuickAddControllerCallback.
 *   eventUrl    — Source URL for "🔗 Открыть матч" URL-button (TOURNAMENT/MATCH events).
 */
public record UserNotificationRequestMessage(
        String notificationLogId,
        String userId,
        Long telegramId,
        String channel,
        String messageText,
        String eventId,
        String quickAddKey,
        String eventUrl
) {}
