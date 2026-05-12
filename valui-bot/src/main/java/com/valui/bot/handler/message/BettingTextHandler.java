package com.valui.bot.handler.message;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.betting.dto.BetSlipRequest;
import com.valui.betting.dto.ParticipantRequest;
import com.valui.betting.service.BetAccountService;
import com.valui.betting.service.BetPersonService;
import com.valui.bot.handler.callback.betting.BetAccountCallback;
import com.valui.bot.handler.callback.betting.BetPersonCallback;
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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Handles text input during the betting wizard states.
 * Runs at order=40 — before WizardTextHandler (order=50).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BettingTextHandler implements BotUpdateHandler {

    private final BotSessionService  sessionService;
    private final BetAccountService  accountService;
    private final BetPersonService   personService;
    private final BetAccountCallback accountCallback;
    private final BetPersonCallback  personCallback;
    private final BettingMenuCallback bettingMenuCallback;
    private final ObjectMapper       objectMapper;

    @Override
    public int order() { return 40; }

    @Override
    public void handle(BotUpdateContext ctx) {
        MessageSend.deleteMessage(ctx.sender(), ctx.chatId(),
                ctx.update().getMessage().getMessageId());
        String text    = ctx.update().getMessage().getText().trim();
        BotState state = ctx.session().getState();

        switch (state) {
            case BETTING_WAITING_TITLE       -> handleTitle(ctx, text);
            case BETTING_WAITING_ODDS        -> handleOdds(ctx, text);
            case BETTING_WAITING_AMOUNT      -> handleAmount(ctx, text);
            case BETTING_WAITING_PART_AMOUNT -> handlePartAmount(ctx, text);
            case BETTING_WAITING_ACCOUNT_NAME -> handleNameEntry(ctx, text,
                    "⚠️ Введите название счёта (не более 64 символов):", CallbackData.ACCT_LIST,
                    accountService::create, c -> accountCallback.showList(c, 0));
            case BETTING_WAITING_PERSON_NAME  -> handleNameEntry(ctx, text,
                    "⚠️ Введите имя участника (не более 64 символов):", CallbackData.PERS_LIST,
                    personService::create, c -> personCallback.showList(c, 0));
            case BETTING_WAITING_ACCOUNT_PERSON_BALANCE -> handleAccountPersonBalance(ctx, text);
            default -> { /* not a betting state */ }
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
        bettingMenuCallback.initDefaultParticipants(ctx);
        replaceWizardViaCallback(ctx);
        bettingMenuCallback.showAccountStep(ctx, 0);
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
        String editPersonIdStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_EDITING_PART_ID).orElse(null);

        if (newStake.compareTo(total) >= 0) {
            replaceWizard(ctx, "⚠️ Сумма одного участника не может быть ≥ общей ставки ("
                    + total.setScale(0, RoundingMode.HALF_UP) + " ₽).\n\nВведите сумму:",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build());
            return;
        }

        List<ParticipantRequest> parts = parseParticipantsFromCtx(ctx, total);
        BigDecimal remaining = total.subtract(newStake);
        long othersCount = parts.stream()
                .filter(p -> !samePersonId(p, editPersonIdStr))
                .count();
        BigDecimal otherStake = othersCount > 0
                ? remaining.divide(BigDecimal.valueOf(othersCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        List<ParticipantRequest> updated = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ParticipantRequest p = parts.get(i);
            boolean isTarget = samePersonId(p, editPersonIdStr);
            boolean isLast   = i == parts.size() - 1;
            BigDecimal stake = isTarget ? newStake : (isLast && !isTarget
                    ? total.subtract(newStake).subtract(otherStake.multiply(BigDecimal.valueOf(othersCount - 1)))
                    : otherStake);
            BigDecimal share = total.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : stake.divide(total, 4, RoundingMode.HALF_UP);
            updated.add(new ParticipantRequest(p.personId(), p.displayName(), stake, share));
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

    // ── Account person balance entry ──────────────────────────────────────────

    private void handleAccountPersonBalance(BotUpdateContext ctx, String text) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(text.replace(",", ".").replace(" ", ""));
            if (amount.compareTo(BigDecimal.ZERO) < 0) throw new NumberFormatException("negative");
        } catch (NumberFormatException e) {
            replaceWizard(ctx, "⚠️ Введите корректную сумму (например: `1000` или `0`):",
                    InlineKeyboardBuilder.create().button("✕ Отмена", CallbackData.ACCT_LIST).build());
            return;
        }

        String accountIdStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_ACCT_EDIT_ID).orElse(null);
        String personIdStr  = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_ACCT_EDIT_PERSON_ID).orElse(null);
        String op           = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_ACCT_EDIT_OP).orElse("SET");
        if (accountIdStr == null || personIdStr == null) { sessionService.clearSession(ctx.fromId()); return; }

        try {
            UUID accountId = UUID.fromString(accountIdStr);
            UUID personId  = UUID.fromString(personIdStr);
            switch (op) {
                case "ADD" -> accountService.adjustPersonBalance(accountId, personId, ctx.chatId(), amount);
                case "SUB" -> accountService.adjustPersonBalance(accountId, personId, ctx.chatId(), amount.negate());
                default    -> accountService.setPersonBalance(accountId, personId, ctx.chatId(), amount);
            }
            sessionService.setStateAndMergeContext(ctx.fromId(), BotState.IDLE, Map.of());
            replaceWizardViaCallback(ctx);
            accountCallback.showEditScreen(ctx, accountId, 0);
        } catch (Exception e) {
            MessageSend.text(ctx.sender(), ctx.chatId(), "❌ " + e.getMessage());
        }
    }

    // ── Account / Person name entry ───────────────────────────────────────────

    private void handleNameEntry(BotUpdateContext ctx, String text, String errorMsg, String cancelCallback,
                                  BiConsumer<Long, String> serviceCreate,
                                  Consumer<BotUpdateContext> showResult) {
        if (text.isBlank() || text.length() > 64) {
            replaceWizard(ctx, errorMsg,
                    InlineKeyboardBuilder.create().button("✕ Отмена", cancelCallback).build());
            return;
        }
        try {
            serviceCreate.accept(ctx.chatId(), text.trim());
            sessionService.clearSession(ctx.fromId());
            replaceWizardViaCallback(ctx);
            showResult.accept(ctx);
        } catch (Exception e) {
            MessageSend.text(ctx.sender(), ctx.chatId(), "❌ " + e.getMessage());
        }
    }

    // ── Express helpers ───────────────────────────────────────────────────────

    private List<BetSlipRequest> parseLegs(BotUpdateContext ctx) {
        Optional<String> json = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_EXPRESS_JSON);
        if (json.isEmpty() || json.get().equals("[]")) return new ArrayList<>();
        try {
            return new ArrayList<>(objectMapper.readValue(json.get(), new TypeReference<>() {}));
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private void saveLegs(BotUpdateContext ctx, List<BetSlipRequest> legs) {
        try {
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_EXPRESS_JSON,
                    objectMapper.writeValueAsString(legs));
        } catch (Exception e) {
            log.warn("[BETTING] Failed to save express legs: {}", e.getMessage());
        }
    }

    // ── Participant helpers ───────────────────────────────────────────────────

    private List<ParticipantRequest> parseParticipantsFromCtx(BotUpdateContext ctx, BigDecimal total) {
        Optional<String> json = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_PARTS_JSON);
        if (json.isEmpty() || json.get().equals("[]")) return List.of();
        try {
            return objectMapper.readValue(json.get(), new TypeReference<List<ParticipantRequest>>() {});
        } catch (Exception e) { return List.of(); }
    }

    private static boolean samePersonId(ParticipantRequest p, String personIdStr) {
        if (personIdStr == null || p.personId() == null) return false;
        return p.personId().toString().equals(personIdStr);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private int getWizardMsgId(BotUpdateContext ctx) {
        return sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG)
                .map(s -> { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } })
                .orElse(0);
    }

    private void replaceWizard(BotUpdateContext ctx, String text,
            org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup kb) {
        int oldId = getWizardMsgId(ctx);
        if (oldId > 0) MessageSend.deleteMessage(ctx.sender(), ctx.chatId(), oldId);
        int newId = MessageSend.sendMarkdownGetId(ctx.sender(), ctx.chatId(), text, kb);
        if (newId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(newId));
    }

    private void replaceWizardViaCallback(BotUpdateContext ctx) {
        int oldId = getWizardMsgId(ctx);
        if (oldId > 0) MessageSend.deleteMessage(ctx.sender(), ctx.chatId(), oldId);
    }

    private static boolean isBettingState(BotState state) {
        if (state == null) return false;
        return state == BotState.BETTING_WAITING_TITLE
                || state == BotState.BETTING_WAITING_ODDS
                || state == BotState.BETTING_WAITING_AMOUNT
                || state == BotState.BETTING_WAITING_PART_AMOUNT
                || state == BotState.BETTING_WAITING_ACCOUNT_NAME
                || state == BotState.BETTING_WAITING_PERSON_NAME
                || state == BotState.BETTING_WAITING_ACCOUNT_PERSON_BALANCE;
    }

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || update.getMessage().getText() == null) return false;
        if (update.getMessage().getText().startsWith("/")) return false;
        Long fromId = update.getMessage().getFrom() != null ? update.getMessage().getFrom().getId() : null;
        if (fromId == null) return false;
        BotState state = sessionService.getSession(fromId).getState();
        return isBettingState(state);
    }
}
