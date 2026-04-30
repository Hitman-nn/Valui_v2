package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Handles 🔍 search button clicks on the sport / tournament list.
 * Edits the current message in-place to show a text input prompt.
 * The user then sends a text query handled by WizardTextHandler.
 */
@Component
@RequiredArgsConstructor
public class WizardSearchCallback implements CallbackHandler {

    private final BotSessionService sessionService;

    @Override
    public String callbackPrefix() { return "SEARCH:"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        boolean isSportSearch = data.equals(CallbackData.SEARCH_SPORT);
        BotState requiredState = isSportSearch ? BotState.SELECTING_SPORT : BotState.SELECTING_TOURNAMENT;

        if (ctx.session().getState() != requiredState) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "⚠️ Это не ваше меню");
            return;
        }

        MessageSend.answerCallback(ctx.sender(), callbackId);

        String target = isSportSearch ? "SPORT" : "TOURNAMENT";
        String prompt  = isSportSearch
            ? "🔍 Введите название вида спорта:"
            : "🔍 Введите название турнира:";

        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.WAITING_WIZARD_SEARCH,
            Map.of(UserBotSession.CTX_SEARCH_TARGET,  target,
                   UserBotSession.CTX_WIZARD_MSG_ID,  String.valueOf(messageId)));

        // Edit the list message in-place — keeps the same message ID so we can replace it
        // later without tracking a new message ID
        var keyboard = InlineKeyboardBuilder.create()
            .button("✕ Отмена", CallbackData.CANCEL)
            .build();
        MessageSend.editTextWithKeyboard(ctx.sender(), ctx.chatId(), messageId, prompt, keyboard);
    }
}
