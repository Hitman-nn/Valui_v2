package com.valui.bot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.time.Duration;

/**
 * Tracks the reply-keyboard anchor message per chat.
 *
 * /start, /menu, and /help send a message with ReplyKeyboardMarkup. That message
 * is stored here (NOT in WizardMessageTracker) so that wizard cleanup (deleteStale)
 * never deletes it — which would cause Telegram clients to hide the reply keyboard.
 *
 * When a new anchor is sent (e.g. /menu called again), the old one is deleted first.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuAnchorService {

    private static final String KEY_PREFIX = "menu:anchor:";
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;

    /**
     * Deletes the previous anchor message (if any) and stores the new one.
     * Call this after sending any message with MainMenuKeyboard.
     */
    public void replaceAnchor(long chatId, AbsSender sender, int newMsgId) {
        String key = KEY_PREFIX + chatId;
        // Atomic: set new value and get old one in a single Redis round-trip.
        String prev = redis.opsForValue().getAndSet(key, String.valueOf(newMsgId));
        redis.expire(key, TTL);

        if (prev != null) {
            try {
                sender.execute(DeleteMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(Integer.parseInt(prev))
                    .build());
            } catch (Exception e) {
                log.debug("Could not delete old menu anchor chatId={}: {}", chatId, e.getMessage());
            }
        }
    }
}
