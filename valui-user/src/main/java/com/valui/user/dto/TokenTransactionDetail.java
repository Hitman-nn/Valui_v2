package com.valui.user.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TokenTransactionDetail(
    UUID id,
    int delta,
    int balanceAfter,
    UUID refId,
    OffsetDateTime createdAt
) {}
