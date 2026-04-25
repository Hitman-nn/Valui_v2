package com.valui.bot;

import com.valui.bot.config.BotProperties;
import com.valui.bot.handler.CommandRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

@Slf4j
@Component
public class ValuiTelegramBot extends TelegramLongPollingBot {

    private final CommandRouter commandRouter;
    private final String botUsername;

    public ValuiTelegramBot(CommandRouter commandRouter, BotProperties botProperties) {
        super(botProperties.token());
        this.commandRouter = commandRouter;
        this.botUsername = botProperties.username();
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        commandRouter.route(update, this);
    }
}
