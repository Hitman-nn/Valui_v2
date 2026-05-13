package com.valui.user.service;

import com.valui.common.entity.GlobalFilterEntity;

import java.util.List;
import java.util.UUID;

public interface GlobalFilterService {

    /**
     * В личке (chatId == telegramId) возвращает все фильтры пользователя, включая созданные в группах.
     * В группе — только фильтры этой группы.
     */
    List<GlobalFilterEntity> getFilters(Long telegramId, Long chatId);

    /** Cached variant for high-frequency callers (e.g. Kafka consumer). Returns active rules for the target chat. */
    List<String> findByChatId(Long chatId);

    void addFilter(Long telegramId, Long chatId, String rule);

    /**
     * Удаляет фильтр. В группе — любой участник чата; в личке — владелец управляет всеми своими фильтрами.
     */
    void deleteFilter(Long telegramId, Long chatId, UUID filterId);

    /**
     * Редактирует фильтр. В группе — любой участник чата; в личке — владелец управляет всеми своими фильтрами.
     */
    void updateFilter(Long telegramId, Long chatId, UUID filterId, String newRule);
}
