package com.valui.monitor.migration.dto;

import java.util.List;

public record ChatGroupDto(
        String chatId,
        int controllerCount,
        List<LegacyControllerPreviewDto> controllers
) {}
