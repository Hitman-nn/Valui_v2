package com.valui.bot.handler.callback.betting;

import com.valui.betting.cache.BetNotifCacheService;
import com.valui.betting.cache.BetNotifData;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Handles "💸 Поставил" button tap from a match notification.
 *
 * Three sub-paths (all share prefix "BET:NOTIF:"):
 *   BET:NOTIF:{key}    — first tap: shows type-choice inline menu
 *   BET:NOTIF:S:{key}  — user chose single → pre-fill title, ask odds
 *   BET:NOTIF:E:{key}  — user chose express → pre-fill title, ask odds, preserve existing legs
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BetNotifCallback implements CallbackHandler {

    private final BetNotifCacheService betNotifCache;
    private final BotSessionService    sessionService;

    @Override
    public String callbackPrefix() { return CallbackData.BET_NOTIF_PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        String after = data.substring(CallbackData.BET_NOTIF_PREFIX.length());

        if (after.startsWith("S:")) {
            handleSingle(ctx, after.substring(2), callbackId, messageId);
        } else if (after.startsWith("E:")) {
            handleExpress(ctx, after.substring(2), callbackId, messageId);
        } else {
            handleChoice(ctx, after, callbackId, messageId);
        }
    }

    // ── Step 1: show choice ───────────────────────────────────────────────────

    private void handleChoice(BotUpdateContext ctx, String notifKey, String callbackId, int messageId) {
        Optional<BetNotifData> cached = betNotifCache.find(notifKey);
        if (cached.isEmpty()) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⏱ Данные устарели. Введите ставку вручную через /bet");
            return;
        }
        BetNotifData nd = cached.get();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        String text = "💸 *" + escape(nd.matchTitle()) + "*\n\nВыберите тип ставки:";
        var kb = InlineKeyboardBuilder.create()
                .button("💸 Одиночная",  CallbackData.betNotifSingle(notifKey))
                .button("🎰 В экспресс", CallbackData.betNotifExpress(notifKey)).row()
                .build();
        MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), text, kb);
    }

    // ── Step 2a: single bet ───────────────────────────────────────────────────

    private void handleSingle(BotUpdateContext ctx, String notifKey, String callbackId, int messageId) {
        Optional<BetNotifData> cached = betNotifCache.find(notifKey);
        if (cached.isEmpty()) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⏱ Данные устарели. Введите ставку вручную через /bet");
            return;
        }
        BetNotifData nd = cached.get();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        sessionService.setStateWithContext(ctx.fromId(), BotState.BETTING_WAITING_ODDS, Map.of(
                UserBotSession.CTX_BET_TYPE,        "SINGLE",
                UserBotSession.CTX_BET_MATCH_TITLE, nd.matchTitle() != null ? nd.matchTitle() : "",
                UserBotSession.CTX_BET_MATCH_URL,   nd.matchUrl()   != null ? nd.matchUrl()   : "",
                UserBotSession.CTX_BET_BOOKMAKER,   nd.bookmaker()  != null ? nd.bookmaker()  : "",
                UserBotSession.CTX_BET_WIZARD_MSG,  String.valueOf(messageId)
        ));

        String text = "💸 *Одиночная ставка*\n🏆 " + escape(nd.matchTitle())
                    + "\n\nВведите *коэффициент* (например: `1.85`):";
        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, kb);
    }

    // ── Step 2b: add to express ───────────────────────────────────────────────

    private void handleExpress(BotUpdateContext ctx, String notifKey, String callbackId, int messageId) {
        Optional<BetNotifData> cached = betNotifCache.find(notifKey);
        if (cached.isEmpty()) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⏱ Данные устарели. Введите ставку вручную через /bet");
            return;
        }
        BetNotifData nd = cached.get();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        // Preserve existing express legs
        String legsJson = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_EXPRESS_JSON)
                .filter(j -> !j.isBlank()).orElse("[]");

        // If express already in progress, delete the new choice message and reuse existing wizard
        int existingWizardId = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG)
                .map(s -> { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } })
                .orElse(0);
        boolean hasExpress = !legsJson.equals("[]") && existingWizardId > 0;
        int wizardId = hasExpress ? existingWizardId : messageId;
        if (hasExpress) {
            MessageSend.deleteMessage(ctx.sender(), ctx.chatId(), messageId);
        }

        sessionService.setStateWithContext(ctx.fromId(), BotState.BETTING_WAITING_ODDS, Map.of(
                UserBotSession.CTX_BET_TYPE,          "EXPRESS",
                UserBotSession.CTX_BET_MATCH_TITLE,   nd.matchTitle() != null ? nd.matchTitle() : "",
                UserBotSession.CTX_BET_MATCH_URL,     nd.matchUrl()   != null ? nd.matchUrl()   : "",
                UserBotSession.CTX_BET_BOOKMAKER,     nd.bookmaker()  != null ? nd.bookmaker()  : "",
                UserBotSession.CTX_BET_EXPRESS_JSON,  legsJson,
                UserBotSession.CTX_BET_WIZARD_MSG,    String.valueOf(wizardId)
        ));

        String text = "🎰 *Добавить в экспресс*\n🏆 " + escape(nd.matchTitle())
                    + "\n\nВведите *коэффициент* (например: `1.85`):";
        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), wizardId, text, kb);
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }
}
