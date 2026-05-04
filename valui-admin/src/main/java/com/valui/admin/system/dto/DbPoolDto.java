package com.valui.admin.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "HikariCP connection pool stats")
public record DbPoolDto(
        @Schema(description = "Активных соединений") int activeConnections,
        @Schema(description = "Ожидающих соединений") int pendingConnections,
        @Schema(description = "Свободных соединений") int idleConnections,
        @Schema(description = "Всего соединений в пуле") int totalConnections,
        @Schema(description = "Максимальный размер пула") int maxPoolSize
) {}
