package com.valui.betting.cache;

/** Data cached in Redis when a match notification is sent. Used by "💸 Поставил" callback. */
public record BetNotifData(
        String matchTitle,
        String matchUrl,
        String bookmaker
) {}
