package com.valui.admin.costs;

public record TokenActionCostDto(
    String actionCode,
    int    costTokens,
    String description
) {}
