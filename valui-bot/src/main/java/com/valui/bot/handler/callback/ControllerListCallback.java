package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.menu.ControllerMenuBuilder;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ControllerListCallback implements CallbackHandler {

    private final ControllerService controllerService;

    @Override
    public String callbackPrefix() { return CallbackData.CTRL_LIST; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        int page = 0;
        if (data.contains(":PAGE:")) {
            try { page = Integer.parseInt(data.substring(data.lastIndexOf(':') + 1)); }
            catch (NumberFormatException ignored) {}
        }

        List<ControllerDto> controllers = controllerService.getUserControllers(ctx.chatId());
        var menu = ControllerMenuBuilder.build(controllers, page);
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId, menu.text(), menu.keyboard());
    }
}
