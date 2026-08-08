package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.BetDmLinkDto;
import com.valui.betting.service.BetDmLinkService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Renders the "which linked group's betting journal?" picker shown when a user enters the
 * betting section from DM, and handles their tap.
 *
 * Not itself a {@link com.valui.bot.handler.CallbackHandler} — {@code BET:CHAT:} shares the
 * {@code "BET:"} prefix that {@link BettingMenuCallback} already owns, so routing a tap here
 * happens as one more branch inside {@code BettingMenuCallback.handle()} rather than as a
 * competing top-level handler (which would need order-based tie-breaking against it instead).
 */
@Component
@RequiredArgsConstructor
public class BetChatPickerCallback {

    private final BetDmLinkService     betDmLinkService;
    private final BettingChatResolver  resolver;

    /** Sends (messageId==0) or edits (messageId>0) the chat-picker keyboard. */
    public void renderPicker(BotUpdateContext ctx, int messageId) {
        List<BetDmLinkDto> links = betDmLinkService.listLinks(ctx.fromId());
        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetDmLinkDto link : links) {
            kb.button(link.displayName(), CallbackData.betChatSel(link.chatId())).row();
        }
        String text = "💬 *В каком чате вести учёт ставок?*";
        if (messageId > 0) {
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, kb.build());
        } else {
            MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), text, kb.build());
        }
    }

    /**
     * Handles a {@code BET:CHAT:{chatId}} tap: validates the link is still active (it may have
     * been revoked via /unlink_bets since the picker was rendered), records the selection, and
     * answers the callback query either way.
     *
     * @return the selected chatId, or empty if the link was no longer valid — callers should not
     *         render a menu in that case, the alert shown here is the whole response.
     */
    public Optional<Long> handleSelection(BotUpdateContext ctx, String data, String callbackId) {
        long chatId = Long.parseLong(data.substring(CallbackData.BET_CHAT_SEL_PREFIX.length()));
        if (!betDmLinkService.isLinked(chatId, ctx.fromId())) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⚠️ Привязка к этому чату больше не действует. Выполните /link_bets заново в группе.");
            return Optional.empty();
        }
        resolver.select(ctx, chatId);
        MessageSend.answerCallback(ctx.sender(), callbackId);
        return Optional.of(chatId);
    }
}
