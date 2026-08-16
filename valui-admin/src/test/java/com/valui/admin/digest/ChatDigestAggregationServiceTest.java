package com.valui.admin.digest;

import com.valui.user.repository.ControllerSubscriptionRepository;
import com.valui.user.repository.NotificationLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatDigestAggregationService — merges 3 batched queries into one DTO per chat")
class ChatDigestAggregationServiceTest {

    @Mock ControllerSubscriptionRepository subscriptionRepository;
    @Mock NotificationLogRepository        notificationLogRepository;

    @InjectMocks ChatDigestAggregationService service;

    @Test
    @DisplayName("Merges controller/notification/stale rows for the same chat by chatId")
    void merge_combinesAllThreeQueries() {
        given(subscriptionRepository.aggregateDigestStatsByChat()).willReturn(List.<Object[]>of(
                new Object[]{-100L, 3L, 2L, 1L, 0L}
        ));
        given(subscriptionRepository.countStaleControllersByChat(any())).willReturn(List.<Object[]>of(
                new Object[]{-100L, 2L}
        ));
        given(notificationLogRepository.countNotificationsByChatBetween(any(), any()))
                .willReturn(List.<Object[]>of(new Object[]{-100L, 5L}))  // first call: this week
                .willReturn(List.<Object[]>of(new Object[]{-100L, 4L})); // second call: last week

        List<ChatDigestStatsDto> result = service.buildDigests(30, 7);

        assertThat(result).hasSize(1);
        ChatDigestStatsDto dto = result.get(0);
        assertThat(dto.chatId()).isEqualTo(-100L);
        assertThat(dto.activeControllers()).isEqualTo(3);
        assertThat(dto.activeBookmakers()).isEqualTo(2);
        assertThat(dto.mutedControllers()).isEqualTo(1);
        assertThat(dto.pausedByTokensControllers()).isEqualTo(0);
        assertThat(dto.staleControllers()).isEqualTo(2);
        assertThat(dto.notificationsThisWeek()).isEqualTo(5);
        assertThat(dto.notificationsLastWeek()).isEqualTo(4);
    }

    @Test
    @DisplayName("A chat missing from the notification/stale query results defaults to 0, not omitted")
    void merge_missingChatIdsDefaultToZero() {
        given(subscriptionRepository.aggregateDigestStatsByChat()).willReturn(List.<Object[]>of(
                new Object[]{-200L, 1L, 1L, 0L, 0L}
        ));
        given(subscriptionRepository.countStaleControllersByChat(any())).willReturn(List.of());
        given(notificationLogRepository.countNotificationsByChatBetween(any(), any())).willReturn(List.of());

        List<ChatDigestStatsDto> result = service.buildDigests(30, 7);

        assertThat(result).hasSize(1);
        ChatDigestStatsDto dto = result.get(0);
        assertThat(dto.staleControllers()).isZero();
        assertThat(dto.notificationsThisWeek()).isZero();
        assertThat(dto.notificationsLastWeek()).isZero();
    }

    @Test
    @DisplayName("No eligible chats — empty result, not a null or exception")
    void noEligibleChats_emptyResult() {
        given(subscriptionRepository.aggregateDigestStatsByChat()).willReturn(List.of());
        given(subscriptionRepository.countStaleControllersByChat(any(OffsetDateTime.class))).willReturn(List.of());
        given(notificationLogRepository.countNotificationsByChatBetween(any(), any())).willReturn(List.of());

        List<ChatDigestStatsDto> result = service.buildDigests(30, 7);

        assertThat(result).isEmpty();
    }
}
