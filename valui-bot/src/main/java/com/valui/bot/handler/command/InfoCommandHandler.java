package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.user.api.PlanLimitFacade;
import com.valui.user.dto.LimitInfoDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** /info — показывает баланс токенов и количество контроллеров. */
@Slf4j
@Component
@RequiredArgsConstructor
public class InfoCommandHandler implements CommandHandler {

    private final BotMessageSource messageSource;
    private final PlanLimitFacade  planLimitFacade;

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

        LimitInfoDto info;
        try {
            info = planLimitFacade.getLimitInfo(ctx.fromId());
        } catch (Exception e) {
            log.warn("Failed to load limit info for fromId={}: {}", ctx.fromId(), e.getMessage());
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.fromId()));
            return;
        }

        String text = messageSource.getMessage("info.personal_no_expiry", ctx.fromId(),
            "—",
            0,
            info.controllersUsed(),
            0,
            info.tokenBalance(),
            info.monthlyTokenGrant());

        MessageSend.textMarkdown(ctx.sender(), ctx.chatId(), text);
    }
}
