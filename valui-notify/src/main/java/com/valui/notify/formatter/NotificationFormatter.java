package com.valui.notify.formatter;

import com.valui.common.entity.ControllerEntity;
import com.valui.common.kafka.SportEventDetectedMessage;
import org.springframework.stereotype.Component;

@Component
public class NotificationFormatter {

    private static final int TELEGRAM_MAX_LEN = 4096;

    public String buildTelegramMessage(SportEventDetectedMessage event, ControllerEntity ctrl) {
        String bookmaker = event.bookmaker() != null ? event.bookmaker() : "";
        String title     = event.title() != null ? event.title() : event.externalEventId();
        String url       = event.url()   != null ? event.url()   : "";

        // Markdown v1: bold bookmaker, plain title, raw URL (Telegram auto-links)
        String text = "🔔 *" + escapeMarkdown(bookmaker) + "*\n"
                    + escapeMarkdown(title) + "\n"
                    + url;

        return text.length() > TELEGRAM_MAX_LEN
                ? text.substring(0, TELEGRAM_MAX_LEN - 3) + "..."
                : text;
    }

    private static String escapeMarkdown(String s) {
        return s.replace("\\", "\\\\")
                .replace("_",  "\\_")
                .replace("*",  "\\*")
                .replace("`",  "\\`")
                .replace("[",  "\\[");
    }
}
