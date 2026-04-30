package com.valui.bot.keyboard;

import com.valui.bot.keyboard.menu.BookmakerMenuBuilder;
import com.valui.bot.keyboard.menu.BookmakerSelectBuilder;
import com.valui.bot.keyboard.menu.ConfirmDeleteBuilder;
import com.valui.bot.keyboard.menu.ControllerMenuBuilder;
import com.valui.bot.keyboard.menu.FilterMenuBuilder;
import com.valui.bot.keyboard.menu.MainMenuBuilder;
import com.valui.common.domain.ControllerType;
import com.valui.common.entity.GlobalFilterEntity;
import com.valui.common.entity.UserEntity;
import com.valui.monitor.dto.ControllerDto;
import com.valui.user.dto.LimitInfoDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Instant;
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
            OffsetDateTime.now().plusDays(30), 0, 200, 10);

        MenuMessage menu = MainMenuBuilder.build(limits);

        assertThat(menu.text()).contains("PRO", "2 / 5", "1 / 3", "60 сек");

        InlineKeyboardMarkup kb = menu.keyboard();
        assertThat(kb.getKeyboard()).hasSize(3);
        assertThat(kb.getKeyboard().get(0).get(0).getCallbackData()).isEqualTo(CallbackData.CTRL_LIST);
        assertThat(kb.getKeyboard().get(1).get(0).getCallbackData()).isEqualTo(CallbackData.FILTER_LIST);
        assertThat(kb.getKeyboard().get(2)).hasSize(2);
    }

    @Test
    @DisplayName("MainMenuBuilder: FREE plan (no expiry) — no expiry line in text")
    void mainMenu_freePlan_noExpiryLine() {
        var limits = new LimitInfoDto(0, 1, 0, 1,
            List.of("XBET"), 120, "FREE", null, 0, 0, 0);
        assertThat(MainMenuBuilder.build(limits).text()).doesNotContain("Действует до");
    }

    @Test
    @DisplayName("MainMenuBuilder: with expiry — text contains expiry date")
    void mainMenu_withExpiry_containsDate() {
        var limits = new LimitInfoDto(1, 5, 0, 3,
            List.of(), 60, "PRO",
            OffsetDateTime.parse("2026-12-31T00:00:00+03:00"), 0, 200, 10);
        assertThat(MainMenuBuilder.build(limits).text()).contains("31.12.2026");
    }

    // ─── BookmakerMenuBuilder ────────────────────────────────────────────────

    @Test
    @DisplayName("BookmakerMenuBuilder.buildSelection: 2 bookmakers → 2 BK buttons")
    void bookmakerMenuBuilder_selection_twoBookmakers() {
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID(), id3 = UUID.randomUUID();
        List<ControllerDto> all = List.of(
            ctrl(id1, "ЛЧ",   "XBET",    ControllerType.TOURNAMENT, true,  false),
            ctrl(id2, "АПЛ",  "XBET",    ControllerType.TOURNAMENT, true,  true),
            ctrl(id3, "NHL",   "BETBOOM", ControllerType.SPORT,      true,  false)
        );
        MenuMessage menu = BookmakerMenuBuilder.buildSelection(all);
        InlineKeyboardMarkup kb = menu.keyboard();
        // 2 BK buttons
        assertThat(kb.getKeyboard()).hasSize(2);
        assertThat(kb.getKeyboard().get(0).get(0).getText()).isEqualTo("XBET (2)");
        assertThat(kb.getKeyboard().get(0).get(0).getCallbackData())
            .isEqualTo(CallbackData.ctrlByBookmaker("XBET"));
        assertThat(kb.getKeyboard().get(1).get(0).getText()).isEqualTo("BETBOOM (1)");
    }

    @Test
    @DisplayName("BookmakerMenuBuilder.buildControllerList: items without [BK] + Back button")
    void bookmakerMenuBuilder_controllerList_noBookmakerSuffix() {
        List<ControllerDto> controllers = List.of(
            ctrl(UUID.randomUUID(), "Лига чемпионов", "XBET", ControllerType.TOURNAMENT, true,  false),
            ctrl(UUID.randomUUID(), "АПЛ",            "XBET", ControllerType.TOURNAMENT, true,  true)
        );
        MenuMessage menu = BookmakerMenuBuilder.buildControllerList("XBET", controllers, 0, 30);
        InlineKeyboardMarkup kb = menu.keyboard();
        // 2 items + back = 3 rows
        assertThat(kb.getKeyboard()).hasSize(3);
        // Labels have no [XBET] suffix
        String label0 = kb.getKeyboard().get(0).get(0).getText();
        assertThat(label0).doesNotContain("[XBET]");
        assertThat(label0).contains("Лига чемпионов");
        assertThat(label0).startsWith("🟢");
        assertThat(kb.getKeyboard().get(1).get(0).getText()).startsWith("🔕");
        // Back button
        assertThat(kb.getKeyboard().get(2).get(0).getCallbackData())
            .isEqualTo(CallbackData.CTRL_BK_LIST);
    }

    // ─── ControllerMenuBuilder ───────────────────────────────────────────────

    private static ControllerDto ctrl(UUID id, String title, String bookmaker,
                                      ControllerType type, boolean active, boolean muted) {
        return new ControllerDto(id, bookmaker, "https://example.com", title,
                null, muted, active, Instant.now(), null, 0, type, null, null);
    }

    @Test
    @DisplayName("ControllerMenuBuilder: empty list → text only, empty keyboard")
    void controllerMenu_empty_noButtons() {
        MenuMessage menu = ControllerMenuBuilder.build(List.of(), 0, 30);
        assertThat(menu.text()).contains("пуст");
        assertThat(menu.keyboard().getKeyboard()).isEmpty();
    }

    @Test
    @DisplayName("ControllerMenuBuilder: active + muted controllers show correct icons (no Back row)")
    void controllerMenu_icons() {
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID(), id3 = UUID.randomUUID();
        List<ControllerDto> controllers = List.of(
            ctrl(id1, "Active", "XBET",   ControllerType.TOURNAMENT, true,  false),
            ctrl(id2, "Muted",  "FONBET", ControllerType.TOURNAMENT, true,  true),
            ctrl(id3, "Stopped","OLIMP",  ControllerType.TOURNAMENT, false, false)
        );

        MenuMessage menu = ControllerMenuBuilder.build(controllers, 0, 30);
        InlineKeyboardMarkup kb = menu.keyboard();
        assertThat(kb.getKeyboard().get(0).get(0).getText()).startsWith("🟢");
        assertThat(kb.getKeyboard().get(1).get(0).getText()).startsWith("🔕");
        assertThat(kb.getKeyboard().get(2).get(0).getText()).startsWith("🔴");
        // 3 items only — no Back row
        assertThat(kb.getKeyboard()).hasSize(3);
    }

    @Test
    @DisplayName("ControllerMenuBuilder: 7 controllers paged → nav row, no Back row")
    void controllerMenu_multiPage_hasNavRow() {
        List<ControllerDto> controllers = java.util.stream.IntStream.rangeClosed(1, 7)
            .mapToObj(i -> ctrl(UUID.randomUUID(), "Ctrl " + i, "XBET",
                    ControllerType.MATCH, true, false))
            .toList();

        MenuMessage menu = ControllerMenuBuilder.build(controllers, 0, 30);
        InlineKeyboardMarkup kb = menu.keyboard();
        // 6 items + 1 nav = 7 rows (no Back)
        assertThat(kb.getKeyboard()).hasSize(7);
        List<InlineKeyboardButton> nav = kb.getKeyboard().get(6);
        assertThat(nav.get(0).getText()).isEqualTo("1/2");
        assertThat(nav.get(1).getText()).isEqualTo("›");
        assertThat(nav.get(1).getCallbackData()).isEqualTo(ControllerMenuBuilder.NAV_PREFIX + ":PAGE:1");
    }

    // ─── FilterMenuBuilder ───────────────────────────────────────────────────

    @Test
    @DisplayName("FilterMenuBuilder: empty list → Add button only (1 row)")
    void filterMenu_empty_addOnly() {
        MenuMessage menu = FilterMenuBuilder.build(List.of());
        assertThat(menu.text()).contains("нет");
        InlineKeyboardMarkup kb = menu.keyboard();
        assertThat(kb.getKeyboard()).hasSize(1);
        assertThat(kb.getKeyboard().get(0).get(0).getCallbackData()).isEqualTo(CallbackData.FILTER_ADD);
    }

    @Test
    @DisplayName("FilterMenuBuilder: 2 filters → 2×[delete,edit] rows + Add (3 rows, no Back)")
    void filterMenu_twoFilters_deleteAndEdit() {
        UserEntity user = new UserEntity();
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID();
        List<GlobalFilterEntity> filters = List.of(
            GlobalFilterEntity.builder().id(id1).user(user).filterRule("kf > 1.5").createdAt(OffsetDateTime.now()).build(),
            GlobalFilterEntity.builder().id(id2).user(user).filterRule("home").createdAt(OffsetDateTime.now()).build()
        );
        MenuMessage menu = FilterMenuBuilder.build(filters);
        InlineKeyboardMarkup kb = menu.keyboard();
        // 2 filter rows + 1 add row = 3 (no Back)
        assertThat(kb.getKeyboard()).hasSize(3);
        assertThat(kb.getKeyboard().get(0)).hasSize(2);
        assertThat(kb.getKeyboard().get(0).get(0).getCallbackData())
            .isEqualTo(CallbackData.filterDelete(id1));
        assertThat(kb.getKeyboard().get(0).get(1).getCallbackData())
            .isEqualTo(CallbackData.filterEdit(id1));
        assertThat(kb.getKeyboard().get(2).get(0).getCallbackData()).isEqualTo(CallbackData.FILTER_ADD);
    }

    // ─── BookmakerSelectBuilder ───────────────────────────────────────────────

    @Test
    @DisplayName("BookmakerSelectBuilder: 3 bookmakers columns(2) → [2][1] (no cancel)")
    void bookmakerMenu_columnsLayout() {
        List<String> bookmakers = List.of("XBET", "FONBET", "OLIMP");
        MenuMessage menu = BookmakerSelectBuilder.build(bookmakers);
        InlineKeyboardMarkup kb = menu.keyboard();
        assertThat(kb.getKeyboard()).hasSize(2);
        assertThat(kb.getKeyboard().get(0)).hasSize(2);
        assertThat(kb.getKeyboard().get(0).get(0).getCallbackData())
            .isEqualTo(CallbackData.bookmakerSelect("XBET"));
        assertThat(kb.getKeyboard().get(1)).hasSize(1);
        assertThat(kb.getKeyboard().get(1).get(0).getCallbackData())
            .isEqualTo(CallbackData.bookmakerSelect("OLIMP"));
    }

    @Test
    @DisplayName("BookmakerSelectBuilder: even count fills complete rows (no cancel)")
    void bookmakerMenu_evenCount() {
        List<String> bookmakers = List.of("A", "B", "C", "D");
        MenuMessage menu = BookmakerSelectBuilder.build(bookmakers);
        InlineKeyboardMarkup kb = menu.keyboard();
        assertThat(kb.getKeyboard()).hasSize(2);
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
