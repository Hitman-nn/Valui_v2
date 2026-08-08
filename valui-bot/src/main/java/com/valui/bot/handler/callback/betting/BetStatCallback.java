package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.BetAccountDto;
import com.valui.betting.dto.BetAccountStatsDto;
import com.valui.betting.dto.BetPersonBalanceDto;
import com.valui.betting.dto.BetPersonDto;
import com.valui.betting.dto.BetPersonStatsDto;
import com.valui.betting.dto.BetStatsDto;
import com.valui.betting.service.BetAccountService;
import com.valui.betting.service.BetPersonService;
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
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class BetStatCallback implements CallbackHandler {

    private final BettingService   bettingService;
    private final BetAccountService accountService;
    private final BetPersonService  personService;
    private final BettingChatResolver chatResolver;

    @Override
    public String callbackPrefix() { return CallbackData.BET_STAT; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        if (data.startsWith(CallbackData.BET_STAT_ACCT_PREFIX)) {
            String accountIdStr = data.substring(CallbackData.BET_STAT_ACCT_PREFIX.length());
            try {
                showAccountStats(ctx, UUID.fromString(accountIdStr), messageId);
            } catch (Exception e) {
                log.warn("[STAT] account stats failed id={}: {}", accountIdStr, e.getMessage());
                MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
            }
        } else {
            showOverallStats(ctx, messageId);
        }
    }

    // ── Overall stats ─────────────────────────────────────────────────────────

    private void showOverallStats(BotUpdateContext ctx, int messageId) {
        long scopeChatId = chatResolver.resolve(ctx);
        BetStatsDto stats = bettingService.getStats(scopeChatId);
        List<BetPersonDto> persons = personService.listForChat(scopeChatId);
        List<BetAccountDto> accounts = accountService.listForChat(scopeChatId);

        StringBuilder sb = new StringBuilder("📊 *Общая статистика*\n\n");
        sb.append(buildOverallStatsText(stats));

        if (!persons.isEmpty()) {
            sb.append("\n\n👥 *По участникам:*\n");
            for (BetPersonDto person : persons) {
                try {
                    BetPersonStatsDto ps = bettingService.getPersonStats(person.id(), scopeChatId);
                    sb.append(buildPersonStatLine(ps));
                } catch (Exception e) {
                    log.warn("[STAT] person stats skipped id={}: {}", person.id(), e.getMessage());
                }
            }
        }

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetAccountDto acc : accounts) {
            kb.button("📊 " + acc.name(), CallbackData.betStatAcct(acc.id().toString())).row();
        }
        kb.button("← К меню", CallbackData.BET_MENU);

        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
    }

    // ── Per-account stats ─────────────────────────────────────────────────────

    private void showAccountStats(BotUpdateContext ctx, UUID accountId, int messageId) {
        BetAccountStatsDto s = bettingService.getAccountStats(accountId, chatResolver.resolve(ctx));

        StringBuilder sb = new StringBuilder("📊 *Счёт: " + escape(s.accountName()) + "*\n\n");

        sb.append("Ставок всего: ").append(s.totalBets()).append("\n");
        sb.append("  🔵 Открытых: ").append(s.openBets()).append("\n");
        sb.append("  ✅ Выигранных: ").append(s.wonBets()).append("\n");
        sb.append("  ❌ Проигранных: ").append(s.lostBets()).append("\n");
        sb.append("  🔄 Возвратов: ").append(s.returnedBets()).append("\n\n");

        String plSign = s.profitLoss().signum() >= 0 ? "+" : "";
        sb.append("💰 Объём ставок: *").append(s.totalVolume().setScale(0, RoundingMode.HALF_UP)).append(" ₽*\n");
        sb.append("💵 Выплачено: *").append(s.totalPayout().setScale(0, RoundingMode.HALF_UP)).append(" ₽*\n");
        sb.append("📈 П/У: *").append(plSign).append(s.profitLoss().setScale(0, RoundingMode.HALF_UP)).append(" ₽*\n");

        List<BetPersonBalanceDto> linked = s.balances().stream()
                .filter(BetPersonBalanceDto::isLinked).toList();
        if (!linked.isEmpty()) {
            sb.append("\n💼 *Балансы участников:*\n");
            for (BetPersonBalanceDto b : linked) {
                String sign = b.balance().signum() >= 0 ? "+" : "";
                sb.append("  • ").append(escape(b.personName())).append(": *")
                  .append(sign).append(b.balance().setScale(0, RoundingMode.HALF_UP)).append(" ₽*\n");
            }
        }

        var kb = InlineKeyboardBuilder.create()
                .button("← Общая стата", CallbackData.BET_STAT)
                .button("← К меню", CallbackData.BET_MENU)
                .build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String buildOverallStatsText(BetStatsDto s) {
        String plSign  = s.profitLoss().signum() >= 0 ? "+" : "";
        String roiSign = s.roi() >= 0 ? "+" : "";
        return "Ставок всего: " + s.totalBets() + "\n"
                + "  🔵 Открытых: " + s.openBets() + "\n"
                + "  ✅ Выигранных: " + s.wonBets() + "\n"
                + "  ❌ Проигранных: " + s.lostBets() + "\n"
                + "  🔄 Возвратов: " + s.returnedBets() + "\n"
                + "  🚫 Отменённых: " + s.cancelledBets() + "\n\n"
                + "💰 Поставлено: *" + s.totalStaked().setScale(0, RoundingMode.HALF_UP) + " ₽*\n"
                + "💵 Выплачено: *" + s.totalPayout().setScale(0, RoundingMode.HALF_UP) + " ₽*\n"
                + "📈 П/У: *" + plSign + s.profitLoss().setScale(0, RoundingMode.HALF_UP) + " ₽*\n"
                + "📉 ROI: *" + roiSign + String.format("%.1f", s.roi()) + "%*";
    }

    private static String buildPersonStatLine(BetPersonStatsDto ps) {
        String plSign  = ps.profitLoss().signum() >= 0 ? "+" : "";
        String roiSign = ps.roi() >= 0 ? "+" : "";
        return "  • *" + escape(ps.displayName()) + "* — ставок: " + ps.totalBets()
                + " (✅" + ps.wonBets() + "/❌" + ps.lostBets() + ")"
                + ", П/У: *" + plSign + ps.profitLoss().setScale(0, RoundingMode.HALF_UP) + " ₽*"
                + ", ROI: " + roiSign + String.format("%.1f", ps.roi()) + "%\n";
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }
}
