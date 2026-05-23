package com.valui.bot.handler.message;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.BotUpdateHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.user.crypto.CryptoPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Map;

/**
 * Handles text input when state is TOPUP_ENTER_AMOUNT.
 * Validates the token count, saves it to session, then shows currency selection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cryptobot.api-token")
public class TopupTextHandler implements BotUpdateHandler {

    private static final int MIN_TOKENS = 100;

    private final BotMessageSource      messageSource;
    private final BotSessionService     sessionService;
    private final CryptoPaymentService  cryptoPaymentService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || update.getMessage().getText() == null) return false;
        return !update.getMessage().getText().startsWith("/");
    }

    @Override
    public int order() { return 45; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.session().getState() != BotState.TOPUP_ENTER_AMOUNT) return;

        MessageSend.deleteMessage(ctx.sender(), ctx.chatId(),
            ctx.update().getMessage().getMessageId());

        String text = ctx.update().getMessage().getText().trim();
        int tokenAmount;
        try {
            tokenAmount = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            tokenAmount = -1;
        }

        if (tokenAmount < MIN_TOKENS) {
            try {
                var msg = ctx.sender().execute(SendMessage.builder()
                    .chatId(ctx.chatId())
                    .text(messageSource.getMessage("topup.invalid_amount", ctx.fromId()))
                    .parseMode("Markdown")
                    .build());
                if (msg != null) ctx.tracker().track(ctx.chatId(), msg.getMessageId());
            } catch (TelegramApiException e) {
                log.error("TopupTextHandler send failed: {}", e.getMessage());
            }
            return;
        }

        // Save amount and switch to IDLE — further text won't override the amount
        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.IDLE,
            Map.of(UserBotSession.CTX_TOPUP_AMOUNT, String.valueOf(tokenAmount)));

        var keyboard = buildCurrencyKeyboard();
        String prompt = messageSource.getMessage("topup.select_currency", ctx.fromId(), tokenAmount);

        try {
            var msg = ctx.sender().execute(SendMessage.builder()
                .chatId(ctx.chatId())
                .text(prompt)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build());
            if (msg != null) ctx.tracker().track(ctx.chatId(), msg.getMessageId());
        } catch (TelegramApiException e) {
            log.error("TopupTextHandler send currency keyboard failed: {}", e.getMessage());
        }
    }

    private org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup buildCurrencyKeyboard() {
        var builder = InlineKeyboardBuilder.create().columns(2);
        for (String currency : cryptoPaymentService.supportedCurrencies()) {
            builder.button(currency, CallbackData.topupCurrency(currency));
        }
        builder.cancelButton();
        return builder.build();
    }
}
