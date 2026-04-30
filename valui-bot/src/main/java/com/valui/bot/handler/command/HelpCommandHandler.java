package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HelpCommandHandler implements CommandHandler {

    private final BotMessageSource messageSource;

    @Override
    public String command() { return "/help"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("bot.help", ctx.fromId()),
            MainMenuKeyboard.build(ctx.fromId(), messageSource));
    }
}
