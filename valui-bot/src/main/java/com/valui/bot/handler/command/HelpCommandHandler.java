package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import org.springframework.stereotype.Component;

@Component
public class HelpCommandHandler implements CommandHandler {

    private static final String HELP_TEXT = """
        Справка по командам Valui:

        /start — перезапустить бота
        /list — список активных контроллеров ставок
        /listfilter — список настроенных фильтров
        /stop — остановить все активные контроллеры
        /deleteall — удалить все контроллеры (с подтверждением)
        /help — эта справка

        По вопросам поддержки обращайтесь к администратору.
        """;

    @Override
    public String command() { return "/help"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        MessageSend.text(ctx.sender(), ctx.chatId(), HELP_TEXT);
    }
}
