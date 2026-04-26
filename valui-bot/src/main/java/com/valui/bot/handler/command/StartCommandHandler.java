package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.bot.service.BotSessionService;
import com.valui.user.dto.TelegramUserDto;
import com.valui.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.User;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartCommandHandler implements CommandHandler {

    private final UserService userService;
    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;

    @Override
    public String command() { return "/start"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.userInfo() == null) {
            User from = ctx.update().getMessage().getFrom();
            userService.registerOrGetUser(new TelegramUserDto(
                ctx.chatId(),
                ctx.username(),
                from != null ? from.getFirstName() : null,
                from != null ? from.getLanguageCode() : null
            ));
            log.info("✅ Новый пользователь зарегистрирован: chatId={} username={}", ctx.chatId(), ctx.username());
        }

        sessionService.clearSession(ctx.chatId());

        String name = ctx.username() != null ? "@" + ctx.username() : "друг";
        com.valui.bot.handler.MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("bot.welcome", ctx.chatId(), name),
            MainMenuKeyboard.build(ctx.chatId(), messageSource));
    }
}
