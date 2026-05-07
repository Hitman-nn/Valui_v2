package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.BetDto;
import com.valui.betting.service.BettingService;
import com.valui.common.domain.BetStatus;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Shows paginated list of bets (open or all).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BetListCallback implements CallbackHandler {

    private static final int PAGE_SIZE = 5;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yy");

    private final BettingService bettingService;

    @Override
    public String callbackPrefix() { return "BET:LIST"; }

    @Override
    public int order() { return 52; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        MessageSend.answerCallback(ctx.sender(), callbackId);

        boolean openOnly;
        int pageNum = 0;

        if (data.startsWith(CallbackData.BET_LIST_OPEN + ":P:")) {
            openOnly = true;
            pageNum  = Integer.parseInt(data.substring((CallbackData.BET_LIST_OPEN + ":P:").length()));
        } else if (data.startsWith(CallbackData.BET_LIST_ALL + ":P:")) {
            openOnly = false;
            pageNum  = Integer.parseInt(data.substring((CallbackData.BET_LIST_ALL + ":P:").length()));
        } else {
            openOnly = data.equals(CallbackData.BET_LIST_OPEN);
        }

        BetStatus filter = openOnly ? BetStatus.OPEN : null;
        Page<BetDto> page = bettingService.listBets(ctx.chatId(), filter, PageRequest.of(pageNum, PAGE_SIZE));
        showList(ctx, page, openOnly, pageNum, messageId);
    }

    private void showList(BotUpdateContext ctx, Page<BetDto> page, boolean openOnly, int pageNum, int messageId) {
        List<BetDto> bets = page.getContent();
        String title = openOnly ? "📋 *Открытые ставки*" : "📚 *Все ставки*";

        if (bets.isEmpty()) {
            String text = title + "\n\nСтавок нет.";
            var kb = InlineKeyboardBuilder.create().button("← К меню", CallbackData.BET_MENU).build();
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, kb);
            return;
        }

        StringBuilder sb = new StringBuilder(title).append("\n\n");
        for (BetDto bet : bets) {
            sb.append(statusEmoji(bet.status())).append(" ")
              .append(escape(bet.mainTitle())).append(" @ ")
              .append(bet.totalOdds().setScale(2, RoundingMode.HALF_UP))
              .append(" — ").append(bet.totalStake().setScale(0, RoundingMode.HALF_UP)).append(" ₽")
              .append(" [").append(bet.createdAt().format(FMT)).append("]\n");
        }
        sb.append("\nСтраница ").append(pageNum + 1).append(" / ").append(page.getTotalPages());

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetDto bet : bets) {
            String label = statusEmoji(bet.status()) + " " + shortTitle(bet);
            kb.button(label, CallbackData.betDetail(bet.id().toString())).row();
        }
        // Pagination
        if (pageNum > 0) {
            kb.button("◀", CallbackData.betListPage(openOnly, pageNum - 1));
        }
        if (page.hasNext()) {
            kb.button("▶", CallbackData.betListPage(openOnly, pageNum + 1));
        }
        if (pageNum > 0 || page.hasNext()) kb.row();
        kb.button("← К меню", CallbackData.BET_MENU);

        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
    }

    private static String statusEmoji(BetStatus s) {
        return switch (s) {
            case OPEN      -> "🔵";
            case WON       -> "✅";
            case LOST      -> "❌";
            case RETURNED  -> "🔄";
            case CANCELLED -> "🚫";
        };
    }

    private static String shortTitle(BetDto bet) {
        String t = bet.mainTitle();
        return t.length() > 25 ? t.substring(0, 23) + "…" : t;
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }
}
