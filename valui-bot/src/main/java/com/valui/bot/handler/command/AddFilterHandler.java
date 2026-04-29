package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AddFilterHandler implements CommandHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource  messageSource;

    @Override
    public String command() { return "/addfilter"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        // Токены проверяются и списываются в GlobalFilterService при отправке правила
        sessionService.setState(ctx.fromId(), BotState.WAITING_FILTER_RULE);
        MessageSend.text(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("bot.enter_filter_rule", ctx.fromId()));
    }
}
