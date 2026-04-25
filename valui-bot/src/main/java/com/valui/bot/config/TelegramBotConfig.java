package com.valui.bot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.LongPollingBot;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.List;

@Slf4j
@Configuration
public class TelegramBotConfig {

    /**
     * Replaces what telegrambots-spring-boot-starter auto-configured.
     * Spring injects all LongPollingBot beans; empty list is fine at startup
     * while bot handlers are not yet implemented.
     */
    @Bean
    public TelegramBotsApi telegramBotsApi(List<LongPollingBot> bots) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        for (LongPollingBot bot : bots) {
            api.registerBot(bot);
            log.info("Registered Telegram bot: {}", bot.getBotUsername());
        }
        if (bots.isEmpty()) {
            log.warn("TelegramBotsApi started with no registered bots");
        }
        return api;
    }
}
