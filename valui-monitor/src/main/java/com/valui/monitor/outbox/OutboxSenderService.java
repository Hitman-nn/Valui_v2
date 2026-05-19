package com.valui.monitor.outbox;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.SportEventDetectedMessage;
import com.valui.monitor.kafka.SportEventKafkaMetrics;
import com.valui.monitor.kafka.SportEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Transactional outbox sender.
 *
 * Two triggers:
 *   1. {@link #publishImmediate(String)} — called by SportEventKafkaProducer right after the
 *      DB transaction commits, for low-latency delivery.
 *   2. {@link #scanAndSend()} — @Scheduled every 5 s, retries any rows still unsent after
 *      15 s (covers app crashes, Kafka downtime, or failed immediate sends).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxSenderService {

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final SportEventMapper mapper;
    private final SportEventKafkaMetrics metrics;
    private final OutboxMarkingService markingService;

    @Async
    public void publishImmediate(String externalEventId) {
        outboxRepo.findAllByExternalEventIdAndSentAtIsNull(externalEventId)
                .forEach(this::doPublish);
    }

    @Scheduled(fixedDelayString = "${valui.monitor.outbox.scan-interval-ms:5000}")
    public void scanAndSend() {
        OffsetDateTime now = OffsetDateTime.now();
        // Pick up rows unsent for >15 s whose lock (if any) is stale by >30 s
        List<OutboxEvent> pending = outboxRepo.findUnsentBefore(
                now.minusSeconds(15), now.minusSeconds(30));
        if (!pending.isEmpty()) {
            log.info("Outbox retry: {} unsent event(s) — Kafka delivery may be lagging", pending.size());
            pending.forEach(this::doPublish);
        }
    }

    private void doPublish(OutboxEvent outbox) {
        // Atomically claim the row before sending to prevent concurrent duplicate sends
        // from publishImmediate() and scanAndSend() racing on the same row.
        int claimed = outboxRepo.tryLock(outbox.getId(), OffsetDateTime.now());
        if (claimed == 0) {
            log.debug("Outbox row {} already locked or sent — skipping", outbox.getId());
            return;
        }

        SportEventDetectedMessage message = mapper.fromOutbox(outbox);
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(outbox.getTopic(), outbox.getMessageKey(), message);
        record.headers().add("source",  "valui-monitor".getBytes(StandardCharsets.UTF_8));
        record.headers().add("version", "1".getBytes(StandardCharsets.UTF_8));

        long startNs = System.nanoTime();
        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                metrics.onSendFailed();
                log.error("Outbox publish failed [id={} externalEventId={}]: {}",
                        outbox.getId(), outbox.getExternalEventId(), ex.getMessage());
                // lockedAt stays set; scan will retry after the 30 s stale-lock window
            } else {
                metrics.onSendSuccess(System.nanoTime() - startNs);
                markingService.markSent(outbox.getId());
                if (log.isDebugEnabled() && result != null) {
                    log.debug("Outbox published [id={} externalEventId={} partition={} offset={}]",
                            outbox.getId(), outbox.getExternalEventId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            }
        });
    }

    /** Builds an unsent {@link OutboxEvent} row for use inside the caller's transaction. */
    public OutboxEvent buildOutboxEvent(
            String externalEventId, String controllerId, String userId, Long telegramId, Long chatId,
            String bookmaker, String title, String url, String extraData) {
        return OutboxEvent.builder()
                .topic(KafkaTopics.SPORT_EVENTS_DETECTED)
                .messageKey(controllerId)
                .externalEventId(externalEventId)
                .controllerId(controllerId)
                .userId(userId)
                .telegramId(telegramId)
                .chatId(chatId)
                .bookmaker(bookmaker)
                .title(title)
                .url(url)
                .extraData(extraData)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
