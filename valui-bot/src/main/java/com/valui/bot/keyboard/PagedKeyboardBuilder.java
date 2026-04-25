package com.valui.bot.keyboard;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Builds an {@link InlineKeyboardMarkup} for a paginated list.
 * Each item occupies one full-width row. A navigation row is appended when there
 * is more than one page: {@code ‹ prev | 1/N | next ›}.
 * Navigation callbacks follow the pattern {@code "{prefix}:PAGE:{pageNum}"} (0-indexed).
 */
public final class PagedKeyboardBuilder<T> {

    private List<T> items = List.of();
    private Function<T, KeyboardButton> itemRenderer;
    private int pageSize = 8;
    private int currentPage = 0;
    private String navigationCallbackPrefix = "PAGE";
    private final List<List<InlineKeyboardButton>> extraRows = new ArrayList<>();

    private PagedKeyboardBuilder() {}

    public static <T> PagedKeyboardBuilder<T> create() {
        return new PagedKeyboardBuilder<>();
    }

    public PagedKeyboardBuilder<T> items(List<T> items) {
        this.items = List.copyOf(items);
        return this;
    }

    public PagedKeyboardBuilder<T> itemRenderer(Function<T, KeyboardButton> renderer) {
        this.itemRenderer = renderer;
        return this;
    }

    public PagedKeyboardBuilder<T> pageSize(int size) {
        this.pageSize = size;
        return this;
    }

    public PagedKeyboardBuilder<T> currentPage(int page) {
        this.currentPage = page;
        return this;
    }

    public PagedKeyboardBuilder<T> navigationCallbackPrefix(String prefix) {
        this.navigationCallbackPrefix = prefix;
        return this;
    }

    /** Appends extra rows below the navigation row (e.g. a back button). */
    public PagedKeyboardBuilder<T> appendRow(KeyboardButton... buttons) {
        List<InlineKeyboardButton> row = Arrays.stream(buttons)
            .map(KeyboardButton::toInline)
            .toList();
        if (!row.isEmpty()) {
            extraRows.add(row);
        }
        return this;
    }

    public InlineKeyboardMarkup build() {
        int totalItems = items.size();
        int totalPages = totalItems == 0 ? 1 : (int) Math.ceil((double) totalItems / pageSize);
        int safePage   = Math.min(Math.max(0, currentPage), totalPages - 1);

        List<List<InlineKeyboardButton>> allRows = new ArrayList<>();

        // Item rows — one button per row
        int from = safePage * pageSize;
        int to   = Math.min(from + pageSize, totalItems);
        for (T item : items.subList(from, to)) {
            allRows.add(List.of(itemRenderer.apply(item).toInline()));
        }

        // Navigation row — rendered only when more than one page exists
        if (totalPages > 1) {
            List<InlineKeyboardButton> navRow = new ArrayList<>();
            if (safePage > 0) {
                navRow.add(InlineKeyboardButton.builder()
                    .text("‹")
                    .callbackData(navigationCallbackPrefix + ":PAGE:" + (safePage - 1))
                    .build());
            }
            navRow.add(InlineKeyboardButton.builder()
                .text((safePage + 1) + "/" + totalPages)
                .callbackData(CallbackData.NOOP)
                .build());
            if (safePage < totalPages - 1) {
                navRow.add(InlineKeyboardButton.builder()
                    .text("›")
                    .callbackData(navigationCallbackPrefix + ":PAGE:" + (safePage + 1))
                    .build());
            }
            allRows.add(navRow);
        }

        allRows.addAll(extraRows);

        return InlineKeyboardMarkup.builder().keyboard(allRows).build();
    }
}
