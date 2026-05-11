package com.valui.user.dto;

public record LimitInfoDto(
    int controllersUsed,
    int tokenBalance,
    int monthlyTokenGrant
) {}
