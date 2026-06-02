package com.valui.bot.listener;

import com.valui.bot.i18n.BotMessageSource;
import com.valui.user.api.ControllerPortService;
import com.valui.user.event.TokenThresholdEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

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

        String msgKey = event.getBalance() <= 0 ? "token.no_balance" : "token.threshold_alert";
        String text = messageSource.getMessage(msgKey, event.getTelegramId(),
            event.getBalance(), event.getThreshold());

        // Try personal chat first
        if (trySend(event.getTelegramId(), text)) {
            log.info("[TOKEN] Alert sent to personal chat: telegramId={}", event.getTelegramId());
            return;
        }

        // Fallback: try each active group chat until one succeeds
        List<Long> groupChats = controllerPort.findActiveGroupChatIds(event.getTelegramId());
        for (Long chatId : groupChats) {
            if (trySend(chatId, text)) {
                log.info("[TOKEN] Alert sent to group chat={}: telegramId={}", chatId, event.getTelegramId());
                return;
            }
        }
        log.error("[TOKEN] No reachable chat for telegramId={} (tried {} groups), alert lost",
            event.getTelegramId(), groupChats.size());
    }

    private boolean trySend(long chatId, String text) {
        try {
            bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("MarkdownV2")
                .build());
            return true;
        } catch (TelegramApiException e) {
            log.debug("[TOKEN] Cannot send to chatId={}: {}", chatId, e.getMessage());
            return false;
        }
    }
}
