package com.valui.user.service;

import com.valui.common.entity.GlobalFilterEntity;

import java.util.List;
import java.util.UUID;

public interface GlobalFilterService {

    List<GlobalFilterEntity> getFilters(Long telegramId);

    /** Cached variant for high-frequency callers (e.g. Kafka consumer). TTL 30 s. Returns filter rules only. */
    List<String> findByUserId(UUID userId);

    void addFilter(Long telegramId, String rule);

    void deleteFilter(Long telegramId, UUID filterId);

    void updateFilter(Long telegramId, UUID filterId, String newRule);
}
