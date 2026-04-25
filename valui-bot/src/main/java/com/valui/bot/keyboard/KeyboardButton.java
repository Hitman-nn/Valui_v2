package com.valui.bot.keyboard;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

/**
 * Represents one inline keyboard button before it is turned into a Telegram API object.
 * Exactly one of {@code callbackData} or {@code url} must be non-null.
 */
public record KeyboardButton(String text, String callbackData, String url) {

    public static KeyboardButton callback(String text, String callbackData) {
        return new KeyboardButton(text, callbackData, null);
    }

    public static KeyboardButton url(String text, String url) {
        return new KeyboardButton(text, null, url);
    }

    boolean isUrl() {
        return url != null;
    }

    InlineKeyboardButton toInline() {
        if (isUrl()) {
            return InlineKeyboardButton.builder().text(text).url(url).build();
        }
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }
}
