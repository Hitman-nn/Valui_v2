package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.service.BotSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StopCommandHandler implements CommandHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;

    @Override
    public String command() { return "/stop"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        sessionService.clearSession(ctx.fromId());
        MessageSend.text(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("bot.monitoring_stopped", ctx.fromId()));
    }
}
