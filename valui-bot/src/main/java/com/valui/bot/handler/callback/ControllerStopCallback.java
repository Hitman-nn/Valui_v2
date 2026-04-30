package com.valui.bot.handler.callback;

import com.valui.bot.config.BotWizardProperties;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
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

    private final ControllerService   controllerService;
    private final BotWizardProperties wizardProps;

    @Override public String callbackPrefix() { return PREFIX; }
    @Override public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        UUID stoppedId = null;
        String stoppedBookmaker = null;
        try {
            stoppedId = UUID.fromString(
                ctx.update().getCallbackQuery().getData().substring(PREFIX.length()));
            ControllerDto c = controllerService.getController(stoppedId);
            stoppedBookmaker = c.bookmaker();
            controllerService.stopForChat(stoppedId, ctx.fromId(), ctx.chatId());
        } catch (Exception e) {
            log.warn("stopForChat failed chatId={}: {}", ctx.chatId(), e.getMessage());
            stoppedId = null; // stop didn't complete — don't exclude from list
        }

        final String bookmaker = stoppedBookmaker;
        final UUID removedId = stoppedId;
        if (bookmaker != null) {
            List<ControllerDto> all = ctx.isGroupChat()
                ? controllerService.getGroupControllers(ctx.chatId())
                : controllerService.getUserControllersForChat(ctx.fromId(), ctx.chatId());
            List<ControllerDto> remaining = all.stream()
                .filter(c -> bookmaker.equalsIgnoreCase(c.bookmaker()))
                .filter(c -> removedId == null || !c.id().equals(removedId))
                .toList();
            var menu = BookmakerMenuBuilder.buildControllerList(bookmaker, remaining, 0, wizardProps.getStaleThresholdDays());
            MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId, menu.text(), menu.keyboard());
        } else {
            List<ControllerDto> all = ctx.isGroupChat()
                ? controllerService.getGroupControllers(ctx.chatId())
                : controllerService.getUserControllersForChat(ctx.fromId(), ctx.chatId());
            var menu = BookmakerMenuBuilder.buildSelection(all);
            MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId, menu.text(), menu.keyboard());
        }
    }
}
