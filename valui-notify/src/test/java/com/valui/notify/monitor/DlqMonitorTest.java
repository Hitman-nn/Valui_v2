package com.valui.notify.monitor;

import com.valui.notify.retry.DeadLetterPublisher;
import com.valui.notify.service.AdminNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DlqMonitor — unit tests")
class DlqMonitorTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock AdminNotificationService adminNotificationService;

    @InjectMocks DlqMonitor monitor;

    // ── below threshold ───────────────────────────────────────────────────────

    @Test
    @DisplayName("count = 0 → no alert")
    void countZero_noAlert() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(DeadLetterPublisher.DLQ_FINAL_COUNTER_KEY)).willReturn("0");

        monitor.checkDlqFinal();

        verifyNoInteractions(adminNotificationService);
    }

    @Test
    @DisplayName("count = threshold (10) → no alert (strict >)")
    void countEqualThreshold_noAlert() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(DeadLetterPublisher.DLQ_FINAL_COUNTER_KEY))
                .willReturn(String.valueOf(DlqMonitor.ALERT_THRESHOLD));

        monitor.checkDlqFinal();

        verifyNoInteractions(adminNotificationService);
    }

    // ── above threshold ───────────────────────────────────────────────────────

    @Test
    @DisplayName("count > threshold → alertAdmin called")
    void countAboveThreshold_alertSent() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(DeadLetterPublisher.DLQ_FINAL_COUNTER_KEY))
                .willReturn(String.valueOf(DlqMonitor.ALERT_THRESHOLD + 1));

        monitor.checkDlqFinal();

        verify(adminNotificationService).alertAdmin(anyString());
    }

    @Test
    @DisplayName("count = 100 → alert message mentions count")
    void largeCount_alertMentionsCount() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(DeadLetterPublisher.DLQ_FINAL_COUNTER_KEY)).willReturn("100");

        monitor.checkDlqFinal();

        verify(adminNotificationService).alertAdmin(argThat(msg -> msg.contains("100")));
    }

    // ── counter reading ───────────────────────────────────────────────────────

    @Test
    @DisplayName("null counter → getDlqFinalCount returns 0")
    void nullCounter_returnsZero() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(DeadLetterPublisher.DLQ_FINAL_COUNTER_KEY)).willReturn(null);

        assertThat(monitor.getDlqFinalCount()).isZero();
    }

    @Test
    @DisplayName("invalid counter string → getDlqFinalCount returns 0")
    void invalidCounter_returnsZero() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(DeadLetterPublisher.DLQ_FINAL_COUNTER_KEY)).willReturn("not-a-number");

        assertThat(monitor.getDlqFinalCount()).isZero();
    }
}
