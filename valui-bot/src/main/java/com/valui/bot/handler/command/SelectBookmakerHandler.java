package com.valui.bot.handler.command;

import com.valui.bot.guard.BotAccessGuard;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.common.exception.SubscriptionLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SelectBookmakerHandler implements CommandHandler {

    private final BotAccessGuard guard;
    private final BotMessageSource messageSource;

    @Override
    public String command() { return "/bookmaker"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String text = ctx.update().getMessage().getText();
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.bookmaker_select_hint", ctx.fromId()));
            return;
        }
        String bookmaker = parts[1].trim().toUpperCase();

        try {
            guard.guardBookmakerAccess(ctx.fromId(), ctx.chatId(), bookmaker, ctx.sender());
        } catch (SubscriptionLimitExceededException e) {
            return;
        }

        MessageSend.text(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("bot.bookmaker_available", ctx.fromId(), bookmaker));
    }
}
