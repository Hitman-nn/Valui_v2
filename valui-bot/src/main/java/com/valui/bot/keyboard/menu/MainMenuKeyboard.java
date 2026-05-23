package com.valui.bot.keyboard.menu;

import com.valui.bot.i18n.BotMessageSource;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.List;

/**
 * Persistent main-menu keyboard shown after /start and /help.
 *
 * Row 1:  [add]        [list]
 * Row 2:  [listfilter] [stop]
 * Row 3:  [info]       [topup]
 * Row 4:  [bet]        [settings]
 *
 * Button text dispatches by leading emoji in MenuButtonHandler.
 */
public final class MainMenuKeyboard {

    private MainMenuKeyboard() {}

    public static ReplyKeyboardMarkup build(Long fromId, BotMessageSource msg) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton(msg.getMessage("menu.btn.add",      fromId)));
        row1.add(new KeyboardButton(msg.getMessage("menu.btn.list",     fromId)));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton(msg.getMessage("menu.btn.listfilter", fromId)));
        row2.add(new KeyboardButton(msg.getMessage("menu.btn.stop",       fromId)));

        KeyboardRow row3 = new KeyboardRow();
        row3.add(new KeyboardButton(msg.getMessage("menu.btn.info",   fromId)));
        row3.add(new KeyboardButton(msg.getMessage("menu.btn.topup",  fromId)));

        KeyboardRow row4 = new KeyboardRow();
        row4.add(new KeyboardButton(msg.getMessage("menu.btn.bet",      fromId)));
        row4.add(new KeyboardButton(msg.getMessage("menu.btn.settings", fromId)));

        return ReplyKeyboardMarkup.builder()
            .keyboard(List.of(row1, row2, row3, row4))
            .resizeKeyboard(true)
            .isPersistent(true)
            .oneTimeKeyboard(false)
            .selective(false)
            .build();
    }
}
