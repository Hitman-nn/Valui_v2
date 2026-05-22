package com.valui.bot.keyboard.menu;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.KeyboardButton;
import com.valui.bot.keyboard.MenuMessage;
import com.valui.bot.keyboard.PagedKeyboardBuilder;
import com.valui.monitor.dto.ControllerDto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class BookmakerMenuBuilder {

    public static final String BK_NAV_PREFIX = "CTRL:BK";
    private static final int PAGE_SIZE = 15;

    private BookmakerMenuBuilder() {}

    /**
     * Builds the bookmaker selection screen.
     * Groups controllers by bookmaker, shows one button per BK with a status icon and count.
     */
    public static MenuMessage buildSelection(List<ControllerDto> all) {
        if (all.isEmpty()) {
            var kb = InlineKeyboardBuilder.create()
                .button("📡 Добавить контроллер", CallbackData.CTRL_ADD)
                .build();
            return new MenuMessage("📋 Контроллеров нет.", kb);
        }

        // Preserve insertion order (first seen = first listed)
        Map<String, List<ControllerDto>> grouped = all.stream()
            .collect(Collectors.groupingBy(
                c -> c.bookmaker().toUpperCase(),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        var builder = InlineKeyboardBuilder.create();
        grouped.forEach((bm, controllers) -> {
            int count = controllers.size();
            builder.button(bm + " (" + count + ")", CallbackData.ctrlByBookmaker(bm));
            builder.row();
        });

        return new MenuMessage("📋 Мои контроллеры — выберите букмекера:", builder.build());
    }

    /**
     * Builds the per-bookmaker controller list (no [BK] suffix in labels, with "← Букмекеры" back).
     */
    public static MenuMessage buildControllerList(String bookmaker, List<ControllerDto> controllers,
                                                   int page, int staleThresholdDays, String sort) {
        if (controllers.isEmpty()) {
            var keyboard = InlineKeyboardBuilder.create()
                .button("📡 Добавить контроллер", CallbackData.CTRL_ADD).row()
                .button("← Букмекеры", CallbackData.CTRL_BK_LIST)
                .build();
            return new MenuMessage("📋 " + bookmaker + " — контроллеров нет.", keyboard);
        }

        List<ControllerDto> sorted = sort(controllers, sort);
        int totalPages = (int) Math.ceil((double) sorted.size() / PAGE_SIZE);
        String text = totalPages > 1
            ? String.format("📋 %s — стр. %d / %d:", bookmaker, page + 1, totalPages)
            : "📋 " + bookmaker + ":";

        boolean isName = ControllerMenuBuilder.SORT_NAME.equalsIgnoreCase(sort);
        String toggleLabel    = isName ? "📅 По дате"    : "🔡 По алфавиту";
        String toggleCallback = isName
                ? CallbackData.ctrlByBookmakerSort(bookmaker, ControllerMenuBuilder.SORT_DATE)
                : CallbackData.ctrlByBookmakerSort(bookmaker, ControllerMenuBuilder.SORT_NAME);

        var keyboard = PagedKeyboardBuilder.<ControllerDto>create()
            .items(sorted)
            .itemRenderer(c -> {
                String statusIcon = !c.isActive() ? "🔴" : (c.isMuted() ? "🔕" : "🟢");
                String staleIcon  = isStale(c.lastEventAt(), staleThresholdDays) ? "🕰️" : "";
                String label = statusIcon + staleIcon + " " + (c.title() != null ? c.title() : c.url());
                return KeyboardButton.callback(label, CallbackData.ctrlDetail(c.id()));
            })
            .pageSize(PAGE_SIZE)
            .currentPage(page)
            .navigationCallbackPrefix(BK_NAV_PREFIX + ":" + bookmaker.toUpperCase())
            .appendRow(KeyboardButton.callback(toggleLabel, toggleCallback))
            .appendRow(KeyboardButton.callback("← Букмекеры", CallbackData.CTRL_BK_LIST))
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
