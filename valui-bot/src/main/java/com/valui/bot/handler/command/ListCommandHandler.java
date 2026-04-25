package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import org.springframework.stereotype.Component;

@Component
public class ListCommandHandler implements CommandHandler {

    @Override
    public String command() { return "/list"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.userInfo() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                "Сначала зарегистрируйтесь: /start");
            return;
        }
        MessageSend.text(ctx.sender(), ctx.chatId(),
            "У вас нет активных контроллеров.\n\nДобавьте первый контроллер через меню.");
    }
}
