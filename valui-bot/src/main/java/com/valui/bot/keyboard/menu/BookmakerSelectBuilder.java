package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.MenuMessage;

import java.util.List;

public final class BookmakerSelectBuilder {

    private BookmakerSelectBuilder() {}

    public static MenuMessage build(List<String> allowedBookmakers) {
        var builder = InlineKeyboardBuilder.create().columns(2);

        for (String bm : allowedBookmakers) {
            builder.button(bm, CallbackData.bookmakerSelect(bm));
        }

        return new MenuMessage("📚 Выберите букмекера:", builder.build());
    }
}
