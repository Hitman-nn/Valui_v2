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

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookmakerSelectCallback implements CallbackHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;
    private final ParserFactory parserFactory;

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
                messageSource.getMessage("wizard.parser_error", ctx.chatId(), bookmakerCode));
            return;
        }

        // Show "loading" state while the HTTP call is in progress
        MessageSend.editTextWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.loading", ctx.chatId(), bookmakerCode),
            buildLoadingKeyboard());

        ParseResult<List<SportDto>> result = parser.fetchSports();
        if (!result.success() || result.data() == null || result.data().isEmpty()) {
            MessageSend.editTextWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                messageSource.getMessage("wizard.parser_error", ctx.chatId(), bookmakerCode),
                buildLoadingKeyboard());
            return;
        }

        sessionService.setStateAndMergeContext(ctx.chatId(), BotState.SELECTING_SPORT,
            Map.of(UserBotSession.CTX_BOOKMAKER, bookmakerCode));

        InlineKeyboardMarkup keyboard = buildSportsKeyboard(result.data(), 0,
            messageSource.getMessage("menu.back", ctx.chatId()));
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            messageSource.getMessage("wizard.select_sport", ctx.chatId(), bookmakerCode),
            keyboard);
    }

    private static InlineKeyboardMarkup buildLoadingKeyboard() {
        return PagedKeyboardBuilder.<String>create()
            .items(List.of())
            .itemRenderer(s -> KeyboardButton.callback(s, CallbackData.NOOP))
            .build();
    }

    static InlineKeyboardMarkup buildSportsKeyboard(
            List<SportDto> sports, int page, String backText) {
        return PagedKeyboardBuilder.<SportDto>create()
            .items(sports)
            .itemRenderer(s -> KeyboardButton.callback(s.name(), CallbackData.sportSel(s.id())))
            .pageSize(8)
            .currentPage(page)
            .navigationCallbackPrefix(CallbackData.SPORT_PAGE_PREFIX)
            .appendRow(KeyboardButton.callback(backText, CallbackData.SPORT_BACK))
            .build();
    }
}
