package com.valui.common.kafka;

/**
 * Kafka message published to {@code user.notifications.pending}.
 * Key = userId guarantees ordering for a single user's notifications.
 * Mirrors UserNotificationRequest.avsc.
 *
 * Inline-button fields (all nullable):
 *   quickAddKey — Redis cache key for "➕ Следить за турниром" button (SPORT controller events).
 *                 Populated by SportEventConsumer; consumed by QuickAddControllerCallback.
 *   eventUrl    — Source URL for "🔗 Открыть матч" URL-button (TOURNAMENT/MATCH events).
 *   betKey      — notificationLogId used as Redis key for "💸 Поставил" button (TOURNAMENT/MATCH only).
 *                 Populated by SportEventConsumer; consumed by BetNotifCallback.
 */
public record UserNotificationRequestMessage(
        String notificationLogId,
        String userId,
        Long telegramId,
        String channel,
        String messageText,
        String eventId,
        String quickAddKey,
        String eventUrl,
        String betKey
) {}
