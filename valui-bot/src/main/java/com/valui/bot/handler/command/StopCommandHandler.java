package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.service.BotSessionService;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StopCommandHandler implements CommandHandler {

    private final BotSessionService  sessionService;
    private final BotMessageSource   messageSource;
    private final ControllerService  controllerService;

    @Override
    public String command() { return "/stop"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        sessionService.clearSession(ctx.fromId());

        int stopped;
        String messageKey;
        try {
            if (ctx.isGroupChat()) {
                stopped = controllerService.stopAllForUserInChat(ctx.fromId(), ctx.chatId());
                messageKey = stopped > 0 ? "bot.stop_all.group_done" : "bot.stop_all.nothing";
            } else {
                stopped = controllerService.stopAllForUser(ctx.fromId());
                messageKey = stopped > 0 ? "bot.stop_all.private_done" : "bot.stop_all.nothing";
            }
        } catch (Exception e) {
            log.error("stopAll failed fromId={} chatId={}: {}", ctx.fromId(), ctx.chatId(), e.getMessage());
            stopped = 0;
            messageKey = "bot.stop_all.nothing";
        }

        MessageSend.text(ctx.sender(), ctx.chatId(),
            messageSource.getMessage(messageKey, ctx.fromId(), stopped));
    }
}
