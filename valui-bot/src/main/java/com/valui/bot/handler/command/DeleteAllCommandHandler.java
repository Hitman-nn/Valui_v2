package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeleteAllCommandHandler implements CommandHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;

    @Override
    public String command() { return "/deleteall"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.userInfo() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.user_not_registered", ctx.chatId()));
            return;
        }

        sessionService.setState(ctx.chatId(), BotState.WAITING_CONFIRM_DELETE);
        MessageSend.text(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("bot.confirm_delete_all", ctx.chatId()));
    }
}
