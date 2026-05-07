package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.BetAccountDto;
import com.valui.betting.dto.BetPersonBalanceDto;
import com.valui.betting.service.BetAccountService;
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

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class BetAccountCallback implements CallbackHandler {

    private final BetAccountService accountService;
    private final BotSessionService sessionService;

    @Override
    public String callbackPrefix() { return "ACCT:"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        if (data.equals(CallbackData.ACCT_LIST)) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            showList(ctx, messageId);
        } else if (data.equals(CallbackData.ACCT_NEW)) {
            handleNew(ctx, callbackId, messageId);
        } else if (data.startsWith(CallbackData.ACCT_EDIT_PREFIX)) {
            handleEdit(ctx, data, callbackId, messageId);
        } else if (data.startsWith(CallbackData.ACCT_DEL_PREFIX)) {
            handleDelete(ctx, data, callbackId, messageId);
        } else if (data.startsWith(CallbackData.ACCT_PERS_TOGGLE_PREFIX)) {
            handlePersonToggle(ctx, data, callbackId, messageId);
        } else if (data.startsWith(CallbackData.ACCT_PERS_BAL_PREFIX)) {
            handlePersonBalEdit(ctx, data, callbackId, messageId);
        } else if (data.startsWith(CallbackData.ACCT_PERS_ADD_PREFIX)) {
            handlePersonAdjust(ctx, data, callbackId, messageId, "ADD");
        } else if (data.startsWith(CallbackData.ACCT_PERS_SUB_PREFIX)) {
            handlePersonAdjust(ctx, data, callbackId, messageId, "SUB");
        } else if (data.startsWith(CallbackData.ACCT_PERS_REM_PREFIX)) {
            handlePersonRemove(ctx, data, callbackId, messageId);
        } else {
            MessageSend.answerCallback(ctx.sender(), callbackId);
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────

    public void showList(BotUpdateContext ctx, int messageId) {
        List<BetAccountDto> accounts = accountService.listForChat(ctx.chatId());

        StringBuilder sb = new StringBuilder("💰 *Счета*\n\n");
        if (accounts.isEmpty()) sb.append("Счётов пока нет. Создайте первый.");

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetAccountDto acc : accounts) {
            sb.append("  • *").append(escape(acc.name())).append("*\n");
            kb.button(escape(acc.name()), CallbackData.acctEdit(acc.id().toString()))
              .button("🗑", CallbackData.acctDel(acc.id().toString()))
              .row();
        }
        kb.button("➕ Новый счёт", CallbackData.ACCT_NEW).row()
          .button("← К меню", CallbackData.BET_MENU);

        int resultId = MessageSend.editOrSendMarkdown(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
        if (messageId == 0 && resultId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(resultId));
    }

    // ── New account ───────────────────────────────────────────────────────────

    private void handleNew(BotUpdateContext ctx, String callbackId, int messageId) {
        MessageSend.answerCallback(ctx.sender(), callbackId);
        sessionService.setStateWithContext(ctx.fromId(), BotState.BETTING_WAITING_ACCOUNT_NAME, Map.of(
                UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(messageId)
        ));
        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.ACCT_LIST).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                "💰 *Новый счёт*\n\nВведите название счёта:", kb);
    }

    // ── Edit screen: participants with balances ────────────────────────────────

    private void handleEdit(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String accountId = data.substring(CallbackData.ACCT_EDIT_PREFIX.length());
        try {
            sessionService.putContext(ctx.fromId(), UserBotSession.CTX_ACCT_EDIT_ID, accountId);
            MessageSend.answerCallback(ctx.sender(), callbackId);
            showEditScreen(ctx, UUID.fromString(accountId), messageId);
        } catch (Exception e) {
            log.warn("[ACCT] edit failed id={}: {}", accountId, e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    public void showEditScreen(BotUpdateContext ctx, UUID accountId, int messageId) {
        List<BetPersonBalanceDto> persons = accountService.getPersonsWithBalances(accountId, ctx.chatId());
        String accountIdStr = accountId.toString();

        java.math.BigDecimal total = persons.stream()
                .filter(BetPersonBalanceDto::isLinked)
                .map(BetPersonBalanceDto::balance)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        String totalSign = total.signum() >= 0 ? "+" : "";

        StringBuilder sb = new StringBuilder("💰 *Счёт — участники*\n\n");

        if (persons.isEmpty()) {
            sb.append("В чате нет участников. Добавьте их через меню *Участники*.\n");
        } else {
            boolean anyLinked = persons.stream().anyMatch(BetPersonBalanceDto::isLinked);
            if (anyLinked) {
                sb.append("💼 *Итого: ").append(totalSign)
                  .append(total.setScale(0, RoundingMode.HALF_UP)).append(" ₽*\n\n");
            } else {
                sb.append("Никто не привязан. Нажмите на имя чтобы добавить.\n");
            }
        }

        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();
        for (BetPersonBalanceDto p : persons) {
            String pid = p.personId().toString();
            if (p.isLinked()) {
                String label = "✅ " + escape(p.personName()) + ": "
                        + p.balance().setScale(0, RoundingMode.HALF_UP) + " ₽";
                kb.button(label, CallbackData.acctPersBal(pid))
                  .button("✕", CallbackData.acctPersRem(pid))
                  .row()
                  .button("➕ Внёс",   CallbackData.acctPersAdd(pid))
                  .button("➖ Забрал", CallbackData.acctPersSub(pid))
                  .row();
            } else {
                kb.button("☐ " + escape(p.personName()),
                        CallbackData.acctPersToggle(pid)).row();
            }
        }
        kb.button("← Счета", CallbackData.ACCT_LIST);

        int resultId = MessageSend.editOrSendMarkdown(ctx.sender(), ctx.chatId(), messageId, sb.toString(), kb.build());
        if (messageId == 0 && resultId > 0) sessionService.putContext(ctx.fromId(), UserBotSession.CTX_BET_WIZARD_MSG, String.valueOf(resultId));
    }

    // ── Toggle person (add with balance prompt) ───────────────────────────────

    private void handlePersonToggle(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String personId = data.substring(CallbackData.ACCT_PERS_TOGGLE_PREFIX.length());
        String accountId = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_ACCT_EDIT_ID).orElse(null);
        if (accountId == null) { MessageSend.answerCallback(ctx.sender(), callbackId); return; }

        MessageSend.answerCallback(ctx.sender(), callbackId);
        promptBalance(ctx, accountId, personId, messageId, "SET");
    }

    // ── Edit existing person balance ──────────────────────────────────────────

    private void handlePersonBalEdit(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String personId = data.substring(CallbackData.ACCT_PERS_BAL_PREFIX.length());
        String accountId = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_ACCT_EDIT_ID).orElse(null);
        if (accountId == null) { MessageSend.answerCallback(ctx.sender(), callbackId); return; }

        MessageSend.answerCallback(ctx.sender(), callbackId);
        promptBalance(ctx, accountId, personId, messageId, "SET");
    }

    private void handlePersonAdjust(BotUpdateContext ctx, String data, String callbackId,
                                     int messageId, String op) {
        String prefix = op.equals("ADD") ? CallbackData.ACCT_PERS_ADD_PREFIX : CallbackData.ACCT_PERS_SUB_PREFIX;
        String personId  = data.substring(prefix.length());
        String accountId = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_ACCT_EDIT_ID).orElse(null);
        if (accountId == null) { MessageSend.answerCallback(ctx.sender(), callbackId); return; }

        MessageSend.answerCallback(ctx.sender(), callbackId);
        promptBalance(ctx, accountId, personId, messageId, op);
    }

    private void promptBalance(BotUpdateContext ctx, String accountId, String personId,
                                int messageId, String op) {
        sessionService.setStateWithContext(ctx.fromId(), BotState.BETTING_WAITING_ACCOUNT_PERSON_BALANCE, Map.of(
                UserBotSession.CTX_ACCT_EDIT_ID,        accountId,
                UserBotSession.CTX_ACCT_EDIT_PERSON_ID, personId,
                UserBotSession.CTX_ACCT_EDIT_OP,        op,
                UserBotSession.CTX_BET_WIZARD_MSG,       String.valueOf(messageId)
        ));
        String prompt = switch (op) {
            case "ADD" -> "➕ *Внёс* — введите сумму (₽):";
            case "SUB" -> "➖ *Забрал* — введите сумму (₽):";
            default    -> "✏️ Введите новый баланс (₽):";
        };
        var kb = InlineKeyboardBuilder.create()
                .button("✕ Отмена", CallbackData.acctEdit(accountId)).build();
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, prompt, kb);
    }

    // ── Remove person from account ────────────────────────────────────────────

    private void handlePersonRemove(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String personId = data.substring(CallbackData.ACCT_PERS_REM_PREFIX.length());
        String accountId = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_ACCT_EDIT_ID).orElse(null);
        if (accountId == null) { MessageSend.answerCallback(ctx.sender(), callbackId); return; }
        try {
            accountService.removePersonBalance(UUID.fromString(accountId), UUID.fromString(personId), ctx.chatId());
            MessageSend.answerCallback(ctx.sender(), callbackId);
            showEditScreen(ctx, UUID.fromString(accountId), messageId);
        } catch (Exception e) {
            log.warn("[ACCT] remove person failed: {}", e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    // ── Delete account ────────────────────────────────────────────────────────

    private void handleDelete(BotUpdateContext ctx, String data, String callbackId, int messageId) {
        String id = data.substring(CallbackData.ACCT_DEL_PREFIX.length());
        try {
            accountService.delete(UUID.fromString(id), ctx.chatId());
            MessageSend.answerCallback(ctx.sender(), callbackId);
            showList(ctx, messageId);
        } catch (Exception e) {
            log.warn("[ACCT] delete failed id={}: {}", id, e.getMessage());
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, "❌ " + e.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "—";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[").replace("`", "\\`");
    }
}
