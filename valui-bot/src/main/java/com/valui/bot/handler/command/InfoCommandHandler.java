package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.user.dto.GroupStatusDto;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.api.GroupQuotaFacade;
import com.valui.user.api.PlanLimitFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * /info — shows plan and quota information.
 *
 * Private chat: personal plan summary with token balance.
 * Group chat:   group controller quota status with contributor breakdown.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InfoCommandHandler implements CommandHandler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final BotMessageSource messageSource;
    private final PlanLimitFacade planLimitFacade;
    private final GroupQuotaFacade groupQuotaFacade;

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

        if (ctx.isGroupChat()) {
            showGroupInfo(ctx);
        } else {
            showPersonalInfo(ctx);
        }
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
                info.controllersUsed(), info.controllersMax(),
                info.filtersUsed(), info.filtersMax(),
                info.pollIntervalSec(),
                info.tokenBalance(),
                info.expiresAt().format(DATE_FMT));
        } else {
            text = messageSource.getMessage("info.personal_no_expiry", ctx.fromId(),
                info.planName(),
                info.controllersUsed(), info.controllersMax(),
                info.filtersUsed(), info.filtersMax(),
                info.pollIntervalSec(),
                info.tokenBalance());
        }

        var keyboard = InlineKeyboardBuilder.create()
            .button(messageSource.getMessage("menu.btn.plans", ctx.fromId()), CallbackData.PLANS_VIEW)
            .build();
        MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), text, keyboard);
    }

    private void showGroupInfo(BotUpdateContext ctx) {
        GroupStatusDto status;
        try {
            status = groupQuotaFacade.getGroupStatus(ctx.chatId());
        } catch (Exception e) {
            log.warn("Failed to load group status for chatId={}: {}", ctx.chatId(), e.getMessage());
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.fromId()));
            return;
        }

        String header = messageSource.getMessage("info.group", ctx.fromId(),
            status.activeControllers(), status.maxControllers(), status.freeSlots());

        String contributors;
        if (status.contributors().isEmpty()) {
            contributors = "\n\n" + messageSource.getMessage("info.group_no_contributors", ctx.fromId());
        } else {
            contributors = "\n\n" + status.contributors().stream()
                .map(c -> messageSource.getMessage("info.group_contributor", ctx.fromId(),
                    c.username() != null ? "@" + c.username() : c.telegramId().toString(),
                    c.tokensCommitted()))
                .collect(Collectors.joining("\n"));
        }

        MessageSend.textMarkdown(ctx.sender(), ctx.chatId(), header + contributors);
    }
}
