package com.valui.bot.handler.callback;

import com.valui.bot.config.BotProperties;
import com.valui.bot.config.BotWizardProperties;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.bot.listener.ParserAvailabilityRegistry;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.service.WizardCacheService;
import com.valui.bot.service.WizardMessageTracker;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.factory.ParserFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class WizardBackNavigator {

    private final BotSessionService         sessionService;
    private final BotMessageSource          messageSource;
    private final ControllerService         controllerService;
    private final ParserFactory             parserFactory;
    private final WizardCacheService        wizardCache;
    private final BotWizardProperties       wizardProps;
    private final BotProperties             botProperties;
    private final WizardMessageTracker      tracker;
    private final ParserAvailabilityRegistry availabilityRegistry;

    public void returnToBookmakerSelection(AbsSender sender, long fromId, long chatId, int messageId) {
        sessionService.setStateWithContext(fromId, BotState.SELECTING_BOOKMAKER, new HashMap<>());
        var kb = InlineKeyboardBuilder.create().columns(2);
        for (BookmakerType bm : BookmakerType.values()) {
            String label = availabilityRegistry.isUnavailable(bm) ? "⚠️ " + bm.name() : bm.name();
            kb.button(label, CallbackData.bookmakerSelect(bm.name()));
        }
        tracker.replaceAndTrack(sender, chatId, messageId,
                messageSource.getMessage("wizard.select_bookmaker", fromId),
                kb.build());
    }

    public void returnToSportList(AbsSender sender, long fromId, long chatId, int messageId) {
        Optional<String> bmOpt = sessionService.getContext(fromId, UserBotSession.CTX_BOOKMAKER);
        if (bmOpt.isEmpty()) {
            returnToBookmakerSelection(sender, fromId, chatId, messageId);
            return;
        }
        String bm = bmOpt.get();

        // Use cache — user is just navigating back, no need to re-fetch sports
        List<SportDto> sports;
        Optional<List<SportDto>> cached = wizardCache.getCachedSports(fromId);
        if (cached.isPresent()) {
            sports = cached.get();
        } else {
            BookmakerParser parser;
            try {
                parser = parserFactory.getParser(BookmakerType.valueOf(bm.toUpperCase()));
            } catch (Exception e) {
                log.warn("No parser for bookmaker: {}", bm);
                returnToBookmakerSelection(sender, fromId, chatId, messageId);
                return;
            }
            ParseResult<List<SportDto>> result = parser.fetchSports();
            if (!result.success() || result.data() == null) {
                returnToBookmakerSelection(sender, fromId, chatId, messageId);
                return;
            }
            sports = result.data();
            wizardCache.cacheSports(fromId, sports);
        }

        // Clear tournament cache — user may pick a different sport next
        wizardCache.clearTournamentsCache(fromId);

        sessionService.setStateAndMergeContext(fromId, BotState.SELECTING_SPORT,
                Map.of(UserBotSession.CTX_BOOKMAKER, bm));

        int savedPage = sessionService.getContext(fromId, UserBotSession.CTX_SPORT_PAGE)
            .map(s -> { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } })
            .orElse(0);

        InlineKeyboardMarkup keyboard = BookmakerSelectCallback.buildSportsKeyboard(
                sports, savedPage,
                messageSource.getMessage("menu.back", fromId),
                wizardProps.getSportPageSize());
        tracker.replaceAndTrack(sender, chatId, messageId,
                messageSource.getMessage("wizard.select_sport", fromId, bm),
                keyboard);
    }

    public void returnToTournamentList(AbsSender sender, long fromId, long chatId, int messageId) {
        Optional<String> bmOpt         = sessionService.getContext(fromId, UserBotSession.CTX_BOOKMAKER);
        Optional<String> sportIdOpt    = sessionService.getContext(fromId, UserBotSession.CTX_SPORT_ID);
        Optional<String> sportNameOpt  = sessionService.getContext(fromId, UserBotSession.CTX_SPORT_NAME);
        Optional<String> sportAliasOpt = sessionService.getContext(fromId, UserBotSession.CTX_SPORT_ALIAS);

        if (bmOpt.isEmpty() || sportIdOpt.isEmpty()) {
            sessionService.clearSession(fromId);
            int id = MessageSend.sendGetId(sender, chatId,
                    messageSource.getMessage("menu.main", fromId));
            if (id > 0) tracker.track(chatId, id);
            return;
        }

        String bm         = bmOpt.get();
        String sportId    = sportIdOpt.get();
        String sportName  = sportNameOpt.orElse(sportId);
        String sportAlias = sportAliasOpt.orElse(sportId);

        // Read both page and search query BEFORE resetting context
        int savedPage = sessionService.getContext(fromId, UserBotSession.CTX_TOURNAMENT_PAGE)
            .map(s -> { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } })
            .orElse(0);
        String searchQuery = sessionService.getContext(fromId, UserBotSession.CTX_WIZARD_SEARCH_QUERY)
            .filter(s -> !s.isBlank())
            .orElse(null);

        Map<String, String> newCtx = new HashMap<>(Map.of(
            UserBotSession.CTX_BOOKMAKER,   bm,
            UserBotSession.CTX_SPORT_ID,    sportId,
            UserBotSession.CTX_SPORT_NAME,  sportName,
            UserBotSession.CTX_SPORT_ALIAS, sportAlias
        ));
        if (searchQuery != null) newCtx.put(UserBotSession.CTX_WIZARD_SEARCH_QUERY, searchQuery);
        sessionService.setStateWithContext(fromId, BotState.SELECTING_TOURNAMENT, newCtx);

        // Use cache — avoids HTTP call when returning to the same tournament list
        List<TournamentDto> tournaments;
        Optional<List<TournamentDto>> cached = wizardCache.getCachedTournaments(fromId);
        if (cached.isPresent()) {
            tournaments = cached.get();
        } else {
            BookmakerParser parser;
            try {
                parser = parserFactory.getParser(BookmakerType.valueOf(bm.toUpperCase()));
            } catch (Exception e) {
                log.warn("No parser for bookmaker: {}", bm);
                fallbackToMainMenu(sender, fromId, chatId);
                return;
            }
            ParseResult<List<TournamentDto>> result = parser.fetchTournaments(sportId);
            if (!result.success() || result.data() == null) {
                fallbackToMainMenu(sender, fromId, chatId);
                return;
            }
            tournaments = result.data();
            wizardCache.cacheTournaments(fromId, tournaments);
        }

        BookmakerType bmType = BookmakerType.valueOf(bm.toUpperCase());
        String sportUrl = TournamentSelectCallback.buildSportUrl(bmType, sportId, sportAlias);

        boolean isGroupChat = chatId < 0;
        List<ControllerDto> controllers = isGroupChat
            ? controllerService.getGroupControllers(chatId)
            : controllerService.getUserControllers(fromId);
        Map<String, Instant> urlToLastEventAt =
            SportSelectCallback.buildControllerLookupMap(controllers, bm);

        // Apply search filter if user came from a search
        List<TournamentDto> toDisplay;
        String listText;
        int displayPage;
        if (searchQuery != null) {
            String lower = searchQuery.toLowerCase();
            toDisplay = tournaments.stream()
                .filter(t -> t.title().toLowerCase().contains(lower))
                .collect(java.util.stream.Collectors.toList());
            listText = toDisplay.isEmpty()
                ? "🔍 По запросу «" + searchQuery + "» ничего не найдено."
                : messageSource.getMessage("wizard.select_tournament", fromId, sportName);
            displayPage = 0;
        } else {
            toDisplay = tournaments;
            listText = messageSource.getMessage("wizard.select_tournament", fromId, sportName);
            displayPage = savedPage;
        }

        String monitorAllText = messageSource.getMessage("wizard.monitor_all_sport", fromId, sportName);
        String backText   = messageSource.getMessage("menu.back",   fromId);
        String cancelText = messageSource.getMessage("menu.cancel", fromId);
        InlineKeyboardMarkup keyboard = SportSelectCallback.buildTournamentKeyboard(
            toDisplay, displayPage, monitorAllText, backText, cancelText, urlToLastEventAt, sportUrl,
            bmType, wizardProps.getTournamentPageSize(), botProperties.staleThresholdDays());

        tracker.replaceAndTrack(sender, chatId, messageId, listText, keyboard);
    }

    private void fallbackToMainMenu(AbsSender sender, long fromId, long chatId) {
        sessionService.clearSession(fromId);
        int id = MessageSend.sendGetId(sender, chatId,
            messageSource.getMessage("menu.main", fromId));
        if (id > 0) tracker.track(chatId, id);
    }
}
