package com.valui.bot.handler.callback;

import com.valui.bot.config.BotProperties;
import com.valui.bot.config.BotWizardProperties;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.service.WizardCacheService;
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
import com.valui.user.api.PlanLimitFacade;
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

    private final BotSessionService   sessionService;
    private final BotMessageSource    messageSource;
    private final ControllerService   controllerService;
    private final ParserFactory       parserFactory;
    private final PlanLimitFacade     planLimitFacade;
    private final WizardCacheService  wizardCache;
    private final BotWizardProperties wizardProps;
    private final BotProperties       botProperties;

    public void returnToBookmakerSelection(AbsSender sender, long fromId, long chatId, int messageId) {
        sessionService.setStateWithContext(fromId, BotState.SELECTING_BOOKMAKER, new HashMap<>());
        var kb = InlineKeyboardBuilder.create().columns(2);
        for (com.valui.common.domain.BookmakerType bm : com.valui.common.domain.BookmakerType.values()) {
            kb.button(bm.name(), CallbackData.bookmakerSelect(bm.name()));
        }
        MessageSend.replaceWithKeyboard(sender, chatId, messageId,
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
        MessageSend.replaceWithKeyboard(sender, chatId, messageId,
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
            MessageSend.textWithKeyboard(sender, chatId,
                    messageSource.getMessage("menu.main", fromId),
                    MainMenuKeyboard.build(fromId, messageSource));
            return;
        }

        String bm         = bmOpt.get();
        String sportId    = sportIdOpt.get();
        String sportName  = sportNameOpt.orElse(sportId);
        String sportAlias = sportAliasOpt.orElse(sportId);

        sessionService.setStateWithContext(fromId, BotState.SELECTING_TOURNAMENT,
            new HashMap<>(Map.of(
                UserBotSession.CTX_BOOKMAKER,   bm,
                UserBotSession.CTX_SPORT_ID,    sportId,
                UserBotSession.CTX_SPORT_NAME,  sportName,
                UserBotSession.CTX_SPORT_ALIAS, sportAlias
            )));

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

        boolean isGroupChat = chatId < 0;
        List<ControllerDto> controllers = isGroupChat
            ? controllerService.getGroupControllers(chatId)
            : controllerService.getUserControllers(fromId);
        Map<String, Instant> urlToLastEventAt = new HashMap<>();
        controllers.stream()
            .filter(c -> bm.equalsIgnoreCase(c.bookmaker()))
            .forEach(c -> urlToLastEventAt.put(c.url(), c.lastEventAt()));

        BookmakerType bmType = BookmakerType.valueOf(bm.toUpperCase());
        String sportUrl = TournamentSelectCallback.buildSportUrl(bmType, sportId, sportAlias);

        int savedPage = sessionService.getContext(fromId, UserBotSession.CTX_TOURNAMENT_PAGE)
            .map(s -> { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } })
            .orElse(0);

        String monitorAllText = messageSource.getMessage("wizard.monitor_all_sport", fromId, sportName);
        String backText   = messageSource.getMessage("menu.back",   fromId);
        String cancelText = messageSource.getMessage("menu.cancel", fromId);
        InlineKeyboardMarkup keyboard = SportSelectCallback.buildTournamentKeyboard(
            tournaments, savedPage, monitorAllText, backText, cancelText, urlToLastEventAt, sportUrl,
            wizardProps.getTournamentPageSize(), botProperties.staleThresholdDays());

        MessageSend.replaceWithKeyboard(sender, chatId, messageId,
            messageSource.getMessage("wizard.select_tournament", fromId, sportName), keyboard);
    }

    private void fallbackToMainMenu(AbsSender sender, long fromId, long chatId) {
        sessionService.clearSession(fromId);
        MessageSend.textWithKeyboard(sender, chatId,
            messageSource.getMessage("menu.main", fromId),
            MainMenuKeyboard.build(fromId, messageSource));
    }
}
