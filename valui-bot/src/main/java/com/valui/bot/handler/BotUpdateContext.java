package com.valui.bot.handler;

import com.valui.bot.state.UserBotSession;
import com.valui.user.dto.UserWithSubscriptionDto;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;

/**
 * Immutable context passed to every {@link BotUpdateHandler#handle} call.
 * Aggregates everything a handler might need: the raw update, resolved ids,
 * current FSM session, subscription info, and the AbsSender for sending replies.
 *
 * @param update    the raw Telegram update
 * @param chatId    resolved Telegram chat ID
 * @param username  Telegram username (may be null)
 * @param session   current bot session (FSM state + wizard context)
 * @param userInfo  subscription plan snapshot, null for unregistered users
 * @param sender    AbsSender for executing Telegram API methods
 */
public record BotUpdateContext(
    Update update,
    Long chatId,
    String username,
    UserBotSession session,
    UserWithSubscriptionDto userInfo,
    AbsSender sender
) {}
