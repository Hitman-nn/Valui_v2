package com.valui.bot.keyboard;

/** Shared MarkdownV2 helpers used across bot handlers. */
public final class BotMarkdownUtil {

    private BotMarkdownUtil() {}

    /**
     * Escapes characters in a match/event title that have special meaning in Telegram MarkdownV2.
     * Returns "—" for null input.
     */
    public static String escapeTitle(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
    }
}
