package com.valui.bot.handler.command;

import com.valui.betting.service.BetDmLinkService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** /unlink_bets — revokes this user's own DM access to this group's betting journal (see {@link LinkBetsCommandHandler}). */
@Component
@RequiredArgsConstructor
public class UnlinkBetsCommandHandler implements CommandHandler {

    private final BetDmLinkService betDmLinkService;
    private final BotMessageSource msg;

    @Override
    public String command() { return "/unlink_bets"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                msg.getMessage("bot.user_not_registered", ctx.fromId()));
            return;
        }
        if (!ctx.isGroupChat()) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                "Эта команда выполняется в том групповом чате, который вы хотите отвязать.");
            return;
        }

        betDmLinkService.unlink(ctx.chatId(), ctx.fromId());
        MessageSend.text(ctx.sender(), ctx.chatId(), "🔕 Личка отвязана от ставок этой группы.");
    }
}
