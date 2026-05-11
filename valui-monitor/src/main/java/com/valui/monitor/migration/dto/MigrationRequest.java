package com.valui.monitor.migration.dto;

import java.util.List;

public record MigrationRequest(
        List<MigrationControllerEntry> controllers,
        List<ChatMappingDto>           chatMappings,
        int                            pollIntervalSec
) {}
