package com.valui.user.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record LimitInfoDto(
    int controllersUsed,
    int controllersMax,
    int filtersUsed,
    int filtersMax,
    List<String> allowedBookmakers,
    int pollIntervalSec,
    String planName,
    OffsetDateTime expiresAt  // null = no expiry (FREE plan)
) {}
