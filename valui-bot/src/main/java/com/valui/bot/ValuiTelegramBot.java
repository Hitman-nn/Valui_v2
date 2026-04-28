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

    // Proxy may not be ready at the exact moment the bot registers.
    // Retry a few times with backoff before giving up so that a transient
    // startup delay doesn't permanently break long-polling delivery.
    @Override
    public void clearWebhook() {
        int[] delaysMs = {0, 3_000, 7_000, 15_000};
        for (int i = 0; i < delaysMs.length; i++) {
            if (delaysMs[i] > 0) {
                try { Thread.sleep(delaysMs[i]); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                super.clearWebhook();
                if (i > 0) log.info("Telegram webhook cleared on attempt {}", i + 1);
                return;
            } catch (TelegramApiRequestException e) {
                if (i < delaysMs.length - 1) {
                    log.warn("Webhook clear attempt {} failed ({}), retrying in {} ms…",
                            i + 1, e.getMessage(), delaysMs[i + 1]);
                } else {
                    log.error("Could not clear Telegram webhook after {} attempts — " +
                            "long-polling may not receive updates if a webhook was set. " +
                            "Run /deleteWebhook via Bot API to fix manually.", delaysMs.length);
                }
            }
        }
    }
}
