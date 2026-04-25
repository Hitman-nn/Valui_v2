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
        return switch (limitType) {
            case "controllers" -> String.format(
                "Достигнут лимит плана %s (%d/%d контроллера)",
                limits.planName(), limits.controllersUsed(), limits.controllersMax());
            case "filters" -> String.format(
                "Достигнут лимит фильтров плана %s (%d/%d фильтров)",
                limits.planName(), limits.filtersUsed(), limits.filtersMax());
            case "bookmaker" -> String.format(
                "Букмекер недоступен в плане %s. Доступные: %s",
                limits.planName(), String.join(", ", limits.allowedBookmakers()));
            default -> "Достигнут лимит плана " + limits.planName();
        };
    }
}
