package com.valui.bot.handler;

import com.valui.bot.handler.message.MenuButtonHandler;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.service.WizardMessageTracker;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.common.entity.UserEntity;
import com.valui.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.util.Comparator;
import java.util.List;

/**
 * Central dispatcher: extracts context, selects the matching {@link BotUpdateHandler}
 * with the lowest {@link BotUpdateHandler#order()}, and delegates.
 */
@Slf4j
@Component
public class CommandRouter {

    private final List<BotUpdateHandler> handlers;
    private final BotSessionService sessionService;
    private final UserService userService;
    private final WizardMessageTracker tracker;

    public CommandRouter(List<BotUpdateHandler> handlers,
                         BotSessionService sessionService,
                         UserService userService,
                         WizardMessageTracker tracker) {
        this.handlers = handlers.stream()
            .sorted(Comparator.comparingInt(BotUpdateHandler::order))
            .toList();
        this.sessionService = sessionService;
        this.userService = userService;
        this.tracker = tracker;
        long callbacks = this.handlers.stream()
                .filter(h -> h.getClass().getSimpleName().endsWith("Callback"))
                .count();
        long commands = this.handlers.stream()
                .filter(h -> !h.getClass().getSimpleName().endsWith("Callback")
                          && !h.getClass().getSimpleName().equals("UnknownUpdateHandler"))
                .count();
        log.info("Маршрутизатор запущен: {} обработчиков (команды: {}, коллбэки: {}, fallback: 1)",
                handlers.size(), commands, callbacks);
    }

    public void route(Update update, AbsSender sender) {
        Long chatId = extractChatId(update);
        Long fromId = extractFromId(update);

        if (chatId == null || fromId == null) {
            log.warn("Не удалось извлечь chatId/fromId из апдейта — пропускаем");
            return;
        }

        String username = extractUsername(update);
        UserBotSession session = sessionService.getSession(fromId);

        // In group chats: ignore plain text when user has no active wizard state.
        // Menu button presses and commands are always processed; random text is not.
        if (chatId < 0
                && update.hasMessage()
                && update.getMessage().hasText()
                && !update.getMessage().getText().startsWith("/")
                && !MenuButtonHandler.isMenuButtonText(update.getMessage().getText())
                && session.getState() == BotState.IDLE) {
            log.debug("Group text ignored (state=IDLE): chatId={} fromId={}", chatId, fromId);
            return;
        }

        UserEntity user = loadUser(fromId);
        String updateType = resolveUpdateType(update);

        BotUpdateHandler handler = handlers.stream()
            .filter(h -> h.canHandle(update))
            .findFirst()
            .orElse(null);

        if (handler == null) {
            log.warn("Нет обработчика: chatId={} тип={}", chatId, updateType);
            return;
        }

        BotUpdateContext context = new BotUpdateContext(update, chatId, fromId, username, session, user, sender, tracker);

        long started = System.currentTimeMillis();
        String handlerName = handler.getClass().getSimpleName();
        log.debug("📩 chatId={} тип={} → {}", chatId, updateType, handlerName);

        try {
            handler.handle(context);
            log.debug("✅ chatId={} обработан {} за {}мс",
                chatId, handlerName, System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("❌ Ошибка в обработчике {} для chatId={}", handlerName, chatId, e);
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Long extractChatId(Update update) {
        if (update.hasMessage())           return update.getMessage().getChatId();
        if (update.hasCallbackQuery())     return update.getCallbackQuery().getMessage().getChatId();
        if (update.hasEditedMessage())     return update.getEditedMessage().getChatId();
        if (update.hasChannelPost())       return update.getChannelPost().getChatId();
        if (update.hasMyChatMember())      return update.getMyChatMember().getChat().getId();
        return null;
    }

    private Long extractFromId(Update update) {
        if (update.hasMessage() && update.getMessage().getFrom() != null)
            return update.getMessage().getFrom().getId();
        if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null)
            return update.getCallbackQuery().getFrom().getId();
        if (update.hasEditedMessage() && update.getEditedMessage().getFrom() != null)
            return update.getEditedMessage().getFrom().getId();
        if (update.hasMyChatMember() && update.getMyChatMember().getFrom() != null)
            return update.getMyChatMember().getFrom().getId();
        return null;
    }

    private String extractUsername(Update update) {
        if (update.hasMessage()) {
            Message m = update.getMessage();
            return m.getFrom() != null ? m.getFrom().getUserName() : null;
        }
        if (update.hasCallbackQuery()) {
            CallbackQuery cb = update.getCallbackQuery();
            return cb.getFrom() != null ? cb.getFrom().getUserName() : null;
        }
        return null;
    }

    private UserEntity loadUser(Long fromId) {
        try {
            return userService.findByTelegramId(fromId).orElse(null);
        } catch (Exception e) {
            log.debug("User unavailable for fromId={}: {}", fromId, e.getMessage());
            return null;
        }
    }

    private String resolveUpdateType(Update update) {
        if (update.hasMessage())       return "message";
        if (update.hasCallbackQuery()) return "callback";
        if (update.hasEditedMessage()) return "edited_message";
        if (update.hasChannelPost())   return "channel_post";
        return "unknown";
    }
}
