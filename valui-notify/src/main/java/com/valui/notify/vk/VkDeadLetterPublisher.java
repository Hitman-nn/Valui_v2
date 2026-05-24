package com.valui.notify.vk;

import com.valui.common.kafka.KafkaTopics;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.retry.RetryHeaders;
import com.valui.notify.service.AdminNotificationService;
import com.valui.notify.stats.NotificationStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Routes failed VK notification records through the retry ladder or to the final DLQ.
 *
 * Routing table (retryCount after increment):
 *   1 → vk.notifications.retry.1s
 *   2 → vk.notifications.retry.5s
 *   3 → vk.notifications.retry.30s
 *   4 → vk.notifications.dlq       (5-minute delay in VkDlqConsumer)
 *   >4 or non-retryable → vk.notifications.dlq.final (terminal)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VkDeadLetterPublisher {

    public static final int    MAX_RETRIES           = 4;
    public static final String DLQ_FINAL_COUNTER_KEY = "vk:dlq:final:count";

    private static final String[] RETRY_TOPICS = {
        KafkaTopics.VK_NOTIFICATIONS_RETRY_1S,
        KafkaTopics.VK_NOTIFICATIONS_RETRY_5S,
        KafkaTopics.VK_NOTIFICATIONS_RETRY_30S,
        KafkaTopics.VK_NOTIFICATIONS_DLQ
    };

    private static final long ALERT_THROTTLE_MS = 10 * 60 * 1_000L;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate           redisTemplate;
    private final NotificationStats             stats;
    private final AdminNotificationService      adminNotificationService;

    private final AtomicLong lastAlertAt = new AtomicLong(0);

    public void publishToDlq(ConsumerRecord<?, ?> original, RetryableNotificationException ex) {
        int currentCount = readRetryCount(original);
        int newCount     = currentCount + 1;

        String target;
        if (!ex.isRetryable() || newCount > MAX_RETRIES) {
            target = KafkaTopics.VK_NOTIFICATIONS_DLQ_FINAL;
            redisTemplate.opsForValue().increment(DLQ_FINAL_COUNTER_KEY);
            stats.incVkDlqFinal();
            log.error("[VK-DLQ-FINAL] Permanently failed after {} retries: topic={} error={}",
                    currentCount, original.topic(), ex.getMessage());
            alertDlqFinal(currentCount, ex.getMessage());
        } else {
            target = RETRY_TOPICS[newCount - 1];
            stats.incVkDlqRetry();
            log.warn("[VK-DLQ] Routing to {} (attempt {}/{}): {}", target, newCount, MAX_RETRIES, ex.getMessage());
        }

        ProducerRecord<String, Object> out = buildRecord(target, original, newCount, ex);
        kafkaTemplate.send(out)
                .whenComplete((r, sendEx) -> {
                    if (sendEx != null) {
                        log.error("[VK-DLQ] Failed to publish to {}: {}", target, sendEx.getMessage());
                    }
                });
    }

    private void alertDlqFinal(int retries, String error) {
        long now  = System.currentTimeMillis();
        long prev = lastAlertAt.get();
        if (now - prev < ALERT_THROTTLE_MS) return;
        if (!lastAlertAt.compareAndSet(prev, now)) return;
        adminNotificationService.alertAdmin(String.format(
                "🔴 *VK DLQ-final*: VK-уведомление безвозвратно потеряно после %d попыток\n`%s`",
                retries, error != null ? error : "unknown error"));
    }

    private ProducerRecord<String, Object> buildRecord(String topic,
                                                        ConsumerRecord<?, ?> original,
                                                        int newCount,
                                                        RetryableNotificationException ex) {
        String key = original.key() != null ? original.key().toString() : null;
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, original.value());
        for (Header h : original.headers()) {
            if (!isRetryHeader(h.key())) record.headers().add(h);
        }
        String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        record.headers().add(RetryHeaders.RETRY_COUNT,    str(newCount));
        record.headers().add(RetryHeaders.ORIGINAL_TOPIC, str(original.topic()));
        record.headers().add(RetryHeaders.ERROR_MESSAGE,  str(truncate(errMsg, 500)));
        record.headers().add(RetryHeaders.FAILED_AT,      str(Instant.now().toString()));
        return record;
    }

    private static int readRetryCount(ConsumerRecord<?, ?> record) {
        Header h = record.headers().lastHeader(RetryHeaders.RETRY_COUNT);
        if (h == null) return 0;
        try { return Integer.parseInt(new String(h.value(), StandardCharsets.UTF_8)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static boolean isRetryHeader(String name) {
        return RetryHeaders.RETRY_COUNT.equals(name)
                || RetryHeaders.ORIGINAL_TOPIC.equals(name)
                || RetryHeaders.ERROR_MESSAGE.equals(name)
                || RetryHeaders.FAILED_AT.equals(name);
    }

    private static byte[] str(Object value) {
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
