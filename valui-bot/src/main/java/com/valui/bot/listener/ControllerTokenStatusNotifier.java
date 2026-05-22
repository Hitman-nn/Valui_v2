package com.valui.bot.listener;

import com.valui.bot.i18n.BotMessageSource;
import com.valui.user.event.UserControllersPausedEvent;
import com.valui.user.event.UserControllersResumedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ControllerTokenStatusNotifier {

    private final AbsSender bot;
    private final BotMessageSource messageSource;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onControllersPaused(UserControllersPausedEvent event) {
        String text = messageSource.getMessage("token.controllers_paused", event.telegramId());
        sendToChats(event.chatIds(), text, event.telegramId(), "paused");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onControllersResumed(UserControllersResumedEvent event) {
        String text = messageSource.getMessage("token.controllers_resumed", event.telegramId());
        sendToChats(event.chatIds(), text, event.telegramId(), "resumed");
    }

    private void sendToChats(List<Long> chatIds, String text, long telegramId, String action) {
        for (Long chatId : chatIds) {
            try {
                bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("MarkdownV2")
                    .build());
                log.info("[TOKEN] {} notification sent to chatId={} telegramId={}", action, chatId, telegramId);
            } catch (TelegramApiException e) {
                log.warn("[TOKEN] Failed to send {} notification to chatId={}: {}", action, chatId, e.getMessage());
            }
        }
    }
}
