package com.valui.user.dto;

import com.valui.common.domain.TokenReasonCode;

import java.time.LocalDate;
import java.util.List;

public record TokenHistoryEntry(
    TokenReasonCode reasonCode,
    LocalDate date,
    int totalDelta,
    int count,
    List<TokenTransactionDetail> transactions
) {}
