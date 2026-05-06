package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.MenuMessage;
import com.valui.common.entity.GlobalFilterEntity;

import java.util.List;

public final class FilterMenuBuilder {

    private FilterMenuBuilder() {}

    public static MenuMessage build(List<GlobalFilterEntity> filters) {
        var builder = InlineKeyboardBuilder.create();

        for (GlobalFilterEntity f : filters) {
            String display = FilterWordBuilder.regexToDisplay(f.getFilterRule());
            builder.button("🚫 " + display, CallbackData.NOOP);
            builder.button("✏️", CallbackData.filterEdit(f.getId()));
            builder.button("🗑", CallbackData.filterDelete(f.getId()));
            builder.row();
        }

        builder.button("➕ Добавить", CallbackData.FILTER_ADD);
        builder.row();

        String text = filters.isEmpty()
                ? "🔍 Глобальных фильтров нет.\n\nНажмите «Добавить» чтобы задать фильтр, который будет применяться ко всем контроллерам."
                : String.format("🔍 Глобальные фильтры (%d) — 🗑 удалить / ✏️ редактировать:", filters.size());

        return new MenuMessage(text, builder.build());
    }
}
