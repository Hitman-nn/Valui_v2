package com.valui.betting.service;

import com.valui.betting.dto.analytics.AnalyticsResponse;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface AnalyticsService {

    enum Scope { ACCOUNT, PERSON }

    AnalyticsResponse getAnalytics(Scope scope, UUID id, Long chatId,
                                   OffsetDateTime from, OffsetDateTime to);
}
