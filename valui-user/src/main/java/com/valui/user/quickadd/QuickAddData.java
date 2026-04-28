package com.valui.user.quickadd;

/** Payload cached in Redis for the "➕ Следить за турниром" notification button. */
public record QuickAddData(
        String url,
        String bookmaker,
        String title
) {}
