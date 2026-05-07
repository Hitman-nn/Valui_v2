package com.valui.betting.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ParticipantRequest(
        UUID personId,
        String displayName,
        BigDecimal stake,       // null → equal distribution applied by normalizeParticipants
        BigDecimal profitShare  // null → equal distribution applied by normalizeParticipants
) {}
