package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.common.domain.ControllerType;
import com.valui.common.exception.InsufficientTokensException;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.common.exception.ValuiException;
import com.valui.monitor.dto.CreateControllerRequest;
import com.valui.monitor.service.ControllerService;
import com.valui.user.quickadd.QuickAddCacheService;
import com.valui.user.quickadd.QuickAddData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;

/**
 * Handles the "➕ Следить за турниром" inline button embedded in sport-event notifications.
 *
 * Flow:
 *   1. Extract cache key from callback data ("QADD:{notificationLogId}")
 *   2. Fetch {url, bookmaker, title} from Redis via QuickAddCacheService
 *   3. Call ControllerService.addController() directly (no wizard, no confirmation step)
 *   4. On success  → toast "✅ Контроллер добавлен!" + replace button with "✅ Добавлено"
 *   5. On duplicate → toast "✅ Уже добавлен" + replace button (idempotent)
 *   6. On limit     → toast with plan-upgrade hint, button stays (user may upgrade later)
 *   7. Cache miss   → toast "Ссылка устарела", button removed
 */
@Slf4j
@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class QuickAddControllerCallback implements CallbackHandler {

    private final QuickAddCacheService quickAddCacheService;
    private final ControllerService controllerService;

    @Override
    public String callbackPrefix() { return CallbackData.QADD_PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId     = ctx.update().getCallbackQuery().getMessage().getMessageId();

        String cacheKey = data.substring(CallbackData.QADD_PREFIX.length());

        Optional<QuickAddData> cached = quickAddCacheService.find(cacheKey);
        if (cached.isEmpty()) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⏱ Ссылка устарела. Добавьте контроллер вручную через /add");
            removeKeyboard(ctx.sender(), ctx.chatId(), messageId);
            return;
        }

        QuickAddData qd = cached.get();

        try {
            controllerService.addController(
                    new CreateControllerRequest(qd.url(), qd.bookmaker(), qd.title(), false, ControllerType.TOURNAMENT),
                    ctx.fromId(), ctx.chatId());

            log.info("[QUICK-ADD] Controller added: fromId={} notifChat={} bookmaker={} url={}",
                    ctx.fromId(), ctx.chatId(), qd.bookmaker(), qd.url());

            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "✅ Контроллер добавлен!");
            replaceWithDone(ctx.sender(), ctx.chatId(), messageId);

        } catch (InsufficientTokensException e) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⚠️ Недостаточно токенов. Пополните баланс: /plans");
        } catch (SubscriptionLimitExceededException e) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⚠️ Недостаточно токенов. Обновите тариф: /plans");
            // Button stays — user may upgrade and come back to this message

        } catch (ValuiException e) {
            if (e.getHttpStatus() == 409) {
                MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "✅ Уже добавлен");
                replaceWithDone(ctx.sender(), ctx.chatId(), messageId);
            } else {
                log.warn("[QUICK-ADD] Failed chatId={} url={}: {}", ctx.chatId(), qd.url(), e.getMessage());
                MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                        "❌ " + e.getMessage());
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Replaces the "➕ Следить за турниром" button with a disabled "✅ Добавлено". */
    private void replaceWithDone(AbsSender sender, long chatId, int messageId) {
        InlineKeyboardMarkup done = InlineKeyboardBuilder.create()
                .button("✅ Добавлено", CallbackData.NOOP)
                .build();
        editKeyboard(sender, chatId, messageId, done);
    }

    /** Removes the keyboard entirely (e.g. when cache has expired). */
    private void removeKeyboard(AbsSender sender, long chatId, int messageId) {
        editKeyboard(sender, chatId, messageId,
                InlineKeyboardMarkup.builder().keyboard(java.util.List.of()).build());
    }

    private void editKeyboard(AbsSender sender, long chatId, int messageId, InlineKeyboardMarkup markup) {
        try {
            sender.execute(EditMessageReplyMarkup.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .replyMarkup(markup)
                    .build());
        } catch (TelegramApiException e) {
            log.debug("[QUICK-ADD] Cannot edit keyboard chatId={} msgId={}: {}",
                    chatId, messageId, e.getMessage());
        }
    }
}
