package com.valui.user.watch;

import com.valui.common.entity.MarketWatchEntity;

import java.util.List;
import java.util.UUID;

public interface MarketWatchService {

    /**
     * Creates an ACTIVE watch. Throws {@link org.springframework.dao.DataIntegrityViolationException}
     * on duplicate (same chat + event + bookmaker + market_type + ACTIVE).
     */
    MarketWatchEntity create(long chatId, long telegramId, UUID controllerId,
                             String externalEventId, String bookmaker, String marketType,
                             String matchTitle, String matchUrl, Integer messageId,
                             UUID notifLogId, Long startEpoch);

    boolean existsActive(long chatId, String externalEventId, String bookmaker, String marketType);

    /** Active watches for the controller whose externalEventId is in the given set. */
    List<MarketWatchEntity> findActiveByController(UUID controllerId, List<String> externalEventIds);

    void markFired(UUID watchId);
}
