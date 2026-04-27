package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ControllerFilterEditCallback implements CallbackHandler {

    private static final String PREFIX = "CTRL:FILTER:";

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;

    @Override
    public String callbackPrefix() { return PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        String controllerId = ctx.update().getCallbackQuery().getData().substring(PREFIX.length());

        sessionService.setStateAndMergeContext(ctx.chatId(), BotState.WAITING_FILTER_RULE, Map.of(
                UserBotSession.CTX_FILTER_MODE,        "CONTROLLER_FILTER",
                UserBotSession.CTX_EDIT_CONTROLLER_ID, controllerId,
                UserBotSession.CTX_WIZARD_MSG_ID,      String.valueOf(messageId)
        ));

        var keyboard = InlineKeyboardBuilder.create()
                .button(messageSource.getMessage("menu.cancel", ctx.chatId()), CallbackData.CANCEL)
                .build();

        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                messageSource.getMessage("filter.enter_rule", ctx.chatId()),
                keyboard);
    }
}
