package com.valui.user.dto;

import java.util.List;

public record TokenInfoDto(
    int controllersUsed,
    int tokenBalance,
    int monthlyTokenGrant,
    Integer tokenLowThreshold,
    List<TokenHistoryEntry> recentHistory,
    int spentThisMonth,
    int avgPerMonth,
    int spentAllTime
) {}
