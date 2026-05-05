package com.valui.bot.handler.callback;

import com.valui.bot.config.BotProperties;
import com.valui.bot.config.BotWizardProperties;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.service.WizardCacheService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.dto.CreateControllerRequest;
import com.valui.monitor.service.ControllerService;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.factory.ParserFactory;
import com.valui.common.exception.InsufficientTokensException;
import com.valui.user.api.PlanLimitFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TournamentSelectCallback implements CallbackHandler {

    private final BotSessionService   sessionService;
    private final BotMessageSource    messageSource;
    private final ParserFactory       parserFactory;
    private final ControllerService   controllerService;
    private final PlanLimitFacade     planLimitFacade;
    private final WizardBackNavigator backNavigator;
    private final WizardCacheService  wizardCache;
    private final BotWizardProperties wizardProps;
    private final BotProperties       botProperties;

    @Override
    public String callbackPrefix() { return "TOURN:"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data = ctx.update().getCallbackQuery().getData();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        String callbackId = ctx.update().getCallbackQuery().getId();

        if (data.equals(CallbackData.TOURN_EXIST)) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "✅ Уже добавлен");
            return;
        }

        if (ctx.session().getState() != BotState.SELECTING_TOURNAMENT) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "⚠️ Это не ваше меню");
            return;
        }

        // "← Назад" from tournament list → back to sport selection
        if (data.equals(CallbackData.TOURN_BACK)) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            backNavigator.returnToSportList(ctx.sender(), ctx.fromId(), ctx.chatId(), messageId);
            return;
        }

        if (data.startsWith("TOURN:PAGE:")) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            handleTournamentPage(ctx, data, messageId);
        } else if (data.equals(CallbackData.TOURN_ALL)) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            handleMonitorAll(ctx, messageId);
        } else if (data.startsWith(CallbackData.TOURN_SEL_PREFIX)) {
            handleTournamentSelect(ctx, data, messageId, callbackId);
        }
    }

    private void handleTournamentPage(BotUpdateContext ctx, String data, int messageId) {
        int page = parsePageNum(data.substring("TOURN:PAGE:".length()));
        Optional<String> bm         = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BOOKMAKER);
        Optional<String> sportId    = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SPORT_ID);
        Optional<String> sportName  = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SPORT_NAME);
        Optional<String> sportAlias = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SPORT_ALIAS);

        if (bm.isEmpty() || sportId.isEmpty()) return;

        // Use cache — no HTTP call on page navigation
        List<TournamentDto> tournaments;
        Optional<List<TournamentDto>> cached = wizardCache.getCachedTournaments(ctx.fromId());
        if (cached.isPresent()) {
            tournaments = cached.get();
        } else {
            BookmakerParser parser = getParser(bm.get());
            if (parser == null) return;
            ParseResult<List<TournamentDto>> result = parser.fetchTournaments(sportId.get());
            if (!result.success() || result.data() == null) return;
            tournaments = result.data();
            wizardCache.cacheTournaments(ctx.fromId(), tournaments);
        }

        String sName  = sportName.orElse(sportId.get());
        String sAlias = sportAlias.orElse(sportId.get());
        Map<String, Instant> urlToLastEventAt = buildUrlToLastEventAtMap(ctx, bm.get());
        BookmakerType bookmakerType = BookmakerType.valueOf(bm.get().toUpperCase());
        String sportUrl = buildSportUrl(bookmakerType, sportId.get(), sAlias);

        String monitorAllText = messageSource.getMessage("wizard.monitor_all_sport", ctx.fromId(), sName);
        String backText   = messageSource.getMessage("menu.back",   ctx.fromId());
        String cancelText = messageSource.getMessage("menu.cancel", ctx.fromId());
        InlineKeyboardMarkup keyboard = SportSelectCallback.buildTournamentKeyboard(
            tournaments, page, monitorAllText, backText, cancelText, urlToLastEventAt, sportUrl,
            wizardProps.getTournamentPageSize(), botProperties.staleThresholdDays());
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.select_tournament", ctx.fromId(), sName), keyboard);
    }

    private void handleMonitorAll(BotUpdateContext ctx, int messageId) {
        Optional<String> bm         = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BOOKMAKER);
        Optional<String> sportId    = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SPORT_ID);
        Optional<String> sportName  = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SPORT_NAME);
        Optional<String> sportAlias = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SPORT_ALIAS);

        if (bm.isEmpty() || sportId.isEmpty()) return;

        BookmakerType bookmakerType = BookmakerType.valueOf(bm.get().toUpperCase());
        String sportUrl = buildSportUrl(bookmakerType, sportId.get(), sportAlias.orElse(sportId.get()));
        String sName = sportName.orElse(sportId.get());

        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.WAITING_FILTER_RULE, Map.of(
            UserBotSession.CTX_CONTROLLER_TYPE, "SPORT",
            UserBotSession.CTX_TOURNAMENT_URL,   sportUrl,
            UserBotSession.CTX_TOURNAMENT_TITLE, sName,
            UserBotSession.CTX_FILTER_MODE,      "INDIVIDUAL",
            UserBotSession.CTX_WIZARD_MSG_ID,    String.valueOf(messageId)
        ));

        showFilterPrompt(ctx, messageId);
    }

    /**
     * Directly creates a TOURNAMENT controller without a confirmation step.
     * After creation the user is returned to the tournament list, which now shows
     * the new entry marked with ✅.
     */
    private void handleTournamentSelect(BotUpdateContext ctx, String data, int messageId, String callbackId) {
        String tournamentId = data.substring(CallbackData.TOURN_SEL_PREFIX.length());
        Optional<String> bm      = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BOOKMAKER);
        Optional<String> sportId = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_SPORT_ID);

        if (bm.isEmpty() || sportId.isEmpty()) return;

        // Try to resolve the tournament from cache first — avoids a full re-fetch on every selection
        TournamentDto tournament = wizardCache.getCachedTournaments(ctx.fromId())
            .flatMap(list -> list.stream().filter(t -> t.id().equals(tournamentId)).findFirst())
            .orElse(null);

        if (tournament == null) {
            BookmakerParser parser = getParser(bm.get());
            if (parser == null) {
                MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "❌ Не удалось загрузить данные. Попробуйте ещё раз.");
                return;
            }
            ParseResult<List<TournamentDto>> result = parser.fetchTournaments(sportId.get());
            if (!result.success() || result.data() == null) {
                MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                    "❌ Не удалось загрузить данные. Попробуйте ещё раз.");
                return;
            }
            wizardCache.cacheTournaments(ctx.fromId(), result.data());
            tournament = result.data().stream()
                .filter(t -> t.id().equals(tournamentId))
                .findFirst()
                .orElse(null);
        }

        if (tournament == null) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                "❌ Не удалось загрузить данные. Попробуйте ещё раз.");
            return;
        }

        try {
            controllerService.addController(
                new CreateControllerRequest(tournament.url(), bm.get(), tournament.title(), false, null),
                ctx.fromId(), ctx.chatId());
            log.info("✅ Контроллер создан: fromId={} chatId={} bm={} url={}", ctx.fromId(), ctx.chatId(), bm.get(), tournament.url());
        } catch (InsufficientTokensException e) {
            MessageSend.answerCallbackWithModal(ctx.sender(), callbackId, e.toAlertText());
            return;
        } catch (Exception e) {
            log.error("❌ Ошибка создания контроллера chatId={}: {}", ctx.chatId(), e.getMessage());
            sessionService.clearSession(ctx.fromId());
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                "❌ Произошла ошибка. Попробуйте ещё раз.");
            return;
        }

        // Return to tournament list — the created entry will now appear marked with ✅
        MessageSend.answerCallback(ctx.sender(), callbackId);
        backNavigator.returnToTournamentList(ctx.sender(), ctx.fromId(), ctx.chatId(), messageId);
    }

    private void showFilterPrompt(BotUpdateContext ctx, int messageId) {
        String skipText = messageSource.getMessage("wizard.filter_skip", ctx.fromId());
        String backText = messageSource.getMessage("menu.back",          ctx.fromId());
        InlineKeyboardMarkup keyboard = InlineKeyboardBuilder.create()
            .button(skipText, CallbackData.FILTER_SKIP)
            .row()
            .button(backText, CallbackData.CANCEL)  // CancelCallback: INDIVIDUAL → список турниров
            .build();
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.enter_filter", ctx.fromId()),
            keyboard);
    }

    public static String buildSportUrl(BookmakerType bm, String sportId, String alias) {
        return switch (bm) {
            case XBET    -> "https://1xstavka.ru/line/" + sportId;
            case FONBET  -> "https://www.fon.bet/sports/" + sportId;
            case OLIMP   -> "https://www.olimp.bet/line/" + sportId;
            case BETCITY -> "https://betcity.ru/ru/line/" + alias;
            case BETBOOM -> "https://betboom.ru/sport/" + alias;
        };
    }

    Map<String, Instant> buildUrlToLastEventAtMap(BotUpdateContext ctx, String bookmakerCode) {
        List<ControllerDto> controllers = ctx.isGroupChat()
            ? controllerService.getGroupControllers(ctx.chatId())
            : controllerService.getUserControllers(ctx.fromId());
        Map<String, Instant> map = new HashMap<>();
        controllers.stream()
            .filter(c -> bookmakerCode.equalsIgnoreCase(c.bookmaker()))
            .forEach(c -> map.put(c.url(), c.lastEventAt()));
        return map;
    }

    private BookmakerParser getParser(String bookmakerCode) {
        try {
            return parserFactory.getParser(BookmakerType.valueOf(bookmakerCode.toUpperCase()));
        } catch (Exception e) {
            log.warn("No parser for bookmaker: {}", bookmakerCode);
            return null;
        }
    }

    private static int parsePageNum(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
