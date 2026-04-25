package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.MenuMessage;
import com.valui.user.dto.LimitInfoDto;

import java.time.format.DateTimeFormatter;

public final class MainMenuBuilder {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private MainMenuBuilder() {}

    public static MenuMessage build(LimitInfoDto limits) {
        String expiryLine = limits.expiresAt() != null
            ? "\n⏳ Действует до: " + limits.expiresAt().format(DATE_FMT)
            : "";

        String text = String.format(
            "🏠 Главное меню\n\n" +
            "📦 Тариф: %s%s\n" +
            "📋 Контроллеры: %d / %d\n" +
            "🔍 Фильтры: %d / %d\n" +
            "⏱ Интервал: %d сек",
            limits.planName(), expiryLine,
            limits.controllersUsed(), limits.controllersMax(),
            limits.filtersUsed(), limits.filtersMax(),
            limits.pollIntervalSec()
        );

        var keyboard = InlineKeyboardBuilder.create()
            .button("📋 Контроллеры (" + limits.controllersUsed() + "/" + limits.controllersMax() + ")",
                CallbackData.CTRL_LIST)
            .row()
            .button("🔍 Фильтры (" + limits.filtersUsed() + "/" + limits.filtersMax() + ")",
                CallbackData.FILTER_LIST)
            .row()
            .button("💳 Подписка", CallbackData.MENU_SUBSCRIPTION)
            .button("❓ Помощь",   CallbackData.MENU_HELP)
            .build();

        return new MenuMessage(text, keyboard);
    }
}
