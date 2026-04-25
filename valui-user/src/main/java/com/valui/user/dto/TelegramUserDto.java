package com.valui.user.dto;

public record TelegramUserDto(
    Long telegramId,
    String username,
    String firstName,
    String languageCode
) {}
