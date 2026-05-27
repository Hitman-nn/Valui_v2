package com.valui.bot.keyboard;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for {@link InlineKeyboardMarkup}.
 *
 * <p>Buttons accumulate in a "current row". Call {@link #row()} to break the row explicitly,
 * or set {@link #columns(int)} to auto-break after every N buttons. {@link #backButton} and
 * {@link #cancelButton} always occupy their own dedicated row regardless of columns setting.
 */
public final class InlineKeyboardBuilder {

    private final List<List<InlineKeyboardButton>> rows = new ArrayList<>();
    private List<InlineKeyboardButton> currentRow = new ArrayList<>();
    private int maxColumns = 0;

    private InlineKeyboardBuilder() {}

    public static InlineKeyboardBuilder create() {
        return new InlineKeyboardBuilder();
    }

    /** Sets max buttons per row; auto-breaks before adding the button that would exceed the limit. */
    public InlineKeyboardBuilder columns(int n) {
        this.maxColumns = n;
        return this;
    }

    /** Adds a callback button to the current row. */
    public InlineKeyboardBuilder button(String text, String callbackData) {
        maybeAutoBreak();
        currentRow.add(InlineKeyboardButton.builder()
            .text(text)
            .callbackData(callbackData)
            .build());
        return this;
    }

    /** Adds a URL button to the current row. */
    public InlineKeyboardBuilder urlButton(String text, String url) {
        maybeAutoBreak();
        currentRow.add(InlineKeyboardButton.builder()
            .text(text)
            .url(url)
            .build());
        return this;
    }

    /** Adds a Telegram Mini App (WebApp) button to the current row. */
    public InlineKeyboardBuilder webAppButton(String text, String url) {
        maybeAutoBreak();
        currentRow.add(InlineKeyboardButton.builder()
            .text(text)
            .webApp(new WebAppInfo(url))
            .build());
        return this;
    }

    /** Flushes the current row. No-op if the current row is already empty. */
    public InlineKeyboardBuilder row() {
        if (!currentRow.isEmpty()) {
            rows.add(new ArrayList<>(currentRow));
            currentRow.clear();
        }
        return this;
    }

    /** Flushes the current row, then adds "← Назад" as its own full-width row. */
    public InlineKeyboardBuilder backButton(String callbackData) {
        row();
        currentRow.add(InlineKeyboardButton.builder()
            .text("← Назад")
            .callbackData(callbackData)
            .build());
        row();
        return this;
    }

    /** Flushes the current row, then adds "✕ Отмена" as its own full-width row. */
    public InlineKeyboardBuilder cancelButton() {
        row();
        currentRow.add(InlineKeyboardButton.builder()
            .text("✕ Отмена")
            .callbackData(CallbackData.CANCEL)
            .build());
        row();
        return this;
    }

    public InlineKeyboardMarkup build() {
        row(); // flush any remaining buttons
        return InlineKeyboardMarkup.builder().keyboard(new ArrayList<>(rows)).build();
    }

    // Package-visible: lets PagedKeyboardBuilder append pre-built rows without going through the column logic.
    void addRawRow(List<InlineKeyboardButton> raw) {
        if (!raw.isEmpty()) {
            rows.add(new ArrayList<>(raw));
        }
    }

    private void maybeAutoBreak() {
        if (maxColumns > 0 && currentRow.size() >= maxColumns) {
            rows.add(new ArrayList<>(currentRow));
            currentRow.clear();
        }
    }
}
