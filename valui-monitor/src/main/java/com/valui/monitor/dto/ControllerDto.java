package com.valui.monitor.dto;

import java.time.Instant;
import java.util.UUID;

public record ControllerDto(
        UUID id,
        String bookmaker,
        String url,
        String title,
        String filterRule,
        boolean isMuted,
        boolean isActive,
        Instant lastCheckedAt,
        Instant lastEventAt,
        int detectedEventsCount
) {}
