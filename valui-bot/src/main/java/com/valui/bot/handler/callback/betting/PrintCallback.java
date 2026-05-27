package com.valui.bot.handler.callback.betting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.betting.dto.*;
import com.valui.betting.service.BetAccountService;
import com.valui.betting.service.BettingService;
import com.valui.common.domain.BetStatus;
import com.valui.common.domain.BetType;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.UserBotSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Wizard: select account → select person → checkbox bets+transactions → send text.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrintCallback implements CallbackHandler {

    private static final int PAGE_SIZE = 15;
    private static final DateTimeFormatter FMT_SHORT = DateTimeFormatter.ofPattern("dd.MM");

    private final BetAccountService  accountService;
    private final BettingService     bettingService;
    private final BotSessionService  sessionService;
    private final ObjectMapper       objectMapper;

    @Override
    public String callbackPrefix() { return "PRINT:"; }

    @Override
    public int order() { return 53; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        if (data.equals(CallbackData.PRINT_MENU)) {
            showAccountSelect(ctx, messageId);
        } else if (data.startsWith(CallbackData.PRINT_ACCT_PREFIX)) {
            String accountId = data.substring(CallbackData.PRINT_ACCT_PREFIX.length());
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_PRINT_ACCOUNT_ID, accountId);
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_PRINT_PERSON_ID, "");
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_PRINT_SELECTED, "[]");
            showPersonSelect(ctx, messageId);
        } else if (data.startsWith(CallbackData.PRINT_PERS_PREFIX)) {
            String personId = data.substring(CallbackData.PRINT_PERS_PREFIX.length());
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_PRINT_PERSON_ID, personId);
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_PRINT_SELECTED, "[]");
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_PRINT_PAGE, "0");
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_PRINT_MSG, String.valueOf(messageId));
            showItemSelect(ctx, messageId, 0);
        } else if (data.startsWith(CallbackData.PRINT_TOGGLE_PREFIX)) {
            String itemKey = data.substring(CallbackData.PRINT_TOGGLE_PREFIX.length()); // "B:uuid" or "X:uuid"
            toggleItem(ctx, itemKey);
            int page = parseIntCtx(ctx, UserBotSession.CTX_PRINT_PAGE);
            showItemSelect(ctx, messageId, page);
        } else if (data.startsWith(CallbackData.PRINT_PAGE_PREFIX)) {
            int page = Integer.parseInt(data.substring(CallbackData.PRINT_PAGE_PREFIX.length()));
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_PRINT_PAGE, String.valueOf(page));
            showItemSelect(ctx, messageId, page);
        } else if (data.equals(CallbackData.PRINT_SEL_ALL)) {
            selectAllOnPage(ctx);
            int page = parseIntCtx(ctx, UserBotSession.CTX_PRINT_PAGE);
            showItemSelect(ctx, messageId, page);
        } else if (data.equals(CallbackData.PRINT_GO)) {
            generatePrint(ctx, messageId);
        }
    }

    // ── Account selection ─────────────────────────────────────────────────────

    private void showAccountSelect(BotUpdateContext ctx, int messageId) {
        List<BetAccountDto> accounts = accountService.listForChat(ctx.chatId());
        if (accounts.isEmpty()) {
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    "🖨 *Печать*\n\nНет счетов.",
                    InlineKeyboardBuilder.create().button("← К меню", CallbackData.BET_MENU).build());
            return;
        }
        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetAccountDto acc : accounts) {
            kb.button(escape(acc.name()), CallbackData.printAcct(acc.id().toString())).row();
        }
        kb.button("← К меню", CallbackData.BET_MENU);
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "🖨 *Печать — счёт*\n\nВыберите счёт:", kb.build());
    }

    // ── Person selection ──────────────────────────────────────────────────────

    private void showPersonSelect(BotUpdateContext ctx, int messageId) {
        String accountIdStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_PRINT_ACCOUNT_ID).orElse(null);
        if (accountIdStr == null) { showAccountSelect(ctx, messageId); return; }

        UUID accountId = UUID.fromString(accountIdStr);
        List<BetPersonBalanceDto> persons = accountService.getPersonsWithBalances(accountId, ctx.chatId())
                .stream().filter(BetPersonBalanceDto::isLinked).toList();

        if (persons.isEmpty()) {
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    "🖨 *Печать*\n\nНет привязанных участников на этом счёте.",
                    InlineKeyboardBuilder.create()
                            .button("← Счета", CallbackData.PRINT_MENU).build());
            return;
        }

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetPersonBalanceDto p : persons) {
            kb.button(escape(p.personName()), CallbackData.printPers(p.personId().toString())).row();
        }
        kb.button("← Счета", CallbackData.PRINT_MENU);
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "🖨 *Печать — участник*\n\nВыберите участника:", kb.build());
    }

    // ── Item (bet + transaction) checkbox selection ───────────────────────────

    private void showItemSelect(BotUpdateContext ctx, int messageId, int page) {
        String accountIdStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_PRINT_ACCOUNT_ID).orElse(null);
        String personIdStr  = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_PRINT_PERSON_ID).orElse(null);
        if (accountIdStr == null || personIdStr == null || personIdStr.isBlank()) {
            showAccountSelect(ctx, messageId);
            return;
        }

        UUID accountId = UUID.fromString(accountIdStr);
        UUID personId  = UUID.fromString(personIdStr);

        List<BetDto> bets = bettingService.listBetsForPrint(accountId, personId, ctx.chatId());
        List<BetAccountTransactionDto> txs = accountService.getTransactions(accountId, personId, ctx.chatId());

        // Merge and sort by date
        List<PrintItem> all = new ArrayList<>();
        for (BetDto b : bets) {
            if (b.status() != BetStatus.CANCELLED) all.add(new PrintItem("B", b.id().toString(), b.createdAt(), formatBetLabel(b, personId), calcBetPnl(b, personId)));
        }
        for (BetAccountTransactionDto tx : txs) {
            all.add(new PrintItem("X", tx.id().toString(), tx.createdAt(), formatTxLabel(tx), tx.amount()));
        }
        all.sort(Comparator.comparing(PrintItem::date));

        Set<String> selected = loadSelected(ctx);
        int totalPages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int fromIdx = page * PAGE_SIZE;
        int toIdx   = Math.min(fromIdx + PAGE_SIZE, all.size());
        List<PrintItem> pageItems = all.subList(fromIdx, toIdx);

        long selCount = selected.size();
        StringBuilder sb = new StringBuilder("🖨 *Печать*\n\n");
        sb.append("Выбрано: ").append(selCount).append(" из ").append(all.size());
        if (totalPages > 1) sb.append(" (стр. ").append(page + 1).append("/").append(totalPages).append(")");
        sb.append("\n");

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (PrintItem item : pageItems) {
            String key    = item.type() + ":" + item.id();
            boolean isSel = selected.contains(key);
            String label  = (isSel ? "✅ " : "☐ ") + item.label();
            kb.button(label, isSel ? CallbackData.printToggleBet(item.id()) : // reuse toggle, key carries type prefix
                    (item.type().equals("B") ? CallbackData.printToggleBet(item.id()) : CallbackData.printToggleTx(item.id()))).row();
        }

        // Navigation
        if (totalPages > 1) {
            if (page > 0)             kb.button("◀", CallbackData.printPage(page - 1));
            kb.button("Всё на стр.", CallbackData.PRINT_SEL_ALL);
            if (page < totalPages - 1) kb.button("▶", CallbackData.printPage(page + 1));
            kb.row();
        }

        if (!selected.isEmpty()) kb.button("✅ Сформировать", CallbackData.PRINT_GO).row();
        kb.button("← Участник", CallbackData.printAcct(accountIdStr));

        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
    }

    private void toggleItem(BotUpdateContext ctx, String itemKey) {
        Set<String> selected = loadSelected(ctx);
        if (!selected.remove(itemKey)) selected.add(itemKey);
        saveSelected(ctx, selected);
    }

    private void selectAllOnPage(BotUpdateContext ctx) {
        String accountIdStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_PRINT_ACCOUNT_ID).orElse(null);
        String personIdStr  = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_PRINT_PERSON_ID).orElse(null);
        if (accountIdStr == null || personIdStr == null) return;

        UUID accountId = UUID.fromString(accountIdStr);
        UUID personId  = UUID.fromString(personIdStr);
        int page = parseIntCtx(ctx, UserBotSession.CTX_PRINT_PAGE);

        List<BetDto> bets = bettingService.listBetsForPrint(accountId, personId, ctx.chatId());
        List<BetAccountTransactionDto> txs = accountService.getTransactions(accountId, personId, ctx.chatId());

        List<PrintItem> all = new ArrayList<>();
        for (BetDto b : bets) {
            if (b.status() != BetStatus.CANCELLED) all.add(new PrintItem("B", b.id().toString(), b.createdAt(), "", BigDecimal.ZERO));
        }
        for (BetAccountTransactionDto tx : txs) {
            all.add(new PrintItem("X", tx.id().toString(), tx.createdAt(), "", BigDecimal.ZERO));
        }
        all.sort(Comparator.comparing(PrintItem::date));

        Set<String> selected = loadSelected(ctx);
        int fromIdx = page * PAGE_SIZE;
        int toIdx   = Math.min(fromIdx + PAGE_SIZE, all.size());
        for (PrintItem item : all.subList(fromIdx, toIdx)) {
            selected.add(item.type() + ":" + item.id());
        }
        saveSelected(ctx, selected);
    }

    // ── Generate print text ───────────────────────────────────────────────────

    private void generatePrint(BotUpdateContext ctx, int messageId) {
        String accountIdStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_PRINT_ACCOUNT_ID).orElse(null);
        String personIdStr  = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_PRINT_PERSON_ID).orElse(null);
        if (accountIdStr == null || personIdStr == null) return;

        UUID accountId = UUID.fromString(accountIdStr);
        UUID personId  = UUID.fromString(personIdStr);

        List<BetDto> bets = bettingService.listBetsForPrint(accountId, personId, ctx.chatId());
        List<BetAccountTransactionDto> txs = accountService.getTransactions(accountId, personId, ctx.chatId());
        Set<String> selected = loadSelected(ctx);

        // Merge, filter by selected, sort by date
        List<PrintItem> all = new ArrayList<>();
        for (BetDto b : bets) {
            if (b.status() != BetStatus.CANCELLED) {
                String key = "B:" + b.id();
                if (selected.contains(key)) all.add(new PrintItem("B", b.id().toString(), b.createdAt(), formatBetLine(b, personId), calcBetPnl(b, personId)));
            }
        }
        for (BetAccountTransactionDto tx : txs) {
            String key = "X:" + tx.id();
            if (selected.contains(key)) all.add(new PrintItem("X", tx.id().toString(), tx.createdAt(), formatTxLine(tx), tx.amount()));
        }
        all.sort(Comparator.comparing(PrintItem::date));

        if (all.isEmpty()) {
            MessageSend.answerCallback(ctx.sender(), ctx.update().getCallbackQuery().getId());
            return;
        }

        // Get person name and balance
        String personName = bets.stream()
                .flatMap(b -> b.participants().stream())
                .filter(p -> p.personId() != null && p.personId().equals(personId))
                .map(BetParticipantDto::displayName).findFirst().orElse("—");

        List<BetPersonBalanceDto> balances = accountService.getPersonsWithBalances(accountId, ctx.chatId());
        BigDecimal balance = balances.stream()
                .filter(b -> b.personId().equals(personId))
                .map(BetPersonBalanceDto::balance)
                .findFirst().orElse(BigDecimal.ZERO);

        StringBuilder sb = new StringBuilder();
        for (PrintItem item : all) {
            sb.append(item.label()).append("\n");
        }
        sb.append("—\n");
        sb.append(fmtK(balance)).append(" ").append(escape(personName));

        // Send as separate message (so user can copy/forward it)
        MessageSend.text(ctx.sender(), ctx.chatId(), sb.toString());

        // Return to item selection
        showItemSelect(ctx, messageId, parseIntCtx(ctx, UserBotSession.CTX_PRINT_PAGE));
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private String formatBetLabel(BetDto b, UUID personId) {
        String pnlStr = fmtK(calcBetPnl(b, personId));
        String title  = shortTitle(b);
        String suffix = b.type() == BetType.EXPRESS ? " (э)" : "";
        return b.createdAt().format(FMT_SHORT) + " " + pnlStr + " " + title + suffix;
    }

    private String formatTxLabel(BetAccountTransactionDto tx) {
        return tx.createdAt().format(FMT_SHORT) + " " + fmtK(tx.amount()) + " " + tx.personName();
    }

    /** Print line format: {pnl} {full title} (э if express). */
    private String formatBetLine(BetDto b, UUID personId) {
        String pnlStr = fmtK(calcBetPnl(b, personId));
        String title;
        if (b.slips().size() <= 1) {
            title = b.slips().isEmpty() ? "—" : b.slips().get(0).matchTitle().toLowerCase();
        } else {
            title = b.slips().stream()
                    .map(s -> s.matchTitle().toLowerCase())
                    .collect(Collectors.joining(", ")) + " (э)";
        }
        return pnlStr + " " + title;
    }

    private String formatTxLine(BetAccountTransactionDto tx) {
        return fmtK(tx.amount()) + " " + tx.personName().toLowerCase();
    }

    private BigDecimal calcBetPnl(BetDto b, UUID personId) {
        if (b.status() == BetStatus.OPEN) {
            return participantStake(b, personId).negate();
        }
        if (b.status() == BetStatus.RETURNED) return BigDecimal.ZERO;
        if (b.status() == BetStatus.LOST)    return participantStake(b, personId).negate();
        // WON
        if (b.actualPayout() == null) return BigDecimal.ZERO;
        BigDecimal stake = participantStake(b, personId);
        BigDecimal share = participantShare(b, personId);
        return share.multiply(b.actualPayout()).subtract(stake).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal participantStake(BetDto b, UUID personId) {
        return b.participants().stream()
                .filter(p -> personId.equals(p.personId()))
                .map(BetParticipantDto::stake)
                .findFirst().orElse(b.totalStake());
    }

    private BigDecimal participantShare(BetDto b, UUID personId) {
        return b.participants().stream()
                .filter(p -> personId.equals(p.personId()))
                .map(BetParticipantDto::profitShare)
                .findFirst().orElse(BigDecimal.ONE);
    }

    /** Format BigDecimal in thousands with up to 3 decimal places, comma separator, with sign. */
    static String fmtK(BigDecimal v) {
        if (v == null) return "0";
        BigDecimal thousands = v.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP).stripTrailingZeros();
        String plain = thousands.toPlainString().replace('.', ',');
        return (v.signum() >= 0 ? "+" : "") + plain;
    }

    private String shortTitle(BetDto b) {
        if (b.slips().isEmpty()) return "—";
        String title = b.slips().get(0).matchTitle();
        if (b.slips().size() > 1) {
            title = b.slips().stream().map(s -> {
                String t = s.matchTitle();
                return t.length() > 12 ? t.substring(0, 11) + "…" : t;
            }).collect(Collectors.joining("/"));
        }
        return title.length() > 30 ? title.substring(0, 28) + "…" : title;
    }

    // ── Session helpers ───────────────────────────────────────────────────────

    private Set<String> loadSelected(BotUpdateContext ctx) {
        String json = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_PRINT_SELECTED).orElse("[]");
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<>() {});
            return new LinkedHashSet<>(list);
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    private void saveSelected(BotUpdateContext ctx, Set<String> selected) {
        try {
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_PRINT_SELECTED,
                    objectMapper.writeValueAsString(new ArrayList<>(selected)));
        } catch (Exception e) {
            log.warn("[PRINT] failed to save selection: {}", e.getMessage());
        }
    }

    private int parseIntCtx(BotUpdateContext ctx, String key) {
        return sessionService.getContext(ctx.fromId(), key)
                .map(s -> { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } })
                .orElse(0);
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }

    private record PrintItem(String type, String id, OffsetDateTime date, String label, BigDecimal pnl) {}
}
