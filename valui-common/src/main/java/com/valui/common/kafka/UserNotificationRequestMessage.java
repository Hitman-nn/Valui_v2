package com.valui.common.kafka;

/**
 * Kafka message published to {@code user.notifications.pending}.
 * Key = userId guarantees ordering for a single user's notifications.
 * Mirrors UserNotificationRequest.avsc.
 */
public record UserNotificationRequestMessage(
        String notificationLogId, // UUID of the notification_log row — used by dispatcher to update status
        String userId,
        Long telegramId,
        String channel,
        String messageText,
        String eventId
) {}
