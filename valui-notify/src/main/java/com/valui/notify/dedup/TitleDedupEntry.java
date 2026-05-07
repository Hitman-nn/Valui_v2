package com.valui.notify.dedup;

/**
 * Cached entry for title-based notification deduplication.
 *
 * Stored in Redis under key {@code notif:title-dedup:{chatId}:{dedupHash}}
 * with a configurable TTL (default 60 min).
 *
 * When a new event with the same dedupHash arrives within the TTL:
 *   - betKey / quickAddKey Redis entries are updated with the new URL
 *   - The existing Telegram message (telegramMessageId) is edited rather than
 *     a new notification being sent
 */
public record TitleDedupEntry(
        Integer telegramMessageId,
        Long    chatId,
        String  betKey,       // null for SPORT controller events
        String  quickAddKey   // null for TOURNAMENT/MATCH events
) {}
