package com.valui.betting.dto;

import com.valui.common.entity.BetDmLinkEntity;

public record BetDmLinkDto(Long chatId, String chatTitle) {
    public static BetDmLinkDto from(BetDmLinkEntity e) {
        return new BetDmLinkDto(e.getChatId(), e.getChatTitle());
    }

    /** Display label for the chat picker — falls back to the raw chat ID if the title is unknown. */
    public String displayName() {
        return chatTitle != null && !chatTitle.isBlank() ? chatTitle : ("Чат " + chatId);
    }
}
