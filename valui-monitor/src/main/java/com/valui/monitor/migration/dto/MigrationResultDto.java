package com.valui.monitor.migration.dto;

import java.util.Map;

public record MigrationResultDto(
        int imported,
        int skipped,
        int failed,
        int dedupSeeded,
        Map<String, Integer> byBookmaker
) {}
