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
import com.valui.common.domain.BookmakerType;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TournamentSelectCallback implements CallbackHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final ParserFactory parserFactory;
    private final ControllerService controllerService;
    private final PlanLimitChecker planLimitChecker;
    private final WizardBackNavigator backNavigator;

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

        // "← Назад" from tournament list → back to sport selection
        if (data.equals(CallbackData.TOURN_BACK)) {
            MessageSend.answerCallback(ctx.sender(), callbackId);
            backNavigator.returnToSportList(ctx.sender(), ctx.chatId(), messageId);
            return;
        }

        MessageSend.answerCallback(ctx.sender(), callbackId);

        if (data.startsWith("TOURN:PAGE:")) {
            handleTournamentPage(ctx, data, messageId);
        } else if (data.equals(CallbackData.TOURN_ALL)) {
            handleMonitorAll(ctx, messageId);
        } else if (data.startsWith(CallbackData.TOURN_SEL_PREFIX)) {
            handleTournamentSelect(ctx, data, messageId);
        }
    }

    private void handleTournamentPage(BotUpdateContext ctx, String data, int messageId) {
        int page = parsePageNum(data.substring("TOURN:PAGE:".length()));
        Optional<String> bm       = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_BOOKMAKER);
        Optional<String> sportId  = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_SPORT_ID);
        Optional<String> sportName  = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_SPORT_NAME);
        Optional<String> sportAlias = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_SPORT_ALIAS);

        if (bm.isEmpty() || sportId.isEmpty()) return;

        BookmakerParser parser = getParser(bm.get());
        if (parser == null) return;

        ParseResult<List<TournamentDto>> result = parser.fetchTournaments(sportId.get());
        if (!result.success() || result.data() == null) return;

        String sName  = sportName.orElse(sportId.get());
        String sAlias = sportAlias.orElse(sportId.get());
        Set<String> existingUrls = buildExistingUrls(ctx.chatId(), bm.get());
        BookmakerType bookmakerType = BookmakerType.valueOf(bm.get().toUpperCase());
        String sportUrl = buildSportUrl(bookmakerType, sportId.get(), sAlias);

        String monitorAllText = messageSource.getMessage("wizard.monitor_all_sport", ctx.chatId(), sName);
        String backText   = messageSource.getMessage("menu.back",   ctx.chatId());
        String cancelText = messageSource.getMessage("menu.cancel", ctx.chatId());
        InlineKeyboardMarkup keyboard = SportSelectCallback.buildTournamentKeyboard(
            result.data(), page, monitorAllText, backText, cancelText, existingUrls, sportUrl);
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.select_tournament", ctx.chatId(), sName), keyboard);
    }

    private void handleMonitorAll(BotUpdateContext ctx, int messageId) {
        Optional<String> bm         = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_BOOKMAKER);
        Optional<String> sportId    = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_SPORT_ID);
        Optional<String> sportName  = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_SPORT_NAME);
        Optional<String> sportAlias = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_SPORT_ALIAS);

        if (bm.isEmpty() || sportId.isEmpty()) return;

        BookmakerType bookmakerType = BookmakerType.valueOf(bm.get().toUpperCase());
        String sportUrl = buildSportUrl(bookmakerType, sportId.get(), sportAlias.orElse(sportId.get()));
        String sName = sportName.orElse(sportId.get());

        sessionService.setStateAndMergeContext(ctx.chatId(), BotState.WAITING_FILTER_RULE, Map.of(
            UserBotSession.CTX_CONTROLLER_TYPE, "SPORT",
            UserBotSession.CTX_TOURNAMENT_URL,   sportUrl,
            UserBotSession.CTX_TOURNAMENT_TITLE, sName,
            UserBotSession.CTX_FILTER_MODE,      "INDIVIDUAL",
            UserBotSession.CTX_WIZARD_MSG_ID,    String.valueOf(messageId)
        ));

        showFilterPrompt(ctx, messageId);
    }

    private void handleTournamentSelect(BotUpdateContext ctx, String data, int messageId) {
        String tournamentId = data.substring(CallbackData.TOURN_SEL_PREFIX.length());
        Optional<String> bm      = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_BOOKMAKER);
        Optional<String> sportId = sessionService.getContext(ctx.chatId(), UserBotSession.CTX_SPORT_ID);

        if (bm.isEmpty() || sportId.isEmpty()) return;

        BookmakerParser parser = getParser(bm.get());
        if (parser == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("wizard.parser_error", ctx.chatId(), bm.get()));
            return;
        }

        ParseResult<List<TournamentDto>> result = parser.fetchTournaments(sportId.get());
        if (!result.success() || result.data() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("wizard.parser_error", ctx.chatId(), bm.get()));
            return;
        }

        TournamentDto tournament = result.data().stream()
            .filter(t -> t.id().equals(tournamentId))
            .findFirst()
            .orElse(null);

        if (tournament == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("wizard.parser_error", ctx.chatId(), bm.get()));
            return;
        }

        sessionService.setStateAndMergeContext(ctx.chatId(), BotState.WAITING_CONFIRM_CREATE, Map.of(
            UserBotSession.CTX_CONTROLLER_TYPE,  "TOURNAMENT",
            UserBotSession.CTX_TOURNAMENT_ID,    tournament.id(),
            UserBotSession.CTX_TOURNAMENT_TITLE, tournament.title(),
            UserBotSession.CTX_TOURNAMENT_URL,   tournament.url()
        ));

        showConfirmPrompt(ctx, messageId);
    }

    private void showFilterPrompt(BotUpdateContext ctx, int messageId) {
        String skipText = messageSource.getMessage("wizard.filter_skip", ctx.chatId());
        String backText = messageSource.getMessage("menu.back",          ctx.chatId());
        InlineKeyboardMarkup keyboard = InlineKeyboardBuilder.create()
            .button(skipText, CallbackData.FILTER_SKIP)
            .row()
            .button(backText, CallbackData.CANCEL)  // CancelCallback: INDIVIDUAL → список турниров
            .build();
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.enter_filter", ctx.chatId()),
            keyboard);
    }

    private void showConfirmPrompt(BotUpdateContext ctx, int messageId) {
        String text = ControllerConfirmCallback.buildConfirmText(ctx.chatId(), sessionService, messageSource);
        InlineKeyboardMarkup keyboard = ControllerConfirmCallback.buildConfirmKeyboard(ctx.chatId(), messageSource);
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, keyboard);
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

    Set<String> buildExistingUrls(Long chatId, String bookmakerCode) {
        return controllerService.getUserControllers(chatId).stream()
            .filter(c -> bookmakerCode.equalsIgnoreCase(c.bookmaker()))
            .map(ControllerDto::url)
            .collect(Collectors.toSet());
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
