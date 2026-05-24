package com.valui.notify.vk;

import com.valui.common.kafka.KafkaTopics;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.retry.RetryHeaders;
import com.valui.notify.stats.NotificationStats;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VkDeadLetterPublisher — unit tests")
class VkDeadLetterPublisherTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @Mock StringRedisTemplate            redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock NotificationStats              stats;
    @Mock com.valui.notify.service.AdminNotificationService adminNotificationService;

    @InjectMocks VkDeadLetterPublisher publisher;

    // ── routing ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("retryCount=0 (first failure) → routes to vk.retry.1s")
    void firstFailure_routesToRetry1s() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(0, KafkaTopics.VK_NOTIFICATIONS_PENDING), retryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.VK_NOTIFICATIONS_RETRY_1S.equals(r.topic())));
    }

    @Test
    @DisplayName("retryCount=1 → routes to vk.retry.5s")
    void retryCount1_routesToRetry5s() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(1, KafkaTopics.VK_NOTIFICATIONS_RETRY_1S), retryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.VK_NOTIFICATIONS_RETRY_5S.equals(r.topic())));
    }

    @Test
    @DisplayName("retryCount=2 → routes to vk.retry.30s")
    void retryCount2_routesToRetry30s() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(2, KafkaTopics.VK_NOTIFICATIONS_RETRY_5S), retryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.VK_NOTIFICATIONS_RETRY_30S.equals(r.topic())));
    }

    @Test
    @DisplayName("retryCount=3 → routes to vk.dlq (5-min tier)")
    void retryCount3_routesToDlq() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(3, KafkaTopics.VK_NOTIFICATIONS_RETRY_30S), retryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.VK_NOTIFICATIONS_DLQ.equals(r.topic())));
    }

    @Test
    @DisplayName("retryCount=4 (exhausted) → routes to vk.dlq.final, increments Redis counter")
    void retryCountExhausted_routesToDlqFinal() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(4, KafkaTopics.VK_NOTIFICATIONS_DLQ), retryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.VK_NOTIFICATIONS_DLQ_FINAL.equals(r.topic())));
        verify(valueOps).increment(VkDeadLetterPublisher.DLQ_FINAL_COUNTER_KEY);
        verify(stats).incVkDlqFinal();
    }

    @Test
    @DisplayName("non-retryable exception → skips ladder, goes straight to vk.dlq.final")
    void nonRetryable_routesDirectlyToDlqFinal() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(0, KafkaTopics.VK_NOTIFICATIONS_PENDING), nonRetryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.VK_NOTIFICATIONS_DLQ_FINAL.equals(r.topic())));
        verify(valueOps).increment(VkDeadLetterPublisher.DLQ_FINAL_COUNTER_KEY);
    }

    // ── headers ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Headers X-Retry-Count, X-Original-Topic, X-Error-Message, X-Failed-At are set")
    void headers_arePopulated() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(0, KafkaTopics.VK_NOTIFICATIONS_PENDING), retryable());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<?, ?> sent = captor.getValue();
        assertThat(header(sent, RetryHeaders.RETRY_COUNT)).isEqualTo("1");
        assertThat(header(sent, RetryHeaders.ORIGINAL_TOPIC)).isEqualTo(KafkaTopics.VK_NOTIFICATIONS_PENDING);
        assertThat(header(sent, RetryHeaders.ERROR_MESSAGE)).isNotBlank();
        assertThat(header(sent, RetryHeaders.FAILED_AT)).isNotBlank();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ConsumerRecord<String, Object> record(int retryCount, String topic) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(RetryHeaders.RETRY_COUNT,
                String.valueOf(retryCount).getBytes(StandardCharsets.UTF_8));
        return new ConsumerRecord<>(topic, 0, 0L, 0L,
                null, 0L, 0, 0, "key", "payload", headers);
    }

    private RetryableNotificationException retryable() {
        return new RetryableNotificationException("VK network error", null, true, 0L);
    }

    private RetryableNotificationException nonRetryable() {
        return new RetryableNotificationException("VK auth error (code=5)", null, false, 0L);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, Object>> okFuture() {
        return CompletableFuture.completedFuture(null);
    }

    private String header(ProducerRecord<?, ?> record, String key) {
        var h = record.headers().lastHeader(key);
        return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
    }
}
