package com.valui.notify.dispatcher;

import com.valui.common.domain.TokenReasonCode;
import com.valui.common.exception.UserNotFoundException;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.log.NotificationLogService;
import com.valui.notify.retry.DeadLetterPublisher;
import com.valui.notify.retry.NotificationRetryPolicy;
import com.valui.notify.stats.NotificationStats;
import com.valui.notify.dedup.TitleDedupCacheService;
import com.valui.user.service.TokenLedgerService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * REGRESSION: tokenLedgerService.tryDebit(UUID, ...) can throw UserNotFoundException (user
 * deleted between outbox event creation and Kafka delivery) — this used to be called outside the
 * try/catch that routes failures to deadLetterPublisher.publishToDlq(), so the exception escaped
 * onNotificationPending entirely and bypassed the DLQ's custom retry-tier headers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationDispatcher — unit tests")
class NotificationDispatcherTest {

    @Mock NotificationDispatchService dispatchService;
    @Mock NotificationLogService      logService;
    @Mock DeadLetterPublisher         deadLetterPublisher;
    @Mock NotificationRetryPolicy     retryPolicy;
    @Mock TokenLedgerService          tokenLedgerService;
    @Mock TitleDedupCacheService      titleDedupCache;
    @Mock NotificationStats           stats;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks NotificationDispatcher dispatcher;

    static final UUID LOG_ID  = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();

    UserNotificationRequestMessage request;

    @BeforeEach
    void setUp() {
        request = new UserNotificationRequestMessage(
                LOG_ID.toString(), USER_ID.toString(), 123456L,
                "TELEGRAM", "test message", UUID.randomUUID().toString(),
                null, null, null);
        given(tokenLedgerService.getCost("NOTIFICATION_SENT")).willReturn(1);
    }

    private ConsumerRecord<String, Object> record() {
        return new ConsumerRecord<>("user.notifications.pending", 0, 0L, "key", request);
    }

    @Test
    @DisplayName("tryDebit throwing UserNotFoundException is caught and routed to the DLQ, not left to escape the listener")
    void tryDebitThrows_routedToDlq_notPropagated() {
        given(tokenLedgerService.tryDebit(eq(USER_ID), anyInt(), eq(TokenReasonCode.NOTIFICATION_SENT), isNull()))
                .willThrow(new UserNotFoundException(USER_ID));
        given(retryPolicy.classify(any(UserNotFoundException.class)))
                .willReturn(new RetryableNotificationException("gone", null, true, 0L));

        dispatcher.onNotificationPending(record());

        verify(deadLetterPublisher).publishToDlq(any(), any(RetryableNotificationException.class));
        verify(logService).markFailed(eq(LOG_ID), any());
        verifyNoInteractions(dispatchService);
    }

    @Test
    @DisplayName("Successful debit + dispatch marks sent, no DLQ routing")
    void debitAndDispatchSucceed_marksSent() throws Exception {
        given(tokenLedgerService.tryDebit(eq(USER_ID), anyInt(), eq(TokenReasonCode.NOTIFICATION_SENT), isNull()))
                .willReturn(true);
        given(dispatchService.dispatch(request)).willReturn(42);

        dispatcher.onNotificationPending(record());

        verify(logService).markSent(LOG_ID, 42);
        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    @DisplayName("Zero balance: debit returns false — skipped, no dispatch, no DLQ (not a failure)")
    void zeroBalance_skipped_noDlq() {
        given(tokenLedgerService.tryDebit(eq(USER_ID), anyInt(), eq(TokenReasonCode.NOTIFICATION_SENT), isNull()))
                .willReturn(false);

        dispatcher.onNotificationPending(record());

        verifyNoInteractions(dispatchService, deadLetterPublisher);
        verify(logService).markFailed(LOG_ID, "Zero token balance");
    }
}
