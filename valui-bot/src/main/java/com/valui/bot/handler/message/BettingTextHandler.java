package com.valui.bot.handler.message;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.betting.dto.BankAccountDto;
import com.valui.betting.dto.BetSlipRequest;
import com.valui.betting.dto.ParticipantRequest;
import com.valui.betting.service.BankAccountService;
import com.valui.bot.handler.callback.betting.BankAccountCallback;
import com.valui.bot.handler.callback.betting.BettingMenuCallback;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.BotUpdateHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles text input during the betting wizard states.
 * Runs at order=40 — before WizardTextHandler (order=50) to capture betting states first.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BettingTextHandler implements BotUpdateHandler {

    private final BotSessionService   sessionService;
    private final BankAccountService  bankService;
    private final BankAccountCallback bankAccountCallback;
    private final BettingMenuCallback bettingMenuCallback;
    private final ObjectMapper        objectMapper;

    @Override
    public int order() { return 40; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String text  = ctx.update().getMessage().getText().trim();
        BotState state = ctx.session().getState();

        switch (state) {
            case BETTING_WAITING_TITLE        -> handleTitle(ctx, text);
            case BETTING_WAITING_ODDS         -> handleOdds(ctx, text);
            case BETTING_WAITING_AMOUNT       -> handleAmount(ctx, text);
            case BETTING_WAITING_PARTICIPANT  -> handleParticipant(ctx, text);
            case BETTING_WAITING_PART_AMOUNT  -> handlePartAmount(ctx, text);
            case BANKING_WAITING_BALANCE      -> handleBankBalance(ctx, text);
            case BANKING_EDITING_BALANCE      -> handleBankEditBalance(ctx, text);
            case BANKING_WAITING_NAME         -> handleBankName(ctx, text); // legacy fallback
            default -> { /* should not reach */ }
        }
    }

    // ── Title ─────────────────────────────────────────────────────────────────

    private void handleTitle(BotUpdateContext ctx, String text) {
        var cancelKb = InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        if (text.isBlank()) {
            replaceWizard(ctx, "⚠️ Название не может быть пустым. Введите *название матча*:", cancelKb);
            return;
        }
        String betType = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_TYPE).orElse("SINGLE");
        Map<String, String> ctxUpdate = "EXPRESS".equals(betType)
                ? Map.of(UserBotSession.CTX_BET_MATCH_TITLE, text,
                         UserBotSession.CTX_BET_BOOKMAKER,   "",
                         UserBotSession.CTX_BET_MATCH_URL,   "")
                : Map.of(UserBotSession.CTX_BET_MATCH_TITLE, text);
        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_WAITING_ODDS, ctxUpdate);

        replaceWizard(ctx, "📈 Введите *коэффициент* (например: `1.85`):", cancelKb);
    }

    // ── Odds ──────────────────────────────────────────────────────────────────

    private void handleOdds(BotUpdateContext ctx, String text) {
        BigDecimal odds;
        try {
            odds = new BigDecimal(text.replace(",", "."));
            if (odds.compareTo(BigDecimal.ONE) <= 0) throw new NumberFormatException("too low");
        } catch (NumberFormatException e) {
            replaceWizard(ctx, "⚠️ Коэффициент должен быть числом больше 1 (например `1.85`).\n\nВведите *коэффициент*:",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build());
            return;
        }

        String betType = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_TYPE).orElse("SINGLE");

        if ("EXPRESS".equals(betType)) {
            // Save leg and go back to express menu
            String legTitle    = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_MATCH_TITLE).orElse("");
            String legUrl      = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_MATCH_URL)
                    .filter(s -> !s.isBlank()).orElse(null);
            String legBookmaker = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_BOOKMAKER)
                    .filter(s -> !s.isBlank()).orElse(null);
            List<BetSlipRequest> legs = parseLegs(ctx);
            legs.add(new BetSlipRequest(legTitle, legUrl, legBookmaker, odds));
            saveLegs(ctx, legs);

            sessionService.setStateAndMergeContext(ctx.fromId(), BotState.IDLE, Map.of());
            replaceWizardViaCallback(ctx);
            bettingMenuCallback.showExpressMenu(ctx, 0, legs);
        } else {
            sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_WAITING_AMOUNT, Map.of(
                    UserBotSession.CTX_BET_ODDS, odds.toPlainString()
            ));
            replaceWizard(ctx, "💰 Введите *сумму ставки* (рублей):",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build());
        }
    }

    // ── Amount ────────────────────────────────────────────────────────────────

    private void handleAmount(BotUpdateContext ctx, String text) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(text.replace(",", ".").replace(" ", ""));
            if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException("zero");
        } catch (NumberFormatException e) {
            replaceWizard(ctx, "⚠️ Введите корректную сумму (например: `1000`):",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build());
            return;
        }

        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_CONFIRM, Map.of(
                UserBotSession.CTX_BET_AMOUNT, amount.toPlainString()
        ));

        bettingMenuCallback.initDefaultParticipants(ctx, amount);
        replaceWizardViaCallback(ctx);
        bettingMenuCallback.showParticipantStep(ctx, 0);
    }

    // ── Participant ───────────────────────────────────────────────────────────

    private void handleParticipant(BotUpdateContext ctx, String text) {
        long partnerId;
        try {
            partnerId = Long.parseLong(text.trim().replace("@", "").replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            replaceWizard(ctx, "⚠️ Введите числовой Telegram ID партнёра:",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build());
            return;
        }

        // Add participant: split 50/50 between current user and partner
        String amountStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_AMOUNT).orElse("0");
        BigDecimal total = new BigDecimal(amountStr);
        BigDecimal half  = total.divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);

        String myName = ctx.username() != null ? "@" + ctx.username() : String.valueOf(ctx.fromId());
        var myAcc      = bankService.findForUser(ctx.fromId());
        var partnerAcc = bankService.findForUser(partnerId);
        List<ParticipantRequest> parts = new ArrayList<>();
        parts.add(new ParticipantRequest(ctx.fromId(), myName, half, new BigDecimal("0.5"),
                myAcc.map(BankAccountDto::id).orElse(null)));
        parts.add(new ParticipantRequest(partnerId, String.valueOf(partnerId), half, new BigDecimal("0.5"),
                partnerAcc.map(BankAccountDto::id).orElse(null)));

        try {
            String json = objectMapper.writeValueAsString(parts);
            sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_CONFIRM, Map.of(
                    UserBotSession.CTX_BET_PARTS_JSON, json
            ));
        } catch (Exception e) {
            log.warn("[BETTING] Failed to serialize participants: {}", e.getMessage());
        }

        replaceWizardViaCallback(ctx);
        bettingMenuCallback.showFullConfirmation(ctx, 0);
    }

    // ── Participant custom stake ──────────────────────────────────────────────

    private void handlePartAmount(BotUpdateContext ctx, String text) {
        BigDecimal newStake;
        try {
            newStake = new BigDecimal(text.replace(",", ".").replace(" ", ""));
            if (newStake.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException("zero");
        } catch (NumberFormatException e) {
            replaceWizard(ctx, "⚠️ Введите корректную сумму:",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build());
            return;
        }

        BigDecimal total = new BigDecimal(
                sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_AMOUNT).orElse("0"));
        long editTid = Long.parseLong(
                sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_EDITING_PART_TID).orElse("0"));

        if (newStake.compareTo(total) >= 0) {
            replaceWizard(ctx, "⚠️ Сумма одного участника не может быть ≥ общей ставки ("
                    + total.setScale(0, java.math.RoundingMode.HALF_UP) + " ₽).\n\nВведите сумму:",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build());
            return;
        }

        List<ParticipantRequest> parts = parseParticipantsFromCtx(ctx, total);
        BigDecimal remaining = total.subtract(newStake);
        long othersCount = parts.stream().filter(p -> p.telegramId() != editTid).count();
        BigDecimal otherStake = othersCount > 0
                ? remaining.divide(BigDecimal.valueOf(othersCount), 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        List<ParticipantRequest> updated = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ParticipantRequest p = parts.get(i);
            boolean isTarget = p.telegramId() == editTid;
            boolean isLast   = i == parts.size() - 1;
            BigDecimal stake = isTarget ? newStake : (isLast && !isTarget
                    ? total.subtract(newStake).subtract(otherStake.multiply(BigDecimal.valueOf(othersCount - 1)))
                    : otherStake);
            BigDecimal share = total.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : stake.divide(total, 4, java.math.RoundingMode.HALF_UP);
            updated.add(new ParticipantRequest(p.telegramId(), p.displayName(), stake, share, p.bankAccountId()));
        }

        try {
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_PARTS_JSON,
                    objectMapper.writeValueAsString(updated));
        } catch (Exception ex) {
            log.warn("[BETTING] Failed to save participants: {}", ex.getMessage());
        }
        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_CONFIRM, Map.of());
        replaceWizardViaCallback(ctx);
        bettingMenuCallback.showParticipantStep(ctx, 0);
    }

    // ── Edit existing bank account balance ────────────────────────────────────

    private void handleBankEditBalance(BotUpdateContext ctx, String text) {
        BigDecimal newBalance;
        try {
            newBalance = new BigDecimal(text.replace(",", ".").replace(" ", ""));
            if (newBalance.compareTo(BigDecimal.ZERO) < 0) throw new NumberFormatException("negative");
        } catch (NumberFormatException e) {
            replaceWizard(ctx, "⚠️ Введите корректную сумму (≥ 0):",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BANK_LIST).build());
            return;
        }

        String editId = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_BANK_EDIT_ID).orElse(null);
        if (editId == null) { sessionService.clearSession(ctx.fromId()); return; }

        try {
            java.util.UUID accId = java.util.UUID.fromString(editId);
            var acc = bankService.listAccountsForChat(ctx.chatId(), ctx.fromId()).stream()
                    .filter(a -> a.id().equals(accId)).findFirst().orElse(null);
            if (acc == null) throw new IllegalStateException("Баланс не найден");

            BigDecimal delta = newBalance.subtract(acc.balance());
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                bankService.adjustBalance(acc.ownerTelegramId(), accId, delta);
            }
            sessionService.clearSession(ctx.fromId());
            // Show updated list — replace wizard with the accounts screen
            replaceWizardViaCallback(ctx);
            bankAccountCallback.showAccountList(ctx,
                    bankService.listAccountsForChat(ctx.chatId(), ctx.fromId()), 0);
        } catch (Exception e) {
            MessageSend.text(ctx.sender(), ctx.chatId(), "❌ " + e.getMessage());
        }
    }

    // ── Bank balance (auto-named + owner pre-selected, user enters initial balance) ──

    private void handleBankBalance(BotUpdateContext ctx, String text) {
        BigDecimal balance;
        try {
            balance = new BigDecimal(text.replace(",", ".").replace(" ", ""));
            if (balance.compareTo(BigDecimal.ZERO) < 0) throw new NumberFormatException("negative");
        } catch (NumberFormatException e) {
            MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(),
                    "⚠️ Введите корректную сумму (например: `5000` или `0`):",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build());
            return;
        }
        replaceWizardViaCallback(ctx);
        bankAccountCallback.handleCreateWithBalance(ctx, null, 0, balance);
    }

    // ── Bank name (legacy — delegates to new balance flow) ───────────────────

    private void handleBankName(BotUpdateContext ctx, String name) {
        // Legacy path: pre-fill name and redirect to balance input
        if (name.isBlank() || name.length() > 64) {
            replaceWizard(ctx, "⚠️ Введите название (не более 64 символов):",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build());
            return;
        }
        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BANKING_WAITING_BALANCE, Map.of(
                UserBotSession.CTX_BET_BANK_NAME,     name,
                UserBotSession.CTX_BET_BANK_OWNER_ID, String.valueOf(ctx.fromId())
        ));
        var kb = InlineKeyboardBuilder.create()
                .button("0 ₽ (пропустить)", CallbackData.BANK_BALANCE_SKIP).row()
                .button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();
        replaceWizard(ctx, "💰 Введите *начальный баланс* (₽) или нажмите «0 ₽»:", kb);
    }

    // ── Participant helpers ───────────────────────────────────────────────────

    private List<ParticipantRequest> parseParticipantsFromCtx(BotUpdateContext ctx, BigDecimal total) {
        Optional<String> json = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_PARTS_JSON);
        if (json.isEmpty() || json.get().equals("[]")) {
            String myName = ctx.username() != null ? "@" + ctx.username() : String.valueOf(ctx.fromId());
            return List.of(new ParticipantRequest(ctx.fromId(), myName, total, BigDecimal.ONE, null));
        }
        try {
            return objectMapper.readValue(json.get(), new com.fasterxml.jackson.core.type.TypeReference<List<ParticipantRequest>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Express helpers ───────────────────────────────────────────────────────

    private List<BetSlipRequest> parseLegs(BotUpdateContext ctx) {
        Optional<String> json = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_EXPRESS_JSON);
        if (json.isEmpty() || json.get().equals("[]")) return new ArrayList<>();
        try {
            return new ArrayList<>(objectMapper.readValue(json.get(), new TypeReference<>() {}));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveLegs(BotUpdateContext ctx, List<BetSlipRequest> legs) {
        try {
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_EXPRESS_JSON,
                    objectMapper.writeValueAsString(legs));
        } catch (Exception e) {
            log.warn("[BETTING] Failed to save express legs: {}", e.getMessage());
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private int getWizardMsgId(BotUpdateContext ctx) {
        return sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG)
                .map(s -> { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } })
                .orElse(0);
    }

    /**
     * Replaces the wizard message: deletes the old one (if any) and sends a new message
     * at the bottom of the chat. Saves the new message ID to CTX_BET_WIZARD_MSG.
     */
    private void replaceWizard(BotUpdateContext ctx, String text, org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup kb) {
        int oldId = getWizardMsgId(ctx);
        if (oldId > 0) MessageSend.deleteMessage(ctx.sender(), ctx.chatId(), oldId);
        int newId = MessageSend.sendMarkdownGetId(ctx.sender(), ctx.chatId(), text, kb);
        if (newId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(newId));
    }

    /**
     * Deletes the old wizard message, then calls the given show-method with messageId=0
     * so it sends a new message and saves the new ID itself.
     */
    private void replaceWizardViaCallback(BotUpdateContext ctx) {
        int oldId = getWizardMsgId(ctx);
        if (oldId > 0) MessageSend.deleteMessage(ctx.sender(), ctx.chatId(), oldId);
    }

    private static boolean isBettingState(BotState state) {
        if (state == null) return false;
        return state == BotState.BETTING_WAITING_TITLE
                || state == BotState.BETTING_WAITING_ODDS
                || state == BotState.BETTING_WAITING_AMOUNT
                || state == BotState.BETTING_WAITING_PARTICIPANT
                || state == BotState.BETTING_WAITING_PART_AMOUNT
                || state == BotState.BANKING_WAITING_BALANCE
                || state == BotState.BANKING_EDITING_BALANCE
                || state == BotState.BANKING_WAITING_NAME
                || state == BotState.BANKING_SELECTING_OWNER;
    }

    /**
     * Checks both message type AND session state so that only betting-wizard interactions
     * are intercepted here; all other text falls through to WizardTextHandler (order=50).
     */
    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || update.getMessage().getText() == null) return false;
        if (update.getMessage().getText().startsWith("/")) return false;
        Long fromId = update.getMessage().getFrom() != null ? update.getMessage().getFrom().getId() : null;
        if (fromId == null) return false;
        // Quick session peek — no TTL side-effect needed here (CommandRouter re-reads with TTL refresh)
        BotState state = sessionService.getSession(fromId).getState();
        return isBettingState(state);
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }
}
