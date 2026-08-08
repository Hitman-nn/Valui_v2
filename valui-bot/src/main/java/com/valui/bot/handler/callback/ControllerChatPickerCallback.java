package com.valui.bot.handler.callback;

import com.valui.betting.dto.BetDmLinkDto;
import com.valui.betting.service.BetDmLinkService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.betting.BettingChatResolver;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * "👥 Контроллеры группы" from DM: lets the user pick which linked group's controllers to
 * browse, reusing the same {@link BettingChatResolver} selection (and {@code /link_bets}
 * links) as the betting journal's {@link com.valui.bot.handler.callback.betting.BetChatPickerCallback}
 * — pick it here and {@code /bet} honours the choice too, and vice versa.
 */
@Component
@RequiredArgsConstructor
public class ControllerChatPickerCallback implements CallbackHandler {

    private final BetDmLinkService     betDmLinkService;
    private final BettingChatResolver  resolver;
    private final ControllerListCallback controllerList;

    @Override
    public String callbackPrefix() { return CallbackData.CTRL_CHAT_SEL_PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        if (data.equals(CallbackData.CTRL_CHAT_PICK)) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            renderPicker(ctx, messageId);
            return;
        }

        long chatId;
        try {
            chatId = Long.parseLong(data.substring(CallbackData.CTRL_CHAT_SEL_PREFIX.length()));
        } catch (NumberFormatException e) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            return;
        }

        if (!betDmLinkService.isLinked(chatId, ctx.fromId())) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⚠️ Привязка к этому чату больше не действует. Выполните /link_bets заново в группе.");
            return;
        }
        resolver.select(ctx, chatId);
        MessageSend.answerCallback(ctx.sender(), callbackId);
        controllerList.renderList(ctx, messageId);
    }

    private void renderPicker(BotUpdateContext ctx, int messageId) {
        List<BetDmLinkDto> links = betDmLinkService.listLinks(ctx.fromId());
        if (links.isEmpty()) {
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    "У вас нет привязанных чатов.\n\nВыполните /link_bets в групповом чате, чьи контроллеры хотите видеть здесь.",
                    InlineKeyboardBuilder.create().button("← К списку", CallbackData.CTRL_LIST).build());
            return;
        }
        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetDmLinkDto link : links) {
            kb.button(link.displayName(), CallbackData.ctrlChatSel(link.chatId())).row();
        }
        kb.button("← К списку", CallbackData.CTRL_LIST);
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "👥 *Контроллеры какой группы показать?*", kb.build());
    }
}
