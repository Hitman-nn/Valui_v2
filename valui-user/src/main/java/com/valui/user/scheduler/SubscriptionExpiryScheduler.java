package com.valui.user.scheduler;

import com.valui.common.entity.SubscriptionEntity;
import com.valui.user.repository.SubscriptionRepository;
import com.valui.user.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryScheduler {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;

    /**
     * Every 6 hours: find all ACTIVE subscriptions whose expires_at is in the past,
     * mark them EXPIRED, and downgrade the user to the FREE plan.
     * Each subscription is processed in its own transaction via SubscriptionService.
     */
    @Scheduled(cron = "0 0 */6 * * *")
    public void expireStaleSubscriptions() {
        List<SubscriptionEntity> expired = subscriptionRepository.findExpiredBefore(OffsetDateTime.now());

        if (expired.isEmpty()) {
            log.debug("Subscription expiry sweep: no expired subscriptions found");
            return;
        }

        log.info("Subscription expiry sweep: processing {} expired subscriptions", expired.size());

        int success = 0, failed = 0;
        for (SubscriptionEntity sub : expired) {
            try {
                subscriptionService.expireSubscription(sub.getId());
                success++;
            } catch (Exception e) {
                log.error("Failed to expire subscriptionId={}", sub.getId(), e);
                failed++;
            }
        }
        log.info("Subscription expiry sweep complete: success={} failed={}", success, failed);
    }
}
