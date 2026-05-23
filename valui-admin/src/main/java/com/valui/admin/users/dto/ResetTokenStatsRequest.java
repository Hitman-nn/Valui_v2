package com.valui.admin.users.dto;

import java.time.OffsetDateTime;

/** Тело запроса для POST /{id}/token-stats/reset. Если {@code resetAt} не задан — используется текущий момент. */
public record ResetTokenStatsRequest(OffsetDateTime resetAt) {}
