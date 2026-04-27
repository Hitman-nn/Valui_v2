package com.valui.bot;

import com.valui.bot.config.BotProperties;
import com.valui.bot.handler.CommandRouter;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

@Slf4j
public class ValuiTelegramBot extends TelegramLongPollingBot {

    private final CommandRouter commandRouter;
    private final String botUsername;

    public ValuiTelegramBot(CommandRouter commandRouter, BotProperties botProperties, DefaultBotOptions options) {
        super(options, botProperties.token());
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

    // Telegram API may be unreachable at startup (VPN, network issues).
    // Swallow the error so the Spring context starts — DefaultBotSession will
    // reconnect on its own once the network is available.
    @Override
    public void clearWebhook() {
        try {
            super.clearWebhook();
        } catch (TelegramApiRequestException e) {
            log.warn("Could not clear Telegram webhook at startup (API unreachable): {}", e.getMessage());
        }
    }
}
