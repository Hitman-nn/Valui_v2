package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.KeyboardButton;
import com.valui.bot.keyboard.PagedKeyboardBuilder;
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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SportSelectCallback implements CallbackHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final ParserFactory parserFactory;
    private final ControllerService controllerService;
    private final PlanLimitChecker planLimitChecker;

    @Override
    public String callbackPrefix() { return "SPORT:"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data = ctx.update().getCallbackQuery().getData();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        String callbackId = ctx.update().getCallbackQuery().getId();

        MessageSend.answerCallback(ctx.sender(), callbackId);

        if (data.equals(CallbackData.SPORT_BACK)) {
            handleBackToBookmakers(ctx, messageId);
        } else if (data.startsWith("SPORT:PAGE:")) {
            handleSportPage(ctx, data, messageId);
        } else if (data.startsWith(CallbackData.SPORT_SEL_PREFIX)) {
            handleSportSelect(ctx, data, messageId);
        }
    }

    // "← Назад" from sport list → re-show bookmaker selection (no cancel button on BK screen)
    private void handleBackToBookmakers(BotUpdateContext ctx, int messageId) {
        sessionService.setStateWithContext(ctx.chatId(), BotState.SELECTING_BOOKMAKER, new HashMap<>());
        List<String> allowed = planLimitChecker.getLimitInfo(ctx.chatId()).allowedBookmakers();
        var kb = InlineKeyboardBuilder.create().columns(2);
        for (String bm : allowed) {
            kb.button(bm, CallbackData.bookmakerSelect(bm));
        }
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.select_bookmaker", ctx.chatId()),
            kb.build());
    }

    private void handleSportPage(BotUpdateContext ctx, String data, int messageId) {
        int page = parsePageNum(data.substring("SPORT:PAGE:".length()));
        Optional<String> bm = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_BOOKMAKER);
        if (bm.isEmpty()) return;

        BookmakerParser parser = getParser(bm.get());
        if (parser == null) return;

        ParseResult<List<SportDto>> result = parser.fetchSports();
        if (!result.success() || result.data() == null) return;

        InlineKeyboardMarkup keyboard = BookmakerSelectCallback.buildSportsKeyboard(
            result.data(), page,
            messageSource.getMessage("menu.back", ctx.chatId()));
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.select_sport", ctx.chatId(), bm.get()), keyboard);
    }

    private void handleSportSelect(BotUpdateContext ctx, String data, int messageId) {
        String sportId = data.substring(CallbackData.SPORT_SEL_PREFIX.length());
        Optional<String> bm = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_BOOKMAKER);
        if (bm.isEmpty()) return;

        BookmakerParser parser = getParser(bm.get());
        if (parser == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("wizard.parser_error", ctx.chatId(), bm.get()));
            return;
        }

        ParseResult<List<SportDto>> sportsResult = parser.fetchSports();
        String sportName = sportId;
        String sportAlias = sportId;
        if (sportsResult.success() && sportsResult.data() != null) {
            var found = sportsResult.data().stream()
                .filter(s -> s.id().equals(sportId))
                .findFirst();
            if (found.isPresent()) {
                sportName = found.get().name();
                sportAlias = found.get().alias() != null ? found.get().alias() : sportId;
            }
        }

        ParseResult<List<TournamentDto>> tournsResult = parser.fetchTournaments(sportId);
        if (!tournsResult.success() || tournsResult.data() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("wizard.parser_error", ctx.chatId(), bm.get()));
            return;
        }

        sessionService.setStateAndMergeContext(ctx.chatId(), BotState.SELECTING_TOURNAMENT,
            Map.of(UserBotSession.CTX_SPORT_ID, sportId,
                   UserBotSession.CTX_SPORT_NAME, sportName,
                   UserBotSession.CTX_SPORT_ALIAS, sportAlias));

        Set<String> existingUrls = buildExistingUrls(ctx.chatId(), bm.get());
        BookmakerType bookmakerType = BookmakerType.valueOf(bm.get().toUpperCase());
        String sportUrl = buildSportUrl(bookmakerType, sportId, sportAlias);

        String monitorAllText = messageSource.getMessage("wizard.monitor_all_sport", ctx.chatId(), sportName);
        String backText   = messageSource.getMessage("menu.back",   ctx.chatId());
        String cancelText = messageSource.getMessage("menu.cancel", ctx.chatId());
        InlineKeyboardMarkup keyboard = buildTournamentKeyboard(
            tournsResult.data(), 0, monitorAllText, backText, cancelText, existingUrls, sportUrl);
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.select_tournament", ctx.chatId(), sportName),
            keyboard);
    }

    /**
     * Builds the tournament keyboard. Tournaments are sorted alphabetically so the list
     * order is stable across page navigation even if the API response order varies between calls.
     */
    static InlineKeyboardMarkup buildTournamentKeyboard(
            List<TournamentDto> tournaments, int page,
            String monitorAllText, String backText, String cancelText,
            Set<String> existingUrls, String sportUrl) {

        List<TournamentDto> sorted = tournaments.stream()
                .sorted(Comparator.comparing(t -> t.title().toLowerCase()))
                .toList();

        boolean sportExists = existingUrls.contains(sportUrl);
        String monitorAllCallback = sportExists ? CallbackData.TOURN_EXIST : CallbackData.TOURN_ALL;
        String monitorAllLabel   = sportExists ? "✅ " + monitorAllText : monitorAllText;

        return PagedKeyboardBuilder.<TournamentDto>create()
            .items(sorted)
            .itemRenderer(t -> {
                boolean exists = existingUrls.contains(t.url());
                String label    = exists ? "✅ " + t.title() : t.title();
                String callback = exists ? CallbackData.TOURN_EXIST : CallbackData.tournSel(t.id());
                return KeyboardButton.callback(label, callback);
            })
            .pageSize(8)
            .currentPage(page)
            .navigationCallbackPrefix(CallbackData.TOURN_PAGE_PREFIX)
            .appendRow(KeyboardButton.callback(monitorAllLabel, monitorAllCallback))
            .appendRow(KeyboardButton.callback(backText,   CallbackData.TOURN_BACK))
            .appendRow(KeyboardButton.callback(cancelText, CallbackData.CANCEL))
            .build();
    }

    private Set<String> buildExistingUrls(Long chatId, String bookmakerCode) {
        return controllerService.getUserControllers(chatId).stream()
            .filter(c -> bookmakerCode.equalsIgnoreCase(c.bookmaker()))
            .map(ControllerDto::url)
            .collect(Collectors.toSet());
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
