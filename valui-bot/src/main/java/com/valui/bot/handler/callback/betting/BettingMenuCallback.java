package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.*;
import com.valui.betting.service.BetAccountService;
import com.valui.betting.service.BetPersonService;
import com.valui.betting.service.BettingService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.prematch.PreMatchOddsService;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.common.domain.BetType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class BettingMenuCallback implements CallbackHandler {

    private final BotSessionService  sessionService;
    private final BettingService     bettingService;
    private final BetAccountService  accountService;
    private final BetPersonService   personService;
    private final ObjectMapper       objectMapper;
    private final PreMatchOddsService preMatchOddsService;

    @Override
    public String callbackPrefix() { return "BET:"; }

    @Override
    public int order() { return 55; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        switch (data) {
            case CallbackData.BET_MENU            -> handleMenu(ctx, callbackId, messageId);
            case CallbackData.BET_NEW_SINGLE      -> handleNewSingle(ctx, callbackId, messageId);
            case CallbackData.BET_NEW_EXPRESS     -> handleNewExpress(ctx, callbackId, messageId);
            case CallbackData.BET_EXPRESS_ADD     -> handleExpressAdd(ctx, callbackId, messageId);
            case CallbackData.BET_EXPRESS_DONE    -> handleExpressDone(ctx, callbackId, messageId);
            case CallbackData.BET_CONFIRM         -> handleConfirm(ctx, callbackId, messageId);
            case CallbackData.BET_CANCEL_WIZARD   -> handleCancelWizard(ctx, callbackId, messageId);
            case CallbackData.BET_ADD_PARTICIPANT -> { MessageSend.answerCallback(ctx.sender(), callbackId); showParticipantStep(ctx, messageId); }
            case CallbackData.BET_PART_DONE       -> handlePartDone(ctx, callbackId, messageId);
            case CallbackData.BET_PART_STEP       -> { MessageSend.answerCallback(ctx.sender(), callbackId); showParticipantStep(ctx, messageId); }
            case CallbackData.BET_ACCT_STEP       -> { MessageSend.answerCallback(ctx.sender(), callbackId); showAccountStep(ctx, messageId); }
            default -> {
                if (data.startsWith(CallbackData.BET_ACCT_SEL_PREFIX)) {
                    handleAcctSel(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_PART_TOGGLE_PREFIX)) {
                    handlePartToggle(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_PART_SPLIT_PREFIX)) {
                    handlePartSplit(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_PART_AMT_PREFIX)) {
                    handlePartAmtEdit(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_SLIP_RESOLVE_PREFIX)) {
                    handleSlipResolve(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_RESOLVE_PREFIX)) {
                    handleResolve(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_DELETE_CONFIRM_PREFIX)) {
                    handleDeleteBet(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_DELETE_PREFIX)) {
                    handleDeleteConfirm(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_CANCEL_PREFIX)) {
                    handleCancelBet(ctx, data, callbackId, messageId);
                } else {
                    MessageSend.answerCallback(ctx.sender(), callbackId);
                }
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    private void handleMenu(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.clearSession(ctx.fromId());
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                buildMenuText(ctx), buildMenuKeyboard());
    }

    public static String buildMenuText(BotUpdateContext ctx) {
        return "💸 *Журнал ставок*\n\nВыберите действие:";
    }

    public static org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup buildMenuKeyboard() {
        return InlineKeyboardBuilder.create()
                .button("💸 Новая ставка",    CallbackData.BET_NEW_SINGLE)
                .button("🎰 Экспресс",        CallbackData.BET_NEW_EXPRESS).row()
                .button("📋 Открытые ставки", CallbackData.BET_LIST_OPEN)
                .button("📚 Все ставки",      CallbackData.BET_LIST_ALL).row()
                .button("📊 Статистика",      CallbackData.BET_STAT).row()
                .button("💰 Счета",           CallbackData.ACCT_LIST)
                .button("👥 Участники",       CallbackData.PERS_LIST).row()
                .build();
    }

    // ── New single bet ────────────────────────────────────────────────────────

    private void handleNewSingle(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setStateWithContext(ctx.fromId(), BotState.BETTING_WAITING_TITLE, Map.of(
                UserBotSession.CTX_BET_TYPE,       "SINGLE",
                UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(messageId)
        ));
        var kb = InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "💸 *Новая ставка*\n\nВведите *название матча*:", kb);
    }

    // ── New express ───────────────────────────────────────────────────────────

    private void handleNewExpress(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setStateWithContext(ctx.fromId(), BotState.BETTING_WAITING_TITLE, Map.of(
                UserBotSession.CTX_BET_TYPE,        "EXPRESS",
                UserBotSession.CTX_BET_EXPRESS_JSON, "[]",
                UserBotSession.CTX_BET_WIZARD_MSG,  String.valueOf(messageId)
        ));
        showExpressMenu(ctx, messageId, List.of());
    }

    private void handleExpressAdd(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_WAITING_TITLE, Map.of(
                UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(messageId)
        ));
        var kb = InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "🎰 *Добавить событие*\n\nВведите *название матча*:", kb);
    }

    private void handleExpressDone(BotUpdateContext ctx, String callbackId, int messageId) {
        List<BetSlipRequest> legs = parseLegs(ctx);
        if (legs.size() < 2) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⚠️ Добавьте минимум 2 события для экспресса");
            return;
        }
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_WAITING_AMOUNT, Map.of(
                UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(messageId)
        ));
        var kb = InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "🎰 *Экспресс*\n\nВведите *сумму ставки* (₽):", kb);
    }

    // ── Confirm / Place ───────────────────────────────────────────────────────

    private void handleConfirm(BotUpdateContext ctx, String callbackId, int messageId) {
        try {
            BetDto placed = buildAndPlace(ctx);
            sessionService.clearSession(ctx.fromId());
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    BetDetailCallback.buildDetailText(placed), BetDetailCallback.buildDetailKeyboard(placed));
            MessageSend.answerCallback(ctx.sender(), callbackId);
            // Async: fetch odds + schedule -30 min snapshot for each slip
            placed.slips().forEach(slip -> preMatchOddsService.register(slip.id()));
        } catch (Exception e) {
            log.error("[BETTING] Place bet failed fromId={}: {}", ctx.fromId(), e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    // ── Account selection step ────────────────────────────────────────────────

    public void showAccountStep(BotUpdateContext ctx, int messageId) {
        List<BetAccountDto> accounts = accountService.listForChat(ctx.chatId());
        String currentId = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_ACCOUNT_ID).orElse(null);

        StringBuilder sb = new StringBuilder("🏦 *Выберите счёт*\n\n");
        if (accounts.isEmpty()) {
            sb.append("Счётов пока нет. Создайте счёт в меню *Счета*.");
        }

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetAccountDto acc : accounts) {
            boolean sel = acc.id().toString().equals(currentId);
            kb.button((sel ? "✅ " : "") + escape(acc.name()),
                    CallbackData.betAcctSel(acc.id().toString())).row();
        }
        kb.button("⏭ Без счёта", CallbackData.betAcctSel("none")).row()
          .button("✅ Далее →",   CallbackData.BET_ADD_PARTICIPANT).row()
          .button("✕ Отмена",    CallbackData.BET_CANCEL_WIZARD);

        int resultId = MessageSend.editOrSendMarkdown(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
        if (messageId == 0 && resultId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(resultId));
    }

    private void handleAcctSel(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String id = data.substring(CallbackData.BET_ACCT_SEL_PREFIX.length());
        MessageSend.answerCallback(ctx.sender(), callbackId);
        if ("none".equals(id)) {
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_ACCOUNT_ID, "");
        } else {
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_ACCOUNT_ID, id);
        }
        showAccountStep(ctx, messageId);
    }

    // ── Participant step ──────────────────────────────────────────────────────

    private void handlePartDone(BotUpdateContext ctx, String callbackId, int messageId) {
        List<ParticipantRequest> parts = parseParticipants(ctx, parseAmount(ctx));
        if (parts.isEmpty()) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "⚠️ Выберите хотя бы одного участника");
            return;
        }
        MessageSend.answerCallback(ctx.sender(), callbackId);
        showFullConfirmation(ctx, messageId);
    }

    /** Public so BettingTextHandler can call it. */
    public void showParticipantStep(BotUpdateContext ctx, int messageId) {
        BigDecimal amount = parseAmount(ctx);
        List<BetPersonDto> allPersons = personService.listForChat(ctx.chatId());
        List<ParticipantRequest> selected = parseParticipants(ctx, amount);
        Set<String> selectedIds = selected.stream()
                .map(p -> p.personId() != null ? p.personId().toString() : "")
                .collect(Collectors.toCollection(LinkedHashSet::new));

        StringBuilder sb = new StringBuilder("👥 *С кем ставишь?*\n\n");
        if (allPersons.isEmpty()) {
            sb.append("Участников нет. Добавьте их в меню *Участники*.\n");
        } else if (selected.isEmpty()) {
            sb.append("Никто не выбран.\n");
        } else if (selected.size() == 1) {
            ParticipantRequest p = selected.get(0);
            sb.append(escape(p.displayName())).append(" — 100%\n");
        } else {
            for (ParticipantRequest p : selected) {
                int pct = p.profitShare().multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP).intValue();
                sb.append(escape(p.displayName())).append(" — ").append(pct).append("%\n");
            }
        }

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetPersonDto person : allPersons) {
            boolean sel = selectedIds.contains(person.id().toString());
            String label = (sel ? "✅ " : "☐ ") + escape(person.displayName());
            kb.button(label, CallbackData.betPartToggle(person.id().toString()));
            if (sel) {
                selected.stream().filter(p -> person.id().toString().equals(
                        p.personId() != null ? p.personId().toString() : ""))
                        .findFirst()
                        .ifPresent(p -> kb.button("✏️ " + p.stake().setScale(0, RoundingMode.HALF_UP) + "₽",
                                CallbackData.betPartAmtEdit(person.id().toString())));
            }
            kb.row();
        }

        // Ratio presets for exactly 2 participants
        if (selected.size() == 2) {
            BigDecimal firstShare = selected.get(0).profitShare();
            kb.button(isRatio(firstShare,1,1) ? "✅ 1:1" : "1:1", CallbackData.betPartSplit(1,1))
              .button(isRatio(firstShare,2,1) ? "✅ 2:1" : "2:1", CallbackData.betPartSplit(2,1))
              .button(isRatio(firstShare,1,2) ? "✅ 1:2" : "1:2", CallbackData.betPartSplit(1,2)).row()
              .button(isRatio(firstShare,3,1) ? "✅ 3:1" : "3:1", CallbackData.betPartSplit(3,1))
              .button(isRatio(firstShare,1,3) ? "✅ 1:3" : "1:3", CallbackData.betPartSplit(1,3))
              .button(isRatio(firstShare,3,2) ? "✅ 3:2" : "3:2", CallbackData.betPartSplit(3,2))
              .button(isRatio(firstShare,2,3) ? "✅ 2:3" : "2:3", CallbackData.betPartSplit(2,3)).row();
        }

        kb.button("✅ Готово",   CallbackData.BET_PART_DONE).row()
          .button("← Счёт",    CallbackData.BET_ACCT_STEP).row()
          .button("✕ Отмена",   CallbackData.BET_CANCEL_WIZARD);

        int resultId = MessageSend.editOrSendMarkdown(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
        if (messageId == 0 && resultId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(resultId));
    }

    // ── Participant: toggle ───────────────────────────────────────────────────

    private void handlePartToggle(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String personIdStr = data.substring(CallbackData.BET_PART_TOGGLE_PREFIX.length());
        MessageSend.answerCallback(ctx.sender(), callbackId);

        BigDecimal amount = parseAmount(ctx);
        List<BetPersonDto> allPersons = personService.listForChat(ctx.chatId());
        List<ParticipantRequest> parts = new ArrayList<>(parseParticipants(ctx, amount));

        boolean alreadyIn = parts.stream().anyMatch(p -> personIdStr.equals(
                p.personId() != null ? p.personId().toString() : ""));
        if (alreadyIn) {
            parts.removeIf(p -> personIdStr.equals(p.personId() != null ? p.personId().toString() : ""));
        } else {
            String name = allPersons.stream()
                    .filter(p -> p.id().toString().equals(personIdStr))
                    .map(BetPersonDto::displayName).findFirst().orElse("?");
            parts.add(new ParticipantRequest(UUID.fromString(personIdStr), name, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        saveParticipants(ctx, redistributeEvenly(parts, amount));
        showParticipantStep(ctx, messageId);
    }

    // ── Participant: split ratio ──────────────────────────────────────────────

    private void handlePartSplit(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String[] parts2 = data.substring(CallbackData.BET_PART_SPLIT_PREFIX.length()).split(":");
        int myN   = Integer.parseInt(parts2[0]);
        int partN = Integer.parseInt(parts2[1]);
        MessageSend.answerCallback(ctx.sender(), callbackId);

        BigDecimal amount = parseAmount(ctx);
        List<ParticipantRequest> parts = new ArrayList<>(parseParticipants(ctx, amount));
        if (parts.size() != 2) { showParticipantStep(ctx, messageId); return; }

        BigDecimal share0  = BigDecimal.valueOf(myN).divide(BigDecimal.valueOf(myN + partN), 4, RoundingMode.HALF_UP);
        BigDecimal share1  = BigDecimal.ONE.subtract(share0);
        BigDecimal stake0  = amount.multiply(share0).setScale(2, RoundingMode.HALF_UP);
        BigDecimal stake1  = amount.subtract(stake0);

        ParticipantRequest p0 = parts.get(0);
        ParticipantRequest p1 = parts.get(1);
        saveParticipants(ctx, List.of(
                new ParticipantRequest(p0.personId(), p0.displayName(), stake0, share0),
                new ParticipantRequest(p1.personId(), p1.displayName(), stake1, share1)
        ));
        showParticipantStep(ctx, messageId);
    }

    // ── Participant: edit stake amount ────────────────────────────────────────

    private void handlePartAmtEdit(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String personIdStr = data.substring(CallbackData.BET_PART_AMT_PREFIX.length());
        MessageSend.answerCallback(ctx.sender(), callbackId);

        BigDecimal amount = parseAmount(ctx);
        List<ParticipantRequest> parts = parseParticipants(ctx, amount);
        ParticipantRequest p = parts.stream()
                .filter(x -> personIdStr.equals(x.personId() != null ? x.personId().toString() : ""))
                .findFirst().orElse(null);
        if (p == null) { showParticipantStep(ctx, messageId); return; }

        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_WAITING_PART_AMOUNT, Map.of(
                UserBotSession.CTX_BET_EDITING_PART_ID, personIdStr,
                UserBotSession.CTX_BET_WIZARD_MSG,      String.valueOf(messageId)
        ));

        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "✏️ *" + escape(p.displayName()) + "*\n\nТекущая сумма: *"
                        + p.stake().setScale(0, RoundingMode.HALF_UP) + " ₽*\n\n"
                        + "Общая ставка: *" + amount.setScale(0, RoundingMode.HALF_UP) + " ₽*\n\n"
                        + "Введите новую сумму для этого участника:", kb);
    }

    // ── Full confirmation ─────────────────────────────────────────────────────

    /** Public so BettingTextHandler can call it. */
    public void showFullConfirmation(BotUpdateContext ctx, int messageId) {
        String betType    = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_TYPE).orElse("SINGLE");
        BigDecimal amount = parseAmount(ctx);
        List<ParticipantRequest> parts = parseParticipants(ctx, amount);

        String typeLabel = "EXPRESS".equals(betType) ? "🎰 Экспресс" : "💸 Одиночная";
        StringBuilder sb = new StringBuilder(typeLabel + " *— подтверждение*\n\n");

        BigDecimal totalOdds;
        if ("EXPRESS".equals(betType)) {
            List<BetSlipRequest> legs = parseLegs(ctx);
            totalOdds = legs.stream().map(BetSlipRequest::odds)
                    .reduce(BigDecimal.ONE, BigDecimal::multiply)
                    .setScale(4, RoundingMode.HALF_UP);
            for (int i = 0; i < legs.size(); i++) {
                sb.append(i + 1).append(". ").append(escape(legs.get(i).matchTitle()))
                  .append(" @ ").append(legs.get(i).odds()).append("\n");
            }
            sb.append("📈 Общий кэф: *").append(totalOdds.setScale(2, RoundingMode.HALF_UP)).append("*\n");
        } else {
            String title   = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_MATCH_TITLE).orElse("?");
            String oddsStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_ODDS).orElse("1");
            try { totalOdds = new BigDecimal(oddsStr); } catch (Exception e) { totalOdds = BigDecimal.ONE; }
            sb.append("🏆 ").append(escape(title)).append("\n");
            sb.append("📈 Кэф: *").append(oddsStr).append("*\n");
        }

        BigDecimal potential = amount.multiply(totalOdds).setScale(0, RoundingMode.HALF_UP);
        sb.append("💰 Сумма: *").append(amount.setScale(0, RoundingMode.HALF_UP)).append(" ₽*\n");
        sb.append("🎯 Потенциал: *").append(potential).append(" ₽*\n");

        String acctId = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_ACCOUNT_ID).orElse(null);
        if (acctId != null && !acctId.isBlank()) {
            accountService.listForChat(ctx.chatId()).stream()
                    .filter(a -> a.id().toString().equals(acctId))
                    .findFirst()
                    .ifPresent(a -> sb.append("💰 Счёт: *").append(escape(a.name())).append("*\n"));
        }

        if (!parts.isEmpty()) {
            sb.append("\n👥 Участники:\n");
            for (ParticipantRequest p : parts) {
                int pct = p.profitShare().multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP).intValue();
                sb.append("  • ").append(escape(p.displayName()))
                  .append(" — ").append(p.stake().setScale(0, RoundingMode.HALF_UP)).append(" ₽")
                  .append(" (").append(pct).append("%)\n");
            }
        }

        var kb = InlineKeyboardBuilder.create()
                .button("✅ Поставить",  CallbackData.BET_CONFIRM).row()
                .button("← Участники",  CallbackData.BET_PART_STEP)
                .button("← Счёт",      CallbackData.BET_ACCT_STEP).row()
                .button("✕ Отмена",     CallbackData.BET_CANCEL_WIZARD).build();

        int resultId = MessageSend.editOrSendMarkdown(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb);
        if (messageId == 0 && resultId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(resultId));
    }

    // ── Express menu ──────────────────────────────────────────────────────────

    public void showExpressMenu(BotUpdateContext ctx, int messageId, List<BetSlipRequest> legs) {
        StringBuilder sb = new StringBuilder("🎰 *Экспресс*\n");
        if (legs.isEmpty()) {
            sb.append("Событий пока нет.\n");
        } else {
            for (int i = 0; i < legs.size(); i++) {
                sb.append(i + 1).append(". ").append(escape(legs.get(i).matchTitle()))
                  .append(" @ *").append(legs.get(i).odds()).append("*\n");
            }
            BigDecimal totalOdds = legs.stream().map(BetSlipRequest::odds)
                    .reduce(BigDecimal.ONE, BigDecimal::multiply);
            sb.append("📈 Общий кэф: *").append(totalOdds.setScale(2, RoundingMode.HALF_UP)).append("*\n");
        }
        sb.append("\n📲 Нажмите «💸 Поставил» в уведомлении или добавьте вручную:");

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create()
                .button("✏️ Добавить вручную", CallbackData.BET_EXPRESS_ADD).row();
        if (!legs.isEmpty()) kb.button("✅ Готово", CallbackData.BET_EXPRESS_DONE).row();
        kb.button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD);

        int resultId = MessageSend.editOrSendMarkdown(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
        if (messageId == 0 && resultId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(resultId));
    }

    // ── Bet resolve/cancel ────────────────────────────────────────────────────

    private void handleResolve(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String rest = data.substring(CallbackData.BET_RESOLVE_PREFIX.length());
        int lastColon = rest.lastIndexOf(':');
        if (lastColon < 0) { MessageSend.answerCallback(ctx.sender(), callbackId); return; }
        String betIdStr  = rest.substring(0, lastColon);
        String resultStr = rest.substring(lastColon + 1);
        try {
            com.valui.common.domain.BetStatus status = switch (resultStr) {
                case "WIN"    -> com.valui.common.domain.BetStatus.WON;
                case "LOSE"   -> com.valui.common.domain.BetStatus.LOST;
                case "RETURN" -> com.valui.common.domain.BetStatus.RETURNED;
                default -> throw new IllegalArgumentException("Unknown result: " + resultStr);
            };
            BetDto updated = bettingService.resolveBet(UUID.fromString(betIdStr), ctx.chatId(), status);
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    BetDetailCallback.buildDetailText(updated), BetDetailCallback.buildDetailKeyboard(updated));
            MessageSend.answerCallback(ctx.sender(), callbackId);
        } catch (Exception e) {
            log.warn("[BET] resolveBet failed id={} fromId={}: {}", betIdStr, ctx.fromId(), e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    private void handleSlipResolve(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String rest = data.substring(CallbackData.BET_SLIP_RESOLVE_PREFIX.length());
        int lastColon = rest.lastIndexOf(':');
        if (lastColon < 0) { MessageSend.answerCallback(ctx.sender(), callbackId); return; }
        String resultChar = rest.substring(lastColon + 1);
        String idAndOrder = rest.substring(0, lastColon);
        int prevColon = idAndOrder.lastIndexOf(':');
        if (prevColon < 0) { MessageSend.answerCallback(ctx.sender(), callbackId); return; }
        String betIdStr = idAndOrder.substring(0, prevColon);
        String orderStr = idAndOrder.substring(prevColon + 1);
        try {
            com.valui.common.domain.SlipResult result = switch (resultChar) {
                case "W" -> com.valui.common.domain.SlipResult.WON;
                case "L" -> com.valui.common.domain.SlipResult.LOST;
                case "R" -> com.valui.common.domain.SlipResult.RETURNED;
                default  -> throw new IllegalArgumentException("Unknown result: " + resultChar);
            };
            BetDto updated = bettingService.resolveSlip(
                    UUID.fromString(betIdStr), Integer.parseInt(orderStr), ctx.chatId(), result);
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    BetDetailCallback.buildDetailText(updated), BetDetailCallback.buildDetailKeyboard(updated));
            MessageSend.answerCallback(ctx.sender(), callbackId);
        } catch (Exception e) {
            log.warn("[BET] resolveSlip failed id={} fromId={}: {}", betIdStr, ctx.fromId(), e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    private void handleDeleteConfirm(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String betIdStr = data.substring(CallbackData.BET_DELETE_PREFIX.length());
        MessageSend.answerCallback(ctx.sender(), callbackId);
        var kb = InlineKeyboardBuilder.create()
                .button("✅ Да, удалить", CallbackData.betDeleteConfirm(betIdStr))
                .button("✕ Отмена", CallbackData.betDetail(betIdStr))
                .build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "🗑 *Удалить ставку?*\n\nЭто действие необратимо.", kb);
    }

    private void handleDeleteBet(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String betIdStr = data.substring(CallbackData.BET_DELETE_CONFIRM_PREFIX.length());
        try {
            UUID betId = UUID.fromString(betIdStr);
            // Cancel pre-match watches before cascading delete removes the slips
            try {
                bettingService.getBet(betId, ctx.chatId()).slips()
                        .forEach(slip -> preMatchOddsService.cancel(slip.id()));
            } catch (Exception ignored) {}
            bettingService.deleteBet(betId, ctx.chatId());
            MessageSend.answerCallback(ctx.sender(), callbackId);
            sessionService.clearSession(ctx.fromId());
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    buildMenuText(ctx), buildMenuKeyboard());
        } catch (Exception e) {
            log.warn("[BET] deleteBet failed id={}: {}", betIdStr, e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    private void handleCancelBet(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String betIdStr = data.substring(CallbackData.BET_CANCEL_PREFIX.length());
        try {
            UUID betId = UUID.fromString(betIdStr);
            // Cancel pre-match watches — bet is cancelled, snapshot no longer needed
            try {
                bettingService.getBet(betId, ctx.chatId()).slips()
                        .forEach(slip -> preMatchOddsService.cancel(slip.id()));
            } catch (Exception ignored) {}
            BetDto updated = bettingService.cancelBet(betId, ctx.chatId());
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                    BetDetailCallback.buildDetailText(updated), BetDetailCallback.buildDetailKeyboard(updated));
            MessageSend.answerCallback(ctx.sender(), callbackId);
        } catch (Exception e) {
            log.warn("[BET] cancelBet failed id={} fromId={}: {}", betIdStr, ctx.fromId(), e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    private void handleCancelWizard(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.clearSession(ctx.fromId());
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                buildMenuText(ctx), buildMenuKeyboard());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BetDto buildAndPlace(BotUpdateContext ctx) {
        String betType    = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_TYPE).orElse("SINGLE");
        BigDecimal amount = parseAmount(ctx);

        List<BetSlipRequest> slips;
        if ("EXPRESS".equals(betType)) {
            slips = parseLegs(ctx);
        } else {
            String title     = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_MATCH_TITLE).orElse("");
            String url       = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_MATCH_URL).orElse(null);
            String bookmaker = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_BOOKMAKER).orElse(null);
            String oddsStr   = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_ODDS).orElse("1.0");
            slips = List.of(new BetSlipRequest(title, url, bookmaker, new BigDecimal(oddsStr)));
        }

        List<ParticipantRequest> participants = parseParticipants(ctx, amount);

        String acctIdStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_ACCOUNT_ID).orElse(null);
        UUID accountId = null;
        if (acctIdStr != null && !acctIdStr.isBlank()) {
            try { accountId = UUID.fromString(acctIdStr); } catch (Exception ignored) {}
        }

        CreateBetRequest req = new CreateBetRequest(BetType.valueOf(betType), slips, amount, accountId, participants);
        return bettingService.placeBet(ctx.fromId(), ctx.chatId(), req);
    }

    private List<ParticipantRequest> parseParticipants(BotUpdateContext ctx, BigDecimal totalStake) {
        Optional<String> json = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_PARTS_JSON);
        if (json.isEmpty() || json.get().equals("[]")) return List.of();
        try {
            return objectMapper.readValue(json.get(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[BETTING] Failed to parse participants JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private List<BetSlipRequest> parseLegs(BotUpdateContext ctx) {
        Optional<String> json = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_EXPRESS_JSON);
        if (json.isEmpty()) return List.of();
        try {
            return objectMapper.readValue(json.get(), new TypeReference<>() {});
        } catch (Exception e) { return List.of(); }
    }

    /** Public so BettingTextHandler can initialize empty participant state. */
    public void initDefaultParticipants(BotUpdateContext ctx) {
        saveParticipants(ctx, List.of());
    }

    private List<ParticipantRequest> redistributeEvenly(List<ParticipantRequest> parts, BigDecimal amount) {
        int n = parts.size();
        if (n == 0) return parts;
        BigDecimal share = BigDecimal.ONE.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        List<ParticipantRequest> result = new ArrayList<>(n);
        BigDecimal remaining = amount;
        for (int i = 0; i < n; i++) {
            ParticipantRequest p = parts.get(i);
            boolean last = (i == n - 1);
            BigDecimal thisStake = last ? remaining : amount.multiply(share).setScale(2, RoundingMode.HALF_UP);
            BigDecimal thisShare = last ? BigDecimal.ONE.subtract(share.multiply(BigDecimal.valueOf(n - 1))) : share;
            remaining = remaining.subtract(thisStake);
            result.add(new ParticipantRequest(p.personId(), p.displayName(), thisStake, thisShare));
        }
        return result;
    }

    private BigDecimal parseAmount(BotUpdateContext ctx) {
        return sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_AMOUNT)
                .map(s -> { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } })
                .orElse(BigDecimal.ZERO);
    }

    private void saveParticipants(BotUpdateContext ctx, List<ParticipantRequest> parts) {
        try {
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_PARTS_JSON,
                    objectMapper.writeValueAsString(parts));
        } catch (Exception e) {
            log.warn("[BETTING] Failed to save participants: {}", e.getMessage());
        }
    }

    private static boolean isRatio(BigDecimal share, int myN, int partN) {
        BigDecimal expected = BigDecimal.valueOf(myN)
                .divide(BigDecimal.valueOf(myN + partN), 4, RoundingMode.HALF_UP);
        return share.setScale(4, RoundingMode.HALF_UP).compareTo(expected) == 0;
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }
}
