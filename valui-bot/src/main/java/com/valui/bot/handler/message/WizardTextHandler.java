package com.valui.bot.handler.message;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.BotUpdateHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.ControllerDetailCallback;
import com.valui.bot.handler.callback.ControllerConfirmCallback;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.menu.FilterMenuBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.common.entity.GlobalFilterEntity;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.common.exception.ValuiException;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import com.valui.user.service.GlobalFilterService;
import com.valui.user.service.GroupQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Component
@RequiredArgsConstructor
public class WizardTextHandler implements BotUpdateHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final GlobalFilterService globalFilterService;
    private final ControllerService controllerService;
    private final GroupQuotaService groupQuotaService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || update.getMessage().getText() == null) return false;
        String text = update.getMessage().getText();
        if (text.startsWith("/")) return false;
        return true;
    }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        BotState state = ctx.session().getState();

        if (state == BotState.WAITING_BOOST_AMOUNT) {
            handleBoostAmount(ctx);
            return;
        }

        if (state != BotState.WAITING_FILTER_RULE) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                    messageSource.getMessage("bot.unknown_command", ctx.fromId()));
            return;
        }

        String filterText = ctx.update().getMessage().getText().trim();

        try {
            Pattern.compile(filterText);
        } catch (PatternSyntaxException e) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                    messageSource.getMessage("wizard.filter_invalid_regex", ctx.fromId()));
            return;
        }

        String mode = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_FILTER_MODE)
                .orElse("GLOBAL");

        switch (mode) {
            case "INDIVIDUAL"        -> handleIndividualFilter(ctx, filterText);
            case "GLOBAL_EDIT"       -> handleGlobalFilterEdit(ctx, filterText);
            case "CONTROLLER_FILTER" -> handleControllerFilterEdit(ctx, filterText);
            default                  -> handleGlobalFilter(ctx, filterText);
        }
    }

    private void handleIndividualFilter(BotUpdateContext ctx, String filterText) {
        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.WAITING_CONFIRM_CREATE,
                Map.of(UserBotSession.CTX_FILTER, filterText));

        String confirmText     = ControllerConfirmCallback.buildConfirmText(ctx.fromId(), sessionService, messageSource);
        var    confirmKeyboard = ControllerConfirmCallback.buildConfirmKeyboard(ctx.fromId(), messageSource);

        replaceOrSend(ctx, confirmText, confirmKeyboard);
    }

    private void handleGlobalFilter(BotUpdateContext ctx, String filterText) {
        try {
            globalFilterService.addFilter(ctx.fromId(), filterText);
        } catch (SubscriptionLimitExceededException e) {
            sessionService.setState(ctx.fromId(), BotState.IDLE);
            return;
        }
        sessionService.setState(ctx.fromId(), BotState.IDLE);
        showFilterList(ctx);
    }

    private void handleGlobalFilterEdit(BotUpdateContext ctx, String filterText) {
        Optional<String> filterIdOpt = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_EDIT_FILTER_ID);
        if (filterIdOpt.isPresent()) {
            try {
                UUID filterId = UUID.fromString(filterIdOpt.get());
                globalFilterService.updateFilter(ctx.fromId(), filterId, filterText);
            } catch (Exception e) {
                log.warn("Failed to update global filter: {}", e.getMessage());
            }
        }
        sessionService.setState(ctx.fromId(), BotState.IDLE);
        showFilterList(ctx);
    }

    private void handleControllerFilterEdit(BotUpdateContext ctx, String filterText) {
        Optional<String> ctrlIdOpt = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_EDIT_CONTROLLER_ID);
        sessionService.setState(ctx.fromId(), BotState.IDLE);

        if (ctrlIdOpt.isEmpty()) return;

        try {
            UUID controllerId = UUID.fromString(ctrlIdOpt.get());
            controllerService.updateFilterRule(controllerId, ctx.fromId(), filterText);
            ControllerDto c = controllerService.getController(controllerId);

            Optional<String> msgIdOpt = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_WIZARD_MSG_ID);
            if (msgIdOpt.isPresent()) {
                try {
                    int msgId = Integer.parseInt(msgIdOpt.get());
                    MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), msgId,
                            ControllerDetailCallback.buildDetailText(c, ctx.chatId()),
                            ControllerDetailCallback.buildDetailKeyboard(c, ctx.chatId()));
                    return;
                } catch (NumberFormatException ignored) {}
            }
            MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(),
                    ControllerDetailCallback.buildDetailText(c, ctx.chatId()),
                    ControllerDetailCallback.buildDetailKeyboard(c, ctx.chatId()));

        } catch (Exception e) {
            log.warn("Failed to update controller filter: {}", e.getMessage());
        }
    }

    private void handleBoostAmount(BotUpdateContext ctx) {
        sessionService.setState(ctx.fromId(), BotState.IDLE);

        String input = ctx.update().getMessage().getText().trim();
        int tokens;
        try {
            tokens = Integer.parseInt(input);
            if (tokens <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("boost.invalid_number", ctx.fromId()));
            return;
        }

        Optional<String> chatIdOpt = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BOOST_CHAT_ID);
        if (chatIdOpt.isEmpty()) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.fromId()));
            return;
        }

        long groupChatId = Long.parseLong(chatIdOpt.get());
        try {
            groupQuotaService.contributeTokens(ctx.fromId(), groupChatId, tokens);
            int freeSlots = groupQuotaService.getMaxControllers(groupChatId)
                           - groupQuotaService.getActiveControllerCount(groupChatId);
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("boost.success", ctx.fromId(), tokens, freeSlots));
        } catch (ValuiException e) {
            MessageSend.text(ctx.sender(), ctx.chatId(), "⚠️ " + e.getMessage());
        } catch (Exception e) {
            log.error("[BOOST] Error contributing tokens: fromId={} chatId={}: {}",
                ctx.fromId(), groupChatId, e.getMessage());
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.fromId()));
        }
    }

    private void showFilterList(BotUpdateContext ctx) {
        List<GlobalFilterEntity> filters = globalFilterService.getFilters(ctx.fromId());
        var menu = FilterMenuBuilder.build(filters);

        Optional<String> msgIdOpt = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_WIZARD_MSG_ID);
        if (msgIdOpt.isPresent()) {
            try {
                int msgId = Integer.parseInt(msgIdOpt.get());
                MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), msgId, menu.text(), menu.keyboard());
                return;
            } catch (NumberFormatException ignored) {}
        }
        MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(), menu.text(), menu.keyboard());
    }

    private void replaceOrSend(BotUpdateContext ctx, String text,
                               org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup kb) {
        Optional<String> msgIdOpt = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_WIZARD_MSG_ID);
        if (msgIdOpt.isPresent()) {
            try {
                int msgId = Integer.parseInt(msgIdOpt.get());
                MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), msgId, text, kb);
                return;
            } catch (NumberFormatException ignored) {}
        }
        MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(), text, kb);
    }
}
