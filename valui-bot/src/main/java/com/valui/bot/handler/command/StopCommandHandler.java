package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.service.BotSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StopCommandHandler implements CommandHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource  messageSource;

    @Override
    public String command() { return "/stop"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        sessionService.clearSession(ctx.fromId());
        var kb = org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.builder()
            .keyboardRow(java.util.List.of(
                org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                    .text(messageSource.getMessage("stop.confirm_yes", ctx.fromId()))
                    .callbackData(CallbackData.STOP_YES).build(),
                org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                    .text(messageSource.getMessage("stop.confirm_no", ctx.fromId()))
                    .callbackData(CallbackData.STOP_NO).build()
            )).build();
        int id = MessageSend.sendGetId(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("stop.confirm_prompt", ctx.fromId()), kb);
        if (id > 0) ctx.tracker().track(ctx.chatId(), id);
    }
}
