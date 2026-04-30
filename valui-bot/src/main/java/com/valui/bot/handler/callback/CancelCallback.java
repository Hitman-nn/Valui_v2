package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.menu.FilterMenuBuilder;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.common.entity.GlobalFilterEntity;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import com.valui.user.service.GlobalFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CancelCallback implements CallbackHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final WizardBackNavigator backNavigator;
    private final GlobalFilterService globalFilterService;
    private final ControllerService controllerService;

    @Override
    public String callbackPrefix() { return CallbackData.CANCEL; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        MessageSend.answerCallback(ctx.sender(), ctx.update().getCallbackQuery().getId());
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();

        BotState state = ctx.session() != null ? ctx.session().getState() : BotState.IDLE;

        // Search Cancel → return to the list the user was searching in
        if (state == BotState.WAITING_WIZARD_SEARCH) {
            String target = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SEARCH_TARGET)
                    .orElse("SPORT");
            if ("TOURNAMENT".equals(target)) {
                backNavigator.returnToTournamentList(ctx.sender(), ctx.fromId(), ctx.chatId(), messageId);
            } else {
                backNavigator.returnToSportList(ctx.sender(), ctx.fromId(), ctx.chatId(), messageId);
            }
            return;
        }

        // Tournament selection Cancel → bookmaker selection
        // Confirmation screen Cancel → bookmaker selection
        if (state == BotState.SELECTING_TOURNAMENT || state == BotState.WAITING_CONFIRM_CREATE) {
            backNavigator.returnToBookmakerSelection(ctx.sender(), ctx.fromId(), ctx.chatId(), messageId);
            return;
        }

        // Filter input Cancel: route by filter mode
        if (state == BotState.WAITING_FILTER_RULE) {
            String mode = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_FILTER_MODE)
                    .orElse("GLOBAL");
            if ("INDIVIDUAL".equals(mode)) {
                backNavigator.returnToTournamentList(ctx.sender(), ctx.fromId(), ctx.chatId(), messageId);
            } else if ("CONTROLLER_FILTER".equals(mode)) {
                // editing controller filter — back to controller detail
                Optional<String> ctrlIdOpt = sessionService.getContext(
                        ctx.fromId(), UserBotSession.CTX_EDIT_CONTROLLER_ID);
                sessionService.setState(ctx.fromId(), BotState.IDLE);
                ctrlIdOpt.ifPresent(idStr -> {
                    try {
                        ControllerDto c = controllerService.getControllerForChat(
                                UUID.fromString(idStr), ctx.chatId());
                        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                                ControllerDetailCallback.buildDetailText(c, ctx.chatId()),
                                ControllerDetailCallback.buildDetailKeyboard(c, ctx.fromId(), ctx.chatId()));
                    } catch (Exception e) {
                        log.warn("cancel: controller not found {}", idStr);
                    }
                });
            } else {
                // global filter add/edit cancelled → show filter list
                sessionService.setState(ctx.fromId(), BotState.IDLE);
                List<GlobalFilterEntity> filters = globalFilterService.getFilters(ctx.fromId());
                var menu = FilterMenuBuilder.build(filters);
                MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                        menu.text(), menu.keyboard());
            }
            return;
        }

        // All other states (IDLE, SELECTING_BOOKMAKER, SELECTING_SPORT) → main menu
        // In group: don't flood the chat — the user pressing Cancel is not the wizard owner
        if (ctx.isGroupChat()) {
            return;
        }
        sessionService.clearSession(ctx.fromId());
        MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("menu.main", ctx.fromId()),
            MainMenuKeyboard.build(ctx.fromId(), messageSource));
    }
}
