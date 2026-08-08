package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.BetPersonDto;
import com.valui.betting.dto.BetPersonStatsDto;
import com.valui.betting.service.BetPersonService;
import com.valui.betting.service.BettingService;
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

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class BetPersonCallback implements CallbackHandler {

    private final BetPersonService  personService;
    private final BettingService    bettingService;
    private final BotSessionService sessionService;
    private final BettingChatResolver chatResolver;

    @Override
    public String callbackPrefix() { return "PERS:"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        if (data.equals(CallbackData.PERS_LIST)) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            showList(ctx, messageId);
        } else if (data.equals(CallbackData.PERS_NEW)) {
            handleNew(ctx, callbackId, messageId);
        } else if (data.startsWith(CallbackData.PERS_DEL_PREFIX)) {
            handleDelete(ctx, data, callbackId, messageId);
        } else if (data.startsWith(CallbackData.PERS_STAT_PREFIX)) {
            handleStat(ctx, data, callbackId, messageId);
        } else {
            MessageSend.answerCallback(ctx.sender(), callbackId);
        }
    }

    public void showList(BotUpdateContext ctx, int messageId) {
        List<BetPersonDto> persons = personService.listForChat(chatResolver.resolve(ctx));

        StringBuilder sb = new StringBuilder("👥 *Участники*\n\n");
        if (persons.isEmpty()) sb.append("Участников пока нет. Добавьте первого.");

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetPersonDto p : persons) {
            sb.append("  • *").append(escape(p.displayName())).append("*\n");
            kb.button(escape(p.displayName()), CallbackData.persStat(p.id().toString()))
              .button("🗑", CallbackData.persDel(p.id().toString()))
              .row();
        }
        kb.button("➕ Новый участник", CallbackData.PERS_NEW).row()
          .button("← К меню", CallbackData.BET_MENU);

        if (messageId > 0) {
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
        } else {
            MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), sb.toString(), kb.build());
        }
    }

    private void handleNew(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setStateWithContext(ctx.fromId(), BotState.BETTING_WAITING_PERSON_NAME, Map.of(
                UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(messageId)
        ));
        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.PERS_LIST).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "👤 *Новый участник*\n\nВведите имя участника:", kb);
    }

    private void handleDelete(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String id = data.substring(CallbackData.PERS_DEL_PREFIX.length());
        try {
            personService.delete(UUID.fromString(id), chatResolver.resolve(ctx));
            MessageSend.answerCallback(ctx.sender(), callbackId);
            showList(ctx, messageId);
        } catch (Exception e) {
            log.warn("[PERS] delete failed id={}: {}", id, e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    private void handleStat(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String id = data.substring(CallbackData.PERS_STAT_PREFIX.length());
        try {
            BetPersonStatsDto s = bettingService.getPersonStats(UUID.fromString(id), chatResolver.resolve(ctx));
            String plSign  = s.profitLoss().signum() >= 0 ? "+" : "";
            String roiSign = s.roi() >= 0 ? "+" : "";

            String text = "📊 *" + escape(s.displayName()) + "*\n\n"
                    + "Всего: " + s.totalBets() + "\n"
                    + "  🔵 Открытых: " + s.openBets() + "\n"
                    + "  ✅ Выигранных: " + s.wonBets() + "\n"
                    + "  ❌ Проигранных: " + s.lostBets() + "\n"
                    + "  🔄 Возвратов: " + s.returnedBets() + "\n\n"
                    + "💰 Поставлено: *" + s.totalStaked().setScale(0, RoundingMode.HALF_UP) + " ₽*\n"
                    + "💵 Выплачено: *" + s.totalPayout().setScale(0, RoundingMode.HALF_UP) + " ₽*\n"
                    + "📈 П/У: *" + plSign + s.profitLoss().setScale(0, RoundingMode.HALF_UP) + " ₽*\n"
                    + "📉 ROI: *" + roiSign + String.format("%.1f", s.roi()) + "%*";

            var kb = InlineKeyboardBuilder.create()
                    .button("← Участники", CallbackData.PERS_LIST).build();
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, kb);
            MessageSend.answerCallback(ctx.sender(), callbackId);
        } catch (Exception e) {
            log.warn("[PERS] stat failed id={}: {}", id, e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }
}
