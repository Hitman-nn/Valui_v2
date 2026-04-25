package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.MenuMessage;
import com.valui.user.dto.LimitInfoDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UpgradePromptBuilder — unit tests")
class UpgradePromptBuilderTest {

    // ─── text content ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("controllers limit: text contains plan name and used/max counts")
    void controllers_textContainsPlanAndCounts() {
        LimitInfoDto limits = limits(3, 3, 0, 1);

        MenuMessage msg = UpgradePromptBuilder.build(limits, "controllers");

        assertThat(msg.text()).contains("FREE", "3/3", "контроллера");
    }

    @Test
    @DisplayName("filters limit: text contains filter counts")
    void filters_textContainsFilterCounts() {
        LimitInfoDto limits = limits(0, 3, 1, 1);

        MenuMessage msg = UpgradePromptBuilder.build(limits, "filters");

        assertThat(msg.text()).contains("FREE", "1/1", "фильтров");
    }

    @Test
    @DisplayName("bookmaker limit: text contains allowed bookmakers list")
    void bookmaker_textContainsAllowedBookmakers() {
        LimitInfoDto limits = new LimitInfoDto(0, 3, 0, 1,
            List.of("XBET", "FONBET"), 120, "FREE", null);

        MenuMessage msg = UpgradePromptBuilder.build(limits, "bookmaker");

        assertThat(msg.text()).contains("FREE", "XBET", "FONBET");
    }

    @Test
    @DisplayName("unknown limitType: graceful fallback text")
    void unknownLimitType_fallbackText() {
        LimitInfoDto limits = limits(1, 5, 0, 1);

        MenuMessage msg = UpgradePromptBuilder.build(limits, "unknown");

        assertThat(msg.text()).contains("FREE");
    }

    // ─── keyboard structure ───────────────────────────────────────────────────

    @Test
    @DisplayName("keyboard: 2 rows — Plans View and Stay on current plan")
    void keyboard_hasTwoRows() {
        LimitInfoDto limits = limits(3, 3, 0, 1);

        InlineKeyboardMarkup kb = UpgradePromptBuilder.build(limits, "controllers").keyboard();

        assertThat(kb.getKeyboard()).hasSize(2);
    }

    @Test
    @DisplayName("first button: callback is PLANS_VIEW")
    void firstButton_plansViewCallback() {
        LimitInfoDto limits = limits(3, 3, 0, 1);

        InlineKeyboardButton first = UpgradePromptBuilder.build(limits, "controllers")
            .keyboard().getKeyboard().get(0).get(0);

        assertThat(first.getCallbackData()).isEqualTo(CallbackData.PLANS_VIEW);
    }

    @Test
    @DisplayName("second button: callback is MENU_MAIN")
    void secondButton_menuMainCallback() {
        LimitInfoDto limits = limits(3, 3, 0, 1);

        InlineKeyboardButton second = UpgradePromptBuilder.build(limits, "controllers")
            .keyboard().getKeyboard().get(1).get(0);

        assertThat(second.getCallbackData()).isEqualTo(CallbackData.MENU_MAIN);
    }

    @Test
    @DisplayName("second button: text shows current plan name")
    void secondButton_showsCurrentPlan() {
        LimitInfoDto limits = limits(3, 3, 0, 1);

        InlineKeyboardButton second = UpgradePromptBuilder.build(limits, "controllers")
            .keyboard().getKeyboard().get(1).get(0);

        assertThat(second.getText()).contains("FREE");
    }

    // ─── helper ──────────────────────────────────────────────────────────────

    private static LimitInfoDto limits(int ctrlUsed, int ctrlMax, int filterUsed, int filterMax) {
        return new LimitInfoDto(ctrlUsed, ctrlMax, filterUsed, filterMax,
            List.of("XBET"), 120, "FREE", null);
    }
}
