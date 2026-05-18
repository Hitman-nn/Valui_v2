package com.valui.bot.handler.callback;

import com.valui.betting.cache.BetNotifCacheService;
import com.valui.betting.cache.BetNotifData;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.BotMarkdownUtil;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.user.api.DetectedEventPortService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handles "🎯 Матч" button tap from controller detail event list.
 *
 * Stores BetNotifData in Redis and shows the single/express choice — same flow
 * as {@link com.valui.bot.handler.callback.betting.BetNotifCallback}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControllerEventBetCallback implements CallbackHandler {

    private static final String PREFIX = CallbackData.CTRL_EVT_BET_PREFIX;

    private final DetectedEventPortService detectedEventPort;
    private final BetNotifCacheService     betNotifCache;

    @Override public String callbackPrefix() { return PREFIX; }
    @Override public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();

        UUID eventId;
        try {
            eventId = UUID.fromString(data.substring(PREFIX.length()));
        } catch (Exception e) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            return;
        }

        DetectedEventEntity event = detectedEventPort.findByIdWithController(eventId).orElse(null);
        if (event == null) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⏱ Событие не найдено или устарело");
            return;
        }
        MessageSend.answerCallback(ctx.sender(), callbackId);

        // Re-use BetNotifCallback flow: store data in Redis keyed by eventId
        String notifKey = "ctrl:event:" + eventId;
        betNotifCache.store(notifKey, new BetNotifData(
                event.getTitle(),
                event.getUrl(),
                event.getController().getBookmaker().name()
        ));

        String text = "💸 *" + BotMarkdownUtil.escapeTitle(event.getTitle()) + "*\n\nВыберите тип ставки:";
        var kb = InlineKeyboardBuilder.create()
                .button("💸 Одиночная",  CallbackData.betNotifSingle(notifKey))
                .button("🎰 В экспресс", CallbackData.betNotifExpress(notifKey)).row()
                .build();
        MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), text, kb);
    }

}
