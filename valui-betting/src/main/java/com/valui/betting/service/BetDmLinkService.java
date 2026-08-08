package com.valui.betting.service;

import com.valui.betting.dto.BetDmLinkDto;

import java.util.List;

/**
 * Manages the opt-in link between a Telegram user's personal chat and a group's betting
 * journal — lets them manage that group's bets from DM. See {@code bet_dm_links} table.
 */
public interface BetDmLinkService {

    /** Creates or refreshes the link for (chatId, telegramId), updating the stored chat title. */
    void link(long chatId, long telegramId, String chatTitle);

    /** Removes the link, if present. No-op if not linked. */
    void unlink(long chatId, long telegramId);

    boolean isLinked(long chatId, long telegramId);

    /** All chats this Telegram user has linked, for the DM chat-picker. */
    List<BetDmLinkDto> listLinks(long telegramId);
}
