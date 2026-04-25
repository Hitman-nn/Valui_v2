package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeleteAllCommandHandler implements CommandHandler {

    private final BotSessionService sessionService;

    @Override
    public String command() { return "/deleteall"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.userInfo() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                "Сначала зарегистрируйтесь: /start");
            return;
        }

        sessionService.setState(ctx.chatId(), BotState.WAITING_CONFIRM_DELETE);
        MessageSend.text(ctx.sender(), ctx.chatId(),
            "Вы уверены, что хотите удалить ВСЕ контроллеры?\n\n" +
            "Напишите ДА для подтверждения или любое другое сообщение для отмены.");
    }
}
