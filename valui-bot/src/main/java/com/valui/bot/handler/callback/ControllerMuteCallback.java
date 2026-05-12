package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
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

    @Override public String callbackPrefix() { return PREFIX; }
    @Override public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        UUID id;
        try {
            id = UUID.fromString(ctx.update().getCallbackQuery().getData().substring(PREFIX.length()));
        } catch (Exception e) {
            return;
        }

        try {
            controllerService.muteForChat(id, ctx.fromId(), ctx.chatId());
            log.info("Controller {} muted by chatId={}", id, ctx.chatId());
        } catch (Exception e) {
            log.warn("muteForChat failed chatId={}: {}", ctx.chatId(), e.getMessage());
        }

        try {
            ControllerDto c = controllerService.getControllerForChat(id, ctx.chatId());
            MessageSend.editTextWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                ControllerDetailCallback.buildDetailText(c, ctx.chatId()),
                ControllerDetailCallback.buildDetailKeyboard(c, ctx.fromId(), ctx.chatId()));
        } catch (Exception e) {
            log.warn("Failed to refresh detail chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}

@Slf4j
@Component
@RequiredArgsConstructor
class ControllerUnmuteCallback implements CallbackHandler {

    private static final String PREFIX = "CTRL:UNMUTE:";
    private final ControllerService controllerService;

    @Override public String callbackPrefix() { return PREFIX; }
    @Override public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        UUID id;
        try {
            id = UUID.fromString(ctx.update().getCallbackQuery().getData().substring(PREFIX.length()));
        } catch (Exception e) {
            return;
        }

        try {
            controllerService.unmuteForChat(id, ctx.fromId(), ctx.chatId());
            log.info("Controller {} unmuted by chatId={}", id, ctx.chatId());
        } catch (Exception e) {
            log.warn("unmuteForChat failed chatId={}: {}", ctx.chatId(), e.getMessage());
        }

        try {
            ControllerDto c = controllerService.getControllerForChat(id, ctx.chatId());
            MessageSend.editTextWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                ControllerDetailCallback.buildDetailText(c, ctx.chatId()),
                ControllerDetailCallback.buildDetailKeyboard(c, ctx.fromId(), ctx.chatId()));
        } catch (Exception e) {
            log.warn("Failed to refresh detail chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}
