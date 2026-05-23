package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.user.api.PlanLimitFacade;
import com.valui.user.crypto.CryptoPaymentService;
import com.valui.user.dto.TokenHistoryEntry;
import com.valui.user.dto.TokenInfoDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.format.DateTimeFormatter;

/** /info — токены, статистика, история. */
@Slf4j
@Component
@RequiredArgsConstructor
public class InfoCommandHandler implements CommandHandler {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd.MM");

    private final BotMessageSource messageSource;
    private final PlanLimitFacade  planLimitFacade;

    @Autowired(required = false)
    private CryptoPaymentService cryptoPaymentService;

    @Override
    public String command() { return "/info"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.user_not_registered", ctx.fromId()));
            return;
        }

        TokenInfoDto info;
        try {
            info = planLimitFacade.getTokenInfo(ctx.fromId());
        } catch (Exception e) {
            log.warn("Failed to load token info for fromId={}: {}", ctx.fromId(), e.getMessage());
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.fromId()));
            return;
        }

        String text = buildText(info, ctx.fromId());
        InlineKeyboardMarkup keyboard = buildKeyboard(ctx.fromId());
        try {
            var builder = SendMessage.builder()
                .chatId(ctx.chatId())
                .text(text)
                .parseMode("Markdown");
            if (keyboard != null) builder.replyMarkup(keyboard);
            var msg = ctx.sender().execute(builder.build());
            if (msg != null) ctx.tracker().track(ctx.chatId(), msg.getMessageId());
        } catch (TelegramApiException e) {
            log.error("InfoCommandHandler send failed chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }

    private InlineKeyboardMarkup buildKeyboard(long fromId) {
        if (cryptoPaymentService == null) return null;
        return InlineKeyboardBuilder.create()
            .button(messageSource.getMessage("info.btn.topup", fromId), CallbackData.TOPUP_START)
            .build();
    }

    private String buildText(TokenInfoDto info, long fromId) {
        StringBuilder sb = new StringBuilder();

        sb.append(messageSource.getMessage("info.header", fromId)).append("\n\n");
        sb.append(messageSource.getMessage("info.tokens_line", fromId,
            info.tokenBalance(), info.monthlyTokenGrant())).append("\n");
        sb.append(messageSource.getMessage("info.controllers_line", fromId,
            info.controllersUsed())).append("\n");

        String thresholdDisplay = info.tokenLowThreshold() != null
            ? String.valueOf(info.tokenLowThreshold())
            : messageSource.getMessage("info.threshold_off", fromId);
        sb.append(messageSource.getMessage("info.threshold_line", fromId, thresholdDisplay)).append("\n");

        sb.append("\n");
        sb.append(messageSource.getMessage("info.stats_header", fromId)).append("\n");
        sb.append(messageSource.getMessage("info.stats_line", fromId,
            info.spentThisMonth(), info.avgPerMonth(), info.spentAllTime())).append("\n");

        sb.append("\n");
        sb.append(messageSource.getMessage("info.history_header", fromId)).append("\n");

        if (info.recentHistory().isEmpty()) {
            sb.append(messageSource.getMessage("info.history_empty", fromId)).append("\n");
        } else {
            for (TokenHistoryEntry entry : info.recentHistory()) {
                String reasonKey = "token.reason." + entry.reasonCode().name();
                String reason = messageSource.getMessage(reasonKey, fromId);
                String delta = (entry.totalDelta() >= 0 ? "+" : "") + entry.totalDelta();
                String suffix = entry.count() > 1 ? " ×" + entry.count() : "";
                String line = messageSource.getMessage("info.history_line", fromId,
                    entry.date().format(DAY_FMT),
                    reason + suffix,
                    " " + delta);
                sb.append("• ").append(line).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
