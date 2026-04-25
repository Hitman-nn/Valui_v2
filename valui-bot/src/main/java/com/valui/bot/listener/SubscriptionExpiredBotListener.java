package com.valui.bot.listener;

import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.user.event.SubscriptionExpiredEvent;
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
public class SubscriptionExpiredBotListener {

    private final AbsSender bot;
    private final BotMessageSource messageSource;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionExpired(SubscriptionExpiredEvent event) {
        log.info("Sending expiry notification: telegramId={} plan={}",
            event.telegramId(), event.oldPlanCode());

        String text = messageSource.getMessage(
            "subscription.expired", event.telegramId(), event.oldPlanCode());

        var keyboard = InlineKeyboardBuilder.create()
            .button("📋 " + messageSource.getMessage("subscription.plans_title", event.telegramId()),
                CallbackData.PLANS_VIEW)
            .row()
            .button("🏠 " + messageSource.getMessage("menu.main", event.telegramId()),
                CallbackData.MENU_MAIN)
            .build();

        try {
            bot.execute(SendMessage.builder()
                .chatId(event.telegramId())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send expiry notification to telegramId={}", event.telegramId(), e);
        }
    }
}
