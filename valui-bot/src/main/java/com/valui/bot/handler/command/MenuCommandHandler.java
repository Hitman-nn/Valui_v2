package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.bot.service.BotSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
@Component
@RequiredArgsConstructor
public class MenuCommandHandler implements CommandHandler {

    private final BotMessageSource messageSource;
    private final BotSessionService sessionService;

    @Override
    public String command() { return "/menu"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        sessionService.clearSession(ctx.fromId());
        ctx.tracker().deleteStale(ctx.chatId(), ctx.sender());
        try {
            Message sent = ctx.sender().execute(SendMessage.builder()
                .chatId(ctx.chatId())
                .text(messageSource.getMessage("menu.main", ctx.fromId()))
                .replyMarkup(MainMenuKeyboard.build(ctx.fromId(), messageSource))
                .build());
            if (sent != null) ctx.tracker().track(ctx.chatId(), sent.getMessageId());
        } catch (TelegramApiException e) {
            log.error("MenuCommandHandler send failed chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}
