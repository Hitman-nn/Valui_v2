package com.valui.bot.handler;

import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Specialization of {@link BotUpdateHandler} for text commands (e.g. /start, /help).
 * {@link #canHandle} is auto-implemented: matches if the message text starts with {@link #command()}.
 */
public interface CommandHandler extends BotUpdateHandler {

    /** The command string this handler responds to, e.g. {@code "/start"}, {@code "/help"}. */
    String command();

    @Override
    default boolean canHandle(Update update) {
        if (!update.hasMessage()) return false;
        String text = update.getMessage().getText();
        return text != null && text.startsWith(command());
    }
}
