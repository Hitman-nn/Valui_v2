package com.valui.bot.handler.callback;

import com.valui.bot.config.BotProperties;
import com.valui.bot.config.BotWizardProperties;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.KeyboardButton;
import com.valui.bot.keyboard.PagedKeyboardBuilder;
import com.valui.bot.listener.ParserAvailabilityRegistry;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SportSelectCallback implements CallbackHandler {

    private final BotSessionService         sessionService;
    private final BotMessageSource          messageSource;
    private final ParserFactory             parserFactory;
    private final ControllerService         controllerService;
    private final WizardCacheService        wizardCache;
    private final BotWizardProperties       wizardProps;
    private final BotProperties             botProperties;
    private final ParserAvailabilityRegistry availabilityRegistry;

    @Override
    public String callbackPrefix() { return "SPORT:"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data = ctx.update().getCallbackQuery().getData();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        String callbackId = ctx.update().getCallbackQuery().getId();

        if (ctx.session().getState() != BotState.SELECTING_SPORT) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId, "⚠️ Это не ваше меню");
            return;
        }

        if (data.equals(CallbackData.SPORT_BACK)) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            handleBackToBookmakers(ctx, messageId);
        } else if (data.startsWith("SPORT:PAGE:")) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            handleSportPage(ctx, data, messageId);
        } else if (data.startsWith(CallbackData.SPORT_SEL_PREFIX)) {
            handleSportSelect(ctx, data, messageId, callbackId);
        }
    }

    private void handleBackToBookmakers(BotUpdateContext ctx, int messageId) {
        sessionService.setStateWithContext(ctx.fromId(), BotState.SELECTING_BOOKMAKER, new HashMap<>());
        var kb = InlineKeyboardBuilder.create().columns(2);
        for (BookmakerType bm : BookmakerType.values()) {
            String label = availabilityRegistry.isUnavailable(bm) ? "⚠️ " + bm.name() : bm.name();
            kb.button(label, CallbackData.bookmakerSelect(bm.name()));
        }
        ctx.tracker().replaceAndTrack(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.select_bookmaker", ctx.fromId()),
            kb.build());
    }

    private void handleSportPage(BotUpdateContext ctx, String data, int messageId) {
        int page = parsePageNum(data.substring("SPORT:PAGE:".length()));
        Optional<String> bm = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BOOKMAKER);
        if (bm.isEmpty()) return;

        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.SELECTING_SPORT,
            Map.of(UserBotSession.CTX_SPORT_PAGE, String.valueOf(page)));

        // Use cache — avoids HTTP round-trip on every page click
        List<SportDto> sports;
        Optional<List<SportDto>> cached = wizardCache.getCachedSports(ctx.fromId());
        if (cached.isPresent()) {
            sports = cached.get();
        } else {
            BookmakerParser parser = getParser(bm.get());
            if (parser == null) return;
            ParseResult<List<SportDto>> result = parser.fetchSports();
            if (!result.success() || result.data() == null) return;
            sports = result.data();
            wizardCache.cacheSports(ctx.fromId(), sports);
        }

        InlineKeyboardMarkup keyboard = BookmakerSelectCallback.buildSportsKeyboard(
            sports, page,
            messageSource.getMessage("menu.back", ctx.fromId()),
            wizardProps.getSportPageSize());
        ctx.tracker().replaceAndTrack(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.select_sport", ctx.fromId(), bm.get()), keyboard);
    }

    private void handleSportSelect(BotUpdateContext ctx, String data, int messageId, String callbackId) {
        String sportId = data.substring(CallbackData.SPORT_SEL_PREFIX.length());
        Optional<String> bm = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_BOOKMAKER);
        if (bm.isEmpty()) return;

        BookmakerParser parser = getParser(bm.get());
        if (parser == null) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                "❌ Не удалось загрузить данные. Попробуйте ещё раз.");
            return;
        }

        // Resolve sport name from cache if available, otherwise fetch
        String sportName = sportId;
        String sportAlias = sportId;
        Optional<List<SportDto>> cachedSports = wizardCache.getCachedSports(ctx.fromId());
        if (cachedSports.isPresent()) {
            var found = cachedSports.get().stream().filter(s -> s.id().equals(sportId)).findFirst();
            if (found.isPresent()) {
                sportName  = found.get().name();
                sportAlias = found.get().alias() != null ? found.get().alias() : sportId;
            }
        } else {
            ParseResult<List<SportDto>> sportsResult = parser.fetchSports();
            if (sportsResult.success() && sportsResult.data() != null) {
                wizardCache.cacheSports(ctx.fromId(), sportsResult.data());
                var found = sportsResult.data().stream().filter(s -> s.id().equals(sportId)).findFirst();
                if (found.isPresent()) {
                    sportName  = found.get().name();
                    sportAlias = found.get().alias() != null ? found.get().alias() : sportId;
                }
            }
        }

        ParseResult<List<TournamentDto>> tournsResult = parser.fetchTournaments(sportId);
        if (!tournsResult.success() || tournsResult.data() == null) {
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                "❌ Не удалось загрузить данные. Попробуйте ещё раз.");
            return;
        }

        MessageSend.answerCallback(ctx.sender(), callbackId);

        // Cache tournaments for this sport; clear any stale previous cache
        wizardCache.cacheTournaments(ctx.fromId(), tournsResult.data());

        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.SELECTING_TOURNAMENT,
            Map.of(UserBotSession.CTX_SPORT_ID,    sportId,
                   UserBotSession.CTX_SPORT_NAME,  sportName,
                   UserBotSession.CTX_SPORT_ALIAS, sportAlias));

        Map<String, Instant> urlToLastEventAt = buildUrlToLastEventAtMap(ctx, bm.get());
        BookmakerType bookmakerType = BookmakerType.valueOf(bm.get().toUpperCase());
        String sportUrl = buildSportUrl(bookmakerType, sportId, sportAlias);

        String monitorAllText = messageSource.getMessage("wizard.monitor_all_sport", ctx.fromId(), sportName);
        String backText   = messageSource.getMessage("menu.back",   ctx.fromId());
        String cancelText = messageSource.getMessage("menu.cancel", ctx.fromId());
        InlineKeyboardMarkup keyboard = buildTournamentKeyboard(
            tournsResult.data(), 0, monitorAllText, backText, cancelText, urlToLastEventAt, sportUrl,
            wizardProps.getTournamentPageSize(), botProperties.staleThresholdDays());
        ctx.tracker().replaceAndTrack(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.select_tournament", ctx.fromId(), sportName),
            keyboard);
    }

    public static InlineKeyboardMarkup buildTournamentKeyboard(
            List<TournamentDto> tournaments, int page,
            String monitorAllText, String backText, String cancelText,
            Map<String, Instant> urlToLastEventAt, String sportUrl, int pageSize, int staleThresholdDays) {

        List<TournamentDto> sorted = tournaments.stream()
                .sorted(Comparator.comparing(t -> t.title().toLowerCase()))
                .toList();

        boolean sportExists = urlToLastEventAt.containsKey(sportUrl);
        String monitorAllCallback = sportExists ? CallbackData.TOURN_EXIST : CallbackData.TOURN_ALL;
        String monitorAllLabel;
        if (sportExists) {
            monitorAllLabel = isStale(urlToLastEventAt.get(sportUrl), staleThresholdDays)
                ? "🕰️ " + monitorAllText : "✅ " + monitorAllText;
        } else {
            monitorAllLabel = monitorAllText;
        }

        return PagedKeyboardBuilder.<TournamentDto>create()
            .items(sorted)
            .itemRenderer(t -> {
                boolean exists = urlToLastEventAt.containsKey(t.url());
                String label;
                if (exists) {
                    label = isStale(urlToLastEventAt.get(t.url()), staleThresholdDays)
                        ? "🕰️ " + t.title() : "✅ " + t.title();
                } else {
                    label = t.title();
                }
                String callback = exists ? CallbackData.TOURN_EXIST : CallbackData.tournSel(t.id());
                return KeyboardButton.callback(label, callback);
            })
            .pageSize(pageSize)
            .currentPage(page)
            .navigationCallbackPrefix(CallbackData.TOURN_PAGE_PREFIX)
            .appendRow(KeyboardButton.callback("🔍 Поиск", CallbackData.SEARCH_TOURN))
            .appendRow(KeyboardButton.callback(monitorAllLabel, monitorAllCallback))
            .appendRow(KeyboardButton.callback(backText,   CallbackData.TOURN_BACK))
            .appendRow(KeyboardButton.callback(cancelText, CallbackData.CANCEL))
            .build();
    }

    private static boolean isStale(Instant lastEventAt, int days) {
        return lastEventAt != null && lastEventAt.isBefore(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    private Map<String, Instant> buildUrlToLastEventAtMap(BotUpdateContext ctx, String bookmakerCode) {
        List<ControllerDto> controllers = ctx.isGroupChat()
            ? controllerService.getGroupControllers(ctx.chatId())
            : controllerService.getUserControllers(ctx.fromId());
        Map<String, Instant> map = new HashMap<>();
        controllers.stream()
            .filter(c -> bookmakerCode.equalsIgnoreCase(c.bookmaker()))
            .forEach(c -> map.put(c.url(), c.lastEventAt()));
        return map;
    }

    static String buildSportUrl(BookmakerType bm, String sportId, String alias) {
        return switch (bm) {
            case XBET    -> "https://1xstavka.ru/line/" + sportId;
            case FONBET  -> "https://www.fon.bet/sports/" + sportId;
            case OLIMP   -> "https://www.olimp.bet/line/" + sportId;
            case BETCITY -> "https://betcity.ru/ru/line/" + alias;
            case BETBOOM -> "https://betboom.ru/sport/" + alias;
        };
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
