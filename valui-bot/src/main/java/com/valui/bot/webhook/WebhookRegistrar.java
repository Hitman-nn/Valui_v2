package com.valui.bot.webhook;

import com.valui.bot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.WebhookInfo;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Registers and deregisters the webhook with the Telegram Bot API on startup/shutdown.
 * Only active when {@code valui.bot.mode=webhook}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "valui.bot.mode", havingValue = "webhook")
@RequiredArgsConstructor
public class WebhookRegistrar {

    private final AbsSender bot;
    private final BotProperties botProperties;

    @PostConstruct
    public void register() {
        String url = botProperties.webhookUrl();
        if (url == null || url.isBlank()) {
            log.warn("Webhook URL is not configured — skipping setWebhook call");
            return;
        }

        try {
            WebhookInfo current = bot.execute(new GetWebhookInfo());
            if (url.equals(current.getUrl())) {
                log.info("Webhook already registered at {}", url);
                return;
            }

            SetWebhook setWebhook = SetWebhook.builder()
                .url(url)
                .secretToken(botProperties.secretToken())
                .dropPendingUpdates(false)
                .build();

            Boolean ok = bot.execute(setWebhook);
            if (Boolean.TRUE.equals(ok)) {
                log.info("Webhook registered: {}", url);
            } else {
                log.error("setWebhook returned false for URL={}", url);
            }
        } catch (TelegramApiException e) {
            log.error("Failed to register webhook at {}: {}", url, e.getMessage(), e);
        }
    }

    /** Returns current webhook info for health-check or diagnostics. */
    public WebhookInfo getWebhookInfo() {
        try {
            return bot.execute(new GetWebhookInfo());
        } catch (TelegramApiException e) {
            log.error("getWebhookInfo failed: {}", e.getMessage(), e);
            return null;
        }
    }

    @PreDestroy
    public void deregister() {
        try {
            bot.execute(new DeleteWebhook());
            log.info("Webhook deregistered");
        } catch (TelegramApiException e) {
            log.warn("Failed to deregister webhook: {}", e.getMessage());
        }
    }
}
