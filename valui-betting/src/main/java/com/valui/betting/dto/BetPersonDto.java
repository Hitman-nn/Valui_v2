package com.valui.betting.dto;

import com.valui.common.entity.BetPersonEntity;

import java.util.UUID;

public record BetPersonDto(UUID id, Long chatId, String displayName) {
    public static BetPersonDto from(BetPersonEntity e) {
        return new BetPersonDto(e.getId(), e.getChatId(), e.getDisplayName());
    }
}
