package com.valui.bot.listener;

import com.valui.bot.i18n.BotMessageSource;
import com.valui.user.api.ControllerPortService;
import com.valui.user.event.TokenThresholdEvent;
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
public class TokenThresholdBotListener {

    private final AbsSender bot;
    private final BotMessageSource messageSource;
    private final ControllerPortService controllerPort;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTokenThreshold(TokenThresholdEvent event) {
        log.info("[TOKEN] Low balance alert: telegramId={} balance={} threshold={}",
            event.getTelegramId(), event.getBalance(), event.getThreshold());

        String msgKey;
        if (event.getBalance() <= 0) {
            msgKey = "token.no_balance";
        } else if (event.getThreshold() <= 10) {
            msgKey = "token.critical_balance";
        } else if (event.getThreshold() <= 50) {
            msgKey = "token.low_balance";
        } else {
            msgKey = "token.info_balance";
        }
        String text = messageSource.getMessage(msgKey, event.getTelegramId(), event.getBalance());

        try {
            bot.execute(SendMessage.builder()
                .chatId(event.getTelegramId())
                .text(text)
                .parseMode("Markdown")
                .build());
            log.info("[TOKEN] Alert sent: telegramId={} level={}", event.getTelegramId(), msgKey);
            return;
        } catch (TelegramApiException e) {
            log.debug("[TOKEN] Personal chat unavailable for telegramId={} — trying group chats", event.getTelegramId());
        }

        List<Long> groupChats = controllerPort.findActiveGroupChatIds(event.getTelegramId());
        if (groupChats.isEmpty()) {
            log.warn("[TOKEN] No reachable chat for telegramId={}, notification lost", event.getTelegramId());
            return;
        }
        int sent = 0;
        for (Long chatId : groupChats) {
            try {
                bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("Markdown")
                    .build());
                sent++;
            } catch (TelegramApiException e) {
                log.warn("[TOKEN] Failed to send to groupChat={}: {}", chatId, e.getMessage());
            }
        }
        if (sent > 0) {
            log.info("[TOKEN] Alert sent to {} group chat(s): telegramId={} level={}", sent, event.getTelegramId(), msgKey);
        }
    }
}
