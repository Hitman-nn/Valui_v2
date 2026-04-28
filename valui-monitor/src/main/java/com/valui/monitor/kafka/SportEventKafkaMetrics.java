package com.valui.monitor.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class SportEventKafkaMetrics {

    private final Counter sentTotal;
    private final Counter failedTotal;
    private final Timer sendLatency;

    public SportEventKafkaMetrics(MeterRegistry registry) {
        sentTotal = Counter.builder("kafka.producer.sport_events.sent")
                .description("Messages successfully acked by broker on sport.events.detected")
                .register(registry);

        failedTotal = Counter.builder("kafka.producer.sport_events.failed")
                .description("Messages that failed delivery to sport.events.detected")
                .register(registry);

        sendLatency = Timer.builder("kafka.producer.sport_events.latency")
                .description("Time from kafkaTemplate.send() to broker ack")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void onSendSuccess(long elapsedNanos) {
        sentTotal.increment();
        sendLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void onSendFailed() {
        failedTotal.increment();
    }
}
