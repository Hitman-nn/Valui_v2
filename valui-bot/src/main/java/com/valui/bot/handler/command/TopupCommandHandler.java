package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/** /topup — пополнение токенов через CryptoBot. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cryptobot.api-token")
public class TopupCommandHandler implements CommandHandler {

    private final BotMessageSource messageSource;
    private final BotSessionService sessionService;

    @Override
    public String command() { return "/topup"; }

    @Override
    public int order() { return 15; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.user_not_registered", ctx.fromId()));
            return;
        }

        sessionService.setState(ctx.fromId(), BotState.TOPUP_ENTER_AMOUNT);

        try {
            var msg = ctx.sender().execute(SendMessage.builder()
                .chatId(ctx.chatId())
                .text(messageSource.getMessage("topup.enter_amount", ctx.fromId()))
                .parseMode("Markdown")
                .build());
            if (msg != null) ctx.tracker().track(ctx.chatId(), msg.getMessageId());
        } catch (TelegramApiException e) {
            log.error("TopupCommandHandler send failed chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}
