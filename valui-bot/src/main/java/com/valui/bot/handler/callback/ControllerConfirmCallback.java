package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.UserBotSession;
import com.valui.monitor.dto.CreateControllerRequest;
import com.valui.monitor.service.ControllerService;
import com.valui.parser.factory.ParserFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ControllerConfirmCallback implements CallbackHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final ControllerService controllerService;
    private final ParserFactory parserFactory;
    private final WizardBackNavigator backNavigator;

    @Override
    public String callbackPrefix() { return CallbackData.CTRL_CONFIRM_PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        // Answer first — controller creation can take time due to DB + event publishing
        MessageSend.answerCallback(ctx.sender(), callbackId);

        if (CallbackData.CTRL_CONFIRM_YES.equals(data)) {
            handleConfirm(ctx, messageId);
        } else {
            handleCancel(ctx, messageId);
        }
    }

    private void handleConfirm(BotUpdateContext ctx, int messageId) {
        Optional<String> bmOpt     = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_BOOKMAKER);
        Optional<String> urlOpt    = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_TOURNAMENT_URL);
        Optional<String> titleOpt  = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_TOURNAMENT_TITLE);
        Optional<String> filterOpt = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_FILTER);

        if (bmOpt.isEmpty() || urlOpt.isEmpty()) {
            log.warn("⚠️  Подтверждение контроллера: отсутствует контекст для chatId={}", ctx.chatId());
            sessionService.clearSession(ctx.chatId());
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.chatId()));
            return;
        }

        String title = titleOpt.orElse(null);
        String filterRule = filterOpt.orElse(null);

        try {
            var created = controllerService.addController(
                new CreateControllerRequest(urlOpt.get(), bmOpt.get(), title, false),
                ctx.chatId());

            if (filterRule != null && !filterRule.isBlank()) {
                controllerService.updateFilterRule(created.id(), ctx.chatId(), filterRule);
            }

            log.info("✅ Контроллер создан: chatId={} бук={} url={}", ctx.chatId(), bmOpt.get(), urlOpt.get());
            backNavigator.returnToTournamentList(ctx.sender(), ctx.chatId(), messageId);

        } catch (Exception e) {
            log.error("❌ Ошибка создания контроллера chatId={}: {}", ctx.chatId(), e.getMessage());
            sessionService.clearSession(ctx.chatId());
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.chatId()));
        }
    }

    private void handleCancel(BotUpdateContext ctx, int messageId) {
        backNavigator.returnToTournamentList(ctx.sender(), ctx.chatId(), messageId);
    }

    // ─── Shared helpers used by FilterSkipCallback and WizardTextHandler ──────

    public static String buildConfirmText(Long chatId, BotSessionService sessionService,
                                           BotMessageSource messageSource) {
        String bm       = sessionService.getContext(chatId, UserBotSession.CTX_BOOKMAKER).orElse("?");
        String sport    = sessionService.getContext(chatId, UserBotSession.CTX_SPORT_NAME).orElse("?");
        String title    = sessionService.getContext(chatId, UserBotSession.CTX_TOURNAMENT_TITLE).orElse("?");
        String type     = sessionService.getContext(chatId, UserBotSession.CTX_CONTROLLER_TYPE).orElse("TOURNAMENT");
        String filter   = sessionService.getContext(chatId, UserBotSession.CTX_FILTER).orElse(null);

        String filterLine = filter != null
            ? messageSource.getMessage("wizard.confirm_filter", chatId, filter)
            : messageSource.getMessage("wizard.confirm_no_filter", chatId);

        String titleKey = "SPORT".equals(type) ? "wizard.confirm_all_sport" : "wizard.confirm_tournament";

        return messageSource.getMessage("wizard.confirm_title", chatId) + "\n\n"
            + "🏢 " + messageSource.getMessage("wizard.confirm_bookmaker", chatId, bm) + "\n"
            + "⚽ " + messageSource.getMessage("wizard.confirm_sport",     chatId, sport) + "\n"
            + "🏆 " + messageSource.getMessage(titleKey,                  chatId, title) + "\n"
            + "🔍 " + filterLine;
    }

    public static InlineKeyboardMarkup buildConfirmKeyboard(Long chatId, BotMessageSource messageSource) {
        return InlineKeyboardBuilder.create()
            .button(messageSource.getMessage("wizard.confirm_yes", chatId), CallbackData.CTRL_CONFIRM_YES)
            .button(messageSource.getMessage("wizard.confirm_no", chatId),  CallbackData.CTRL_CONFIRM_NO)
            .build();
    }
}
