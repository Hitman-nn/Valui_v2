package com.valui.bot.handler;

import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Base interface for all bot update handlers.
 * Spring auto-collects all implementations and injects them into {@link CommandRouter}.
 *
 * <p>Handlers are selected by {@link CommandRouter} via {@link #canHandle(Update)}.
 * When multiple handlers match, the one with the lowest {@link #order()} wins.
 */
public interface BotUpdateHandler {

    /**
     * Returns {@code true} if this handler should process the given update.
     * Should be fast and side-effect-free.
     */
    boolean canHandle(Update update);

    /**
     * Executes the business logic for the update.
     * Implementations may update the session, send Telegram messages, call services, etc.
     */
    void handle(BotUpdateContext context);

    /**
     * Handler priority — lower value = higher priority.
     * When multiple handlers match, the one with the lowest order is selected.
     * Use order=999 for catch-all / fallback handlers.
     */
    default int order() {
        return 100;
    }
}
