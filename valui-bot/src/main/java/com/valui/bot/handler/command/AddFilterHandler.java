package com.valui.bot.handler.command;

import com.valui.bot.guard.BotAccessGuard;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.common.exception.SubscriptionLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AddFilterHandler implements CommandHandler {

    private final BotAccessGuard guard;
    private final BotSessionService sessionService;

    @Override
    public String command() { return "/addfilter"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        try {
            guard.guardAddFilter(ctx.chatId(), ctx.sender());
        } catch (SubscriptionLimitExceededException e) {
            return; // guard already sent the upgrade prompt
        }

        sessionService.setState(ctx.chatId(), BotState.WAITING_FILTER_RULE);
        MessageSend.text(ctx.sender(), ctx.chatId(),
            "Введите правило фильтра (например: kf > 1.5):");
    }
}
