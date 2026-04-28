package com.valui.common.event;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class AuditEvent {

    private final UUID userId;
    private final Long telegramId;
    private final String action;
    private final String entityType;
    private final UUID entityId;
    @Builder.Default
    private final Map<String, Object> details = Map.of();
    private final String ipAddress;
    @Builder.Default
    private final Instant occurredAt = Instant.now();
}
