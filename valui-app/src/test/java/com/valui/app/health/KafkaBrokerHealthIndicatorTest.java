package com.valui.app.health;

import com.valui.notify.service.AdminNotificationService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.common.KafkaFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaBrokerHealthIndicator — DOWN/UP edge-detection, no duplicate alerts")
class KafkaBrokerHealthIndicatorTest {

    @Mock AdminClient adminClient;
    @Mock AdminNotificationService adminNotificationService;
    @Mock ListTopicsResult listTopicsResult;
    @Mock KafkaFuture<Collection<TopicListing>> future;

    private KafkaBrokerHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new KafkaBrokerHealthIndicator(adminClient, adminNotificationService);
        given(adminClient.listTopics(any(ListTopicsOptions.class))).willReturn(listTopicsResult);
        given(listTopicsResult.listings()).willReturn(future);
    }

    private void simulateUp() throws Exception {
        given(future.get(anyLong(), any(TimeUnit.class))).willReturn(Collections.emptyList());
    }

    private void simulateDown() throws Exception {
        given(future.get(anyLong(), any(TimeUnit.class)))
                .willThrow(new ExecutionException(new RuntimeException("Connection refused")));
    }

    @Test
    @DisplayName("broker reachable from the start → no alert")
    void up_fromStart_noAlert() throws Exception {
        simulateUp();

        indicator.health();

        verify(adminNotificationService, never()).alertAdmin(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("first failure → exactly one DOWN alert")
    void firstFailure_alertsOnce() throws Exception {
        simulateDown();

        indicator.health();

        verify(adminNotificationService, times(1)).alertAdmin(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("sustained outage across repeated polls → only one DOWN alert (no spam)")
    void sustainedOutage_alertsOnlyOnce() throws Exception {
        simulateDown();

        indicator.health();
        indicator.health();
        indicator.health();

        verify(adminNotificationService, times(1)).alertAdmin(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("recovery after an outage → exactly one recovery alert")
    void recovery_alertsOnce() throws Exception {
        simulateDown();
        indicator.health(); // DOWN alert

        simulateUp();
        indicator.health(); // UP alert
        indicator.health(); // still up — no further alert

        verify(adminNotificationService, times(2)).alertAdmin(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("concurrent health() calls racing the DOWN transition still send exactly one alert")
    void concurrentCallsAtDownTransition_sendExactlyOneAlert() throws Exception {
        simulateDown();

        int threads = 16;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    indicator.health();
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        verify(adminNotificationService, times(1)).alertAdmin(org.mockito.ArgumentMatchers.anyString());
    }
}
