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
    private static final int PAGE_SIZE = 6;

    private BookmakerMenuBuilder() {}

    /**
     * Builds the bookmaker selection screen.
     * Groups controllers by bookmaker, shows one button per BK with a status icon and count.
     */
    public static MenuMessage buildSelection(List<ControllerDto> all) {
        if (all.isEmpty()) {
            return new MenuMessage("📋 Контроллеров нет.",
                InlineKeyboardBuilder.create().build());
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
    public static MenuMessage buildControllerList(String bookmaker, List<ControllerDto> controllers, int page, int staleThresholdDays) {
        if (controllers.isEmpty()) {
            var keyboard = InlineKeyboardBuilder.create()
                .button("← Букмекеры", CallbackData.CTRL_BK_LIST)
                .build();
            return new MenuMessage("📋 " + bookmaker + " — контроллеров нет.", keyboard);
        }

        int totalPages = (int) Math.ceil((double) controllers.size() / PAGE_SIZE);
        String text = totalPages > 1
            ? String.format("📋 %s — стр. %d / %d:", bookmaker, page + 1, totalPages)
            : "📋 " + bookmaker + ":";

        var keyboard = PagedKeyboardBuilder.<ControllerDto>create()
            .items(controllers)
            .itemRenderer(c -> {
                String statusIcon = !c.isActive() ? "🔴" : (c.isMuted() ? "🔕" : "🟢");
                String staleIcon  = isStale(c.lastEventAt(), staleThresholdDays) ? "🕰️" : "";
                String label = statusIcon + staleIcon + " " + (c.title() != null ? c.title() : c.url());
                return KeyboardButton.callback(label, CallbackData.ctrlDetail(c.id()));
            })
            .pageSize(PAGE_SIZE)
            .currentPage(page)
            .navigationCallbackPrefix(BK_NAV_PREFIX + ":" + bookmaker.toUpperCase())
            .appendRow(KeyboardButton.callback("← Букмекеры", CallbackData.CTRL_BK_LIST))
            .build();

        return new MenuMessage(text, keyboard);
    }

    private static boolean isStale(Instant lastEventAt, int days) {
        return lastEventAt != null && lastEventAt.isBefore(Instant.now().minus(days, ChronoUnit.DAYS));
    }

}
