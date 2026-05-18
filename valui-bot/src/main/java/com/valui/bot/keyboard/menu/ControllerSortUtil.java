package com.valui.bot.keyboard.menu;

import com.valui.monitor.dto.ControllerDto;

import java.util.Comparator;
import java.util.List;

/** Shared sort logic for controller list views. */
final class ControllerSortUtil {

    private ControllerSortUtil() {}

    static List<ControllerDto> sort(List<ControllerDto> controllers, String sort) {
        if (ControllerMenuBuilder.SORT_NAME.equalsIgnoreCase(sort)) {
            return controllers.stream()
                    .sorted(Comparator.comparing(
                            c -> (c.title() != null ? c.title() : c.url()).toLowerCase()))
                    .toList();
        }
        // DATE: newest first; null createdAt treated as oldest
        return controllers.stream()
                .sorted(Comparator.comparing(
                        ControllerDto::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }
}
