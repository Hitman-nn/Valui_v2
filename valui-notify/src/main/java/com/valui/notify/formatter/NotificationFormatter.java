package com.valui.notify.formatter;

import com.valui.common.domain.ControllerType;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.kafka.SportEventDetectedMessage;
import org.springframework.stereotype.Component;

@Component
public class NotificationFormatter {

    private static final int TELEGRAM_MAX_LEN = 4096;

    public String buildTelegramMessage(SportEventDetectedMessage event, ControllerEntity ctrl) {
        String bookmaker = event.bookmaker() != null ? event.bookmaker() : "";
        String title     = event.title()     != null ? event.title()     : event.externalEventId();
        String url       = event.url()       != null ? event.url()       : "";

        StringBuilder sb = new StringBuilder();
        sb.append("🔔 *").append(escapeMarkdown(bookmaker)).append("*\n");

        // For TOURNAMENT controllers add the tournament name so users know which competition
        if (ctrl.getType() == ControllerType.TOURNAMENT
                && ctrl.getTitle() != null && !ctrl.getTitle().isBlank()) {
            sb.append("📋 ").append(escapeMarkdown(ctrl.getTitle())).append("\n");
        }

        sb.append(escapeMarkdown(title)).append("\n").append(url);

        String text = sb.toString();
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
