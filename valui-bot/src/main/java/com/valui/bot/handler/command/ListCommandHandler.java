package com.valui.bot.handler.command;

import com.valui.betting.dto.BetDmLinkDto;
import com.valui.betting.service.BetDmLinkService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.betting.BettingChatResolver;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.menu.BookmakerMenuBuilder;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ListCommandHandler implements CommandHandler {

    private final BotMessageSource messageSource;
    private final ControllerService controllerService;
    private final BettingChatResolver chatResolver;
    private final BetDmLinkService betDmLinkService;

    @Override
    public String command() { return "/list"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.user_not_registered", ctx.fromId()));
            return;
        }

        // DM, nothing picked yet: same auto-resolve as the "📋 Контроллеры" callback screen —
        // see ControllerListCallback for the full rationale. Without this, /list always fell
        // back to "my own controllers" even when a single linked group was already resolvable,
        // independently of whatever ControllerListCallback/ControllerByBookmakerCallback had
        // already resolved elsewhere in the session (this command never read that state).
        if (!ctx.isGroupChat() && !chatResolver.isResolved(ctx)) {
            List<BetDmLinkDto> links = betDmLinkService.listLinks(ctx.fromId());
            if (links.size() == 1) {
                chatResolver.select(ctx, links.get(0).chatId());
            }
        }

        long scopeChatId = chatResolver.resolveOrPhysical(ctx);
        // In a group, or in DM once a linked group has been picked: show that group's full
        // controller list. Otherwise fall back to the caller's own controllers.
        List<ControllerDto> controllers = ctx.isGroupChat() || chatResolver.isResolved(ctx)
            ? controllerService.getGroupControllers(scopeChatId)
            : controllerService.getUserControllersForChat(ctx.fromId(), scopeChatId);

        if (controllers.isEmpty()) {
            var kb = InlineKeyboardBuilder.create()
                .button(messageSource.getMessage("menu.btn.add", ctx.fromId()), CallbackData.CTRL_ADD)
                .build();
            int id = MessageSend.sendGetId(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("controller.list_empty", ctx.fromId()), kb);
            if (id > 0) ctx.tracker().track(ctx.chatId(), id);
            return;
        }

        var menu = BookmakerMenuBuilder.buildSelection(controllers);
        ctx.tracker().sendAndTrack(ctx.sender(), ctx.chatId(), menu.text(), menu.keyboard());
    }
}
