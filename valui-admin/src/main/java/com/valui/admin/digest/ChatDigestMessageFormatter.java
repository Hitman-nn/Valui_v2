package com.valui.admin.digest;

import org.springframework.stereotype.Component;

/**
 * Renders {@link ChatDigestStatsDto} into a MarkdownV2 message for
 * {@code TelegramNotificationSender.send()} (which hardcodes {@code parseMode("MarkdownV2")} —
 * unlike the plain-"Markdown" admin alerts sent directly via {@code AbsSender}).
 *
 * <p>{@link #escapeMarkdown} duplicates {@code NotificationFormatter.escapeMarkdown} in
 * valui-notify — not reused directly because valui-admin does not depend on valui-notify (see
 * module graph in the digest feature plan). Kept for any future free-text field (e.g. a chat
 * title); today every interpolated value is a non-negative long, which can never contain a
 * MarkdownV2-reserved character, so escaping is defensive rather than load-bearing for the
 * current fields — but every literal label below still routes through it so a future field
 * addition can't forget to.
 */
@Component
public class ChatDigestMessageFormatter {

    public String format(ChatDigestStatsDto s) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *").append(escapeMarkdown("Еженедельная статистика чата")).append("*\n\n");
        sb.append(line("🎯 Активных контроллеров", s.activeControllers()));
        sb.append(line("🏢 Букмекеров в работе", s.activeBookmakers()));
        sb.append("🔔 *").append(escapeMarkdown("Уведомлений за 7 дней")).append(": ")
          .append(s.notificationsThisWeek())
          .append(trend(s.notificationsThisWeek(), s.notificationsLastWeek()))
          .append("*\n");
        if (s.staleControllers() > 0) {
            sb.append(line("💤 Без новых событий 30+ дней", s.staleControllers()));
        }
        if (s.mutedControllers() > 0) {
            sb.append(line("🔇 Замьючено", s.mutedControllers()));
        }
        if (s.pausedByTokensControllers() > 0) {
            sb.append(line("⏸ На паузе (не хватает токенов)", s.pausedByTokensControllers()));
        }
        sb.append("\n_").append(escapeMarkdown("Обновляется каждый понедельник")).append("_");
        return sb.toString();
    }

    private static String line(String label, long value) {
        return escapeMarkdown(label) + ": *" + value + "*\n";
    }

    /** Empty string when there's no prior-week baseline to compare against (avoids a bogus "↑ 100%"
     *  the first time a chat has any notifications at all, or a divide-by-zero). */
    private static String trend(long current, long previous) {
        if (previous <= 0) return "";
        double pct = (current - previous) * 100.0 / previous;
        String arrow = pct >= 0 ? "↑" : "↓";
        // Parentheses are MarkdownV2-reserved even inside a *bold* span — hand-escaped here
        // rather than routed through escapeMarkdown() since this whole string is a static
        // template with only a pre-escaped-safe integer interpolated (no "%" escaping needed,
        // it's not a reserved character).
        return " \\(" + arrow + " " + Math.round(Math.abs(pct)) + "%\\)";
    }

    /** Escapes all MarkdownV2 special characters as required by the Telegram Bot API. */
    private static String escapeMarkdown(String text) {
        if (text == null) return "";
        // '\' must be escaped first to avoid double-escaping
        return text.replace("\\", "\\\\")
                .replace("_",  "\\_")
                .replace("*",  "\\*")
                .replace("[",  "\\[")
                .replace("]",  "\\]")
                .replace("(",  "\\(")
                .replace(")",  "\\)")
                .replace("~",  "\\~")
                .replace("`",  "\\`")
                .replace(">",  "\\>")
                .replace("#",  "\\#")
                .replace("+",  "\\+")
                .replace("-",  "\\-")
                .replace("=",  "\\=")
                .replace("|",  "\\|")
                .replace("{",  "\\{")
                .replace("}",  "\\}")
                .replace(".",  "\\.")
                .replace("!",  "\\!");
    }
}
