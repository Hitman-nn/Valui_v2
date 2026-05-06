package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.BankAccountDto;
import com.valui.betting.repository.BetParticipantRepository;
import com.valui.betting.service.BankAccountService;
import com.valui.betting.service.ChatMemberService;
import com.valui.common.entity.ChatMemberEntity;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bank account management: list (chat-scoped), create (select owner → enter balance),
 * set default, delete, select for bet wizard.
 *
 * One account per person (unique constraint). Owner is always chosen from the list of
 * known chat participants (those who appeared in bets) + the current user.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankAccountCallback implements CallbackHandler {

    private final BankAccountService       bankService;
    private final ChatMemberService        chatMemberService;
    private final BetParticipantRepository participantRepo;
    private final BotSessionService        sessionService;

    @Override
    public String callbackPrefix() { return "BANK:"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        if (data.equals(CallbackData.BANK_LIST)) {
            handleList(ctx, callbackId, messageId);
        } else if (data.equals(CallbackData.BANK_NEW)) {
            handleSelectOwner(ctx, callbackId, messageId);
        } else if (data.startsWith(CallbackData.BANK_OWN_PREFIX)) {
            handleOwnerSelected(ctx, data, callbackId, messageId);
        } else if (data.equals(CallbackData.BANK_BALANCE_SKIP)) {
            handleCreateWithBalance(ctx, callbackId, messageId, BigDecimal.ZERO);
        } else if (data.startsWith(CallbackData.BANK_DEF_PREFIX)) {
            handleSetDefault(ctx, data, callbackId, messageId);
        } else if (data.startsWith(CallbackData.BANK_DEL_PREFIX)) {
            handleDelete(ctx, data, callbackId, messageId);
        } else if (data.startsWith(CallbackData.BANK_EDIT_PREFIX)) {
            handleEditBalance(ctx, data, callbackId, messageId);
        } else if (data.startsWith(CallbackData.BANK_SEL_PREFIX)) {
            handleSelect(ctx, data, callbackId, messageId);
        } else {
            MessageSend.answerCallback(ctx.sender(), callbackId);
        }
    }

    // ── List accounts ─────────────────────────────────────────────────────────

    private void handleList(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        List<BankAccountDto> accounts = bankService.listAccountsForChat(ctx.chatId(), ctx.fromId());
        showAccountList(ctx, accounts, messageId);
    }

    public void showAccountList(BotUpdateContext ctx, List<BankAccountDto> accounts, int messageId) {
        if (accounts.isEmpty()) {
            var kb = InlineKeyboardBuilder.create()
                    .button("➕ Новый баланс", CallbackData.BANK_NEW).row()
                    .button("← К меню", CallbackData.BET_MENU).build();
            if (messageId > 0) {
                MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                        "🏦 *Балансы*\n\nБалансов пока нет. Создайте первый.", kb);
            } else {
                MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(),
                        "🏦 *Балансы*\n\nБалансов пока нет. Создайте первый.", kb);
            }
            return;
        }

        StringBuilder sb = new StringBuilder("🏦 *Балансы*\n\n");
        for (BankAccountDto acc : accounts) {
            sb.append("  *").append(escape(acc.name())).append("*")
              .append(": ").append(acc.balance().setScale(2, RoundingMode.HALF_UP))
              .append(" ").append(acc.currency()).append("\n");
        }

        // Each account: [Name: balance] [✏️] [🗑]
        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BankAccountDto acc : accounts) {
            String label = escape(acc.name()) + ": "
                    + acc.balance().setScale(0, RoundingMode.HALF_UP) + " " + acc.currency();
            kb.button(label, CallbackData.NOOP)
              .button("✏️", CallbackData.bankEdit(acc.id().toString()))
              .button("🗑", CallbackData.bankDel(acc.id().toString()))
              .row();
        }
        kb.button("➕ Новый баланс", CallbackData.BANK_NEW).row()
          .button("← К меню", CallbackData.BET_MENU);

        if (messageId > 0) {
            MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
        } else {
            MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), sb.toString(), kb.build());
        }
    }

    // ── Select owner ──────────────────────────────────────────────────────────

    /**
     * Shows all known chat participants who don't yet have a bank account,
     * so the user can pick who the new account belongs to.
     */
    private void handleSelectOwner(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);

        // Build ordered map: telegramId → displayName
        Map<Long, String> candidates = buildCandidateMap(ctx);

        if (candidates.isEmpty()) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "✅ У всех участников уже есть счёт");
            return;
        }

        sessionService.setStateWithContext(ctx.fromId(), BotState.BANKING_SELECTING_OWNER, Map.of(
                UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(messageId)
        ));

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (Map.Entry<Long, String> e : candidates.entrySet()) {
            kb.button(e.getValue(), CallbackData.bankOwn(e.getKey())).row();
        }
        kb.button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD);

        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "🏦 *Новый баланс*\n\nВыберите *владельца счёта*:", kb.build());
    }

    private Map<Long, String> buildCandidateMap(BotUpdateContext ctx) {
        Map<Long, String> all = new LinkedHashMap<>();

        // 1. Known chat members (tracked from every message/callback in the group)
        chatMemberService.getMembers(ctx.chatId()).forEach(m -> {
            String name = m.getFirstName() != null ? m.getFirstName() : (m.getUsername() != null ? "@" + m.getUsername() : "ID " + m.getTelegramId());
            all.put(m.getTelegramId(), name);
        });

        // 2. Also add members from past bet participants (may not be tracked yet if bot was added later)
        participantRepo.findDistinctParticipantsByChatId(ctx.chatId()).forEach(row -> {
            Long tid  = (Long) row[0];
            String nm = row[1] != null ? (String) row[1] : "ID " + tid;
            all.putIfAbsent(tid, nm);
        });

        // 3. Always include current user with their live first_name
        String myName = ctx.update().getCallbackQuery().getFrom().getFirstName();
        if (myName == null || myName.isBlank()) myName = "Я";
        all.put(ctx.fromId(), myName.trim());

        // 4. Remove those who already have an account
        all.keySet().removeIf(bankService::existsForUser);

        return all;
    }

    // ── Owner selected → ask for balance ─────────────────────────────────────

    private void handleOwnerSelected(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);

        long ownerTid = Long.parseLong(data.substring(CallbackData.BANK_OWN_PREFIX.length()));

        // Resolve display name from current user or from recent candidate map
        String ownerName = resolveOwnerName(ctx, ownerTid);

        sessionService.setStateWithContext(ctx.fromId(), BotState.BANKING_WAITING_BALANCE, Map.of(
                UserBotSession.CTX_BET_WIZARD_MSG,    String.valueOf(messageId),
                UserBotSession.CTX_BET_BANK_NAME,     ownerName,
                UserBotSession.CTX_BET_BANK_OWNER_ID, String.valueOf(ownerTid),
                UserBotSession.CTX_BET_BANK_OWNER_NM, ownerName
        ));

        var kb = InlineKeyboardBuilder.create()
                .button("0 ₽ (пропустить)", CallbackData.BANK_BALANCE_SKIP).row()
                .button("✕ Отмена", CallbackData.BET_CANCEL_WIZARD).build();

        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "🏦 *Новый баланс*\n\nВладелец: *" + escape(ownerName) + "*\n\n"
                + "Введите *начальный баланс* (₽) или нажмите «0 ₽»:", kb);
    }

    private String resolveOwnerName(BotUpdateContext ctx, long ownerTid) {
        if (ownerTid == ctx.fromId()) {
            String fn = ctx.update().getCallbackQuery().getFrom().getFirstName();
            return (fn != null && !fn.isBlank()) ? fn.trim() : "Я";
        }
        // Look up from chat members (most up-to-date)
        return chatMemberService.getMembers(ctx.chatId()).stream()
                .filter(m -> m.getTelegramId() == ownerTid)
                .map(m -> m.getFirstName() != null ? m.getFirstName() : "ID " + ownerTid)
                .findFirst()
                // Fallback: bet participants
                .or(() -> participantRepo.findDistinctParticipantsByChatId(ctx.chatId()).stream()
                        .filter(row -> ownerTid == (Long) row[0])
                        .map(row -> row[1] != null ? (String) row[1] : "ID " + ownerTid)
                        .findFirst())
                .orElse("ID " + ownerTid);
    }

    // ── Create account with balance ───────────────────────────────────────────

    /** Called by skip button (balance=0) or BettingTextHandler (balance=entered amount). */
    public void handleCreateWithBalance(BotUpdateContext ctx, String callbackId, int messageId, BigDecimal balance) {
        if (callbackId != null && !callbackId.isBlank()) MessageSend.answerCallback(ctx.sender(), callbackId);

        String ownerIdStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_BANK_OWNER_ID)
                .orElse(String.valueOf(ctx.fromId()));
        String name = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_BANK_NAME)
                .orElse("Счёт");

        long ownerTid;
        try { ownerTid = Long.parseLong(ownerIdStr); }
        catch (NumberFormatException e) { ownerTid = ctx.fromId(); }

        try {
            BankAccountDto created = bankService.createAccount(ownerTid, name, "RUB");
            if (balance.compareTo(BigDecimal.ZERO) > 0) {
                bankService.adjustBalance(ownerTid, created.id(), balance);
            }
            sessionService.clearSession(ctx.fromId());
            List<BankAccountDto> accounts = bankService.listAccountsForChat(ctx.chatId(), ctx.fromId());
            showAccountList(ctx, accounts, messageId);
        } catch (Exception e) {
            log.error("[BANKING] Create account failed ownerTid={}: {}", ownerTid, e.getMessage());
            if (callbackId != null && !callbackId.isBlank()) {
                MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
            } else {
                MessageSend.text(ctx.sender(), ctx.chatId(), "❌ " + e.getMessage());
            }
        }
    }

    // ── Edit balance ─────────────────────────────────────────────────────────

    private void handleEditBalance(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String accountId = data.substring(CallbackData.BANK_EDIT_PREFIX.length());
        MessageSend.answerCallback(ctx.sender(), callbackId);

        BankAccountDto acc = bankService.listAccountsForChat(ctx.chatId(), ctx.fromId()).stream()
                .filter(a -> a.id().toString().equals(accountId))
                .findFirst().orElse(null);
        if (acc == null) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "Баланс не найден");
            return;
        }

        sessionService.setStateWithContext(ctx.fromId(), BotState.BANKING_EDITING_BALANCE, Map.of(
                UserBotSession.CTX_BET_WIZARD_MSG,  String.valueOf(messageId),
                UserBotSession.CTX_BET_BANK_EDIT_ID, accountId
        ));

        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.BANK_LIST).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "✏️ *" + escape(acc.name()) + "*\n\nТекущий баланс: *"
                        + acc.balance().setScale(2, RoundingMode.HALF_UP) + " ₽*\n\n"
                        + "Введите новый баланс:", kb);
    }

    // ── Set default / delete ──────────────────────────────────────────────────

    private void handleSetDefault(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String accountId = data.substring(CallbackData.BANK_DEF_PREFIX.length());
        MessageSend.answerCallback(ctx.sender(), callbackId);
        try {
            bankService.setDefault(ctx.fromId(), UUID.fromString(accountId));
            showAccountList(ctx, bankService.listAccountsForChat(ctx.chatId(), ctx.fromId()), messageId);
        } catch (Exception e) {
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    private void handleDelete(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String accountId = data.substring(CallbackData.BANK_DEL_PREFIX.length());
        MessageSend.answerCallback(ctx.sender(), callbackId);
        try {
            bankService.deleteAccountById(UUID.fromString(accountId));
            showAccountList(ctx, bankService.listAccountsForChat(ctx.chatId(), ctx.fromId()), messageId);
        } catch (Exception e) {
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    // ── Select account for bet wizard ─────────────────────────────────────────

    private void handleSelect(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String accountId = data.substring(CallbackData.BANK_SEL_PREFIX.length());
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.BETTING_CONFIRM, Map.of(
                UserBotSession.CTX_BET_BANK_ID, accountId
        ));
        showConfirmation(ctx, messageId);
    }

    private void showConfirmation(BotUpdateContext ctx, int messageId) {
        String oddsStr   = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_ODDS).orElse("?");
        String amountStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_AMOUNT).orElse("?");
        String title     = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_MATCH_TITLE).orElse("?");
        String bankId    = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BET_BANK_ID).orElse(null);

        String bankName = "нет";
        if (bankId != null) {
            try {
                UUID accId = UUID.fromString(bankId);
                bankName = bankService.listAccountsForChat(ctx.chatId(), ctx.fromId()).stream()
                        .filter(a -> a.id().equals(accId))
                        .map(BankAccountDto::name)
                        .findFirst().orElse("нет");
            } catch (Exception ignored) {}
        }

        String text = "💸 *Подтвердить ставку*\n\n"
                + "🏆 " + escape(title) + "\n"
                + "📈 Кэф: *" + oddsStr + "*\n"
                + "💰 Сумма: *" + amountStr + " ₽*\n"
                + "🏦 Счёт: " + bankName;

        var kb = InlineKeyboardBuilder.create()
                .button("✅ Поставить", CallbackData.BET_CONFIRM).row()
                .button("👥 Участники", CallbackData.BET_ADD_PARTICIPANT)
                .button("🏦 Счёт",      CallbackData.BANK_LIST).row()
                .button("✕ Отмена",    CallbackData.BET_CANCEL_WIZARD).build();

        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, kb);
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }
}
