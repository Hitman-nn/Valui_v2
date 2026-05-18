package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.KeyboardButton;
import com.valui.bot.keyboard.MenuMessage;
import com.valui.bot.keyboard.PagedKeyboardBuilder;
import com.valui.monitor.dto.ControllerDto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class ControllerMenuBuilder {

    public static final String NAV_PREFIX = "CTRL:LIST";
    public static final int PAGE_SIZE = 15;

    public static final String SORT_DATE = "DATE";
    public static final String SORT_NAME = "NAME";

    private ControllerMenuBuilder() {}

    public static MenuMessage build(List<ControllerDto> controllers, int page,
                                    int staleThresholdDays, String sort) {
        if (controllers.isEmpty()) {
            return new MenuMessage("📋 Список контроллеров пуст.",
                InlineKeyboardMarkup.builder().keyboard(List.of()).build());
        }

        List<ControllerDto> sorted = sort(controllers, sort);
        int totalPages = (int) Math.ceil((double) sorted.size() / PAGE_SIZE);
        String text = String.format("📋 Контроллеры — страница %d / %d:", page + 1, Math.max(1, totalPages));

        boolean isName = SORT_NAME.equalsIgnoreCase(sort);
        String toggleLabel    = isName ? "📅 По дате"    : "🔡 По алфавиту";
        String toggleCallback = isName
                ? CallbackData.ctrlListSort(SORT_DATE)
                : CallbackData.ctrlListSort(SORT_NAME);

        var keyboard = PagedKeyboardBuilder.<ControllerDto>create()
            .items(sorted)
            .itemRenderer(c -> {
                String statusIcon = !c.isActive() ? "🔴" : (c.isMuted() ? "🔕" : "🟢");
                String staleIcon  = isStale(c.lastEventAt(), staleThresholdDays) ? "🕰️" : "";
                String label = statusIcon + staleIcon + " "
                        + (c.title() != null ? c.title() : c.url())
                        + " [" + c.bookmaker() + "]";
                return KeyboardButton.callback(label, CallbackData.ctrlDetail(c.id()));
            })
            .pageSize(PAGE_SIZE)
            .currentPage(page)
            .navigationCallbackPrefix(NAV_PREFIX)
            .appendRow(KeyboardButton.callback(toggleLabel, toggleCallback))
            .build();

        return new MenuMessage(text, keyboard);
    }

    private static List<ControllerDto> sort(List<ControllerDto> controllers, String sort) {
        return ControllerSortUtil.sort(controllers, sort);
    }

    private static boolean isStale(Instant lastEventAt, int days) {
        return lastEventAt != null && lastEventAt.isBefore(Instant.now().minus(days, ChronoUnit.DAYS));
    }
}
