package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Handles TOPUP:START callback from the /info screen.
 * Replaces the info message with "enter token amount" prompt.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cryptobot.api-token")
public class TopupStartCallback implements CallbackHandler {

    private final BotMessageSource  messageSource;
    private final BotSessionService sessionService;

    @Override
    public String callbackPrefix() { return CallbackData.TOPUP_START; }

    @Override
    public int order() { return 30; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) return;

        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setState(ctx.fromId(), BotState.TOPUP_ENTER_AMOUNT);

        String prompt = messageSource.getMessage("topup.enter_amount", ctx.fromId());
        try {
            ctx.sender().execute(EditMessageText.builder()
                .chatId(ctx.chatId())
                .messageId(messageId)
                .text(prompt)
                .parseMode("Markdown")
                .build());
        } catch (TelegramApiException e) {
            log.error("TopupStartCallback edit failed chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}
