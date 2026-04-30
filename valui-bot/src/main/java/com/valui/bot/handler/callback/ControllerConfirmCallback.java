package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.common.domain.ControllerType;
import com.valui.common.exception.InsufficientTokensException;
import com.valui.monitor.dto.CreateControllerRequest;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ControllerConfirmCallback implements CallbackHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final ControllerService controllerService;
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
        if (ctx.session().getState() != BotState.WAITING_CONFIRM_CREATE) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "⚠️ Это не ваше меню");
            return;
        }

        MessageSend.answerCallback(ctx.sender(), callbackId);

        if (CallbackData.CTRL_CONFIRM_YES.equals(data)) {
            handleConfirm(ctx, messageId);
        } else {
            handleBack(ctx, messageId);
        }
    }

    private void handleConfirm(BotUpdateContext ctx, int messageId) {
        Optional<String> bmOpt    = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BOOKMAKER);
        Optional<String> urlOpt   = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_TOURNAMENT_URL);
        Optional<String> titleOpt = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_TOURNAMENT_TITLE);
        Optional<String> filterOpt  = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_FILTER);
        Optional<String> ctxTypeOpt = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_CONTROLLER_TYPE);

        if (bmOpt.isEmpty() || urlOpt.isEmpty()) {
            log.warn("⚠️  Подтверждение контроллера: отсутствует контекст для fromId={}", ctx.fromId());
            sessionService.clearSession(ctx.fromId());
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.fromId()));
            return;
        }

        String title = titleOpt.orElse(null);
        String filterRule = filterOpt.orElse(null);
        ControllerType typeHint = "SPORT".equals(ctxTypeOpt.orElse("")) ? ControllerType.SPORT : null;

        try {
            // fromId = user identity; chatId = where notifications go (group or private)
            var created = controllerService.addController(
                new CreateControllerRequest(urlOpt.get(), bmOpt.get(), title, false, typeHint),
                ctx.fromId(), ctx.chatId());

            if (filterRule != null && !filterRule.isBlank()) {
                controllerService.updateFilterRule(created.id(), ctx.fromId(), filterRule);
            }

            log.info("✅ Контроллер создан: fromId={} chatId={} бук={} url={}",
                    ctx.fromId(), ctx.chatId(), bmOpt.get(), urlOpt.get());
            if (ControllerType.SPORT.equals(typeHint)) {
                backNavigator.returnToSportList(ctx.sender(), ctx.fromId(), ctx.chatId(), messageId);
            } else {
                backNavigator.returnToTournamentList(ctx.sender(), ctx.fromId(), ctx.chatId(), messageId);
            }

        } catch (InsufficientTokensException e) {
            log.info("⚠️ Нехватка токенов для контроллера fromId={}: {}", ctx.fromId(), e.getMessage());
            sessionService.clearSession(ctx.fromId());
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.insufficient_tokens", ctx.fromId()));
        } catch (Exception e) {
            log.error("❌ Ошибка создания контроллера fromId={}: {}", ctx.fromId(), e.getMessage());
            sessionService.clearSession(ctx.fromId());
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.fromId()));
        }
    }

    // CCONF:NO — "← Назад": SPORT → обратно к фильтру; TOURNAMENT → список турниров
    private void handleBack(BotUpdateContext ctx, int messageId) {
        String type = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_CONTROLLER_TYPE)
                .orElse("TOURNAMENT");
        if ("SPORT".equals(type)) {
            sessionService.setStateAndMergeContext(ctx.fromId(), BotState.WAITING_FILTER_RULE, Map.of(
                UserBotSession.CTX_FILTER_MODE,   "INDIVIDUAL",
                UserBotSession.CTX_WIZARD_MSG_ID, String.valueOf(messageId)
            ));
            String skipText = messageSource.getMessage("wizard.filter_skip", ctx.fromId());
            String backText = messageSource.getMessage("menu.back",          ctx.fromId());
            InlineKeyboardMarkup keyboard = InlineKeyboardBuilder.create()
                .button(skipText, CallbackData.FILTER_SKIP)
                .row()
                .button(backText, CallbackData.CANCEL)
                .build();
            MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                messageSource.getMessage("wizard.enter_filter", ctx.fromId()), keyboard);
        } else {
            backNavigator.returnToTournamentList(ctx.sender(), ctx.fromId(), ctx.chatId(), messageId);
        }
    }

    public static String buildConfirmText(Long chatId, BotSessionService sessionService,
                                           BotMessageSource messageSource) {
        String bm    = sessionService.getContext(chatId, UserBotSession.CTX_BOOKMAKER).orElse("?");
        String sport = sessionService.getContext(chatId, UserBotSession.CTX_SPORT_NAME).orElse("?");
        String title = sessionService.getContext(chatId, UserBotSession.CTX_TOURNAMENT_TITLE).orElse("?");
        String type  = sessionService.getContext(chatId, UserBotSession.CTX_CONTROLLER_TYPE).orElse("TOURNAMENT");

        String titleKey = "SPORT".equals(type) ? "wizard.confirm_all_sport" : "wizard.confirm_tournament";

        String text = messageSource.getMessage("wizard.confirm_title", chatId) + "\n\n"
            + "🏢 " + messageSource.getMessage("wizard.confirm_bookmaker", chatId, bm) + "\n"
            + "⚽ " + messageSource.getMessage("wizard.confirm_sport",     chatId, sport) + "\n"
            + "🏆 " + messageSource.getMessage(titleKey,                  chatId, title);

        if ("SPORT".equals(type)) {
            String filter = sessionService.getContext(chatId, UserBotSession.CTX_FILTER).orElse(null);
            String filterLine = filter != null
                ? messageSource.getMessage("wizard.confirm_filter",    chatId, filter)
                : messageSource.getMessage("wizard.confirm_no_filter", chatId);
            text += "\n🔍 " + filterLine;
        }

        return text;
    }

    public static InlineKeyboardMarkup buildConfirmKeyboard(Long chatId, BotMessageSource messageSource) {
        return InlineKeyboardBuilder.create()
            .button(messageSource.getMessage("wizard.confirm_yes", chatId), CallbackData.CTRL_CONFIRM_YES)
            .row()
            .button(messageSource.getMessage("menu.back",   chatId), CallbackData.CTRL_CONFIRM_NO)
            .button(messageSource.getMessage("menu.cancel", chatId), CallbackData.CANCEL)
            .build();
    }
}
