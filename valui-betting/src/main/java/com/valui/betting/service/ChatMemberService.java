package com.valui.betting.service;

import com.valui.common.entity.ChatMemberEntity;

import java.util.List;

public interface ChatMemberService {

    /** Records or updates a user seen in the given chat. Fire-and-forget — exceptions are swallowed. */
    void track(long chatId, long telegramId, String firstName, String username);

    List<ChatMemberEntity> getMembers(long chatId);
}
