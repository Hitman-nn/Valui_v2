package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LanguageCommandHandler implements CommandHandler {

    private final BotMessageSource messageSource;

    @Override
    public String command() { return "/language"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        var keyboard = InlineKeyboardBuilder.create()
            .button("🇷🇺 Русский", CallbackData.langSet("ru"))
            .button("🇬🇧 English", CallbackData.langSet("en"))
            .build();

        MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("bot.select_language", ctx.fromId()),
            keyboard);
    }
}
