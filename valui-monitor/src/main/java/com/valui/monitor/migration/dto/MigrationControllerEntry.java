package com.valui.monitor.migration.dto;

import java.util.List;

public record MigrationControllerEntry(
        String       chatId,
        String       link,
        String       title,
        String       ruleFilter,
        List<String> eventIds
) {}
