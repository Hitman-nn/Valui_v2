package com.valui.bot.handler;

import com.valui.bot.service.WizardMessageTracker;
import com.valui.bot.state.UserBotSession;
import com.valui.common.entity.UserEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;

/**
 * Immutable context passed to every {@link BotUpdateHandler#handle} call.
 *
 * @param update   the raw Telegram update
 * @param chatId   destination chat — where replies and notifications are sent.
 *                 Equals the group ID (negative) when the command came from a group,
 *                 or the user's personal Telegram ID in private chat.
 * @param fromId   the user's personal Telegram ID — always the individual, never a group.
 *                 Use this for: session lookup, user identity, quota checks, controller ownership.
 * @param username Telegram username (may be null)
 * @param session  current bot session (FSM state + wizard context), keyed by fromId
 * @param user     registered user entity, null for unregistered users
 * @param sender   AbsSender for executing Telegram API methods
 */
public record BotUpdateContext(
    Update update,
    Long chatId,
    Long fromId,
    String username,
    UserBotSession session,
    UserEntity user,
    AbsSender sender,
    WizardMessageTracker tracker
) {
    /** True when the originating chat is a Telegram group or supergroup. */
    public boolean isGroupChat() {
        return chatId != null && chatId < 0;
    }
}
