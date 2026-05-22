package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.command.AddControllerHandler;
import com.valui.bot.keyboard.CallbackData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Triggered by the «➕ Добавить» inline button on empty controller list screens.
 * Delegates to AddControllerHandler which handles cleanup and wizard startup.
 */
@Component
@RequiredArgsConstructor
public class ControllerAddCallback implements CallbackHandler {

    private final AddControllerHandler addControllerHandler;

    @Override
    public String callbackPrefix() { return CallbackData.CTRL_ADD; }

    @Override
    public int order() { return 20; }

    @Override
    public void handle(BotUpdateContext ctx) {
        MessageSend.answerCallback(ctx.sender(), ctx.update().getCallbackQuery().getId());
        addControllerHandler.handle(ctx);
    }
}
