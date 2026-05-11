package com.valui.monitor.migration.dto;

import java.util.List;

public record ParsedMigrationDto(
        List<ChatGroupDto> groups,
        int totalControllers,
        int unknownBookmakerCount
) {}
