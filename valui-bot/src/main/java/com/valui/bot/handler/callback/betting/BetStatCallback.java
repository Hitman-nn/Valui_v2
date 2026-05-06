package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.BetStatsDto;
import com.valui.betting.service.BettingService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;

@Slf4j
@Component
@RequiredArgsConstructor
public class BetStatCallback implements CallbackHandler {

    private final BettingService bettingService;

    @Override
    public String callbackPrefix() { return CallbackData.BET_STAT; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        BetStatsDto stats = bettingService.getStats(ctx.fromId());
        String text = buildStatsText(stats);
        var kb = InlineKeyboardBuilder.create()
                .button("← К меню", CallbackData.BET_MENU)
                .build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, kb);
    }

    private static String buildStatsText(BetStatsDto s) {
        String plSign = s.profitLoss().signum() >= 0 ? "+" : "";
        String roiSign = s.roi() >= 0 ? "+" : "";
        return "📊 *Статистика ставок*\n\n"
                + "Всего: " + s.totalBets() + "\n"
                + "  🔵 Открытых: " + s.openBets() + "\n"
                + "  ✅ Выигранных: " + s.wonBets() + "\n"
                + "  ❌ Проигранных: " + s.lostBets() + "\n"
                + "  🔄 Возвратов: " + s.returnedBets() + "\n\n"
                + "💰 Поставлено: *" + s.totalStaked().setScale(2, RoundingMode.HALF_UP) + " ₽*\n"
                + "💵 Выплачено: *" + s.totalPayout().setScale(2, RoundingMode.HALF_UP) + " ₽*\n"
                + "📈 П/У: *" + plSign + s.profitLoss().setScale(2, RoundingMode.HALF_UP) + " ₽*\n"
                + "📉 ROI: *" + roiSign + String.format("%.1f", s.roi()) + "%*";
    }
}
