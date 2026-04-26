package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ListFilterCommandHandler implements CommandHandler {

    private final BotMessageSource messageSource;

    @Override
    public String command() { return "/listfilter"; }

    @Override
    public int order() { return 5; }  // must be lower than ListCommandHandler (10) since /list startsWith-matches /listfilter

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.userInfo() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.user_not_registered", ctx.chatId()));
            return;
        }
        MessageSend.text(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("filter.list_empty", ctx.chatId()));
    }
}
