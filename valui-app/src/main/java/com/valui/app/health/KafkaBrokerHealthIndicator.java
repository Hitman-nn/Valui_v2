package com.valui.app.health;

import com.valui.notify.service.AdminNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Checks Kafka broker connectivity via AdminClient.listTopics() with a 3-second timeout.
 * On first DOWN → sends admin Telegram alert.
 * On recovery → sends "Kafka restored" alert.
 * Complements the outbox-based delivery guarantee: DOWN here means degraded latency,
 * not data loss (scanAndSend retries all pending outbox rows automatically).
 */
@Slf4j
@Component
public class KafkaBrokerHealthIndicator implements HealthIndicator, DisposableBean {

    private static final int CHECK_TIMEOUT_MS = 3_000;

    private final AdminClient adminClient;
    private final AdminNotificationService adminNotificationService;

    private volatile boolean wasDown = false;

    public KafkaBrokerHealthIndicator(KafkaProperties kafkaProperties,
                                      AdminNotificationService adminNotificationService) {
        this.adminClient = AdminClient.create(kafkaProperties.buildAdminProperties(null));
        this.adminNotificationService = adminNotificationService;
    }

    @Override
    public Health health() {
        try {
            adminClient.listTopics(new ListTopicsOptions().timeoutMs(CHECK_TIMEOUT_MS))
                       .listings()
                       .get(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (wasDown) {
                wasDown = false;
                adminNotificationService.alertAdmin(
                        "✅ *Kafka восстановлена*. Outbox-очередь будет доставлена автоматически.");
            }
            return Health.up().build();

        } catch (Exception e) {
            String msg = rootCause(e);
            if (!wasDown) {
                wasDown = true;
                adminNotificationService.alertAdmin(
                        "⚠️ *Kafka недоступна*: " + msg
                        + "\nOutbox активен — потерь нет. Доставка возобновится автоматически.");
            }
            log.warn("[KAFKA-HEALTH] Broker check failed: {}", msg);
            return Health.down()
                    .withDetail("error", msg)
                    .withDetail("impact", "outbox retries active, no message loss")
                    .build();
        }
    }

    @Override
    public void destroy() {
        adminClient.close(Duration.ofSeconds(5));
    }

    private static String rootCause(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
