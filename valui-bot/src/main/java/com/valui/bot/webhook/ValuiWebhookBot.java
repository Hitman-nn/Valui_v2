package com.valui.bot.webhook;

import com.valui.bot.config.BotProperties;
import com.valui.bot.handler.CommandRouter;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.TelegramWebhookBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Webhook-mode bot. Provides an {@link org.telegram.telegrambots.meta.bots.AbsSender} for
 * outgoing messages; incoming updates are handled by {@link WebhookController}.
 */
@Slf4j
public class ValuiWebhookBot extends TelegramWebhookBot {

    private final CommandRouter commandRouter;
    private final String botUsername;
    private final String botPath;

    public ValuiWebhookBot(CommandRouter commandRouter, BotProperties botProperties) {
        super(botProperties.token());
        this.commandRouter = commandRouter;
        this.botUsername   = botProperties.username();
        this.botPath       = botProperties.token();
    }

    @Override
    public String getBotUsername() { return botUsername; }

    @Override
    public String getBotPath() { return botPath; }

    /** Not used directly — {@link WebhookController} routes updates asynchronously. */
    @Override
    @SuppressWarnings("rawtypes")
    public BotApiMethod onWebhookUpdateReceived(Update update) {
        commandRouter.route(update, this);
        return null;
    }
}
