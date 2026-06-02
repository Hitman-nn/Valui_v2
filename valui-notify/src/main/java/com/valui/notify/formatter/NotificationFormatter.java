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

        sb.append(escapeMarkdown(title));
        if (!url.isEmpty()) {
            // In MarkdownV2 link syntax [text](url), only ')' and '\' must be escaped inside the url part.
            String linkUrl = url.replace("\\", "\\\\").replace(")", "\\)");
            sb.append("\n[").append(escapeMarkdown(url)).append("](").append(linkUrl).append(")");
        }

        // Append odds block if available
        String oddsBlock = buildOddsBlock(event.extraData());
        if (oddsBlock != null) sb.append("\n\n").append(oddsBlock);

        String text = sb.toString();
        return text.length() > TELEGRAM_MAX_LEN
                ? text.substring(0, TELEGRAM_MAX_LEN - 3) + "..."
                : text;
    }

    /**
     * Extracts and formats a single market line from extraData for watch-fire notifications.
     *
     * @param marketType "HCAP" → handicap line; "TOTAL" → totals line
     * @return formatted MarkdownV2 line, or null if the market is absent or extraData is unparseable
     */
    public String buildMarketLine(String extraData, String marketType) {
        if (extraData == null || extraData.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(extraData);
            if ("HCAP".equals(marketType)) {
                JsonNode h1 = root.path("h1"), h2 = root.path("h2");
                if (h1.isMissingNode() || h2.isMissingNode()) return null;
                String pt1 = escapeMarkdown(h1.path("pt").asText("0"));
                String pt2 = escapeMarkdown(h2.path("pt").asText("0"));
                return "Ф: \\(" + pt1 + "\\) " + esc(h1.path("v"))
                     + " / \\(" + pt2 + "\\) " + esc(h2.path("v"));
            } else {
                JsonNode tb = root.path("tb"), tm = root.path("tm");
                if (tb.isMissingNode() || tm.isMissingNode()) return null;
                return "ТБ\\(" + escapeMarkdown(tb.path("pt").asText("?")) + "\\): " + esc(tb.path("v"))
                     + "   ТМ\\(" + escapeMarkdown(tm.path("pt").asText("?")) + "\\): " + esc(tm.path("v"));
            }
        } catch (Exception e) {
            log.debug("Failed to parse market line from extraData: {}", e.getMessage());
            return null;
        }
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
                    sb.append("П1: ").append(esc(w1))
                      .append("   X: ").append(esc(wX))
                      .append("   П2: ").append(esc(w2));
                } else {
                    sb.append("П1: ").append(esc(w1))
                      .append("   П2: ").append(esc(w2));
                }
            }

            // Handicap line
            JsonNode h1 = root.path("h1"), h2 = root.path("h2");
            boolean hasHcap = !h1.isMissingNode() && !h2.isMissingNode();
            if (hasHcap) {
                if (has1x2) sb.append("\n");
                String pt1 = escapeMarkdown(h1.path("pt").asText("0"));
                String pt2 = escapeMarkdown(h2.path("pt").asText("0"));
                sb.append("Ф: \\(").append(pt1).append("\\) ").append(esc(h1.path("v")))
                  .append(" / \\(").append(pt2).append("\\) ").append(esc(h2.path("v")));
            }

            // Total line
            JsonNode tb = root.path("tb"), tm = root.path("tm");
            if (!tb.isMissingNode() && !tm.isMissingNode()) {
                if (has1x2 || hasHcap) sb.append("\n");
                sb.append("ТБ\\(").append(escapeMarkdown(tb.path("pt").asText("?"))).append("\\): ").append(esc(tb.path("v")))
                  .append("   ТМ\\(").append(escapeMarkdown(tm.path("pt").asText("?"))).append("\\): ").append(esc(tm.path("v")));
            }

            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;

        } catch (Exception e) {
            log.debug("Failed to parse extraData odds: {}", e.getMessage());
            return null;
        }
    }

    private static String esc(JsonNode node) {
        return escapeMarkdown(node.asText());
    }

    /** Escapes all MarkdownV2 special characters as required by the Telegram Bot API. */
    public static String escapeMarkdown(String s) {
        if (s == null) return "";
        // '\' must be escaped first to avoid double-escaping
        return s.replace("\\", "\\\\")
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
