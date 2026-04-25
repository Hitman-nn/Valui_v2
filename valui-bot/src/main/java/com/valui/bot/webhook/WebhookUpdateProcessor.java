package com.valui.bot.webhook;

import com.valui.bot.handler.CommandRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;

/**
 * Fire-and-forget dispatcher for webhook updates.
 * Runs in the async thread pool so the HTTP response returns immediately.
 */
@Component
@RequiredArgsConstructor
public class WebhookUpdateProcessor {

    private final CommandRouter commandRouter;

    @Async
    public void process(Update update, AbsSender sender) {
        commandRouter.route(update, sender);
    }
}
