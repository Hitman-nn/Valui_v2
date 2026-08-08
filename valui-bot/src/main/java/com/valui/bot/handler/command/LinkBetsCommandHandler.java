package com.valui.bot.handler.command;

import com.valui.betting.service.BetDmLinkService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * /link_bets — run in a group chat to opt this user into managing that group's betting
 * journal from their own DM (create/resolve bets, print history, manage accounts) instead
 * of doing it in the group. Each member links themselves individually; see {@link BetDmLinkService}.
 *
 * The betting module doesn't use {@link BotMessageSource} i18n (all its text is hardcoded
 * Russian) — matching that existing convention here rather than mixing styles.
 */
@Component
@RequiredArgsConstructor
public class LinkBetsCommandHandler implements CommandHandler {

    private final BetDmLinkService betDmLinkService;
    private final BotMessageSource msg;

    @Override
    public String command() { return "/link_bets"; }

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
                "Эта команда выполняется в групповом чате — она привязывает вашу личку " +
                "к ставкам именно этой группы.");
            return;
        }

        String chatTitle = ctx.update().hasMessage() && ctx.update().getMessage().getChat() != null
                ? ctx.update().getMessage().getChat().getTitle()
                : null;
        betDmLinkService.link(ctx.chatId(), ctx.fromId(), chatTitle);

        MessageSend.text(ctx.sender(), ctx.chatId(),
                "✅ Личка привязана к этой группе для ставок. Напишите боту /bet в личных " +
                "сообщениях, чтобы вести учёт без сообщений в группу.\n\n" +
                "Отвязать: /unlink_bets");
    }
}
