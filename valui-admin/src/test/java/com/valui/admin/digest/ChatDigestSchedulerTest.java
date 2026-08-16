package com.valui.admin.digest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatDigestScheduler — enabled toggle, one Kafka send per eligible chat")
class ChatDigestSchedulerTest {

    @Mock ChatDigestAggregationService aggregationService;
    @Mock ChatDigestMessageFormatter   formatter;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    private ChatDigestScheduler scheduler;

    private void setUp(boolean enabled) {
        scheduler = new ChatDigestScheduler(aggregationService, formatter, kafkaTemplate);
        ReflectionTestUtils.setField(scheduler, "digestEnabled", enabled);
        ReflectionTestUtils.setField(scheduler, "staleDays", 30);
        ReflectionTestUtils.setField(scheduler, "windowDays", 7);
    }

    @Nested
    @DisplayName("Disabled (valui.digest.enabled=false)")
    class Disabled {

        @Test
        @DisplayName("runWeeklyDigest is a no-op — no repository or Kafka calls at all")
        void disabled_noInteractions() {
            setUp(false);

            scheduler.runWeeklyDigest();

            verifyNoInteractions(aggregationService, formatter, kafkaTemplate);
        }
    }

    @Nested
    @DisplayName("Enabled")
    class Enabled {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("Publishes exactly one Kafka record per eligible chat")
        void enabled_onePerChat() {
            setUp(true);
            given(aggregationService.buildDigests(30, 7)).willReturn(List.of(
                    new ChatDigestStatsDto(-100L, 1, 1, 0, 0, 5, 3, 0),
                    new ChatDigestStatsDto(-200L, 2, 1, 0, 0, 0, 0, 1)
            ));
            given(formatter.format(any())).willReturn("text");
            given(kafkaTemplate.send(any(String.class), any(String.class), any()))
                    .willReturn(CompletableFuture.completedFuture((SendResult<String, Object>) null));

            scheduler.runWeeklyDigest();

            verify(kafkaTemplate, times(2)).send(any(String.class), any(String.class), any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("publishAll() returns the count of enqueued chats (used by the manual-trigger admin endpoint)")
        void publishAll_returnsCount() {
            setUp(true);
            given(aggregationService.buildDigests(30, 7)).willReturn(List.of(
                    new ChatDigestStatsDto(-100L, 1, 1, 0, 0, 0, 0, 0)
            ));
            given(formatter.format(any())).willReturn("text");
            given(kafkaTemplate.send(any(String.class), any(String.class), any()))
                    .willReturn(CompletableFuture.completedFuture((SendResult<String, Object>) null));

            int count = scheduler.publishAll();

            org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("No eligible chats — zero Kafka sends")
        void noEligibleChats_noSends() {
            setUp(true);
            given(aggregationService.buildDigests(30, 7)).willReturn(List.of());

            scheduler.runWeeklyDigest();

            verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any());
        }
    }
}
