package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.KeyboardButton;
import com.valui.bot.keyboard.MenuMessage;
import com.valui.bot.keyboard.PagedKeyboardBuilder;
import com.valui.monitor.dto.ControllerDto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

public final class ControllerMenuBuilder {

    public static final String NAV_PREFIX = "CTRL:LIST";
    private static final int PAGE_SIZE = 6;

    private ControllerMenuBuilder() {}

    public static MenuMessage build(List<ControllerDto> controllers, int page) {
        if (controllers.isEmpty()) {
            return new MenuMessage("📋 Список контроллеров пуст.",
                InlineKeyboardMarkup.builder().keyboard(List.of()).build());
        }

        int totalPages = (int) Math.ceil((double) controllers.size() / PAGE_SIZE);
        String text = String.format("📋 Контроллеры — страница %d / %d:", page + 1, Math.max(1, totalPages));

        var keyboard = PagedKeyboardBuilder.<ControllerDto>create()
            .items(controllers)
            .itemRenderer(c -> {
                String icon = !c.isActive() ? "🔴" : (c.isMuted() ? "🔕" : "🟢");
                String label = icon + " " + (c.title() != null ? c.title() : c.url()) + " [" + c.bookmaker() + "]";
                return KeyboardButton.callback(label, CallbackData.ctrlDetail(c.id()));
            })
            .pageSize(PAGE_SIZE)
            .currentPage(page)
            .navigationCallbackPrefix(NAV_PREFIX)
            .build();

        return new MenuMessage(text, keyboard);
    }
}
