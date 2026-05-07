package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.BetDto;
import com.valui.betting.dto.BetParticipantDto;
import com.valui.betting.dto.BetSlipDto;
import com.valui.betting.service.BettingService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.common.domain.BetStatus;
import com.valui.common.domain.BetType;
import com.valui.common.domain.SlipResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/**
 * Shows bet detail and handles result marking.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BetDetailCallback implements CallbackHandler {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");

    private final BettingService bettingService;

    @Override
    public String callbackPrefix() { return CallbackData.BET_DETAIL_PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        String betIdStr = data.substring(CallbackData.BET_DETAIL_PREFIX.length());

        try {
            BetDto bet = bettingService.getBet(java.util.UUID.fromString(betIdStr), ctx.chatId());
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    buildDetailText(bet), buildDetailKeyboard(bet));
            MessageSend.answerCallback(ctx.sender(), callbackId);
        } catch (Exception e) {
            log.warn("[BET] getBet failed id={} fromId={}: {}", betIdStr, ctx.fromId(), e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    // ── Static builders (reused by BettingMenuCallback) ──────────────────────

    public static String buildDetailText(BetDto bet) {
        StringBuilder sb = new StringBuilder();
        sb.append(statusEmoji(bet.status())).append(" *").append(typeName(bet)).append("*");
        if (bet.status() != BetStatus.OPEN) {
            sb.append(" — ").append(statusName(bet.status()));
        }
        sb.append("\n\n");

        for (BetSlipDto slip : bet.slips()) {
            sb.append(slipEmoji(slip.result())).append(" ")
              .append(escape(slip.matchTitle()))
              .append(" @ *").append(slip.odds().setScale(2, RoundingMode.HALF_UP)).append("*\n");
            if (slip.matchUrl() != null && !slip.matchUrl().isBlank()) {
                sb.append("🔗 ").append(slip.matchUrl()).append("\n");
            }
        }

        sb.append("\n💰 Ставка: *").append(bet.totalStake().setScale(0, RoundingMode.HALF_UP)).append(" ₽*\n");
        sb.append("📈 Кэф: *").append(bet.totalOdds().setScale(2, RoundingMode.HALF_UP)).append("*\n");
        sb.append("🎯 Потенц. выигрыш: *").append(bet.potentialPayout().setScale(0, RoundingMode.HALF_UP)).append(" ₽*\n");

        if (bet.actualPayout() != null && bet.status() != BetStatus.OPEN) {
            sb.append("✅ Фактически: *").append(bet.actualPayout().setScale(0, RoundingMode.HALF_UP)).append(" ₽*\n");
        }

        if (bet.accountName() != null) {
            sb.append("\n💰 Счёт: *").append(escape(bet.accountName())).append("*\n");
        }

        if (!bet.participants().isEmpty()) {
            sb.append("\n👥 Участники:\n");
            for (BetParticipantDto p : bet.participants()) {
                String name = p.displayName() != null ? p.displayName() : "—";
                sb.append("  • ").append(escape(name))
                  .append(": ").append(p.stake().setScale(0, RoundingMode.HALF_UP)).append(" ₽\n");
            }
        }

        if (bet.resolvedAt() != null) {
            sb.append("\n🕐 ").append(bet.resolvedAt().format(FMT));
        } else {
            sb.append("\n🕐 ").append(bet.createdAt().format(FMT));
        }

        return sb.toString();
    }

    public static InlineKeyboardMarkup buildDetailKeyboard(BetDto bet) {
        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        if (bet.status() == BetStatus.OPEN) {
            if (bet.type() == BetType.EXPRESS) {
                for (BetSlipDto slip : bet.slips()) {
                    if (slip.result() == SlipResult.OPEN) {
                        int num = slip.sortOrder() + 1;
                        kb.button("✅ " + num, CallbackData.betSlipResolve(bet.id().toString(), slip.sortOrder(), "W"))
                          .button("❌ " + num, CallbackData.betSlipResolve(bet.id().toString(), slip.sortOrder(), "L"))
                          .button("🔄 " + num, CallbackData.betSlipResolve(bet.id().toString(), slip.sortOrder(), "R"))
                          .row();
                    }
                }
            } else {
                kb.button("✅ Выиграл",  CallbackData.betResolve(bet.id().toString(), "WIN"))
                  .button("❌ Проиграл", CallbackData.betResolve(bet.id().toString(), "LOSE"))
                  .button("🔄 Возврат",  CallbackData.betResolve(bet.id().toString(), "RETURN")).row();
            }
            kb.button("🚫 Отменить", CallbackData.betCancel(bet.id().toString())).row();
        }
        kb.button("🗑 Удалить", CallbackData.betDelete(bet.id().toString()))
          .button("← К меню", CallbackData.BET_MENU);
        return kb.build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String statusEmoji(BetStatus s) {
        return switch (s) {
            case OPEN      -> "🔵";
            case WON       -> "✅";
            case LOST      -> "❌";
            case RETURNED  -> "🔄";
            case CANCELLED -> "🚫";
        };
    }

    private static String statusName(BetStatus s) {
        return switch (s) {
            case OPEN      -> "Открыта";
            case WON       -> "Выиграна";
            case LOST      -> "Проиграна";
            case RETURNED  -> "Возврат";
            case CANCELLED -> "Отменена";
        };
    }

    private static String typeName(BetDto bet) {
        return bet.type() == com.valui.common.domain.BetType.EXPRESS ? "Экспресс" : "Одиночная ставка";
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }

    private static String slipEmoji(SlipResult r) {
        return switch (r) {
            case OPEN     -> "🏆";
            case WON      -> "✅";
            case LOST     -> "❌";
            case RETURNED -> "🔄";
        };
    }
}
