package com.valui.admin.events.dto;

import java.util.UUID;

public record ResendResultDto(
        UUID   eventId,
        UUID   controllerId,
        String eventExternalId,
        int    notifyDedupCleared
) {}
