package com.valui.user.watch;

import com.valui.common.entity.MarketWatchEntity;
import com.valui.user.repository.MarketWatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketWatchServiceImpl implements MarketWatchService {

    private final MarketWatchRepository repo;

    @Override
    @Transactional
    public MarketWatchEntity create(long chatId, long telegramId, UUID controllerId,
                                    String externalEventId, String bookmaker, String marketType,
                                    String matchTitle, String matchUrl, Integer messageId,
                                    UUID notifLogId, Long startEpoch) {
        MarketWatchEntity watch = MarketWatchEntity.builder()
                .chatId(chatId)
                .telegramId(telegramId)
                .controllerId(controllerId)
                .externalEventId(externalEventId)
                .bookmaker(bookmaker)
                .marketType(marketType)
                .matchTitle(matchTitle)
                .matchUrl(matchUrl)
                .messageId(messageId)
                .notifLogId(notifLogId)
                .startEpoch(startEpoch)
                .build();
        return repo.save(watch);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActive(long chatId, String externalEventId, String bookmaker, String marketType) {
        return repo.existsByChatIdAndExternalEventIdAndBookmakerAndMarketTypeAndStatus(
                chatId, externalEventId, bookmaker, marketType, MarketWatchEntity.STATUS_ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MarketWatchEntity> findActiveByController(UUID controllerId, List<String> externalEventIds) {
        if (externalEventIds.isEmpty()) return List.of();
        return repo.findActiveByControllerAndEvents(controllerId, externalEventIds);
    }

    @Override
    @Transactional
    public void markFired(UUID watchId) {
        repo.updateStatus(watchId, MarketWatchEntity.STATUS_FIRED);
        log.debug("[WATCH] Fired watch id={}", watchId);
    }

    @Transactional
    @Scheduled(cron = "0 0 3 * * *")
    public int expireStarted() {
        long nowEpoch = System.currentTimeMillis() / 1000;
        int expired = repo.expireStarted(nowEpoch);
        if (expired > 0) log.info("[WATCH] Expired {} market watches past match start", expired);
        return expired;
    }
}
