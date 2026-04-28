package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.KeyboardButton;
import com.valui.bot.keyboard.PagedKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.SportDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.factory.ParserFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookmakerSelectCallback implements CallbackHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final ParserFactory parserFactory;

    // Sports pinned to the top of page 0 in this priority order.
    // Matched via case-insensitive contains so "Хоккей с шайбой" still ranks as hockey.
    private static final List<String> PRIORITY_KEYWORDS =
            List.of("футбол", "теннис", "хоккей", "баскетбол");

    @Override
    public String callbackPrefix() { return CallbackData.BK_SELECT_PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data = ctx.update().getCallbackQuery().getData();
        String bookmakerCode = data.substring(CallbackData.BK_SELECT_PREFIX.length());
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        String callbackId = ctx.update().getCallbackQuery().getId();

        // Answer immediately so Telegram removes the loading spinner — parser fetch can take seconds
        MessageSend.answerCallback(ctx.sender(), callbackId);

        BookmakerParser parser;
        try {
            parser = parserFactory.getParser(BookmakerType.valueOf(bookmakerCode.toUpperCase()));
        } catch (Exception e) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("wizard.parser_error", ctx.fromId(), bookmakerCode));
            return;
        }

        // Show "loading" state while the HTTP call is in progress
        MessageSend.editTextWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.loading", ctx.fromId(), bookmakerCode),
            buildLoadingKeyboard());

        ParseResult<List<SportDto>> result = parser.fetchSports();
        if (!result.success() || result.data() == null || result.data().isEmpty()) {
            MessageSend.editTextWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                messageSource.getMessage("wizard.parser_error", ctx.fromId(), bookmakerCode),
                buildLoadingKeyboard());
            return;
        }

        sessionService.setStateAndMergeContext(ctx.fromId(), BotState.SELECTING_SPORT,
            Map.of(UserBotSession.CTX_BOOKMAKER, bookmakerCode));

        InlineKeyboardMarkup keyboard = buildSportsKeyboard(result.data(), 0,
            messageSource.getMessage("menu.back", ctx.fromId()));
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.select_sport", ctx.fromId(), bookmakerCode),
            keyboard);
    }

    private static InlineKeyboardMarkup buildLoadingKeyboard() {
        return PagedKeyboardBuilder.<String>create()
            .items(List.of())
            .itemRenderer(s -> KeyboardButton.callback(s, CallbackData.NOOP))
            .build();
    }

    /**
     * Builds the sports keyboard with stable alphabetical ordering and priority sports
     * (Футбол, Теннис, Хоккей, Баскетбол) always pinned to the top of the list so
     * they appear on page 0 regardless of API response order.
     *
     * Stable sort prevents duplicates/missing items on page navigation because the list
     * order is deterministic even if the API returns items in a different sequence per call.
     */
    static InlineKeyboardMarkup buildSportsKeyboard(
            List<SportDto> sports, int page, String backText) {
        return PagedKeyboardBuilder.<SportDto>create()
            .items(sortedSports(sports))
            .itemRenderer(s -> KeyboardButton.callback(s.name(), CallbackData.sportSel(s.id())))
            .pageSize(8)
            .currentPage(page)
            .navigationCallbackPrefix(CallbackData.SPORT_PAGE_PREFIX)
            .appendRow(KeyboardButton.callback(backText, CallbackData.SPORT_BACK))
            .build();
    }

    /**
     * Returns sports sorted so that priority sports (Футбол, Теннис, Хоккей, Баскетбол)
     * appear first in priority order, followed by all remaining sports alphabetically.
     */
    static List<SportDto> sortedSports(List<SportDto> sports) {
        return sports.stream()
                .sorted(Comparator.comparingInt(BookmakerSelectCallback::priorityOf)
                        .thenComparing(s -> s.name().toLowerCase()))
                .toList();
    }

    private static int priorityOf(SportDto s) {
        String lower = s.name().toLowerCase();
        for (int i = 0; i < PRIORITY_KEYWORDS.size(); i++) {
            if (lower.contains(PRIORITY_KEYWORDS.get(i))) return i;
        }
        return PRIORITY_KEYWORDS.size();
    }
}
