package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.bot.service.BotSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
        int id = com.valui.bot.handler.MessageSend.sendGetId(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("menu.main", ctx.fromId()));
        if (id > 0) ctx.tracker().track(ctx.chatId(), id);
    }
}
