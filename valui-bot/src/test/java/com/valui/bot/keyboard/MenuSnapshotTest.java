package com.valui.bot.keyboard;

import com.valui.bot.keyboard.dto.ControllerDto;
import com.valui.bot.keyboard.menu.BookmakerSelectBuilder;
import com.valui.bot.keyboard.menu.ConfirmDeleteBuilder;
import com.valui.bot.keyboard.menu.ControllerMenuBuilder;
import com.valui.bot.keyboard.menu.FilterMenuBuilder;
import com.valui.bot.keyboard.menu.MainMenuBuilder;
import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.user.dto.LimitInfoDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Standard menu builders — structure tests")
class MenuSnapshotTest {

    // ─── MainMenuBuilder ─────────────────────────────────────────────────────

    @Test
    @DisplayName("MainMenuBuilder: text contains plan info; keyboard has 3 rows")
    void mainMenu_structure() {
        var limits = new LimitInfoDto(2, 5, 1, 3,
            List.of("XBET", "FONBET"), 60, "PRO",
            OffsetDateTime.now().plusDays(30));

        MenuMessage menu = MainMenuBuilder.build(limits);

        assertThat(menu.text()).contains("PRO", "2 / 5", "1 / 3", "60 сек");

        InlineKeyboardMarkup kb = menu.keyboard();
        assertThat(kb.getKeyboard()).hasSize(3);
        assertThat(kb.getKeyboard().get(0).get(0).getCallbackData()).isEqualTo(CallbackData.CTRL_LIST);
        assertThat(kb.getKeyboard().get(1).get(0).getCallbackData()).isEqualTo(CallbackData.FILTER_LIST);
        assertThat(kb.getKeyboard().get(2)).hasSize(2); // subscription + help
    }

    @Test
    @DisplayName("MainMenuBuilder: FREE plan (no expiry) — no expiry line in text")
    void mainMenu_freePlan_noExpiryLine() {
        var limits = new LimitInfoDto(0, 1, 0, 1,
            List.of("XBET"), 120, "FREE", null);

        assertThat(MainMenuBuilder.build(limits).text()).doesNotContain("Действует до");
    }

    @Test
    @DisplayName("MainMenuBuilder: with expiry — text contains expiry date")
    void mainMenu_withExpiry_containsDate() {
        var limits = new LimitInfoDto(1, 5, 0, 3,
            List.of(), 60, "PRO",
            OffsetDateTime.parse("2026-12-31T00:00:00+03:00"));

        assertThat(MainMenuBuilder.build(limits).text()).contains("31.12.2026");
    }

    // ─── ControllerMenuBuilder ───────────────────────────────────────────────

    @Test
    @DisplayName("ControllerMenuBuilder: empty list → back button only")
    void controllerMenu_empty_backButtonOnly() {
        MenuMessage menu = ControllerMenuBuilder.build(List.of(), 0);

        assertThat(menu.text()).contains("пуст");
        assertThat(menu.keyboard().getKeyboard()).hasSize(1);
        assertThat(menu.keyboard().getKeyboard().get(0).get(0).getCallbackData())
            .isEqualTo(CallbackData.MENU_MAIN);
    }

    @Test
    @DisplayName("ControllerMenuBuilder: 2 controllers single page → items + back (no nav)")
    void controllerMenu_twoItems_noNav() {
        List<ControllerDto> controllers = List.of(
            new ControllerDto(UUID.randomUUID(), "Live Xbet",  BookmakerType.XBET,   ControllerType.MATCH,      true),
            new ControllerDto(UUID.randomUUID(), "Fonbet Cup", BookmakerType.FONBET, ControllerType.TOURNAMENT, false)
        );

        MenuMessage menu = ControllerMenuBuilder.build(controllers, 0);

        InlineKeyboardMarkup kb = menu.keyboard();
        // 2 items + no nav (single page) + 1 back = 3 rows
        assertThat(kb.getKeyboard()).hasSize(3);
        assertThat(kb.getKeyboard().get(0).get(0).getText()).contains("🟢");
        assertThat(kb.getKeyboard().get(1).get(0).getText()).contains("🔴");
        assertThat(kb.getKeyboard().get(2).get(0).getText()).isEqualTo("← Назад");
        assertThat(kb.getKeyboard().get(2).get(0).getCallbackData()).isEqualTo(CallbackData.MENU_MAIN);
    }

    @Test
    @DisplayName("ControllerMenuBuilder: 7 controllers paged → nav row present")
    void controllerMenu_multiPage_hasNavRow() {
        List<ControllerDto> controllers = java.util.stream.IntStream.rangeClosed(1, 7)
            .mapToObj(i -> new ControllerDto(
                UUID.randomUUID(), "Ctrl " + i, BookmakerType.XBET, ControllerType.MATCH, true))
            .toList();

        MenuMessage menu = ControllerMenuBuilder.build(controllers, 0);

        InlineKeyboardMarkup kb = menu.keyboard();
        // page 0: 6 items + 1 nav + 1 back = 8 rows
        assertThat(kb.getKeyboard()).hasSize(8);
        // nav row is index 6, back row is index 7
        List<InlineKeyboardButton> nav = kb.getKeyboard().get(6);
        assertThat(nav.get(0).getText()).isEqualTo("1/2"); // counter
        assertThat(nav.get(1).getText()).isEqualTo("›");
        assertThat(nav.get(1).getCallbackData()).isEqualTo(ControllerMenuBuilder.NAV_PREFIX + ":PAGE:1");
    }

    // ─── FilterMenuBuilder ───────────────────────────────────────────────────

    @Test
    @DisplayName("FilterMenuBuilder: empty list → back button only")
    void filterMenu_empty_backOnly() {
        MenuMessage menu = FilterMenuBuilder.build(List.of());

        assertThat(menu.text()).contains("нет");
        assertThat(menu.keyboard().getKeyboard()).hasSize(1);
    }

    @Test
    @DisplayName("FilterMenuBuilder: 3 filters → one row per filter + back")
    void filterMenu_threeFilters_rowPerFilter() {
        List<String> filters = List.of("kf > 1.5", "kf < 3.0", "home_only");
        MenuMessage menu = FilterMenuBuilder.build(filters);

        InlineKeyboardMarkup kb = menu.keyboard();
        // 3 filter rows + 1 back row
        assertThat(kb.getKeyboard()).hasSize(4);
        assertThat(kb.getKeyboard().get(0).get(0).getCallbackData()).isEqualTo(CallbackData.filterDelete(0));
        assertThat(kb.getKeyboard().get(2).get(0).getCallbackData()).isEqualTo(CallbackData.filterDelete(2));
        assertThat(kb.getKeyboard().get(3).get(0).getCallbackData()).isEqualTo(CallbackData.MENU_MAIN);
    }

    // ─── BookmakerSelectBuilder ───────────────────────────────────────────────

    @Test
    @DisplayName("BookmakerSelectBuilder: 3 bookmakers columns(2) → [2][1][cancel]")
    void bookmakerMenu_columnsLayout() {
        List<String> bookmakers = List.of("XBET", "FONBET", "OLIMP");
        MenuMessage menu = BookmakerSelectBuilder.build(bookmakers);

        InlineKeyboardMarkup kb = menu.keyboard();
        assertThat(kb.getKeyboard()).hasSize(3);
        assertThat(kb.getKeyboard().get(0)).hasSize(2);
        assertThat(kb.getKeyboard().get(0).get(0).getCallbackData())
            .isEqualTo(CallbackData.bookmakerSelect("XBET"));
        assertThat(kb.getKeyboard().get(1)).hasSize(1);
        assertThat(kb.getKeyboard().get(1).get(0).getCallbackData())
            .isEqualTo(CallbackData.bookmakerSelect("OLIMP"));
        assertThat(kb.getKeyboard().get(2).get(0).getText()).isEqualTo("✕ Отмена");
    }

    @Test
    @DisplayName("BookmakerSelectBuilder: even count fills complete rows before cancel")
    void bookmakerMenu_evenCount() {
        List<String> bookmakers = List.of("A", "B", "C", "D");
        MenuMessage menu = BookmakerSelectBuilder.build(bookmakers);

        InlineKeyboardMarkup kb = menu.keyboard();
        // [A,B] [C,D] [cancel] = 3 rows
        assertThat(kb.getKeyboard()).hasSize(3);
        assertThat(kb.getKeyboard().get(0)).hasSize(2);
        assertThat(kb.getKeyboard().get(1)).hasSize(2);
    }

    // ─── ConfirmDeleteBuilder ─────────────────────────────────────────────────

    @Test
    @DisplayName("ConfirmDeleteBuilder: one row with confirm + cancel buttons")
    void confirmDelete_structure() {
        MenuMessage menu = ConfirmDeleteBuilder.build("Controller X", "CTRL:DELETE:abc");

        assertThat(menu.text()).contains("Controller X");

        InlineKeyboardMarkup kb = menu.keyboard();
        assertThat(kb.getKeyboard()).hasSize(1);

        List<InlineKeyboardButton> buttons = kb.getKeyboard().get(0);
        assertThat(buttons).hasSize(2);
        assertThat(buttons.get(0).getCallbackData()).isEqualTo("CTRL:DELETE:abc");
        assertThat(buttons.get(1).getCallbackData()).isEqualTo(CallbackData.CANCEL);
        assertThat(buttons.get(1).getText()).isEqualTo("✕ Отмена");
    }
}
