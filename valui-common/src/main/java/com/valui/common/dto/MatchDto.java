package com.valui.common.dto;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.MatchStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record MatchDto(
        @NotBlank String id,
        @NotNull BookmakerType bookmaker,
        @NotBlank String sport,
        @NotBlank String homeTeam,
        @NotBlank String awayTeam,
        @NotNull Instant startsAt,
        @NotNull MatchStatus status,
        BigDecimal oddHome,
        BigDecimal oddDraw,
        BigDecimal oddAway
) {}