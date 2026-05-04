package com.valui.admin.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Redis memory info")
public record RedisInfoDto(
        @Schema(description = "Использованная память (bytes)") long usedMemoryBytes,
        @Schema(description = "Использованная память (human-readable)", example = "12.5M") String usedMemoryHuman,
        @Schema(description = "Пиковая память (bytes)") long usedMemoryPeakBytes,
        @Schema(description = "Всего ключей в БД 0") long totalKeys
) {}
