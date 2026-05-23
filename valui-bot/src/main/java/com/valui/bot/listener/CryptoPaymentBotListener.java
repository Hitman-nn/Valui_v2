package com.valui.bot.listener;

import com.valui.bot.i18n.BotMessageSource;
import com.valui.user.crypto.CryptoPaymentSuccessEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cryptobot.api-token")
public class CryptoPaymentBotListener {

    private final AbsSender        bot;
    private final BotMessageSource messageSource;

    @Async
    @EventListener
    public void onPaymentSuccess(CryptoPaymentSuccessEvent event) {
        String text = messageSource.getMessage("topup.paid", event.telegramId(),
            event.tokenAmount(),
            event.cryptoAmount().stripTrailingZeros().toPlainString(),
            event.currency());
        try {
            bot.execute(SendMessage.builder()
                .chatId(event.telegramId())
                .text(text)
                .parseMode("Markdown")
                .build());
        } catch (TelegramApiException e) {
            log.error("[CRYPTO] Failed to notify telegramId={}: {}", event.telegramId(), e.getMessage());
        }
    }
}
