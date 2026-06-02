package com.valui.notify.sender;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.ratelimit.TelegramRateLimiter;
import com.valui.notify.stats.NotificationStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends Telegram notifications with Redis-backed rate limiting (1 msg/s per chat).
 *
 * Two entry points:
 *   {@link #send}             — plain text, used by DLQ retry path (legacy callers)
 *   {@link #sendNotification} — full message with optional inline keyboard button:
 *       SPORT controller event   → "➕ Следить за турниром" callback button
 *       TOURNAMENT/MATCH event   → "💸 Поставил" callback button
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramNotificationSender implements NotificationSender {

    private static final int MAX_RATE_WAIT_MS = 2_000;

    private final AbsSender absSender;
    private final TelegramRateLimiter rateLimiter;
    private final NotificationStats stats;

    /** Legacy entry point — plain text, no keyboard. Used by DLQ consumers and email/webhook dispatch. */
    @Override
    public void send(Long chatId, String messageText) throws Exception {
        if (chatId == null) {
            throw new IllegalArgumentException("chatId is null — user has no linked Telegram account");
        }
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(messageText)
                .parseMode("MarkdownV2")
                .disableWebPagePreview(true)
                .build();
        doSend(chatId, message);
    }

    /**
     * Full notification dispatch — attaches an inline keyboard button when the message carries one.
     *
     * @return Telegram message_id assigned by the API (used for future edits via dedup)
     */
    public Integer sendNotification(UserNotificationRequestMessage request) throws Exception {
        Long chatId = request.telegramId();
        if (chatId == null) {
            throw new IllegalArgumentException("chatId is null — user has no linked Telegram account");
        }

        InlineKeyboardMarkup keyboard = buildKeyboard(request);

        SendMessage.SendMessageBuilder builder = SendMessage.builder()
                .chatId(chatId)
                .text(request.messageText())
                .parseMode("MarkdownV2")
                .disableWebPagePreview(true);

        if (keyboard != null) builder.replyMarkup(keyboard);

        return doSend(chatId, builder.build());
    }

    /**
     * Edits an already-sent notification message in-place.
     * Best-effort: logs and returns null on failure without throwing.
     *
     * @param chatId    target chat
     * @param messageId Telegram message_id to edit
     * @param request   new content (text + keyboard rebuilt from the same Redis keys)
     */
    public void editNotification(long chatId, int messageId, UserNotificationRequestMessage request) {
        try {
            InlineKeyboardMarkup keyboard = buildKeyboard(request);

            EditMessageText.EditMessageTextBuilder builder = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(request.messageText())
                    .parseMode("MarkdownV2")
                    .disableWebPagePreview(true);

            if (keyboard != null) builder.replyMarkup(keyboard);

            absSender.execute(builder.build());
            log.debug("[DEDUP] Edited Telegram message chatId={} messageId={}", chatId, messageId);
        } catch (TelegramApiRequestException ex) {
            // Message too old, bot blocked, or message content unchanged — all non-fatal
            log.warn("[DEDUP] Edit failed chatId={} messageId={}: {} (code={})",
                    chatId, messageId, ex.getMessage(), ex.getErrorCode());
        } catch (Exception ex) {
            log.warn("[DEDUP] Edit failed chatId={} messageId={}: {}", chatId, messageId, ex.getMessage());
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private Integer doSend(Long chatId, SendMessage message) throws Exception {
        long waitMs = rateLimiter.tryAcquire(chatId);
        if (waitMs > 0) {
            // Rate window resets in waitMs — sleep locally then retry once.
            // Safe on Java 21 virtual threads: no platform thread is blocked.
            // Jitter distributes concurrent waiters to avoid thundering herd on window reset.
            long sleepMs = waitMs + ThreadLocalRandom.current().nextLong(10, 51);
            Thread.sleep(Math.min(sleepMs, MAX_RATE_WAIT_MS));
            waitMs = rateLimiter.tryAcquire(chatId);
            if (waitMs > 0) {
                // Still blocked after sleep (concurrent send consumed the window) — fall back to Kafka retry
                stats.incRateLimitBackoff();
                log.warn("Rate limit hit chatId={} — throwing for Kafka retry", chatId);
                throw new RuntimeException("Telegram rate limit exceeded for chatId=" + chatId);
            }
        }

        try {
            Message sent = absSender.execute(message);
            log.debug("Telegram notification sent to chatId={}", chatId);
            return sent != null ? sent.getMessageId() : null;
        } catch (TelegramApiRequestException ex) {
            if (ex.getErrorCode() != null && ex.getErrorCode() == 429) {
                log.warn("Telegram 429 for chatId={} — throwing for Kafka retry (raw: {})",
                        chatId, ex.getApiResponse());
                throw ex;
            }
            throw ex;
        }
    }

    private static InlineKeyboardMarkup buildKeyboard(UserNotificationRequestMessage request) {
        boolean hasQuickAdd = request.quickAddKey() != null && !request.quickAddKey().isBlank();
        boolean hasBetKey   = request.betKey()      != null && !request.betKey().isBlank();
        boolean hasUrl      = request.eventUrl()    != null && !request.eventUrl().isBlank();
        // Watch buttons: shown only when the market is known to be absent (false, not null)
        boolean showWatchHcap  = hasBetKey && Boolean.FALSE.equals(request.hasHcap());
        boolean showWatchTotal = hasBetKey && Boolean.FALSE.equals(request.hasTotal());

        if (!hasQuickAdd && !hasBetKey && !hasUrl) return null;

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();

        if (hasQuickAdd) {
            kb.button("➕ Следить за турниром", CallbackData.qadd(request.quickAddKey())).row();
        }
        if (hasUrl) {
            kb.urlButton("🔗 Открыть матч", request.eventUrl()).row();
        }
        // Watch buttons share one row, appear above "Поставил"
        if (showWatchHcap || showWatchTotal) {
            if (showWatchHcap)  kb.button("👁 Фора",  CallbackData.watchHcap(request.betKey()));
            if (showWatchTotal) kb.button("👁 Тотал", CallbackData.watchTotal(request.betKey()));
            kb.row();
        }
        if (hasBetKey) {
            kb.button("💸 Поставил", CallbackData.betNotif(request.betKey())).row();
        }

        return kb.build();
    }
}
