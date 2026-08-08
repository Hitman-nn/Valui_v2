package com.valui.admin.dlq;

import com.valui.common.annotation.Audit;
import com.valui.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Reads all messages from {@code notifications.dlq.final} and re-publishes them
 * to the topic stored in the {@code X-Original-Topic} header (or back to
 * {@code user.notifications.pending} if the header is absent).
 *
 * After replay the Redis counter is reset so {@code DlqMonitor} stops alerting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqReplayService {

    private static final String X_ORIGINAL_TOPIC   = "X-Original-Topic";
    private static final String DLQ_FINAL_COUNTER  = "dlq:final:count";
    private static final String REPLAYED_COUNTER   = "dlq:replayed:count";
    private static final Duration POLL_TIMEOUT      = Duration.ofSeconds(3);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaProperties kafkaProperties;
    private final StringRedisTemplate redisTemplate;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * Drains dlq.final → re-publishes each record to its original topic.
     * {@code kafkaTemplate.send()} is async and fire-and-forget — per-message failures are
     * logged individually as they complete, but the returned count is how many were dispatched
     * to the producer, not how many were confirmed delivered.
     * @return number of messages dispatched for re-publish
     */
    @Audit(action = "DLQ_REPLAY", entityType = "Kafka")
    public long replay() {
        Properties props = buildConsumerProps();
        long dispatched = 0;

        try (KafkaConsumer<String, Object> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = getPartitions(consumer, KafkaTopics.NOTIFICATIONS_DLQ_FINAL);
            if (partitions.isEmpty()) {
                log.info("[DLQ-REPLAY] No partitions found for {}", KafkaTopics.NOTIFICATIONS_DLQ_FINAL);
                return 0;
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            boolean moreRecords = true;

            while (moreRecords) {
                ConsumerRecords<String, Object> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) break;

                for (ConsumerRecord<String, Object> record : records) {
                    String targetTopic = extractOriginalTopic(record);
                    ProducerRecord<String, Object> out =
                            new ProducerRecord<>(targetTopic, record.key(), record.value());
                    // Copy all headers so consumer receives full context
                    record.headers().forEach(h -> out.headers().add(h));

                    // send() is fire-and-forget — the returned future was previously never
                    // checked, so a silent broker hiccup mid-replay would inflate the reported
                    // count above what actually got re-published, with zero trace anywhere.
                    kafkaTemplate.send(out).whenComplete((r, ex) -> {
                        if (ex != null) {
                            log.error("[DLQ-REPLAY] Failed to republish key={} targetTopic={}: {}",
                                    record.key(), targetTopic, ex.getMessage(), ex);
                        }
                    });
                    dispatched++;
                }

                // Stop when we've reached the end offsets captured before polling
                moreRecords = partitions.stream().anyMatch(tp -> {
                    long consumed = consumer.position(tp);
                    Long end = endOffsets.get(tp);
                    return end != null && consumed < end;
                });
            }
        }

        // Reset the accumulation counter
        if (dispatched > 0) {
            redisTemplate.opsForValue().set(DLQ_FINAL_COUNTER, "0");
            redisTemplate.opsForValue().increment(REPLAYED_COUNTER, dispatched);
        }
        // "Dispatched" not "replayed": send() above is async — this count is how many were
        // handed to the producer, not how many were confirmed delivered. Per-message failures
        // are logged individually above as they complete.
        log.info("[DLQ-REPLAY] Dispatched {} messages from dlq.final for re-publish (async send)", dispatched);
        return dispatched;
    }

    public DlqStatsDto stats() {
        String countStr    = redisTemplate.opsForValue().get(DLQ_FINAL_COUNTER);
        String replayedStr = redisTemplate.opsForValue().get(REPLAYED_COUNTER);
        long count    = parseLong(countStr);
        long replayed = parseLong(replayedStr);
        String status = count == 0 ? "OK" : count > 10 ? "CRITICAL" : "WARNING";
        return new DlqStatsDto(count, replayed, status);
    }

    // ── private ───────────────────────────────────────────────────────────────

    private Properties buildConsumerProps() {
        Properties props = new Properties();
        Map<String, Object> base = kafkaProperties.buildConsumerProperties(null);
        base.forEach((k, v) -> props.put(k, v));

        props.put(ConsumerConfig.GROUP_ID_CONFIG,                  "valui-dlq-replay-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,         "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,        false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,    StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,  JsonDeserializer.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES,               "com.valui.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS,          "true");
        return props;
    }

    private List<TopicPartition> getPartitions(KafkaConsumer<?, ?> consumer, String topic) {
        try {
            var partitionInfos = consumer.partitionsFor(topic);
            if (partitionInfos == null) return List.of();
            return partitionInfos.stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .toList();
        } catch (Exception e) {
            log.error("[DLQ-REPLAY] Failed to list partitions for {}: {}", topic, e.getMessage(), e);
            return List.of();
        }
    }

    private static String extractOriginalTopic(ConsumerRecord<?, ?> record) {
        Header h = record.headers().lastHeader(X_ORIGINAL_TOPIC);
        if (h != null && h.value() != null) {
            String topic = new String(h.value());
            if (!topic.isBlank()) return topic;
        }
        return KafkaTopics.USER_NOTIFICATIONS_PENDING;
    }

    private static long parseLong(String s) {
        if (s == null) return 0L;
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { return 0L; }
    }
}
