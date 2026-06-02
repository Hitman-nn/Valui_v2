package com.valui.app.alert;

import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.monitor.watch.MarketWatchFiredEvent;
import com.valui.notify.formatter.NotificationFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Sends a new Telegram message to the user's chat when a watched market (handicap or total)
 * appears for a previously notified match.
 *
 * Async: market watch checks run on a monitor thread; we must not block it for Telegram I/O.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketWatchAlertListener {

    private final AbsSender            absSender;
    private final NotificationFormatter formatter;

    @Async
    @EventListener
    public void onMarketWatchFired(MarketWatchFiredEvent event) {
        boolean isHcap   = "HCAP".equals(event.marketType());
        String  label    = isHcap ? "фора" : "тотал";
        String  emoji    = isHcap ? "🎯" : "📊";

        String marketLine = formatter.buildMarketLine(event.extraData(), event.marketType());

        StringBuilder sb = new StringBuilder();
        sb.append(emoji).append(" *Появилась ").append(escMd(label)).append("\\!*\n");
        sb.append("*").append(escMd(event.bookmaker())).append("*");
        if (event.matchTitle() != null && !event.matchTitle().isBlank()) {
            sb.append(" — ").append(escMd(event.matchTitle()));
        }
        if (marketLine != null) {
            sb.append("\n\n").append(marketLine);
        }

        InlineKeyboardMarkup keyboard = null;
        if (event.matchUrl() != null && !event.matchUrl().isBlank()) {
            keyboard = InlineKeyboardBuilder.create()
                    .urlButton("🔗 Открыть матч", event.matchUrl())
                    .build();
        }

        SendMessage.SendMessageBuilder builder = SendMessage.builder()
                .chatId(event.chatId())
                .text(sb.toString())
                .parseMode("MarkdownV2")
                .disableWebPagePreview(true);
        if (keyboard != null) builder.replyMarkup(keyboard);

        try {
            absSender.execute(builder.build());
            log.info("[WATCH] Alert sent: market={} chatId={} watchId={}",
                    event.marketType(), event.chatId(), event.watchId());
        } catch (TelegramApiException e) {
            log.warn("[WATCH] Failed to deliver alert chatId={} watchId={}: {}",
                    event.chatId(), event.watchId(), e.getMessage());
        }
    }

    private static String escMd(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("_", "\\_").replace("*", "\\*")
                .replace("[", "\\[").replace("]", "\\]").replace("(", "\\(")
                .replace(")", "\\)").replace("~", "\\~").replace("`", "\\`")
                .replace(">", "\\>").replace("#", "\\#").replace("+", "\\+")
                .replace("-", "\\-").replace("=", "\\=").replace("|", "\\|")
                .replace("{", "\\{").replace("}", "\\}").replace(".", "\\.")
                .replace("!", "\\!");
    }
}
