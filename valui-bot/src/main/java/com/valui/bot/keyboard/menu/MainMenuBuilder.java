package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.MenuMessage;
import com.valui.user.dto.LimitInfoDto;

public final class MainMenuBuilder {

    private MainMenuBuilder() {}

    public static MenuMessage build(LimitInfoDto limits) {
        String text = String.format(
            "🏠 Главное меню\n\n" +
            "📋 Контроллеров: %d\n" +
            "🪙 Токены: %d (грант/месяц: %d)",
            limits.controllersUsed(),
            limits.tokenBalance(),
            limits.monthlyTokenGrant()
        );

        var keyboard = InlineKeyboardBuilder.create()
            .button("📋 Контроллеры (" + limits.controllersUsed() + ")", CallbackData.CTRL_LIST)
            .row()
            .button("🔍 Фильтры", CallbackData.FILTER_LIST)
            .row()
            .button("💸 Ставки", CallbackData.BET_MENU)
            .build();

        return new MenuMessage(text, keyboard);
    }
}
