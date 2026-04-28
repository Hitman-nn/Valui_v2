package com.valui.notify.retry;

import com.valui.common.kafka.KafkaTopics;
import com.valui.notify.exception.RetryableNotificationException;
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
@DisplayName("DeadLetterPublisher — unit tests")
class DeadLetterPublisherTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks DeadLetterPublisher publisher;

    // ── routing ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("retryCount=0 (first failure) → routes to retry.1s")
    void firstFailure_routesToRetry1s() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(0, "user.notifications.pending"), retryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.NOTIFICATIONS_RETRY_1S.equals(r.topic())));
    }

    @Test
    @DisplayName("retryCount=1 → routes to retry.5s")
    void retryCount1_routesToRetry5s() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(1, "notifications.retry.1s"), retryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.NOTIFICATIONS_RETRY_5S.equals(r.topic())));
    }

    @Test
    @DisplayName("retryCount=2 → routes to retry.30s")
    void retryCount2_routesToRetry30s() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(2, "notifications.retry.1s"), retryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.NOTIFICATIONS_RETRY_30S.equals(r.topic())));
    }

    @Test
    @DisplayName("retryCount=3 → routes to notifications.dlq (5-min tier)")
    void retryCount3_routesToDlq() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(3, "notifications.retry.30s"), retryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.NOTIFICATIONS_DLQ.equals(r.topic())));
    }

    @Test
    @DisplayName("retryCount=4 (exhausted) → routes to dlq.final")
    void retryCountExhausted_routesToDlqFinal() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(4, "notifications.dlq"), retryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.NOTIFICATIONS_DLQ_FINAL.equals(r.topic())));
        verify(valueOps).increment(DeadLetterPublisher.DLQ_FINAL_COUNTER_KEY);
    }

    @Test
    @DisplayName("non-retryable exception → skips ladder, goes straight to dlq.final")
    void nonRetryable_routesDirectlyToDlqFinal() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(0, "user.notifications.pending"), nonRetryable());

        verify(kafkaTemplate).send(argThat((ProducerRecord<String, Object> r) ->
                KafkaTopics.NOTIFICATIONS_DLQ_FINAL.equals(r.topic())));
        verify(valueOps).increment(DeadLetterPublisher.DLQ_FINAL_COUNTER_KEY);
    }

    // ── headers ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Headers X-Retry-Count, X-Original-Topic, X-Error-Message, X-Failed-At are set")
    void headers_arePopulated() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(okFuture());

        publisher.publishToDlq(record(0, "user.notifications.pending"), retryable());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<?, ?> sent = captor.getValue();
        assertThat(header(sent, RetryHeaders.RETRY_COUNT)).isEqualTo("1");
        assertThat(header(sent, RetryHeaders.ORIGINAL_TOPIC)).isEqualTo("user.notifications.pending");
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
        return new RetryableNotificationException("network error", null, true, 0L);
    }

    private RetryableNotificationException nonRetryable() {
        return new RetryableNotificationException("bot blocked", null, false, 0L);
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
