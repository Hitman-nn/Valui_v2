package com.valui.bot.handler.command;

import com.valui.bot.guard.BotAccessGuard;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.common.exception.SubscriptionLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AddControllerHandler implements CommandHandler {

    private final BotAccessGuard guard;
    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;

    @Override
    public String command() { return "/add"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        try {
            guard.guardAddController(ctx.chatId(), ctx.sender());
        } catch (SubscriptionLimitExceededException e) {
            return;
        }

        sessionService.setState(ctx.chatId(), BotState.WAITING_CONTROLLER_URL);
        MessageSend.text(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("bot.enter_controller_url", ctx.chatId()));
    }
}
