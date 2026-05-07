package com.valui.notify.retry;

import com.valui.common.kafka.KafkaTopics;
import com.valui.notify.exception.RetryableNotificationException;
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

/**
 * Routes failed notification records through the retry ladder or to the final DLQ.
 *
 * Routing table (retryCount = value from X-Retry-Count header after increment):
 *   1 → notifications.retry.1s  (1-second delay)
 *   2 → notifications.retry.5s  (5-second delay)
 *   3 → notifications.retry.30s (30-second delay)
 *   4 → notifications.dlq       (5-minute delay in DlqConsumer)
 *   >4 or non-retryable → notifications.dlq.final (terminal)
 *
 * Every routed record carries the four standard retry headers plus all original headers.
 * A Redis counter {@code dlq:final:count} tracks total messages sent to dlq.final
 * so that {@link com.valui.notify.monitor.DlqMonitor} can alert on accumulation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterPublisher {

    public static final int MAX_RETRIES = 4;
    public static final String DLQ_FINAL_COUNTER_KEY = "dlq:final:count";

    private static final String[] RETRY_TOPICS = {
        KafkaTopics.NOTIFICATIONS_RETRY_1S,  // attempt 1
        KafkaTopics.NOTIFICATIONS_RETRY_5S,  // attempt 2
        KafkaTopics.NOTIFICATIONS_RETRY_30S, // attempt 3
        KafkaTopics.NOTIFICATIONS_DLQ        // attempt 4 (5-min)
    };

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final NotificationStats stats;

    public void publishToDlq(ConsumerRecord<?, ?> original, RetryableNotificationException ex) {
        int currentCount = readRetryCount(original);
        int newCount     = currentCount + 1;

        String target;
        if (!ex.isRetryable() || newCount > MAX_RETRIES) {
            target = KafkaTopics.NOTIFICATIONS_DLQ_FINAL;
            redisTemplate.opsForValue().increment(DLQ_FINAL_COUNTER_KEY);
            stats.incDlqFinal();
            log.error("[DLQ-FINAL] Permanently failed after {} retries: topic={} error={}",
                    currentCount, original.topic(), ex.getMessage());
        } else {
            target = RETRY_TOPICS[newCount - 1];
            stats.incDlqRetry();
            log.warn("[DLQ] Routing to {} (attempt {}/{}): {}", target, newCount, MAX_RETRIES, ex.getMessage());
        }

        ProducerRecord<String, Object> out = buildRecord(target, original, newCount, ex);
        kafkaTemplate.send(out)
                .whenComplete((r, sendEx) -> {
                    if (sendEx != null) {
                        log.error("[DLQ] Failed to publish to {}: {}", target, sendEx.getMessage());
                    }
                });
    }

    // ── private ───────────────────────────────────────────────────────────────

    private ProducerRecord<String, Object> buildRecord(String topic,
                                                        ConsumerRecord<?, ?> original,
                                                        int newCount,
                                                        RetryableNotificationException ex) {
        String key = original.key() != null ? original.key().toString() : null;
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, original.value());

        // Copy original headers first (skip managed retry headers to avoid duplicates)
        for (Header h : original.headers()) {
            if (!isRetryHeader(h.key())) {
                record.headers().add(h);
            }
        }

        // Overwrite managed headers
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
