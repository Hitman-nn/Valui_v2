package com.valui.betting.dto;

import com.valui.common.entity.BetAccountEntity;

import java.util.UUID;

public record BetAccountDto(UUID id, Long chatId, String name) {
    public static BetAccountDto from(BetAccountEntity e) {
        return new BetAccountDto(e.getId(), e.getChatId(), e.getName());
    }
}
