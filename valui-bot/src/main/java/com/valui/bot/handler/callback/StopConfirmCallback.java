package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StopConfirmCallback implements CallbackHandler {

    private final BotMessageSource  messageSource;
    private final ControllerService controllerService;

    @Override
    public String callbackPrefix() { return CallbackData.STOP_CONFIRM_PREFIX; }

    @Override
    public int order() { return 20; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();
        var    empty      = InlineKeyboardMarkup.builder().keyboard(List.of()).build();

        MessageSend.answerCallback(ctx.sender(), callbackId);

        if (CallbackData.STOP_YES.equals(data)) {
            int stopped;
            String messageKey;
            try {
                if (ctx.isGroupChat()) {
                    stopped   = controllerService.stopAllForUserInChat(ctx.fromId(), ctx.chatId());
                    messageKey = stopped > 0 ? "bot.stop_all.group_done" : "bot.stop_all.nothing";
                } else {
                    stopped   = controllerService.stopAllForUser(ctx.fromId());
                    messageKey = stopped > 0 ? "bot.stop_all.private_done" : "bot.stop_all.nothing";
                }
            } catch (Exception e) {
                log.error("stopAll failed fromId={}: {}", ctx.fromId(), e.getMessage());
                stopped   = 0;
                messageKey = "bot.stop_all.nothing";
            }
            MessageSend.editTextWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                messageSource.getMessage(messageKey, ctx.fromId(), stopped), empty);
        } else {
            MessageSend.editTextWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                messageSource.getMessage("menu.cancel", ctx.fromId()), empty);
        }
    }
}
