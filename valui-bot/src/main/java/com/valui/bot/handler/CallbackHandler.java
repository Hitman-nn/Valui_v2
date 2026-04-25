package com.valui.bot.handler;

import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Specialization of {@link BotUpdateHandler} for InlineKeyboard callback queries.
 * {@link #canHandle} is auto-implemented: matches if callback_data starts with {@link #callbackPrefix()}.
 */
public interface CallbackHandler extends BotUpdateHandler {

    /**
     * The prefix of the callback data this handler responds to.
     * Example: {@code "MENU_FILTER"}, {@code "ADD_CONTROLLER"}, {@code "BOOKMAKER_SELECT"}.
     */
    String callbackPrefix();

    @Override
    default boolean canHandle(Update update) {
        if (!update.hasCallbackQuery()) return false;
        String data = update.getCallbackQuery().getData();
        return data != null && data.startsWith(callbackPrefix());
    }
}
