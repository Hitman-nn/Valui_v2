package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.watch.MarketWatchService;
import com.valui.user.watch.WatchCacheData;
import com.valui.user.watch.WatchCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles "👁 Фора" and "👁 Тотал" watch buttons in TOURNAMENT/MATCH notifications.
 *
 * Flow on tap:
 *   1. Lookup watch cache data (controllerId, externalEventId, bookmaker, …) by notifLogId.
 *   2. Race-condition check: if the market is already present in DB → toast "уже появилась".
 *   3. Otherwise create a market_watch row.
 *   4. Replace the tapped button in the keyboard with "✓ Слежу за форой/тоталом" (NOOP).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchMarketCallback implements CallbackHandler {

    private static final String MARKET_HCAP  = "HCAP";
    private static final String MARKET_TOTAL = "TOTAL";

    private final WatchCacheService       watchCacheService;
    private final MarketWatchService      marketWatchService;
    private final DetectedEventPortService detectedEventPort;

    @Override
    public String callbackPrefix() { return "WATCH:"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        boolean isHcap;
        String  notifLogId;
        if (data.startsWith(CallbackData.WATCH_HCAP_PREFIX)) {
            isHcap     = true;
            notifLogId = data.substring(CallbackData.WATCH_HCAP_PREFIX.length());
        } else {
            isHcap     = false;
            notifLogId = data.substring(CallbackData.WATCH_TOTAL_PREFIX.length());
        }

        String marketType  = isHcap ? MARKET_HCAP : MARKET_TOTAL;
        String marketLabel = isHcap ? "фора" : "тотал";

        Optional<WatchCacheData> cachedOpt = watchCacheService.find(notifLogId);
        if (cachedOpt.isEmpty()) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⏱ Информация о матче устарела — создать слежение невозможно");
            return;
        }
        WatchCacheData cache = cachedOpt.get();

        UUID controllerId = UUID.fromString(cache.controllerId());

        // Race-condition check: market may have appeared between the notification send and this tap.
        // If the detected_event row is absent (event not yet persisted or already cleaned up by
        // the nightly dedup purge), we skip the check and create the watch anyway — it will either
        // fire on the next poll or expire naturally when the match starts.
        Optional<String> currentExtraData = detectedEventPort
                .findExtraDataByControllerIdAndExternalId(controllerId, cache.externalEventId());
        if (currentExtraData.isPresent()) {
            boolean alreadyPresent = isHcap
                    ? currentExtraData.get().contains("\"h1\":{")
                    : currentExtraData.get().contains("\"tb\":{");
            if (alreadyPresent) {
                MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                        "✅ " + capitalise(marketLabel) + " уже появилась — проверьте уведомления");
                return;
            }
        }

        // Create watch (idempotent: unique index guards duplicates)
        try {
            UUID notifLogUuid = parseUuid(notifLogId);
            marketWatchService.create(
                    ctx.chatId(), ctx.fromId(), controllerId,
                    cache.externalEventId(), cache.bookmaker(), marketType,
                    cache.matchTitle(), cache.matchUrl(), messageId,
                    notifLogUuid, cache.startEpoch());
        } catch (DataIntegrityViolationException e) {
            // Already watching — still update the button to reflect confirmed state
            log.debug("[WATCH] Duplicate watch ignored for chatId={} event={} market={}",
                    ctx.chatId(), cache.externalEventId(), marketType);
        }

        log.info("[WATCH] {} watch created: chatId={} event={} bookmaker={}",
                marketType, ctx.chatId(), cache.externalEventId(), cache.bookmaker());

        MessageSend.answerCallback(ctx.sender(), callbackId);
        replaceWatchButton(ctx, messageId, isHcap, notifLogId);
    }

    /**
     * Replaces the tapped watch button in the original keyboard with a disabled "✓ Слежу" label.
     * Leaves all other buttons untouched.
     */
    private void replaceWatchButton(BotUpdateContext ctx, int messageId, boolean isHcap, String notifLogId) {
        var msg = ctx.update().getCallbackQuery().getMessage();
        if (!(msg instanceof Message)) return;
        InlineKeyboardMarkup current = ((Message) msg).getReplyMarkup();
        if (current == null) return;

        String oldData    = isHcap ? CallbackData.watchHcap(notifLogId) : CallbackData.watchTotal(notifLogId);
        String doneLabel  = isHcap ? "✓ Слежу за форой" : "✓ Слежу за тоталом";

        List<List<InlineKeyboardButton>> newRows = new ArrayList<>();
        for (List<InlineKeyboardButton> row : current.getKeyboard()) {
            List<InlineKeyboardButton> newRow = new ArrayList<>();
            for (InlineKeyboardButton btn : row) {
                if (oldData.equals(btn.getCallbackData())) {
                    // Replace with disabled label
                    newRow.add(InlineKeyboardButton.builder()
                            .text(doneLabel)
                            .callbackData(CallbackData.NOOP)
                            .build());
                } else {
                    newRow.add(btn);
                }
            }
            newRows.add(newRow);
        }

        try {
            ctx.sender().execute(EditMessageReplyMarkup.builder()
                    .chatId(ctx.chatId())
                    .messageId(messageId)
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(newRows).build())
                    .build());
        } catch (TelegramApiException e) {
            log.debug("[WATCH] Could not update keyboard chatId={} messageId={}: {}",
                    ctx.chatId(), messageId, e.getMessage());
        }
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static UUID parseUuid(String s) {
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) {
            log.warn("[WATCH] Malformed notifLogId in callback data: '{}'", s);
            return null;
        }
    }
}
