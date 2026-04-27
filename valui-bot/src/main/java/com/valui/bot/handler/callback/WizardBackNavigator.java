package com.valui.bot.handler.callback;

import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.menu.MainMenuKeyboard;
import com.valui.bot.service.BotSessionService;
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
import com.valui.user.service.PlanLimitChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class WizardBackNavigator {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final ControllerService controllerService;
    private final ParserFactory parserFactory;
    private final PlanLimitChecker planLimitChecker;

    public void returnToBookmakerSelection(AbsSender sender, long chatId, int messageId) {
        sessionService.setStateWithContext(chatId, BotState.SELECTING_BOOKMAKER, new HashMap<>());
        List<String> allowed = planLimitChecker.getLimitInfo(chatId).allowedBookmakers();
        var kb = InlineKeyboardBuilder.create().columns(2);
        for (String bm : allowed) {
            kb.button(bm, CallbackData.bookmakerSelect(bm));
        }
        MessageSend.replaceWithKeyboard(sender, chatId, messageId,
                messageSource.getMessage("wizard.select_bookmaker", chatId),
                kb.build());
    }

    public void returnToSportList(AbsSender sender, long chatId, int messageId) {
        Optional<String> bmOpt = sessionService.getContext(chatId, UserBotSession.CTX_BOOKMAKER);
        if (bmOpt.isEmpty()) {
            returnToBookmakerSelection(sender, chatId, messageId);
            return;
        }
        String bm = bmOpt.get();

        BookmakerParser parser;
        try {
            parser = parserFactory.getParser(BookmakerType.valueOf(bm.toUpperCase()));
        } catch (Exception e) {
            log.warn("No parser for bookmaker: {}", bm);
            returnToBookmakerSelection(sender, chatId, messageId);
            return;
        }

        ParseResult<List<SportDto>> result = parser.fetchSports();
        if (!result.success() || result.data() == null) {
            returnToBookmakerSelection(sender, chatId, messageId);
            return;
        }

        sessionService.setStateAndMergeContext(chatId, BotState.SELECTING_SPORT,
                Map.of(UserBotSession.CTX_BOOKMAKER, bm));

        InlineKeyboardMarkup keyboard = BookmakerSelectCallback.buildSportsKeyboard(
                result.data(), 0,
                messageSource.getMessage("menu.back", chatId));
        MessageSend.replaceWithKeyboard(sender, chatId, messageId,
                messageSource.getMessage("wizard.select_sport", chatId, bm),
                keyboard);
    }

    public void returnToTournamentList(AbsSender sender, long chatId, int messageId) {
        Optional<String> bmOpt         = sessionService.getContext(chatId, UserBotSession.CTX_BOOKMAKER);
        Optional<String> sportIdOpt    = sessionService.getContext(chatId, UserBotSession.CTX_SPORT_ID);
        Optional<String> sportNameOpt  = sessionService.getContext(chatId, UserBotSession.CTX_SPORT_NAME);
        Optional<String> sportAliasOpt = sessionService.getContext(chatId, UserBotSession.CTX_SPORT_ALIAS);

        if (bmOpt.isEmpty() || sportIdOpt.isEmpty()) {
            sessionService.clearSession(chatId);
            MessageSend.textWithKeyboard(sender, chatId,
                    messageSource.getMessage("menu.main", chatId),
                    MainMenuKeyboard.build(chatId, messageSource));
            return;
        }

        String bm         = bmOpt.get();
        String sportId    = sportIdOpt.get();
        String sportName  = sportNameOpt.orElse(sportId);
        String sportAlias = sportAliasOpt.orElse(sportId);

        sessionService.setStateWithContext(chatId, BotState.SELECTING_TOURNAMENT,
            new HashMap<>(Map.of(
                UserBotSession.CTX_BOOKMAKER,   bm,
                UserBotSession.CTX_SPORT_ID,    sportId,
                UserBotSession.CTX_SPORT_NAME,  sportName,
                UserBotSession.CTX_SPORT_ALIAS, sportAlias
            )));

        BookmakerParser parser;
        try {
            parser = parserFactory.getParser(BookmakerType.valueOf(bm.toUpperCase()));
        } catch (Exception e) {
            log.warn("No parser for bookmaker: {}", bm);
            fallbackToMainMenu(sender, chatId);
            return;
        }

        ParseResult<List<TournamentDto>> result = parser.fetchTournaments(sportId);
        if (!result.success() || result.data() == null) {
            fallbackToMainMenu(sender, chatId);
            return;
        }

        Set<String> existingUrls = controllerService.getUserControllers(chatId).stream()
            .filter(c -> bm.equalsIgnoreCase(c.bookmaker()))
            .map(ControllerDto::url)
            .collect(Collectors.toSet());

        BookmakerType bmType = BookmakerType.valueOf(bm.toUpperCase());
        String sportUrl = TournamentSelectCallback.buildSportUrl(bmType, sportId, sportAlias);

        String monitorAllText = messageSource.getMessage("wizard.monitor_all_sport", chatId, sportName);
        String backText   = messageSource.getMessage("menu.back",   chatId);
        String cancelText = messageSource.getMessage("menu.cancel", chatId);
        InlineKeyboardMarkup keyboard = SportSelectCallback.buildTournamentKeyboard(
            result.data(), 0, monitorAllText, backText, cancelText, existingUrls, sportUrl);

        MessageSend.replaceWithKeyboard(sender, chatId, messageId,
            messageSource.getMessage("wizard.select_tournament", chatId, sportName), keyboard);
    }

    private void fallbackToMainMenu(AbsSender sender, long chatId) {
        sessionService.clearSession(chatId);
        MessageSend.textWithKeyboard(sender, chatId,
            messageSource.getMessage("menu.main", chatId),
            MainMenuKeyboard.build(chatId, messageSource));
    }
}
