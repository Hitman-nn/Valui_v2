package com.valui.notify.sender;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.ratelimit.TelegramRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

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

    /** Legacy entry point — plain text, no keyboard. Used by DLQ consumers and email/webhook dispatch. */
    @Override
    public void send(Long chatId, String messageText) throws Exception {
        if (chatId == null) {
            throw new IllegalArgumentException("chatId is null — user has no linked Telegram account");
        }
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(messageText)
                .parseMode("Markdown")
                .disableWebPagePreview(true)
                .build();
        doSend(chatId, message);
    }

    /** Full notification dispatch — attaches an inline keyboard button when the message carries one. */
    public void sendNotification(UserNotificationRequestMessage request) throws Exception {
        Long chatId = request.telegramId();
        if (chatId == null) {
            throw new IllegalArgumentException("chatId is null — user has no linked Telegram account");
        }

        InlineKeyboardMarkup keyboard = buildKeyboard(request);

        SendMessage.SendMessageBuilder builder = SendMessage.builder()
                .chatId(chatId)
                .text(request.messageText())
                .parseMode("Markdown")
                .disableWebPagePreview(true);

        if (keyboard != null) builder.replyMarkup(keyboard);

        doSend(chatId, builder.build());
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void doSend(Long chatId, SendMessage message) throws Exception {
        if (!rateLimiter.tryAcquire(chatId)) {
            log.debug("Rate limit hit for chatId={}, backing off", chatId);
            Thread.sleep(1_200);
            if (!rateLimiter.tryAcquire(chatId)) {
                throw new RuntimeException("Telegram rate limit exceeded for chatId=" + chatId);
            }
        }

        try {
            absSender.execute(message);
            log.debug("Telegram notification sent to chatId={}", chatId);
        } catch (TelegramApiRequestException ex) {
            if (ex.getErrorCode() != null && ex.getErrorCode() == 429) {
                log.warn("Telegram 429 for chatId={}, retrying after {} ms (raw: {})",
                        chatId, MAX_RATE_WAIT_MS, ex.getApiResponse());
                Thread.sleep(MAX_RATE_WAIT_MS);
                absSender.execute(message);
            } else {
                throw ex;
            }
        }
    }

    private static InlineKeyboardMarkup buildKeyboard(UserNotificationRequestMessage request) {
        boolean hasQuickAdd = request.quickAddKey() != null && !request.quickAddKey().isBlank();
        boolean hasBetKey   = request.betKey()      != null && !request.betKey().isBlank();
        boolean hasUrl      = request.eventUrl()    != null && !request.eventUrl().isBlank();

        if (!hasQuickAdd && !hasBetKey && !hasUrl) return null;

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();

        if (hasQuickAdd) {
            kb.button("➕ Следить за турниром", CallbackData.qadd(request.quickAddKey())).row();
        }
        if (hasUrl) {
            kb.urlButton("🔗 Открыть матч", request.eventUrl()).row();
        }
        if (hasBetKey) {
            kb.button("💸 Поставил", CallbackData.betNotif(request.betKey())).row();
        }

        return kb.build();
    }
}
