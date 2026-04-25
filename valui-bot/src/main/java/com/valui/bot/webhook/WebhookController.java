package com.valui.bot.webhook;

import com.valui.bot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;

/**
 * Receives Telegram webhook POST requests.
 *
 * <p>Security layers (outermost → innermost):
 * <ol>
 *   <li>{@link TelegramIpFilter} — rejects requests from non-Telegram IP ranges
 *   <li>Path token check — ensures the URL contains our bot token
 *   <li>{@code X-Telegram-Bot-Api-Secret-Token} header — validates Telegram's signature
 * </ol>
 *
 * <p>Responds with 200 immediately; processing runs in the async thread pool.
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "valui.bot.mode", havingValue = "webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final BotProperties botProperties;
    private final WebhookUpdateProcessor processor;
    private final AbsSender bot;

    @PostMapping("/webhook/{token}")
    public ResponseEntity<Void> handleUpdate(
            @PathVariable String token,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false)
            String secretTokenHeader,
            @RequestBody Update update) {

        if (!botProperties.token().equals(token)) {
            log.warn("Webhook received with wrong path token");
            return ResponseEntity.status(403).build();
        }

        String expected = botProperties.secretToken();
        if (expected != null && !expected.isBlank() && !expected.equals(secretTokenHeader)) {
            log.warn("Webhook received with wrong or missing secret token");
            return ResponseEntity.status(403).build();
        }

        log.debug("Webhook update_id={} queued for processing", update.getUpdateId());
        processor.process(update, bot);
        return ResponseEntity.ok().build();
    }
}
