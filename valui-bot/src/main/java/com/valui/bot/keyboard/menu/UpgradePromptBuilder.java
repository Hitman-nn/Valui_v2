package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.MenuMessage;
import com.valui.user.dto.LimitInfoDto;

public final class UpgradePromptBuilder {

    private UpgradePromptBuilder() {}

    /**
     * Builds an upgrade prompt shown when the user hits a plan limit.
     *
     * @param limits    current usage snapshot for the user
     * @param limitType one of "controllers", "filters", "bookmaker"
     */
    public static MenuMessage build(LimitInfoDto limits, String limitType) {
        String limitDesc = describeLimit(limits, limitType);
        String text = String.format(
            "⛔ %s\n\nПерейдите на PRO или PREMIUM для расширения возможностей.", limitDesc);

        var keyboard = InlineKeyboardBuilder.create()
            .button("📋 Посмотреть планы", CallbackData.PLANS_VIEW)
            .row()
            .button("Остаться на " + limits.planName(), CallbackData.MENU_MAIN)
            .build();

        return new MenuMessage(text, keyboard);
    }

    private static String describeLimit(LimitInfoDto limits, String limitType) {
        return String.format(
            "Недостаточно токенов для операции в плане %s. Баланс: %d токенов.",
            limits.planName(), limits.tokenBalance());
    }
}
