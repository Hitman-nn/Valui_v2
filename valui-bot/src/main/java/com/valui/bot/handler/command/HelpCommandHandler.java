package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.bot.service.MenuAnchorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
@Component
@RequiredArgsConstructor
public class HelpCommandHandler implements CommandHandler {

    private final BotMessageSource messageSource;
    private final MenuAnchorService menuAnchorService;

    @Override
    public String command() { return "/help"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        try {
            Message sent = ctx.sender().execute(SendMessage.builder()
                .chatId(ctx.chatId())
                .text(messageSource.getMessage("bot.help", ctx.fromId()))
                .parseMode("Markdown")
                .replyMarkup(MainMenuKeyboard.build(ctx.fromId(), messageSource))
                .build());
            if (sent != null) menuAnchorService.replaceAnchor(ctx.chatId(), ctx.sender(), sent.getMessageId());
        } catch (TelegramApiException e) {
            log.error("HelpCommandHandler send failed chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}
