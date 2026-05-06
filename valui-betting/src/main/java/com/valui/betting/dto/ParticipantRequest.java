package com.valui.betting.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ParticipantRequest(
        long telegramId,
        String displayName,
        BigDecimal stake,
        BigDecimal profitShare,
        UUID bankAccountId         // nullable
) {}
