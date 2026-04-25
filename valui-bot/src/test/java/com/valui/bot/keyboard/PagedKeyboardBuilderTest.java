package com.valui.bot.keyboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PagedKeyboardBuilder — unit tests")
class PagedKeyboardBuilderTest {

    /** 10 items: "Item 1" … "Item 10" */
    private static final List<String> ITEMS_10 =
        IntStream.rangeClosed(1, 10).mapToObj(i -> "Item " + i).toList();

    private static PagedKeyboardBuilder<String> builderFor10(int page) {
        return PagedKeyboardBuilder.<String>create()
            .items(ITEMS_10)
            .itemRenderer(s -> KeyboardButton.callback(s, "CB:" + s))
            .pageSize(4)
            .currentPage(page)
            .navigationCallbackPrefix("TEST");
    }

    // ─── navigation buttons ──────────────────────────────────────────────────

    @Test
    @DisplayName("first page: counter + next button only (no prev)")
    void firstPage_hasNextOnly() {
        InlineKeyboardMarkup kb = builderFor10(0).build();
        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();

        // 4 items + 1 nav row
        assertThat(rows).hasSize(5);

        List<InlineKeyboardButton> nav = rows.get(4);
        assertThat(nav).hasSize(2);
        assertThat(nav.get(0).getText()).isEqualTo("1/3");
        assertThat(nav.get(0).getCallbackData()).isEqualTo(CallbackData.NOOP);
        assertThat(nav.get(1).getText()).isEqualTo("›");
        assertThat(nav.get(1).getCallbackData()).isEqualTo("TEST:PAGE:1");
    }

    @Test
    @DisplayName("last page: prev button + counter only (no next)")
    void lastPage_hasPrevOnly() {
        InlineKeyboardMarkup kb = builderFor10(2).build();  // page 2 = last of 3
        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();

        // items 8,9,10 → 10 – 8 = 2 items + 1 nav row
        assertThat(rows).hasSize(3);

        List<InlineKeyboardButton> nav = rows.get(2);
        assertThat(nav).hasSize(2);
        assertThat(nav.get(0).getText()).isEqualTo("‹");
        assertThat(nav.get(0).getCallbackData()).isEqualTo("TEST:PAGE:1");
        assertThat(nav.get(1).getText()).isEqualTo("3/3");
    }

    @Test
    @DisplayName("middle page: prev + counter + next buttons")
    void middlePage_hasBothNavButtons() {
        InlineKeyboardMarkup kb = builderFor10(1).build();
        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();

        // 4 items + 1 nav row
        assertThat(rows).hasSize(5);

        List<InlineKeyboardButton> nav = rows.get(4);
        assertThat(nav).hasSize(3);
        assertThat(nav.get(0).getText()).isEqualTo("‹");
        assertThat(nav.get(1).getText()).isEqualTo("2/3");
        assertThat(nav.get(2).getText()).isEqualTo("›");
        assertThat(nav.get(2).getCallbackData()).isEqualTo("TEST:PAGE:2");
    }

    // ─── navigation callback format ──────────────────────────────────────────

    @Test
    @DisplayName("prev callback: '{prefix}:PAGE:{page-1}'")
    void prevCallback_format() {
        InlineKeyboardMarkup kb = PagedKeyboardBuilder.<String>create()
            .items(ITEMS_10)
            .itemRenderer(s -> KeyboardButton.callback(s, s))
            .pageSize(3)
            .currentPage(2)
            .navigationCallbackPrefix("CTRL:LIST")
            .build();

        InlineKeyboardButton prevBtn = kb.getKeyboard().get(3).get(0); // nav row, first btn
        assertThat(prevBtn.getCallbackData()).isEqualTo("CTRL:LIST:PAGE:1");
    }

    // ─── single page ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("single page: no navigation row rendered")
    void singlePage_noNavigationRow() {
        InlineKeyboardMarkup kb = PagedKeyboardBuilder.<String>create()
            .items(List.of("A", "B", "C"))
            .itemRenderer(s -> KeyboardButton.callback(s, s))
            .pageSize(10)
            .currentPage(0)
            .navigationCallbackPrefix("TEST")
            .build();

        assertThat(kb.getKeyboard()).hasSize(3);
    }

    // ─── empty list ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("empty items: produces empty keyboard (no items, no nav)")
    void emptyItems_emptyKeyboard() {
        InlineKeyboardMarkup kb = PagedKeyboardBuilder.<String>create()
            .items(List.of())
            .itemRenderer(s -> KeyboardButton.callback(s, s))
            .currentPage(0)
            .navigationCallbackPrefix("TEST")
            .build();

        assertThat(kb.getKeyboard()).isEmpty();
    }

    // ─── item rendering ──────────────────────────────────────────────────────

    @Test
    @DisplayName("items on page: texts match expected slice")
    void itemTexts_matchPageSlice() {
        InlineKeyboardMarkup kb = builderFor10(1).build(); // page 1: Items 5-8

        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();
        assertThat(rows.get(0).get(0).getText()).isEqualTo("Item 5");
        assertThat(rows.get(3).get(0).getText()).isEqualTo("Item 8");
    }

    @Test
    @DisplayName("each item occupies exactly one full-width row")
    void eachItem_ownRow() {
        InlineKeyboardMarkup kb = builderFor10(0).build();
        // rows 0-3 are items
        for (int i = 0; i < 4; i++) {
            assertThat(kb.getKeyboard().get(i)).as("row " + i).hasSize(1);
        }
    }

    // ─── appendRow ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("appendRow: extra row appears below navigation row")
    void appendRow_appearsAfterNav() {
        InlineKeyboardMarkup kb = builderFor10(0)
            .appendRow(KeyboardButton.callback("← Назад", "MENU:MAIN"))
            .build();

        List<List<InlineKeyboardButton>> rows = kb.getKeyboard();
        // 4 items + 1 nav + 1 extra = 6
        assertThat(rows).hasSize(6);
        assertThat(rows.get(5).get(0).getText()).isEqualTo("← Назад");
        assertThat(rows.get(5).get(0).getCallbackData()).isEqualTo("MENU:MAIN");
    }

    @Test
    @DisplayName("appendRow: on single page appears directly after items")
    void appendRow_singlePage_afterItems() {
        InlineKeyboardMarkup kb = PagedKeyboardBuilder.<String>create()
            .items(List.of("X"))
            .itemRenderer(s -> KeyboardButton.callback(s, s))
            .pageSize(10)
            .currentPage(0)
            .navigationCallbackPrefix("P")
            .appendRow(KeyboardButton.callback("Back", "BACK"))
            .build();

        // 1 item + no nav + 1 extra = 2
        assertThat(kb.getKeyboard()).hasSize(2);
        assertThat(kb.getKeyboard().get(1).get(0).getText()).isEqualTo("Back");
    }
}
