package com.valui.monitor.migration.dto;

import java.util.List;

public record MigrationConfigDto(
        List<ChatMappingDto> chatMappings,
        List<String>         selectedLinks,
        int                  pollIntervalSec
) {}
