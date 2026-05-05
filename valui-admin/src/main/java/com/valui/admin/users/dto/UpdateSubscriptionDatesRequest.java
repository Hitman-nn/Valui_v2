package com.valui.admin.users.dto;

import java.time.OffsetDateTime;

public record UpdateSubscriptionDatesRequest(
        OffsetDateTime startedAt,
        OffsetDateTime expiresAt
) {}
