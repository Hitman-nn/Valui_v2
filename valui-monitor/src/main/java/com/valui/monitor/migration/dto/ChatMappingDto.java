package com.valui.monitor.migration.dto;

import java.util.UUID;

public record ChatMappingDto(
        String chatId,
        UUID   userId,
        Long   notificationChatId
) {}
