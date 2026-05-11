package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.menu.BookmakerMenuBuilder;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ListCommandHandler implements CommandHandler {

    private final BotMessageSource messageSource;
    private final ControllerService controllerService;

    @Override
    public String command() { return "/list"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.user_not_registered", ctx.fromId()));
            return;
        }

        // In a group: show that group's controllers. In private: show all user's controllers.
        List<ControllerDto> controllers = ctx.isGroupChat()
            ? controllerService.getGroupControllers(ctx.chatId())
            : controllerService.getUserControllersForChat(ctx.fromId(), ctx.chatId());

        if (controllers.isEmpty()) {
            MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("controller.list_empty", ctx.fromId()),
                MainMenuKeyboard.build(ctx.fromId(), messageSource));
            return;
        }

        var menu = BookmakerMenuBuilder.buildSelection(controllers);
        MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(), menu.text(), menu.keyboard());
    }
}
