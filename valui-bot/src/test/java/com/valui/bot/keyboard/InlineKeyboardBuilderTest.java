package com.valui.bot.keyboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InlineKeyboardBuilder — unit tests")
class InlineKeyboardBuilderTest {

    // ─── columns ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("columns(2): 4 buttons → 2 rows of 2")
    void columns2_fourButtons_twoRowsOfTwo() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create()
            .columns(2)
            .button("A", "A").button("B", "B")
            .button("C", "C").button("D", "D")
            .build();

        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).extracting(InlineKeyboardButton::getText).containsExactly("A", "B");
        assertThat(rows.get(1)).extracting(InlineKeyboardButton::getText).containsExactly("C", "D");
    }

    @Test
    @DisplayName("columns(2): 3 buttons → row of 2 + row of 1")
    void columns2_threeButtons_rowOf2ThenRowOf1() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create()
            .columns(2)
            .button("A", "A").button("B", "B").button("C", "C")
            .build();

        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).hasSize(2);
        assertThat(rows.get(1)).hasSize(1);
        assertThat(rows.get(1).get(0).getText()).isEqualTo("C");
    }

    @Test
    @DisplayName("columns(3): 7 buttons → 2 rows of 3 + 1 row of 1")
    void columns3_sevenButtons_threeRowGroups() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create()
            .columns(3)
            .button("1", "1").button("2", "2").button("3", "3")
            .button("4", "4").button("5", "5").button("6", "6")
            .button("7", "7")
            .build();

        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).hasSize(3);
        assertThat(rows.get(1)).hasSize(3);
        assertThat(rows.get(2)).hasSize(1);
    }

    // ─── row() ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("row(): explicit break produces correct structure")
    void row_explicitBreak() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create()
            .button("A", "A").button("B", "B")
            .row()
            .button("C", "C")
            .build();

        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).hasSize(2);
        assertThat(rows.get(1)).hasSize(1);
    }

    @Test
    @DisplayName("row(): consecutive calls do not produce empty rows")
    void row_consecutiveCalls_noEmptyRows() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create()
            .button("A", "A")
            .row()
            .row()
            .button("B", "B")
            .build();

        assertThat(kb.getKeyboard()).hasSize(2);
    }

    // ─── backButton ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("backButton(): flushes pending buttons and occupies its own row")
    void backButton_flushesAndOwnsRow() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create()
            .button("A", "A").button("B", "B")
            .backButton("MENU:MAIN")
            .build();

        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();
        assertThat(rows).hasSize(2);

        InlineKeyboardButton back = rows.get(1).get(0);
        assertThat(rows.get(1)).hasSize(1);
        assertThat(back.getText()).isEqualTo("← Назад");
        assertThat(back.getCallbackData()).isEqualTo("MENU:MAIN");
    }

    @Test
    @DisplayName("backButton() with columns(3): partial group flushed as its own row first")
    void backButton_flushesColumnsGroup() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create()
            .columns(3)
            .button("A", "A").button("B", "B")
            .backButton("BACK")
            .build();

        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).extracting(InlineKeyboardButton::getText).containsExactly("A", "B");
        assertThat(rows.get(1).get(0).getText()).isEqualTo("← Назад");
    }

    // ─── cancelButton ────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelButton(): text '✕ Отмена' with CANCEL callback, own row")
    void cancelButton_textAndCallback() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create()
            .button("X", "X")
            .cancelButton()
            .build();

        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();
        assertThat(rows).hasSize(2);

        InlineKeyboardButton cancel = rows.get(1).get(0);
        assertThat(cancel.getText()).isEqualTo("✕ Отмена");
        assertThat(cancel.getCallbackData()).isEqualTo(CallbackData.CANCEL);
    }

    // ─── urlButton ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("urlButton(): sets url field, not callbackData")
    void urlButton_setsUrl() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create()
            .urlButton("Visit", "https://example.com")
            .build();

        InlineKeyboardButton btn = kb.getKeyboard().get(0).get(0);
        assertThat(btn.getUrl()).isEqualTo("https://example.com");
        assertThat(btn.getCallbackData()).isNull();
    }

    // ─── edge cases ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("empty builder: produces empty keyboard")
    void emptyBuilder_emptyKeyboard() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create().build();
        assertThat(kb.getKeyboard()).isEmpty();
    }

    @Test
    @DisplayName("callback data is preserved exactly")
    void callbackData_preserved() {
        InlineKeyboardMarkup kb = InlineKeyboardBuilder.create()
            .button("Test", "CTRL:DELETE:some-uuid-here")
            .build();

        assertThat(kb.getKeyboard().get(0).get(0).getCallbackData())
            .isEqualTo("CTRL:DELETE:some-uuid-here");
    }
}
