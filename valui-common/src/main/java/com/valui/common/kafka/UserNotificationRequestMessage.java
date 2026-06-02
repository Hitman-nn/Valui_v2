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
 *   hasHcap     — true if the event already has a handicap market; false if missing → show "👁 Фора" button.
 *                 null means N/A (e.g. SPORT controller, edit path).
 *   hasTotal    — true if the event already has a total market; false if missing → show "👁 Тотал" button.
 *                 null means N/A (e.g. SPORT controller, edit path).
 *   Both watch buttons share the same Redis cache key as betKey (notifLogId → WatchCacheData).
 *
 * Dedup/edit fields (both nullable):
 *   dedupKey      — SHA-256 hash key used for title-based dedup cache. Set on first sends so
 *                   NotificationDispatcher can store the entry after successful Telegram delivery.
 *   editMessageId — If non-null, edit this existing Telegram message instead of sending a new one.
 *                   Used when the same match appears under a different event ID within the dedup TTL.
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
        String betKey,
        String dedupKey,
        Integer editMessageId,
        String bookmaker,
        Integer dedupTtlMinutes, // null = use server default (180 min)
        Long vkPeerId,           // null = no VK delivery
        Boolean hasHcap,         // null = N/A (SPORT / edit); false = no handicap → show watch button
        Boolean hasTotal         // null = N/A (SPORT / edit); false = no total   → show watch button
) {
    /** Backward-compatible constructor for callers that don't use dedup (e.g. DLQ retry). */
    public UserNotificationRequestMessage(
            String notificationLogId, String userId, Long telegramId,
            String channel, String messageText, String eventId,
            String quickAddKey, String eventUrl, String betKey) {
        this(notificationLogId, userId, telegramId, channel, messageText,
             eventId, quickAddKey, eventUrl, betKey, null, null, null, null, null, null, null);
    }
}
