package com.valui.bot.handler.command;

import com.valui.bot.guard.BotAccessGuard;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.service.WizardMessageTracker;
import com.valui.bot.state.BotState;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.user.service.PlanLimitChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.HashMap;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AddControllerHandler implements CommandHandler {

    private final BotAccessGuard guard;
    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final PlanLimitChecker planLimitChecker;
    private final WizardMessageTracker wizardMessageTracker;

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

        // Delete the previous wizard message for this user (if any) before opening a new one.
        wizardMessageTracker.deleteStale(ctx.chatId(), ctx.sender());

        List<String> allowed = planLimitChecker.getLimitInfo(ctx.chatId()).allowedBookmakers();

        var kb = InlineKeyboardBuilder.create().columns(2);
        for (String bm : allowed) {
            kb.button(bm, CallbackData.bookmakerSelect(bm));
        }
        kb.cancelButton();

        sessionService.setStateWithContext(ctx.chatId(), BotState.SELECTING_BOOKMAKER, new HashMap<>());

        try {
            Message sent = ctx.sender().execute(SendMessage.builder()
                .chatId(ctx.chatId())
                .text(messageSource.getMessage("wizard.select_bookmaker", ctx.chatId()))
                .replyMarkup(kb.build())
                .build());
            // Track this message so it can be cleaned up when the next wizard starts.
            wizardMessageTracker.track(ctx.chatId(), sent.getMessageId());
        } catch (TelegramApiException e) {
            log.error("Failed to send bookmaker selection chatId={}: {}", ctx.chatId(), e.getMessage());
        }
    }
}
