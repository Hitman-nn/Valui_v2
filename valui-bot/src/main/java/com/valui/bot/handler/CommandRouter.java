package com.valui.bot.handler;

import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.UserBotSession;
import com.valui.user.dto.UserWithSubscriptionDto;
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
 *
 * <p>Spring injects all {@link BotUpdateHandler} implementations automatically.
 * {@link com.valui.bot.handler.command.UnknownUpdateHandler} (order=999, canHandle=true)
 * acts as the always-present fallback.
 */
@Slf4j
@Component
public class CommandRouter {

    private final List<BotUpdateHandler> handlers;
    private final BotSessionService sessionService;
    private final UserService userService;

    public CommandRouter(List<BotUpdateHandler> handlers,
                         BotSessionService sessionService,
                         UserService userService) {
        this.handlers = handlers.stream()
            .sorted(Comparator.comparingInt(BotUpdateHandler::order))
            .toList();
        this.sessionService = sessionService;
        this.userService = userService;
        log.info("Маршрутизатор запущен: {} обработчиков зарегистрировано  →  {}",
            handlers.size(),
            this.handlers.stream().map(h -> h.getClass().getSimpleName()).toList());
    }

    /**
     * Routes the update to the first matching handler (lowest order).
     *
     * @param update raw Telegram update
     * @param sender bot's AbsSender for sending replies
     */
    public void route(Update update, AbsSender sender) {
        Long chatId = extractChatId(update);
        if (chatId == null) {
            log.warn("Не удалось извлечь chatId из апдейта — пропускаем");
            return;
        }

        String username = extractUsername(update);
        UserBotSession session = sessionService.getSession(chatId);
        UserWithSubscriptionDto userInfo = loadUserInfo(chatId);
        String updateType = resolveUpdateType(update);

        BotUpdateHandler handler = handlers.stream()
            .filter(h -> h.canHandle(update))
            .findFirst()   // already sorted by order in constructor
            .orElse(null);

        if (handler == null) {
            log.warn("Нет обработчика: chatId={} тип={}", chatId, updateType);
            return;
        }

        BotUpdateContext context = new BotUpdateContext(update, chatId, username, session, userInfo, sender);

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
        if (update.hasCallbackQuery())     return update.getCallbackQuery().getFrom().getId();
        if (update.hasEditedMessage())     return update.getEditedMessage().getChatId();
        if (update.hasChannelPost())       return update.getChannelPost().getChatId();
        if (update.hasMyChatMember())      return update.getMyChatMember().getChat().getId();
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

    private UserWithSubscriptionDto loadUserInfo(Long chatId) {
        try {
            return userService.findByTelegramId(chatId)
                .map(u -> userService.getUserWithSubscription(chatId))
                .orElse(null);
        } catch (Exception e) {
            log.debug("User info unavailable for chatId={}: {}", chatId, e.getMessage());
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
