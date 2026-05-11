package com.valui.monitor.migration.dto;

public record LegacyControllerPreviewDto(
        String link,
        String originalTitle,
        String cleanTitle,
        String bookmaker,
        String controllerType,
        int    eventCount,
        String ruleFilter
) {}
