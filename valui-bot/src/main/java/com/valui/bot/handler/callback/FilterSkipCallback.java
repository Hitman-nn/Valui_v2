package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FilterSkipCallback implements CallbackHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;

    @Override
    public String callbackPrefix() { return CallbackData.FILTER_SKIP; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        MessageSend.answerCallback(ctx.sender(), ctx.update().getCallbackQuery().getId());
        sessionService.setState(ctx.chatId(), BotState.WAITING_CONFIRM_CREATE);
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        String text = ControllerConfirmCallback.buildConfirmText(ctx.chatId(), sessionService, messageSource);
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text,
            ControllerConfirmCallback.buildConfirmKeyboard(ctx.chatId(), messageSource));
    }
}
