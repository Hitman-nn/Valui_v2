package com.valui.user.service;

import com.valui.common.entity.GlobalFilterEntity;

import java.util.List;
import java.util.UUID;

public interface GlobalFilterService {

    List<GlobalFilterEntity> getFilters(Long telegramId);

    void addFilter(Long telegramId, String rule);

    void deleteFilter(Long telegramId, UUID filterId);

    void updateFilter(Long telegramId, UUID filterId, String newRule);
}
