package com.valui.bot.handler.message;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.BotUpdateHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.ControllerEventSearchCallback;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.UUID;

/**
 * Handles text input when the user is in {@link BotState#SEARCHING_CONTROLLER_EVENTS} state.
 * Runs at order=45 (before WizardTextHandler=50) so state-specific handling takes priority.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventSearchTextHandler implements BotUpdateHandler {

    private final BotSessionService              sessionService;
    private final ControllerEventSearchCallback  searchCallback;

    @Override
    public int order() { return 45; }

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || update.getMessage().getText() == null) return false;
        if (update.getMessage().getText().startsWith("/")) return false;
        Long fromId = update.getMessage().getFrom() != null ? update.getMessage().getFrom().getId() : null;
        if (fromId == null) return false;
        return sessionService.getSession(fromId).getState() == BotState.SEARCHING_CONTROLLER_EVENTS;
    }

    @Override
    public void handle(BotUpdateContext ctx) {
        String query = ctx.update().getMessage().getText().trim();
        MessageSend.deleteMessage(ctx.sender(), ctx.chatId(), ctx.update().getMessage().getMessageId());

        String ctrlIdStr = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_EVT_SEARCH_CTRL_ID).orElse(null);
        String msgIdStr  = sessionService.getContext(ctx.fromId(), UserBotSession.CTX_EVT_MSG_ID).orElse(null);

        sessionService.setState(ctx.fromId(), BotState.IDLE);

        if (ctrlIdStr == null || query.isBlank()) return;

        UUID controllerId;
        try { controllerId = UUID.fromString(ctrlIdStr); }
        catch (Exception e) { return; }

        int messageId = 0;
        if (msgIdStr != null) {
            try { messageId = Integer.parseInt(msgIdStr); } catch (NumberFormatException ignored) {}
        }
        if (messageId == 0) messageId = ctx.tracker().getTrackedId(ctx.chatId());
        // messageId=0 is still forwarded — showSearchResults falls back to sendAndTrack

        // Store query for pagination
        sessionService.putContext(ctx.fromId(), UserBotSession.CTX_EVT_SEARCH_QUERY, query);

        // messageId=0 is forwarded to showSearchResults, which falls back to sendAndTrack
        searchCallback.showSearchResults(ctx, controllerId, query, 0, messageId);
    }
}
