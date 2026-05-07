package com.valui.bot.handler.message;

import com.valui.bot.config.BotProperties;
import com.valui.bot.config.BotWizardProperties;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.BotUpdateHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.BookmakerSelectCallback;
import com.valui.bot.handler.callback.ControllerDetailCallback;
import com.valui.bot.handler.callback.ControllerConfirmCallback;
import com.valui.bot.handler.callback.SportSelectCallback;
import com.valui.bot.handler.callback.TournamentSelectCallback;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.menu.FilterMenuBuilder;
import com.valui.bot.keyboard.menu.FilterWordBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.service.WizardCacheService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.common.domain.BookmakerType;
import com.valui.common.entity.GlobalFilterEntity;
import com.valui.common.exception.InsufficientTokensException;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import com.valui.user.service.GlobalFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class WizardTextHandler implements BotUpdateHandler {

    private final BotSessionService   sessionService;
    private final BotMessageSource    messageSource;
    private final GlobalFilterService globalFilterService;
    private final ControllerService   controllerService;
    private final WizardCacheService  wizardCache;
    private final BotWizardProperties wizardProps;
    private final BotProperties       botProperties;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || update.getMessage().getText() == null) return false;
        String text = update.getMessage().getText();
        return !text.startsWith("/");
    }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        BotState state = ctx.session().getState();

        if (state == BotState.WAITING_WIZARD_SEARCH) {
            handleWizardSearch(ctx, ctx.update().getMessage().getText().trim());
            return;
        }

        // Betting states are handled by BettingTextHandler (order=40); should not reach here
        if (state == BotState.BETTING_WAITING_TITLE
                || state == BotState.BETTING_WAITING_ODDS
                || state == BotState.BETTING_WAITING_AMOUNT
                || state == BotState.BETTING_WAITING_PART_AMOUNT
                || state == BotState.BETTING_WAITING_ACCOUNT_NAME
                || state == BotState.BETTING_WAITING_PERSON_NAME
                || state == BotState.BETTING_WAITING_ACCOUNT_PERSON_BALANCE) {
            return;
        }

        if (state != BotState.WAITING_FILTER_RULE) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.unknown_command", ctx.fromId()));
            return;
        }

        String filterText = ctx.update().getMessage().getText().trim();

        String mode = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_FILTER_MODE)
            .orElse("GLOBAL");

        // Global filter modes accept plain words — skip raw-regex validation
        boolean isWordMode = "GLOBAL".equals(mode) || "GLOBAL_EDIT".equals(mode);
        if (!isWordMode) {
            try {
                Pattern.compile(filterText);
            } catch (PatternSyntaxException e) {
                MessageSend.text(ctx.sender(), ctx.chatId(),
                    messageSource.getMessage("wizard.filter_invalid_regex", ctx.fromId()));
                return;
            }
        }

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

    private void handleGlobalFilter(BotUpdateContext ctx, String rawInput) {
        List<String> words = FilterWordBuilder.parseWords(rawInput);
        if (words.isEmpty()) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("filter.words_empty", ctx.fromId()));
            return;
        }
        String filterRule = FilterWordBuilder.wordsToRegex(words);
        try {
            globalFilterService.addFilter(ctx.fromId(), filterRule);
        } catch (InsufficientTokensException e) {
            sessionService.setState(ctx.fromId(), BotState.IDLE);
            return;
        }
        sessionService.setState(ctx.fromId(), BotState.IDLE);
        showFilterList(ctx);
    }

    private void handleGlobalFilterEdit(BotUpdateContext ctx, String rawInput) {
        List<String> words = FilterWordBuilder.parseWords(rawInput);
        if (words.isEmpty()) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("filter.words_empty", ctx.fromId()));
            return;
        }
        String filterRule = FilterWordBuilder.wordsToRegex(words);
        Optional<String> filterIdOpt = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_EDIT_FILTER_ID);
        if (filterIdOpt.isPresent()) {
            try {
                UUID filterId = UUID.fromString(filterIdOpt.get());
                globalFilterService.updateFilter(ctx.fromId(), filterId, filterRule);
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
            ControllerDto c = controllerService.getControllerForChat(controllerId, ctx.chatId());

            Optional<String> msgIdOpt = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_WIZARD_MSG_ID);
            if (msgIdOpt.isPresent()) {
                try {
                    int msgId = Integer.parseInt(msgIdOpt.get());
                    MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), msgId,
                        ControllerDetailCallback.buildDetailText(c, ctx.chatId()),
                        ControllerDetailCallback.buildDetailKeyboard(c, ctx.fromId(), ctx.chatId()));
                    return;
                } catch (NumberFormatException ignored) {}
            }
            MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(),
                ControllerDetailCallback.buildDetailText(c, ctx.chatId()),
                ControllerDetailCallback.buildDetailKeyboard(c, ctx.fromId(), ctx.chatId()));

        } catch (InsufficientTokensException e) {
            // tokens exhausted — silently clear state, no message
        } catch (Exception e) {
            log.warn("Failed to update controller filter: {}", e.getMessage());
        }
    }

    private void handleWizardSearch(BotUpdateContext ctx, String query) {
        String target  = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SEARCH_TARGET).orElse("SPORT");
        int    msgId   = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_WIZARD_MSG_ID)
                .map(s -> { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } })
                .orElse(0);

        String lower = query.toLowerCase();

        if ("TOURNAMENT".equals(target)) {
            sessionService.setState(ctx.fromId(), BotState.SELECTING_TOURNAMENT);
            Optional<String> bm        = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BOOKMAKER);
            Optional<String> sportId   = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SPORT_ID);
            Optional<String> sportName = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SPORT_NAME);
            Optional<String> sportAlias= sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SPORT_ALIAS);
            if (bm.isEmpty() || sportId.isEmpty()) return;

            List<TournamentDto> filtered = wizardCache.getCachedTournaments(ctx.fromId())
                .map(list -> list.stream()
                    .filter(t -> t.title().toLowerCase().contains(lower))
                    .collect(Collectors.toList()))
                .orElse(List.of());

            boolean isGroupChat = ctx.isGroupChat();
            List<ControllerDto> controllers = isGroupChat
                ? controllerService.getGroupControllers(ctx.chatId())
                : controllerService.getUserControllers(ctx.fromId());
            Map<String, Instant> urlToLastEventAt = new HashMap<>();
            controllers.stream()
                .filter(c -> bm.get().equalsIgnoreCase(c.bookmaker()))
                .forEach(c -> urlToLastEventAt.put(c.url(), c.lastEventAt()));

            String sAlias   = sportAlias.orElse(sportId.get());
            String sName    = sportName.orElse(sportId.get());
            BookmakerType bmType = BookmakerType.valueOf(bm.get().toUpperCase());
            String sportUrl = TournamentSelectCallback.buildSportUrl(bmType, sportId.get(), sAlias);

            String monitorAllText = messageSource.getMessage("wizard.monitor_all_sport", ctx.fromId(), sName);
            String backText   = messageSource.getMessage("menu.back",   ctx.fromId());
            String cancelText = messageSource.getMessage("menu.cancel", ctx.fromId());
            var keyboard = SportSelectCallback.buildTournamentKeyboard(
                filtered, 0, monitorAllText, backText, cancelText, urlToLastEventAt, sportUrl,
                wizardProps.getTournamentPageSize(), botProperties.staleThresholdDays());

            String text = filtered.isEmpty()
                ? "🔍 По запросу «" + query + "» ничего не найдено."
                : messageSource.getMessage("wizard.select_tournament", ctx.fromId(), sName);
            if (msgId > 0) {
                MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), msgId, text, keyboard);
            } else {
                MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(), text, keyboard);
            }

        } else { // SPORT
            sessionService.setState(ctx.fromId(), BotState.SELECTING_SPORT);
            Optional<String> bm = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BOOKMAKER);
            if (bm.isEmpty()) return;

            List<SportDto> filtered = wizardCache.getCachedSports(ctx.fromId())
                .map(list -> list.stream()
                    .filter(s -> s.name().toLowerCase().contains(lower))
                    .collect(Collectors.toList()))
                .orElse(List.of());

            var keyboard = BookmakerSelectCallback.buildSportsKeyboard(
                filtered, 0,
                messageSource.getMessage("menu.back", ctx.fromId()),
                wizardProps.getSportPageSize());

            String text = filtered.isEmpty()
                ? "🔍 По запросу «" + query + "» ничего не найдено."
                : messageSource.getMessage("wizard.select_sport", ctx.fromId(), bm.get());
            if (msgId > 0) {
                MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), msgId, text, keyboard);
            } else {
                MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(), text, keyboard);
            }
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
