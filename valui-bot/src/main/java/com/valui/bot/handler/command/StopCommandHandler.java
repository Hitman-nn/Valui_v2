package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.service.BotSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StopCommandHandler implements CommandHandler {

    private final BotSessionService sessionService;

    @Override
    public String command() { return "/stop"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        sessionService.clearSession(ctx.chatId());
        MessageSend.text(ctx.sender(), ctx.chatId(),
            "Мониторинг остановлен. Все активные операции отменены.\n\n" +
            "Используйте /list чтобы управлять контроллерами.");
    }
}
