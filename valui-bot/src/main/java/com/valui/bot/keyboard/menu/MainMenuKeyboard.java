package com.valui.bot.keyboard.menu;

import com.valui.bot.i18n.BotMessageSource;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.List;

/**
 * Persistent main-menu keyboard shown after /start and /help.
 *
 * Row 1:  [/add]          [/list]
 * Row 2:  [/listfilter]   [/stop]
 * Row 3:  [/language]     [/help]
 *
 * Button text equals the command so existing CommandHandler.canHandle() (startsWith) works without changes.
 */
public final class MainMenuKeyboard {

    private MainMenuKeyboard() {}

    public static ReplyKeyboardMarkup build(Long chatId, BotMessageSource msg) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton(msg.getMessage("menu.btn.add", chatId)));
        row1.add(new KeyboardButton(msg.getMessage("menu.btn.list", chatId)));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton(msg.getMessage("menu.btn.listfilter", chatId)));
        row2.add(new KeyboardButton(msg.getMessage("menu.btn.stop", chatId)));

        KeyboardRow row3 = new KeyboardRow();
        row3.add(new KeyboardButton(msg.getMessage("menu.btn.language", chatId)));
        row3.add(new KeyboardButton(msg.getMessage("menu.btn.help", chatId)));

        return ReplyKeyboardMarkup.builder()
            .keyboard(List.of(row1, row2, row3))
            .resizeKeyboard(true)
            .oneTimeKeyboard(false)   // stays visible until explicitly removed
            .selective(false)
            .build();
    }
}
