package com.valui.bot.keyboard.menu;

import com.valui.bot.i18n.BotMessageSource;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.List;

/**
 * Persistent main-menu keyboard shown after /start and /help.
 *
 * Private chat rows:
 *   Row 1:  [add]      [list]
 *   Row 2:  [listfilter][stop]
 *   Row 3:  [info]     [help]
 *   Row 4:  [language]
 *
 * Group chat adds an extra row:
 *   Row 5:  [boost]
 *
 * Button text dispatches by leading emoji in MenuButtonHandler.
 */
public final class MainMenuKeyboard {

    private MainMenuKeyboard() {}

    /**
     * @param fromId      user's personal telegram ID — used for i18n language lookup
     * @param isGroupChat when true, appends the "🚀 Расширить квоту" boost button row
     */
    public static ReplyKeyboardMarkup build(Long fromId, BotMessageSource msg, boolean isGroupChat) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton(msg.getMessage("menu.btn.add",      fromId)));
        row1.add(new KeyboardButton(msg.getMessage("menu.btn.list",     fromId)));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton(msg.getMessage("menu.btn.listfilter", fromId)));
        row2.add(new KeyboardButton(msg.getMessage("menu.btn.stop",       fromId)));

        KeyboardRow row3 = new KeyboardRow();
        row3.add(new KeyboardButton(msg.getMessage("menu.btn.info",     fromId)));
        row3.add(new KeyboardButton(msg.getMessage("menu.btn.help",     fromId)));

        KeyboardRow row4 = new KeyboardRow();
        row4.add(new KeyboardButton(msg.getMessage("menu.btn.language", fromId)));

        List<KeyboardRow> rows = new java.util.ArrayList<>(List.of(row1, row2, row3, row4));

        if (isGroupChat) {
            KeyboardRow rowBoost = new KeyboardRow();
            rowBoost.add(new KeyboardButton(msg.getMessage("menu.btn.boost", fromId)));
            rows.add(rowBoost);
        }

        return ReplyKeyboardMarkup.builder()
            .keyboard(rows)
            .resizeKeyboard(true)
            .oneTimeKeyboard(false)
            .selective(false)
            .build();
    }

    /** Convenience overload for private chats. */
    public static ReplyKeyboardMarkup build(Long fromId, BotMessageSource msg) {
        return build(fromId, msg, false);
    }
}
