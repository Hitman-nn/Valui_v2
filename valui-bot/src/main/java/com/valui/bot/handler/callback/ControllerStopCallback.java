package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.menu.BookmakerMenuBuilder;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ControllerStopCallback implements CallbackHandler {

    private static final String PREFIX = "CTRL:STOP:";

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

        String stoppedBookmaker = null;
        try {
            UUID controllerId = UUID.fromString(
                    ctx.update().getCallbackQuery().getData().substring(PREFIX.length()));
            ControllerDto c = controllerService.getController(controllerId);
            stoppedBookmaker = c.bookmaker();
            controllerService.removeController(controllerId, ctx.fromId());
        } catch (Exception e) {
            log.warn("Failed to stop controller for chatId={}: {}", ctx.chatId(), e.getMessage());
        }

        final String bookmaker = stoppedBookmaker;
        if (bookmaker != null) {
            List<ControllerDto> remaining = controllerService.getUserControllers(ctx.fromId()).stream()
                    .filter(c -> bookmaker.equalsIgnoreCase(c.bookmaker()))
                    .toList();
            var menu = BookmakerMenuBuilder.buildControllerList(bookmaker, remaining, 0);
            MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId, menu.text(), menu.keyboard());
        } else {
            List<ControllerDto> all = controllerService.getUserControllers(ctx.fromId());
            var menu = BookmakerMenuBuilder.buildSelection(all);
            MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId, menu.text(), menu.keyboard());
        }
    }
}
