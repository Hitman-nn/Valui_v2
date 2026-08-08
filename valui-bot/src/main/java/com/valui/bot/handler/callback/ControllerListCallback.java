package com.valui.bot.handler.callback;

import com.valui.betting.dto.BetDmLinkDto;
import com.valui.betting.service.BetDmLinkService;
import com.valui.bot.config.BotProperties;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.betting.BettingChatResolver;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.MenuMessage;
import com.valui.bot.keyboard.menu.ControllerMenuBuilder;
import com.valui.bot.service.ControllerSortPreferenceService;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ControllerListCallback implements CallbackHandler {

    private final ControllerService             controllerService;
    private final BotProperties                 botProperties;
    private final ControllerSortPreferenceService sortPreference;
    private final BettingChatResolver            chatResolver;
    private final BetDmLinkService               betDmLinkService;

    @Override
    public String callbackPrefix() { return CallbackData.CTRL_LIST; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        // DM, nothing picked yet: auto-resolve exactly like /bet does when there's exactly one
        // linked group, instead of silently falling back to "my own controllers" — teammates'
        // controllers in that group should be visible on the very first tap, not hidden behind
        // the "👥 Контроллеры группы" button below (easy to miss, and the actual bug report that
        // prompted this: DM was quietly showing only the caller's own controllers). With 0 or 2+
        // links the fallback below still applies — nothing to auto-pick unambiguously.
        if (!ctx.isGroupChat() && !chatResolver.isResolved(ctx)) {
            List<BetDmLinkDto> links = betDmLinkService.listLinks(ctx.fromId());
            if (links.size() == 1) {
                chatResolver.select(ctx, links.get(0).chatId());
            }
        }

        long   scopeChatId = chatResolver.resolveOrPhysical(ctx);

        int    page;
        String sort;

        if (data.contains(":SORT:")) {
            // CTRL:LIST:SORT:DATE or CTRL:LIST:SORT:NAME — switch sort, reset to page 0
            sort = data.substring(data.lastIndexOf(':') + 1).toUpperCase();
            sortPreference.save(scopeChatId, sort);
            page = 0;
        } else {
            sort = sortPreference.load(scopeChatId);
            page = 0;
            if (data.contains(":PAGE:")) {
                try { page = Integer.parseInt(data.substring(data.lastIndexOf(':') + 1)); }
                catch (NumberFormatException ignored) {}
            }
        }

        render(ctx, messageId, scopeChatId, page, sort);
    }

    /**
     * Re-renders the list at page 0 with the stored sort. Public so
     * {@link ControllerChatPickerCallback} can jump straight back here after the user picks a
     * linked group from DM.
     */
    public void renderList(BotUpdateContext ctx, int messageId) {
        long scopeChatId = chatResolver.resolveOrPhysical(ctx);
        render(ctx, messageId, scopeChatId, 0, sortPreference.load(scopeChatId));
    }

    private void render(BotUpdateContext ctx, int messageId, long scopeChatId, int page, String sort) {
        // In a group, or in DM once a linked group has been picked, show that group's full
        // controller list — same as any group member would see. Otherwise (DM, nothing picked
        // yet) fall back to the caller's own controllers regardless of which chat they notify.
        boolean groupScope = ctx.isGroupChat() || chatResolver.isResolved(ctx);
        List<ControllerDto> controllers = groupScope
            ? controllerService.getGroupControllers(scopeChatId)
            : controllerService.getUserControllersForChat(ctx.fromId(), scopeChatId);

        MenuMessage menu = ControllerMenuBuilder.build(controllers, page, botProperties.staleThresholdDays(), sort);
        InlineKeyboardMarkup kb = menu.keyboard();
        if (!groupScope) {
            // Offer a way into a linked group's controllers instead of just "my own". The
            // markup returned above is immutable (telegrambots wraps it internally regardless
            // of how it was built), so a fresh markup has to be built rather than mutated.
            List<List<InlineKeyboardButton>> rows = new java.util.ArrayList<>(kb.getKeyboard());
            rows.add(List.of(InlineKeyboardButton.builder()
                    .text("👥 Контроллеры группы")
                    .callbackData(CallbackData.CTRL_CHAT_PICK)
                    .build()));
            kb = InlineKeyboardMarkup.builder().keyboard(rows).build();
        }

        ctx.tracker().replaceAndTrack(ctx.sender(), ctx.chatId(), messageId, menu.text(), kb);
    }
}
