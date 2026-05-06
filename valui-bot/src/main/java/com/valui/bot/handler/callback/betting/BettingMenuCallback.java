package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.BankAccountDto;
import com.valui.betting.dto.BetDto;
import com.valui.betting.dto.BetSlipRequest;
import com.valui.betting.dto.CreateBetRequest;
import com.valui.betting.dto.ParticipantRequest;
import com.valui.betting.service.BankAccountService;
import com.valui.betting.service.BettingService;
import com.valui.betting.service.ChatMemberService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
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

/**
 * Main betting wizard callbacks: menu, new single/express, add express leg, confirm, cancel.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BettingMenuCallback implements CallbackHandler {

    private final BotSessionService  sessionService;
    private final BettingService     bettingService;
    private final BankAccountService bankService;
    private final ChatMemberService  chatMemberService;
    private final ObjectMapper       objectMapper;

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
            case CallbackData.BET_ADD_PARTICIPANT -> handleAddParticipant(ctx, callbackId, messageId);
            case CallbackData.BET_PART_DONE       -> handlePartDone(ctx, callbackId, messageId);
            case CallbackData.BET_PART_STEP       -> handlePartStep(ctx, callbackId, messageId);
            default -> {
                if (data.startsWith(CallbackData.BET_PART_TOGGLE_PREFIX)) {
                    handlePartToggle(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_PART_SPLIT_PREFIX)) {
                    handlePartSplit(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_PART_AMT_PREFIX)) {
                    handlePartAmtEdit(ctx, data, callbackId, messageId);
                } else if (data.startsWith(CallbackData.BET_RESOLVE_PREFIX)) {
                    handleResolve(ctx, data, callbackId, messageId);
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
        String text = buildMenuText(ctx);
        var kb = buildMenuKeyboard();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, kb);
    }

    public static String buildMenuText(BotUpdateContext ctx) {
        return "💸 *Журнал ставок*\n\nВыберите действие:";
    }

    public static org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup buildMenuKeyboard() {
        return InlineKeyboardBuilder.create()
                .button("💸 Новая ставка",     CallbackData.BET_NEW_SINGLE).row()
                .button("🎰 Экспресс",         CallbackData.BET_NEW_EXPRESS).row()
                .button("📋 Открытые ставки",  CallbackData.BET_LIST_OPEN)
                .button("📚 Все ставки",       CallbackData.BET_LIST_ALL).row()
                .button("📊 Статистика",        CallbackData.BET_STAT)
                .button("💰 Балансы",            CallbackData.BANK_LIST).row()
                .build();
    }

    // ── New single bet ────────────────────────────────────────────────────────

    private void handleNewSingle(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setStateWithContext(ctx.fromId(), BotState.BETTING_WAITING_TITLE, Map.of(
                UserBotSession.CTX_BET_TYPE,       "SINGLE",
                UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(messageId)
        ));
        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "💸 *Новая ставка*\n\nВведите *название матча*:", kb);
    }

    // ── Express bet ───────────────────────────────────────────────────────────

    private void handleNewExpress(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setStateWithContext(ctx.fromId(), BotState.IDLE, Map.of(
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
        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "🎰 *Экспресс — новое событие*\n\nВведите *название матча*:", kb);
    }

    private void handleExpressDone(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        List<BetSlipRequest> legs = parseLegs(ctx);
        if (legs.size() < 2) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "Добавьте минимум 2 события для экспресса");
            return;
        }
        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_WAITING_AMOUNT, Map.of(
                UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(messageId)
        ));
        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "🎰 *Экспресс: " + legs.size() + " событий*\n\nВведите *общую сумму ставки*:", kb);
    }

    // ── Confirm ───────────────────────────────────────────────────────────────

    private void handleConfirm(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        try {
            BetDto bet = buildAndPlace(ctx);
            sessionService.clearSession(ctx.fromId());
            String text = BetDetailCallback.buildDetailText(bet);
            var kb = BetDetailCallback.buildDetailKeyboard(bet);
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, kb);
        } catch (Exception e) {
            log.error("[BETTING] Place bet failed fromId={}: {}", ctx.fromId(), e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    // ── Resolve ───────────────────────────────────────────────────────────────

    private void handleResolve(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        // BET:R:{betId}:WIN|LOSE|RETURN
        String rest = data.substring(CallbackData.BET_RESOLVE_PREFIX.length());
        int lastColon = rest.lastIndexOf(':');
        if (lastColon < 0) { MessageSend.answerCallback(ctx.sender(), callbackId); return; }
        String betIdStr = rest.substring(0, lastColon);
        String resultStr = rest.substring(lastColon + 1);
        MessageSend.answerCallback(ctx.sender(), callbackId);
        try {
            com.valui.common.domain.BetStatus status = switch (resultStr) {
                case "WIN"    -> com.valui.common.domain.BetStatus.WON;
                case "LOSE"   -> com.valui.common.domain.BetStatus.LOST;
                case "RETURN" -> com.valui.common.domain.BetStatus.RETURNED;
                default -> throw new IllegalArgumentException("Unknown result: " + resultStr);
            };
            BetDto updated = bettingService.resolveBet(
                    java.util.UUID.fromString(betIdStr), ctx.fromId(), status);
            String text = BetDetailCallback.buildDetailText(updated);
            var kb = BetDetailCallback.buildDetailKeyboard(updated);
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, kb);
        } catch (Exception e) {
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    // ── Cancel bet ────────────────────────────────────────────────────────────

    private void handleCancelBet(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String betIdStr = data.substring(CallbackData.BET_CANCEL_PREFIX.length());
        MessageSend.answerCallback(ctx.sender(), callbackId);
        try {
            BetDto updated = bettingService.cancelBet(java.util.UUID.fromString(betIdStr), ctx.fromId());
            String text = BetDetailCallback.buildDetailText(updated);
            var kb = BetDetailCallback.buildDetailKeyboard(updated);
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, kb);
        } catch (Exception e) {
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    // ── Cancel wizard ─────────────────────────────────────────────────────────

    private void handleCancelWizard(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.clearSession(ctx.fromId());
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                buildMenuText(ctx), buildMenuKeyboard());
    }

    // ── Add participant ───────────────────────────────────────────────────────

    private void handleAddParticipant(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_WAITING_PARTICIPANT, Map.of(
                UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(messageId)
        ));
        var kb = InlineKeyboardBuilder.create()
                .button("← Назад", CallbackData.BET_CONFIRM).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "👥 Введите *Telegram ID* партнёра (число):", kb);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BetDto buildAndPlace(BotUpdateContext ctx) {
        String betType     = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_TYPE).orElse("SINGLE");
        String amountStr   = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_AMOUNT).orElse("0");
        BigDecimal amount  = new BigDecimal(amountStr);

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

        // Participants: default = current user only
        List<ParticipantRequest> participants = parseParticipants(ctx, amount);

        CreateBetRequest req = new CreateBetRequest(
                BetType.valueOf(betType), slips, amount, participants);
        return bettingService.placeBet(ctx.fromId(), ctx.chatId(), req);
    }

    private List<ParticipantRequest> parseParticipants(BotUpdateContext ctx, BigDecimal totalStake) {
        Optional<String> json = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_PARTS_JSON);
        if (json.isEmpty() || json.get().equals("[]")) {
            // Just the current user, 100%
            Optional<String> bankId = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_BANK_ID);
            java.util.UUID accountId = bankId.map(s -> { try { return java.util.UUID.fromString(s); } catch (Exception e) { return null; } }).orElse(null);
            String displayName = ctx.username() != null ? "@" + ctx.username() : String.valueOf(ctx.fromId());
            return List.of(new ParticipantRequest(ctx.fromId(), displayName, totalStake, BigDecimal.ONE, accountId));
        }
        try {
            return objectMapper.readValue(json.get(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[BETTING] Failed to parse participants JSON: {}", e.getMessage());
            return List.of(new ParticipantRequest(ctx.fromId(), null, totalStake, BigDecimal.ONE, null));
        }
    }

    @SuppressWarnings("unchecked")
    private List<BetSlipRequest> parseLegs(BotUpdateContext ctx) {
        Optional<String> json = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_EXPRESS_JSON);
        if (json.isEmpty()) return List.of();
        try {
            return objectMapper.readValue(json.get(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Public so BettingTextHandler can reuse it.
     * Pass messageId=0 when responding to text input (old message already deleted) —
     * a new message is sent and its ID saved to CTX_BET_WIZARD_MSG.
     */
    public void showExpressMenu(BotUpdateContext ctx, int messageId, List<BetSlipRequest> legs) {
        StringBuilder sb = new StringBuilder("🎰 *Экспресс*\n");
        if (legs.isEmpty()) {
            sb.append("Событий пока нет.\n");
        } else {
            for (int i = 0; i < legs.size(); i++) {
                BetSlipRequest leg = legs.get(i);
                sb.append(i + 1).append(". ").append(escape(leg.matchTitle()))
                  .append(" @ *").append(leg.odds()).append("*\n");
            }
            BigDecimal totalOdds = legs.stream()
                    .map(BetSlipRequest::odds)
                    .reduce(BigDecimal.ONE, BigDecimal::multiply);
            sb.append("📈 Общий кэф: *").append(totalOdds.setScale(2, RoundingMode.HALF_UP)).append("*\n");
        }
        sb.append("\n📲 Нажмите «💸 Поставил» в уведомлении или добавьте вручную:");

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create()
                .button("✏️ Добавить вручную", CallbackData.BET_EXPRESS_ADD).row();
        if (!legs.isEmpty()) {
            kb.button("✅ Готово", CallbackData.BET_EXPRESS_DONE).row();
        }
        kb.button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD);

        if (messageId > 0) {
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
        } else {
            int newId = MessageSend.sendMarkdownGetId(ctx.sender(), ctx.chatId(), sb.toString(), kb.build());
            if (newId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(newId));
        }
    }

    // ── Participant step: show ────────────────────────────────────────────────

    private void handlePartDone(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        showFullConfirmation(ctx, messageId);
    }

    private void handlePartStep(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        showParticipantStep(ctx, messageId);
    }

    // ── Participant step: toggle member ───────────────────────────────────────

    private void handlePartToggle(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        long toggleId = Long.parseLong(data.substring(CallbackData.BET_PART_TOGGLE_PREFIX.length()));
        MessageSend.answerCallback(ctx.sender(), callbackId);

        if (toggleId == ctx.fromId()) return; // current user can't be deselected

        BigDecimal amount = parseAmount(ctx);
        List<ParticipantRequest> parts = new ArrayList<>(parseParticipants(ctx, amount));

        boolean alreadyIn = parts.stream().anyMatch(p -> p.telegramId() == toggleId);
        if (alreadyIn) {
            parts.removeIf(p -> p.telegramId() == toggleId);
        } else {
            String name = resolveName(ctx, toggleId);
            Optional<BankAccountDto> acc = bankService.findForUser(toggleId);
            parts.add(new ParticipantRequest(toggleId, name, BigDecimal.ZERO, BigDecimal.ZERO,
                    acc.map(BankAccountDto::id).orElse(null)));
        }

        saveParticipants(ctx, redistributeEvenly(parts, amount));
        showParticipantStep(ctx, messageId);
    }

    // ── Participant step: change split by ratio (2-person only) ─────────────

    private void handlePartSplit(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String ratioStr = data.substring(CallbackData.BET_PART_SPLIT_PREFIX.length());
        String[] parts2 = ratioStr.split(":");
        int myN   = Integer.parseInt(parts2[0]);
        int partN = Integer.parseInt(parts2[1]);
        MessageSend.answerCallback(ctx.sender(), callbackId);

        BigDecimal amount = parseAmount(ctx);
        List<ParticipantRequest> parts = new ArrayList<>(parseParticipants(ctx, amount));
        if (parts.size() != 2) { showParticipantStep(ctx, messageId); return; }

        BigDecimal myShare    = BigDecimal.valueOf(myN).divide(BigDecimal.valueOf(myN + partN), 4, RoundingMode.HALF_UP);
        BigDecimal otherShare = BigDecimal.ONE.subtract(myShare);
        BigDecimal myStake    = amount.multiply(myShare).setScale(2, RoundingMode.HALF_UP);
        BigDecimal otherStake = amount.subtract(myStake);

        List<ParticipantRequest> updated = parts.stream().map(p -> {
            boolean isMe = p.telegramId() == ctx.fromId();
            return new ParticipantRequest(p.telegramId(), p.displayName(),
                    isMe ? myStake : otherStake,
                    isMe ? myShare : otherShare,
                    p.bankAccountId());
        }).collect(Collectors.toList());

        saveParticipants(ctx, updated);
        showParticipantStep(ctx, messageId);
    }

    // ── Participant step: edit one participant's stake amount ─────────────────

    private void handlePartAmtEdit(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        long tid = Long.parseLong(data.substring(CallbackData.BET_PART_AMT_PREFIX.length()));
        MessageSend.answerCallback(ctx.sender(), callbackId);

        BigDecimal amount = parseAmount(ctx);
        List<ParticipantRequest> parts = parseParticipants(ctx, amount);
        ParticipantRequest p = parts.stream().filter(x -> x.telegramId() == tid).findFirst().orElse(null);
        if (p == null) { showParticipantStep(ctx, messageId); return; }

        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_WAITING_PART_AMOUNT, Map.of(
                UserBotSession.CTX_BET_EDITING_PART_TID, String.valueOf(tid),
                UserBotSession.CTX_BET_WIZARD_MSG,       String.valueOf(messageId)
        ));

        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "✏️ *" + escape(p.displayName()) + "*\n\nТекущая сумма: *"
                        + p.stake().setScale(2, RoundingMode.HALF_UP) + " ₽*\n\n"
                        + "Общая ставка: *" + amount.setScale(2, RoundingMode.HALF_UP) + " ₽*\n\n"
                        + "Введите новую сумму для этого участника:", kb);
    }

    // ── Participant step: display ─────────────────────────────────────────────

    /** Shows the participant selection step. Public so BettingTextHandler can call it. */
    public void showParticipantStep(BotUpdateContext ctx, int messageId) {
        BigDecimal amount = parseAmount(ctx);
        List<BankAccountDto> available = bankService.listAccountsForChat(ctx.chatId(), ctx.fromId());
        List<ParticipantRequest> selected = parseParticipants(ctx, amount);
        Set<Long> selectedIds = selected.stream()
                .map(ParticipantRequest::telegramId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        StringBuilder sb = new StringBuilder("👥 *С кем ставишь?*\n\n");
        if (selected.size() == 1) {
            sb.append("Ставите только вы.\n");
        } else if (selected.size() == 2) {
            for (ParticipantRequest p : selected) {
                int pct = p.profitShare().multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP).intValue();
                sb.append(escape(p.displayName())).append(" — ").append(pct).append("%\n");
            }
        } else {
            String names = selected.stream().map(p -> escape(p.displayName())).collect(Collectors.joining(", "));
            sb.append(names).append(" — равные доли\n");
        }

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BankAccountDto acc : available) {
            boolean sel  = selectedIds.contains(acc.ownerTelegramId());
            boolean isMe = acc.ownerTelegramId() == ctx.fromId();
            String  name = resolveName(ctx, acc.ownerTelegramId());
            String  toggleLabel = (sel ? "✅ " : "☐ ") + name + (isMe ? " (Вы)" : "");
            kb.button(toggleLabel, CallbackData.betPartToggle(acc.ownerTelegramId()));
            if (sel) {
                selected.stream().filter(p -> p.telegramId() == acc.ownerTelegramId()).findFirst()
                        .ifPresent(p -> kb.button("✏️ " + p.stake().setScale(0, RoundingMode.HALF_UP) + "₽",
                                CallbackData.betPartAmtEdit(acc.ownerTelegramId())));
            }
            kb.row();
        }

        // Ratio presets for exactly 2 participants
        if (selected.size() == 2) {
            BigDecimal myShare = selected.stream()
                    .filter(p -> p.telegramId() == ctx.fromId())
                    .map(ParticipantRequest::profitShare)
                    .findFirst().orElse(new BigDecimal("0.5"));
            kb.button(isRatio(myShare,1,1) ? "✅ 1:1" : "1:1", CallbackData.betPartSplit(1,1))
              .button(isRatio(myShare,2,1) ? "✅ 2:1" : "2:1", CallbackData.betPartSplit(2,1))
              .button(isRatio(myShare,1,2) ? "✅ 1:2" : "1:2", CallbackData.betPartSplit(1,2)).row()
              .button(isRatio(myShare,3,1) ? "✅ 3:1" : "3:1", CallbackData.betPartSplit(3,1))
              .button(isRatio(myShare,1,3) ? "✅ 1:3" : "1:3", CallbackData.betPartSplit(1,3))
              .button(isRatio(myShare,3,2) ? "✅ 3:2" : "3:2", CallbackData.betPartSplit(3,2))
              .button(isRatio(myShare,2,3) ? "✅ 2:3" : "2:3", CallbackData.betPartSplit(2,3)).row();
        }

        kb.button("✅ Готово", CallbackData.BET_PART_DONE).row()
          .button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD);

        if (messageId > 0) {
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
        } else {
            int newId = MessageSend.sendMarkdownGetId(ctx.sender(), ctx.chatId(), sb.toString(), kb.build());
            if (newId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(newId));
        }
    }

    /** Initialises CTX_BET_PARTS_JSON with just the current user at 100%. */
    public void initDefaultParticipants(BotUpdateContext ctx, BigDecimal amount) {
        String myName = resolveName(ctx, ctx.fromId());
        Optional<BankAccountDto> myAcc = bankService.findForUser(ctx.fromId());
        List<ParticipantRequest> parts = List.of(
                new ParticipantRequest(ctx.fromId(), myName, amount, BigDecimal.ONE,
                        myAcc.map(BankAccountDto::id).orElse(null)));
        saveParticipants(ctx, parts);
    }

    // ── Full confirmation (after participant step) ────────────────────────────

    /** Public so BettingTextHandler can call after participant text-input flow. */
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
                    .setScale(2, RoundingMode.HALF_UP);
            for (int i = 0; i < legs.size(); i++) {
                sb.append(i + 1).append(". ").append(escape(legs.get(i).matchTitle()))
                  .append(" @ ").append(legs.get(i).odds()).append("\n");
            }
            sb.append("📈 Общий кэф: *").append(totalOdds).append("*\n");
        } else {
            String title   = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_MATCH_TITLE).orElse("?");
            String oddsStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_ODDS).orElse("1");
            try { totalOdds = new BigDecimal(oddsStr); } catch (Exception e) { totalOdds = BigDecimal.ONE; }
            sb.append("🏆 ").append(escape(title)).append("\n");
            sb.append("📈 Кэф: *").append(oddsStr).append("*\n");
        }

        BigDecimal potential = amount.multiply(totalOdds).setScale(2, RoundingMode.HALF_UP);
        sb.append("💰 Сумма: *").append(amount.setScale(2, RoundingMode.HALF_UP)).append(" ₽*\n");
        sb.append("🎯 Потенциал: *").append(potential).append(" ₽*\n\n");
        sb.append("👥 Участники:\n");
        for (ParticipantRequest p : parts) {
            int pct = p.profitShare().multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP).intValue();
            sb.append("  • ").append(escape(p.displayName()))
              .append(" — ").append(p.stake().setScale(2, RoundingMode.HALF_UP)).append(" ₽")
              .append(" (").append(pct).append("%)\n");
        }

        var kb = InlineKeyboardBuilder.create()
                .button("✅ Поставить",    CallbackData.BET_CONFIRM).row()
                .button("← Участники",    CallbackData.BET_PART_STEP).row()
                .button("✕ Отмена",       CallbackData.BET_CANCEL_WIZARD).build();

        if (messageId > 0) {
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb);
        } else {
            int newId = MessageSend.sendMarkdownGetId(ctx.sender(), ctx.chatId(), sb.toString(), kb);
            if (newId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(newId));
        }
    }

    // ── Participant step helpers ───────────────────────────────────────────────

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
            result.add(new ParticipantRequest(p.telegramId(), p.displayName(), thisStake, thisShare, p.bankAccountId()));
        }
        return result;
    }

    private BigDecimal parseAmount(BotUpdateContext ctx) {
        return sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_AMOUNT)
                .map(s -> { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } })
                .orElse(BigDecimal.ZERO);
    }

    private String resolveName(BotUpdateContext ctx, long telegramId) {
        if (telegramId == ctx.fromId()) {
            String fn = ctx.update().hasCallbackQuery()
                    ? ctx.update().getCallbackQuery().getFrom().getFirstName()
                    : (ctx.update().hasMessage() && ctx.update().getMessage().getFrom() != null
                            ? ctx.update().getMessage().getFrom().getFirstName() : null);
            if (fn != null && !fn.isBlank()) return fn;
            return ctx.username() != null ? "@" + ctx.username() : "Я";
        }
        return chatMemberService.getMembers(ctx.chatId()).stream()
                .filter(m -> m.getTelegramId() == telegramId)
                .map(m -> m.getFirstName() != null ? m.getFirstName()
                        : (m.getUsername() != null ? "@" + m.getUsername() : "ID " + telegramId))
                .findFirst()
                .orElse("ID " + telegramId);
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
