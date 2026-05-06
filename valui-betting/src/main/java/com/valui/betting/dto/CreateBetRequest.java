package com.valui.betting.dto;

import com.valui.common.domain.BetType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record CreateBetRequest(
        @NotNull BetType type,
        @NotEmpty @Valid List<BetSlipRequest> slips,
        @NotNull @DecimalMin("0.01") BigDecimal totalStake,
        @NotEmpty @Valid List<ParticipantRequest> participants
) {}
