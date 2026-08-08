package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.betting.BettingChatResolver;
import com.valui.bot.keyboard.BotMarkdownUtil;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import com.valui.user.api.DetectedEventPortService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.Map;
import java.util.UUID;

/**
 * Handles the event-search button and search-result pagination in controller detail.
 *
 * Prefix: "CTRL:EVT:SRCH:"
 *   CTRL:EVT:SRCH:{controllerId}         — first tap: set SEARCHING_CONTROLLER_EVENTS, ask text
 *   CTRL:EVT:SRCH:{controllerId}:P:{page} — navigate existing search results (query from session)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControllerEventSearchCallback implements CallbackHandler {

    private static final String PREFIX         = CallbackData.CTRL_EVT_SRCH_PREFIX;
    private static final int    EVENTS_PER_PAGE = 10;

    private final BotSessionService        sessionService;
    private final ControllerService        controllerService;
    private final DetectedEventPortService detectedEventPort;
    private final BettingChatResolver      chatResolver;

    @Override public String callbackPrefix() { return PREFIX; }
    @Override public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        String payload = data.substring(PREFIX.length()); // "{uuid}" or "{uuid}:P:{page}"
        UUID controllerId;
        int page = 0;
        boolean isNavigation = payload.contains(":P:");

        if (isNavigation) {
            try {
                controllerId = UUID.fromString(payload.substring(0, payload.indexOf(":P:")));
                page         = Integer.parseInt(payload.substring(payload.lastIndexOf(':') + 1));
            } catch (Exception e) {
                MessageSend.answerCallback(ctx.sender(), callbackId);
                return;
            }
        } else {
            try { controllerId = UUID.fromString(payload); }
            catch (Exception e) {
                MessageSend.answerCallback(ctx.sender(), callbackId);
                return;
            }
        }

        if (isNavigation) {
            // Navigate existing search results; query must be in session
            String query = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_EVT_SEARCH_QUERY)
                    .orElse(null);
            if (query == null || query.isBlank()) {
                // Session expired — redirect to normal detail view
                MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                        "⏱ Сессия поиска истекла. Откройте страницу заново.");
                ctx.tracker().replaceAndTrack(ctx.sender(), ctx.chatId(), messageId,
                        "🔄 Перезагрузите страницу контроллера.",
                        InlineKeyboardBuilder.create()
                                .button("← К контроллеру", CallbackData.ctrlDetail(controllerId))
                                .build());
                return;
            }
            MessageSend.answerCallback(ctx.sender(), callbackId);
            showSearchResults(ctx, controllerId, query, page, messageId);
        } else {
            // First tap: set state, store context, ask for query text
            MessageSend.answerCallback(ctx.sender(), callbackId);
            sessionService.setStateWithContext(ctx.fromId(), BotState.SEARCHING_CONTROLLER_EVENTS, Map.of(
                    UserBotSession.CTX_EVT_SEARCH_CTRL_ID, controllerId.toString(),
                    UserBotSession.CTX_EVT_MSG_ID,         String.valueOf(messageId)
            ));
            String prompt = "🔍 Введите название события для поиска:";
            var kb = InlineKeyboardBuilder.create()
                    .button("✕ Отмена", CallbackData.ctrlDetail(controllerId))
                    .build();
            ctx.tracker().replaceAndTrack(ctx.sender(), ctx.chatId(), messageId, prompt, kb);
        }
    }

    public void showSearchResults(BotUpdateContext ctx, UUID controllerId, String query, int page, int messageId) {
        Page<DetectedEventEntity> results = detectedEventPort.searchByControllerIdAndTitle(
                controllerId, query, PageRequest.of(page, EVENTS_PER_PAGE));

        ControllerDto c;
        try { c = controllerService.getControllerForChat(controllerId, chatResolver.resolveOrPhysical(ctx)); }
        catch (Exception e) { return; }

        String text = results.isEmpty()
                ? "🔍 По запросу «" + query + "» ничего не найдено."
                : "🔍 *" + BotMarkdownUtil.escapeTitle(query) + "* — " + results.getTotalElements() + " событий:";

        var builder = InlineKeyboardBuilder.create();
        for (DetectedEventEntity event : results.getContent()) {
            builder.button("🎯 " + truncate(event.getTitle(), 55),
                    CallbackData.ctrlEvtBet(event.getId())).row();
        }

        if (results.getTotalPages() > 1) {
            if (page > 0) builder.button("‹", CallbackData.ctrlEvtSrchPage(controllerId, page - 1));
            builder.button((page + 1) + "/" + results.getTotalPages(), CallbackData.NOOP);
            if (page < results.getTotalPages() - 1)
                builder.button("›", CallbackData.ctrlEvtSrchPage(controllerId, page + 1));
            builder.row();
        }

        builder.button("🔍 Новый поиск", CallbackData.ctrlEvtSrch(controllerId));
        builder.button("← Назад",        CallbackData.ctrlDetail(c.id()));
        builder.row();

        if (messageId > 0) {
            ctx.tracker().replaceAndTrack(ctx.sender(), ctx.chatId(), messageId, text, builder.build());
        } else {
            ctx.tracker().sendAndTrack(ctx.sender(), ctx.chatId(), text, builder.build());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

}
