package com.valui.user.dto;

import com.valui.common.domain.TokenReasonCode;

import java.time.LocalDate;

public record TokenHistoryEntry(
    TokenReasonCode reasonCode,
    LocalDate date,
    int totalDelta,
    int count
) {}
