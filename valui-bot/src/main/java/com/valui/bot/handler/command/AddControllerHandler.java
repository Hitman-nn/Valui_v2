package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.service.WizardMessageTracker;
import com.valui.bot.state.BotState;
import com.valui.common.domain.BookmakerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AddControllerHandler implements CommandHandler {

    private final BotSessionService    sessionService;
    private final BotMessageSource     messageSource;
    private final WizardMessageTracker wizardMessageTracker;

    @Override
    public String command() { return "/add"; }

    @Override
    public int order() { return 10; }

    @Override
    public void handle(BotUpdateContext ctx) {
        wizardMessageTracker.deleteStale(ctx.chatId(), ctx.sender());

        // Показываем все поддерживаемые букмекеры (проверка токенов произойдёт при подтверждении)
        List<String> allBk = Arrays.stream(BookmakerType.values())
            .map(Enum::name)
            .toList();

        var kb = InlineKeyboardBuilder.create().columns(2);
        for (String bm : allBk) {
            kb.button(bm, CallbackData.bookmakerSelect(bm));
        }

        sessionService.setStateWithContext(ctx.fromId(), BotState.SELECTING_BOOKMAKER, new HashMap<>());

        try {
            Message sent = ctx.sender().execute(SendMessage.builder()
                .chatId(ctx.chatId())
                .text(messageSource.getMessage("wizard.select_bookmaker", ctx.fromId()))
                .replyMarkup(kb.build())
                .build());
            wizardMessageTracker.track(ctx.chatId(), sent.getMessageId());
        } catch (TelegramApiException e) {
            log.error("Failed to send bookmaker selection chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}
