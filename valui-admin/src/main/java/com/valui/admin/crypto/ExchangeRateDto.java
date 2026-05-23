package com.valui.admin.crypto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ExchangeRateDto(
    String currency,
    BigDecimal tokensPerUnit,
    OffsetDateTime updatedAt
) {}
