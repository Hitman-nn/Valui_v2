package com.valui.bot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.time.Duration;

/**
 * Tracks the active wizard message per user.
 * Key: wizard:activemsg:{chatId}  TTL: 47h (just under Telegram's 48h edit window).
 *
 * When a new wizard starts, the previous wizard message (if any) is deleted so it
 * doesn't linger in the chat with dead buttons.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WizardMessageTracker {

    private static final String KEY_PREFIX = "wizard:activemsg:";
    private static final Duration TTL = Duration.ofHours(47);

    private final StringRedisTemplate redis;

    /** Stores a new wizard message ID for the user (overwrites any previous). */
    public void track(long chatId, int messageId) {
        redis.opsForValue().set(KEY_PREFIX + chatId, String.valueOf(messageId), TTL);
    }

    /** Deletes the previously tracked wizard message (if any) and removes the Redis key. */
    public void deleteStale(long chatId, AbsSender sender) {
        String key = KEY_PREFIX + chatId;
        String stored = redis.opsForValue().get(key);
        if (stored == null) return;

        redis.delete(key);
        try {
            sender.execute(DeleteMessage.builder()
                .chatId(String.valueOf(chatId))
                .messageId(Integer.parseInt(stored))
                .build());
            log.debug("Deleted stale wizard message chatId={} msgId={}", chatId, stored);
        } catch (Exception e) {
            // Message may already be gone or too old — not a problem.
            log.debug("Could not delete stale wizard message chatId={} msgId={}: {}", chatId, stored, e.getMessage());
        }
    }
}
