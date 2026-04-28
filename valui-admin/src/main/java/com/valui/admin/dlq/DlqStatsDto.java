package com.valui.admin.dlq;

public record DlqStatsDto(
        long dlqFinalCount,
        long replayed,
        String status
) {}
