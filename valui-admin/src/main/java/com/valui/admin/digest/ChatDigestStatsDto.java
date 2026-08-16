package com.valui.admin.digest;

/** Per-chat digest numbers for one group chat with ≥1 active controller. */
public record ChatDigestStatsDto(
        Long chatId,
        long activeControllers,
        long activeBookmakers,
        long mutedControllers,
        long pausedByTokensControllers,
        long notificationsThisWeek,
        long notificationsLastWeek,
        long staleControllers
) {}
