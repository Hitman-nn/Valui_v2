package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.MenuMessage;

public final class ConfirmDeleteBuilder {

    private ConfirmDeleteBuilder() {}

    public static MenuMessage build(String entityName, String confirmCallback) {
        String text = String.format(
            "⚠️ Удалить *%s*?\n\nЭто действие нельзя отменить.", entityName);

        var keyboard = InlineKeyboardBuilder.create()
            .button("✅ Да, удалить", confirmCallback)
            .button("✕ Отмена",      CallbackData.CANCEL)
            .build();

        return new MenuMessage(text, keyboard);
    }
}
