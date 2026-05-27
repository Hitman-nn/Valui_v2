package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.betting.BettingMenuCallback;
import com.valui.bot.service.BotSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * /bet — opens the Betting Journal inline menu.
 * The bottom ReplyKeyboard refreshes naturally when /start or /help is called.
 */
@Component
@RequiredArgsConstructor
public class BetCommandHandler implements CommandHandler {

    private final BotSessionService  sessionService;
    private final BettingMenuCallback bettingMenu;

    @Override
    public String command() { return "/bet"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        sessionService.clearSession(ctx.fromId());
        int id = MessageSend.sendMarkdownGetId(ctx.sender(), ctx.chatId(),
                BettingMenuCallback.buildMenuText(ctx),
                bettingMenu.buildMenuKeyboard(ctx.chatId()));
        if (id > 0) ctx.tracker().track(ctx.chatId(), id);
    }
}
