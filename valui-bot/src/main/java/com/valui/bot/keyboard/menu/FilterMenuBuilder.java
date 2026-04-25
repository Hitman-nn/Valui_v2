package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.MenuMessage;

import java.util.List;

public final class FilterMenuBuilder {

    private FilterMenuBuilder() {}

    public static MenuMessage build(List<String> filters) {
        if (filters.isEmpty()) {
            return new MenuMessage(
                "🔍 Активных фильтров нет.",
                InlineKeyboardBuilder.create()
                    .backButton(CallbackData.MENU_MAIN)
                    .build()
            );
        }

        var builder = InlineKeyboardBuilder.create();
        for (int i = 0; i < filters.size(); i++) {
            builder.button("🗑 " + filters.get(i), CallbackData.filterDelete(i));
            builder.row();
        }
        builder.backButton(CallbackData.MENU_MAIN);

        String text = String.format("🔍 Фильтры (%d шт.) — нажмите для удаления:", filters.size());
        return new MenuMessage(text, builder.build());
    }
}
