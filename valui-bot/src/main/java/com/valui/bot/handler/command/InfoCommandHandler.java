package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.api.PlanLimitFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * /info — показывает информацию о плане и токенном балансе.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InfoCommandHandler implements CommandHandler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final BotMessageSource messageSource;
    private final PlanLimitFacade  planLimitFacade;

    @Override
    public String command() { return "/info"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.userInfo() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.user_not_registered", ctx.fromId()));
            return;
        }
        showPersonalInfo(ctx);
    }

    private void showPersonalInfo(BotUpdateContext ctx) {
        LimitInfoDto info;
        try {
            info = planLimitFacade.getLimitInfo(ctx.fromId());
        } catch (Exception e) {
            log.warn("Failed to load limit info for fromId={}: {}", ctx.fromId(), e.getMessage());
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.fromId()));
            return;
        }

        String text;
        if (info.expiresAt() != null) {
            text = messageSource.getMessage("info.personal", ctx.fromId(),
                info.planName(),
                info.pollIntervalSec(),
                info.controllersUsed(),
                info.filtersUsed(),
                info.tokenBalance(),
                info.monthlyTokenGrant(),
                info.expiresAt().format(DATE_FMT));
        } else {
            text = messageSource.getMessage("info.personal_no_expiry", ctx.fromId(),
                info.planName(),
                info.pollIntervalSec(),
                info.controllersUsed(),
                info.filtersUsed(),
                info.tokenBalance(),
                info.monthlyTokenGrant());
        }

        var keyboard = InlineKeyboardBuilder.create()
            .button(messageSource.getMessage("menu.btn.plans", ctx.fromId()), CallbackData.PLANS_VIEW)
            .build();
        MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), text, keyboard);
    }
}
