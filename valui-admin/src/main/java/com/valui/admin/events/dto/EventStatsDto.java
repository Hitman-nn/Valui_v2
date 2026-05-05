package com.valui.admin.events.dto;

import java.util.List;

public record EventStatsDto(
        long totalEvents,
        long expiredEvents,
        List<BookmakerCount> byBookmaker
) {
    public record BookmakerCount(String bookmaker, long count) {}
}
