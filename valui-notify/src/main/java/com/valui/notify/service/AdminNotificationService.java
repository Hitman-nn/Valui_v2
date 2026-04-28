package com.valui.notify.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Sends operational alerts to the admin Telegram chat.
 * Configure {@code telegram.admin-chat-id} in application.yml (or via env var).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminNotificationService {

    private final AbsSender absSender;

    @Value("${telegram.admin-chat-id:0}")
    private long adminChatId;

    public void alertAdmin(String message) {
        if (adminChatId <= 0) {
            log.warn("[ADMIN-ALERT] admin-chat-id not configured, alert suppressed: {}", message);
            return;
        }
        try {
            absSender.execute(SendMessage.builder()
                    .chatId(adminChatId)
                    .text(message)
                    .parseMode("Markdown")
                    .build());
            log.info("[ADMIN-ALERT] Sent to chatId={}: {}", adminChatId, message);
        } catch (TelegramApiException e) {
            log.error("[ADMIN-ALERT] Failed to deliver alert to chatId={}: {}", adminChatId, e.getMessage());
        }
    }
}
