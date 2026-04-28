package com.valui.notify.sender;

import com.valui.notify.ratelimit.TelegramRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

/**
 * Sends Telegram notifications with Redis-backed rate limiting (1 msg/s per chat).
 *
 * If the local rate limiter allows the send but Telegram responds with 429,
 * we wait for the retry-after hint and attempt once more.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramNotificationSender implements NotificationSender {

    private static final int MAX_RATE_WAIT_MS = 2_000;

    private final AbsSender absSender;
    private final TelegramRateLimiter rateLimiter;

    @Override
    public void send(Long chatId, String messageText) throws Exception {
        if (chatId == null) {
            throw new IllegalArgumentException("chatId is null — user has no linked Telegram account");
        }

        // Local rate-limit check — wait up to 1.2 s for the window to reset
        if (!rateLimiter.tryAcquire(chatId)) {
            log.debug("Rate limit hit for chatId={}, backing off", chatId);
            Thread.sleep(1_200);
            if (!rateLimiter.tryAcquire(chatId)) {
                throw new RuntimeException("Telegram rate limit exceeded for chatId=" + chatId);
            }
        }

        try {
            absSender.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(messageText)
                    .parseMode("Markdown")
                    .disableWebPagePreview(true)
                    .build());
            log.debug("Telegram notification sent to chatId={}", chatId);
        } catch (TelegramApiRequestException ex) {
            if (ex.getErrorCode() != null && ex.getErrorCode() == 429) {
                // telegrambots 6.x: getApiResponse() returns raw String, not a typed object.
                // Use a fixed 1 s back-off; in practice Telegram retry-after is 1–5 s.
                log.warn("Telegram 429 for chatId={}, retrying after {} ms (raw: {})",
                        chatId, MAX_RATE_WAIT_MS, ex.getApiResponse());
                Thread.sleep(MAX_RATE_WAIT_MS);
                absSender.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(messageText)
                        .parseMode("Markdown")
                        .disableWebPagePreview(true)
                        .build());
            } else {
                throw ex;
            }
        }
    }
}
