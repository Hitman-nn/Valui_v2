package com.valui.bot.handler.command;

import com.valui.betting.dto.BetDmLinkDto;
import com.valui.betting.service.BetDmLinkService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.betting.BetChatPickerCallback;
import com.valui.bot.handler.callback.betting.BettingChatResolver;
import com.valui.bot.handler.callback.betting.BettingMenuCallback;
import com.valui.bot.service.BotSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /bet — opens the Betting Journal inline menu.
 * The bottom ReplyKeyboard refreshes naturally when /start or /help is called.
 *
 * <p>In a group chat this is unambiguous — the menu always scopes to that group. In DM there's
 * no such built-in scope, so a fresh /bet always clears any remembered {@link BettingChatResolver}
 * selection and either opens the menu directly (exactly one linked group), shows the chat picker
 * (more than one), or tells the user to run /link_bets first (none).
 */
@Component
@RequiredArgsConstructor
public class BetCommandHandler implements CommandHandler {

    private final BotSessionService   sessionService;
    private final BettingMenuCallback bettingMenu;
    private final BetDmLinkService    betDmLinkService;
    private final BettingChatResolver resolver;
    private final BetChatPickerCallback chatPicker;

    @Override
    public String command() { return "/bet"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        sessionService.clearSession(ctx.fromId());

        if (ctx.isGroupChat()) {
            openMenu(ctx, ctx.chatId());
            return;
        }

        // Fresh /bet in DM always re-prompts rather than silently reusing a stale selection.
        resolver.clear(ctx.fromId());
        List<BetDmLinkDto> links = betDmLinkService.listLinks(ctx.fromId());
        if (links.isEmpty()) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                    "У вас нет привязанных чатов со ставками.\n\n" +
                    "Выполните /link_bets в групповом чате, где ведёте учёт ставок.");
            return;
        }
        if (links.size() == 1) {
            resolver.select(ctx, links.get(0).chatId());
            openMenu(ctx, links.get(0).chatId());
            return;
        }
        chatPicker.renderPicker(ctx, 0);
    }

    private void openMenu(BotUpdateContext ctx, long scopeChatId) {
        int id = MessageSend.sendMarkdownGetId(ctx.sender(), ctx.chatId(),
                BettingMenuCallback.buildMenuText(ctx),
                bettingMenu.buildMenuKeyboard(ctx, scopeChatId));
        if (id > 0) ctx.tracker().track(ctx.chatId(), id);
    }
}
