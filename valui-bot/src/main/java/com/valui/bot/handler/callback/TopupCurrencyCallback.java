package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.common.entity.CryptoInvoiceEntity;
import com.valui.user.crypto.CryptoPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;

/**
 * Handles TOPUP:CUR:{currency} callback — creates a CryptoBot invoice
 * and sends the pay link to the user.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cryptobot.api-token")
public class TopupCurrencyCallback implements CallbackHandler {

    private final BotMessageSource      messageSource;
    private final BotSessionService     sessionService;
    private final CryptoPaymentService  cryptoPaymentService;

    @Override
    public String callbackPrefix() { return CallbackData.TOPUP_CURRENCY_PREFIX; }

    @Override
    public int order() { return 30; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) return;

        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();
        String data       = ctx.update().getCallbackQuery().getData();
        String currency   = data.substring(CallbackData.TOPUP_CURRENCY_PREFIX.length());

        String amountStr = sessionService.getContext(ctx.fromId(), com.valui.bot.state.UserBotSession.CTX_TOPUP_AMOUNT)
            .orElse("0");
        int tokenAmount;
        try {
            tokenAmount = Integer.parseInt(amountStr);
        } catch (NumberFormatException e) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            return;
        }

        sessionService.setState(ctx.fromId(), BotState.IDLE);
        MessageSend.answerCallback(ctx.sender(), callbackId);

        CryptoInvoiceEntity invoice;
        try {
            invoice = cryptoPaymentService.createInvoice(ctx.fromId(), tokenAmount, currency);
        } catch (Exception e) {
            log.error("Failed to create crypto invoice for telegramId={}: {}", ctx.fromId(), e.getMessage());
            try {
                ctx.sender().execute(EditMessageText.builder()
                    .chatId(ctx.chatId())
                    .messageId(messageId)
                    .text(messageSource.getMessage("topup.error", ctx.fromId()))
                    .parseMode("Markdown")
                    .build());
            } catch (TelegramApiException ex) {
                log.error("Failed to edit message: {}", ex.getMessage());
            }
            return;
        }

        BigDecimal cryptoAmount = invoice.getCryptoAmount();
        String text = messageSource.getMessage("topup.invoice_created", ctx.fromId(),
            invoice.getTokenAmount(),
            cryptoAmount.stripTrailingZeros().toPlainString(),
            invoice.getCurrency(),
            invoice.getPayUrl());

        try {
            ctx.sender().execute(EditMessageText.builder()
                .chatId(ctx.chatId())
                .messageId(messageId)
                .text(text)
                .parseMode("Markdown")
                .disableWebPagePreview(true)
                .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send invoice message: {}", e.getMessage());
        }
    }
}
