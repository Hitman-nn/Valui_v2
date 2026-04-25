package com.valui.bot.listener;

import com.valui.bot.ValuiTelegramBot;
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
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpiredBotListener {

    private final ValuiTelegramBot bot;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionExpired(SubscriptionExpiredEvent event) {
        log.info("Sending expiry notification: telegramId={} plan={}",
            event.telegramId(), event.oldPlanCode());

        String text = String.format(
            "⚠️ Ваша подписка *%s* истекла.\n\n" +
            "Вы переведены на план FREE. " +
            "Ваши контроллеры сверх лимита приостановлены.",
            event.oldPlanCode());

        var keyboard = InlineKeyboardBuilder.create()
            .button("📋 Посмотреть планы", CallbackData.PLANS_VIEW)
            .row()
            .button("🏠 Главное меню", CallbackData.MENU_MAIN)
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
