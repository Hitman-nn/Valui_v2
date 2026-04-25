package com.valui.bot.config;

import com.valui.bot.ValuiTelegramBot;
import com.valui.bot.handler.CommandRouter;
import com.valui.bot.webhook.ValuiWebhookBot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.LongPollingBot;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.List;

@Slf4j
@Configuration
@EnableConfigurationProperties(BotProperties.class)
public class TelegramBotConfig {

    // ─── Long-polling mode ────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "valui.bot.mode", havingValue = "long_polling", matchIfMissing = true)
    public ValuiTelegramBot valuiTelegramBot(CommandRouter commandRouter, BotProperties botProperties) {
        return new ValuiTelegramBot(commandRouter, botProperties);
    }

    /**
     * Registers all LongPollingBot beans with the Telegram API.
     * Only active in long-polling mode.
     */
    @Bean
    @ConditionalOnProperty(name = "valui.bot.mode", havingValue = "long_polling", matchIfMissing = true)
    public TelegramBotsApi telegramBotsApi(List<LongPollingBot> bots) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        for (LongPollingBot bot : bots) {
            api.registerBot(bot);
            log.info("Registered Telegram bot (long-polling): {}", bot.getBotUsername());
        }
        if (bots.isEmpty()) {
            log.warn("TelegramBotsApi started with no registered bots");
        }
        return api;
    }

    // ─── Webhook mode ─────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "valui.bot.mode", havingValue = "webhook")
    public ValuiWebhookBot valuiWebhookBot(CommandRouter commandRouter, BotProperties botProperties) {
        return new ValuiWebhookBot(commandRouter, botProperties);
    }
}
