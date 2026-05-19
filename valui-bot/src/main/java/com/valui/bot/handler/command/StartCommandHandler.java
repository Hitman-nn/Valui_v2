package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.user.dto.TelegramUserDto;
import com.valui.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

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
        if (ctx.session().getState() != BotState.IDLE) {
            log.debug("/start ignored — user in wizard state={} fromId={}", ctx.session().getState(), ctx.fromId());
            return;
        }

        if (ctx.user() == null) {
            User from = ctx.update().getMessage().getFrom();
            userService.registerOrGetUser(new TelegramUserDto(
                ctx.fromId(),
                ctx.username(),
                from != null ? from.getFirstName() : null,
                from != null ? from.getLanguageCode() : null
            ));
            log.info("✅ Новый пользователь зарегистрирован: fromId={} username={}", ctx.fromId(), ctx.username());
        }

        sessionService.clearSession(ctx.fromId());
        ctx.tracker().deleteStale(ctx.chatId(), ctx.sender());

        String name = ctx.username() != null ? "@" + ctx.username() : "друг";
        try {
            Message sent = ctx.sender().execute(SendMessage.builder()
                .chatId(ctx.chatId())
                .text(messageSource.getMessage("bot.welcome", ctx.fromId(), name))
                .parseMode("Markdown")
                .replyMarkup(MainMenuKeyboard.build(ctx.fromId(), messageSource))
                .build());
            if (sent != null) ctx.tracker().track(ctx.chatId(), sent.getMessageId());
        } catch (TelegramApiException e) {
            log.error("StartCommandHandler send failed chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}
