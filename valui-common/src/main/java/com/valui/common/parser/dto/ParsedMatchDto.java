package com.valui.common.parser.dto;

import java.time.Instant;

public record ParsedMatchDto(
        String id,
        String title,
        String tournamentId,
        String url,
        Instant startsAt,
        boolean isLive
) {}
