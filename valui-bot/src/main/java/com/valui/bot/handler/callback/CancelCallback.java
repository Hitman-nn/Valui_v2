package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CancelCallback implements CallbackHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final WizardBackNavigator backNavigator;

    @Override
    public String callbackPrefix() { return CallbackData.CANCEL; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        MessageSend.answerCallback(ctx.sender(), ctx.update().getCallbackQuery().getId());

        BotState state = ctx.session() != null ? ctx.session().getState() : BotState.IDLE;

        if (state == BotState.WAITING_FILTER_RULE || state == BotState.WAITING_CONFIRM_CREATE) {
            int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
            backNavigator.returnToTournamentList(ctx.sender(), ctx.chatId(), messageId);
            return;
        }

        // For all other states (bookmaker/sport selection, IDLE, etc.) — go to main menu
        sessionService.clearSession(ctx.chatId());
        MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("menu.main", ctx.chatId()),
            MainMenuKeyboard.build(ctx.chatId(), messageSource));
    }
}
