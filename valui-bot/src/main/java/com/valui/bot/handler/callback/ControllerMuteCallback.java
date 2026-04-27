package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ControllerMuteCallback implements CallbackHandler {

    private static final String PREFIX = "CTRL:MUTE:";

    private final ControllerService controllerService;

    @Override
    public String callbackPrefix() { return PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        try {
            UUID id = UUID.fromString(
                    ctx.update().getCallbackQuery().getData().substring(PREFIX.length()));
            controllerService.muteController(id, ctx.chatId());
            ControllerDto c = controllerService.getController(id);
            MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    ControllerDetailCallback.buildDetailText(c, ctx.chatId()),
                    ControllerDetailCallback.buildDetailKeyboard(c, ctx.chatId()));
        } catch (Exception e) {
            log.warn("mute failed chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}

@Slf4j
@Component
@RequiredArgsConstructor
class ControllerUnmuteCallback implements CallbackHandler {

    private static final String PREFIX = "CTRL:UNMUTE:";

    private final ControllerService controllerService;

    @Override
    public String callbackPrefix() { return PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        try {
            UUID id = UUID.fromString(
                    ctx.update().getCallbackQuery().getData().substring(PREFIX.length()));
            controllerService.unmuteController(id, ctx.chatId());
            ControllerDto c = controllerService.getController(id);
            MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    ControllerDetailCallback.buildDetailText(c, ctx.chatId()),
                    ControllerDetailCallback.buildDetailKeyboard(c, ctx.chatId()));
        } catch (Exception e) {
            log.warn("unmute failed chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}
