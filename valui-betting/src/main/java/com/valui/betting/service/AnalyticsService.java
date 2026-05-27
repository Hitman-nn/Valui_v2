package com.valui.betting.service;

import com.valui.betting.dto.analytics.AnalyticsResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AnalyticsService {

    /**
     * @param accountIds selected accounts (always required)
     * @param telegramId user's telegram ID; null means admin — no creator filter
     * @param personIds  optional person filter (admin only); null/empty = no filter
     */
    AnalyticsResponse getAnalytics(List<UUID> accountIds, Long telegramId, List<UUID> personIds,
                                   OffsetDateTime from, OffsetDateTime to);
}
