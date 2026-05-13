package com.valui.notify.formatter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.common.domain.ControllerType;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.kafka.SportEventDetectedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationFormatter {

    private static final int TELEGRAM_MAX_LEN = 4096;

    private final ObjectMapper objectMapper;

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

        // Append odds block if available
        String oddsBlock = buildOddsBlock(event.extraData());
        if (oddsBlock != null) sb.append("\n\n").append(oddsBlock);

        String text = sb.toString();
        return text.length() > TELEGRAM_MAX_LEN
                ? text.substring(0, TELEGRAM_MAX_LEN - 3) + "..."
                : text;
    }

    /**
     * Parses extraData JSON and formats odds for display.
     * JSON keys: w1 (П1), wX (draw), w2 (П2), h1/h2 (handicap, each with v + pt).
     * Returns null if no odds data or on parse error.
     */
    private String buildOddsBlock(String extraData) {
        if (extraData == null || extraData.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(extraData);
            StringBuilder sb = new StringBuilder();

            // 1x2 line
            JsonNode w1 = root.path("w1"), wX = root.path("wX"), w2 = root.path("w2");
            boolean has1x2 = !w1.isMissingNode() && !w2.isMissingNode();
            if (has1x2) {
                boolean hasDraw = !wX.isMissingNode();
                if (hasDraw) {
                    sb.append("П1: ").append(w1.asText())
                      .append("   X: ").append(wX.asText())
                      .append("   П2: ").append(w2.asText());
                } else {
                    sb.append("П1: ").append(w1.asText())
                      .append("   П2: ").append(w2.asText());
                }
            }

            // Handicap line
            JsonNode h1 = root.path("h1"), h2 = root.path("h2");
            boolean hasHcap = !h1.isMissingNode() && !h2.isMissingNode();
            if (hasHcap) {
                if (has1x2) sb.append("\n");
                String pt1 = h1.path("pt").asText("0");
                String pt2 = h2.path("pt").asText("0");
                sb.append("Ф: (").append(pt1).append(") ").append(h1.path("v").asText())
                  .append(" / (").append(pt2).append(") ").append(h2.path("v").asText());
            }

            // Total line
            JsonNode tb = root.path("tb"), tm = root.path("tm");
            if (!tb.isMissingNode() && !tm.isMissingNode()) {
                if (has1x2 || hasHcap) sb.append("\n");
                sb.append("ТБ(").append(tb.path("pt").asText("?")).append("): ").append(tb.path("v").asText())
                  .append("   ТМ(").append(tm.path("pt").asText("?")).append("): ").append(tm.path("v").asText());
            }

            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;

        } catch (Exception e) {
            log.debug("Failed to parse extraData odds: {}", e.getMessage());
            return null;
        }
    }

    private static String escapeMarkdown(String s) {
        return s.replace("\\", "\\\\")
                .replace("_",  "\\_")
                .replace("*",  "\\*")
                .replace("`",  "\\`")
                .replace("[",  "\\[");
    }
}
