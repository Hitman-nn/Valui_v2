package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.common.exception.ValuiException;
import com.valui.user.api.GroupQuotaFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;

/**
 * Handles the "🚀 Расширить квоту группы" inline button.
 *
 * Callback format: "BOOST:{chatId}:{tokens}"
 * Spends {@code tokens} from the user's token_balance, committing them to expand
 * the group's controller quota by the same amount.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupBoostCallback implements CallbackHandler {

    private final GroupQuotaFacade groupQuotaFacade;

    @Override
    public String callbackPrefix() { return CallbackData.BOOST_PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();

        // Parse "BOOST:{chatId}:{tokens}"
        String[] parts = data.substring(CallbackData.BOOST_PREFIX.length()).split(":");
        if (parts.length < 2) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "❌ Некорректный запрос");
            return;
        }

        long groupChatId;
        int tokens;
        try {
            groupChatId = Long.parseLong(parts[0]);
            tokens = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "❌ Некорректный запрос");
            return;
        }

        try {
            groupQuotaFacade.contributeTokens(ctx.fromId(), groupChatId, tokens);
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "✅ Квота группы расширена на " + tokens + " слот(ов)");
            log.info("[BOOST] fromId={} contributed {} tokens to groupChatId={}", ctx.fromId(), tokens, groupChatId);
        } catch (ValuiException e) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "⚠️ " + e.getMessage());
        } catch (Exception e) {
            log.error("[BOOST] Error for fromId={} chatId={}: {}", ctx.fromId(), groupChatId, e.getMessage());
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "❌ Ошибка. Попробуйте позже.");
        }
    }
}
