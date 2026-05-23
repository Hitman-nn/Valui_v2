package com.valui.user.dto;

import com.valui.common.domain.TokenReasonCode;

import java.time.LocalDate;
import java.util.List;

public record TokenHistoryEntry(
    TokenReasonCode reasonCode,
    LocalDate date,
    int totalDelta,
    /** Денормализованное поле: всегда равно {@code transactions.size()}. Удобно для JSON-клиентов без разбора массива. */
    int count,
    List<TokenTransactionDetail> transactions
) {}
