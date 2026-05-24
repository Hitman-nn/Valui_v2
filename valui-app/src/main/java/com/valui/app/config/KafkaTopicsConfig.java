package com.valui.app.config;

import com.valui.common.kafka.KafkaTopics;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Declares all Kafka topics. KafkaAdmin creates them on startup if absent (idempotent).
 * Replication factor is profile-driven: 1 for local/docker, 3 for prod.
 *
 * Topic inventory:
 *   sport.events.detected      — new match events from parsers (main pipeline)
 *   user.notifications.pending — outbound notification requests (keyed by userId)
 *   subscription.events        — compacted: latest subscription state per user
 *   audit.log                  — append-only audit trail (90-day retention)
 *   notifications.dlq          — dead-letter for failed notification deliveries
 */
@Configuration
public class KafkaTopicsConfig {

    private final short replicationFactor;

    public KafkaTopicsConfig(
            @Value("${kafka.topics.replication-factor:1}") short replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    /**
     * Explicit KafkaAdmin bean so we can set fatalIfBrokerNotAvailable=false:
     * the application starts even when Kafka is temporarily unavailable.
     * autoCreate — creates declared topics on startup if absent (set false to let infra own topics).
     * modifyTopicConfigs — applies config changes (retention etc.) to existing topics on restart.
     */
    @Bean
    public KafkaAdmin kafkaAdmin(
            KafkaProperties kafkaProperties,
            @Value("${kafka.admin.auto-create:true}") boolean autoCreate,
            @Value("${kafka.admin.modify-topic-configs:false}") boolean modifyTopicConfigs) {
        KafkaAdmin admin = new KafkaAdmin(kafkaProperties.buildAdminProperties(null));
        admin.setFatalIfBrokerNotAvailable(false);
        admin.setAutoCreate(autoCreate);
        admin.setModifyTopicConfigs(modifyTopicConfigs);
        return admin;
    }

    // ── sport.events.detected ──────────────────────────────────────────────────

    /**
     * 12 partitions = 6 bookmakers × 2 (headroom for future parsers).
     * Key = externalEventId → same event always lands on the same partition.
     */
    @Bean
    public org.apache.kafka.clients.admin.NewTopic sportEventsDetected() {
        return TopicBuilder.name(KafkaTopics.SPORT_EVENTS_DETECTED)
                .partitions(12)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,          ms(7, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,        TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    // ── user.notifications.pending ─────────────────────────────────────────────

    /**
     * Key = userId → notifications for the same user are ordered within a partition,
     * preventing out-of-order delivery when a user fires multiple controllers.
     */
    @Bean
    public org.apache.kafka.clients.admin.NewTopic userNotificationsPending() {
        return TopicBuilder.name(KafkaTopics.USER_NOTIFICATIONS_PENDING)
                .partitions(6)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, ms(1, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    // ── subscription.events ────────────────────────────────────────────────────

    /**
     * Compacted topic: log-compaction keeps only the latest record per userId key.
     * Consumers can replay to rebuild current subscription state without a DB query.
     * min.cleanable.dirty.ratio=0.1 + segment.ms=24h → compaction runs frequently.
     */
    @Bean
    public org.apache.kafka.clients.admin.NewTopic subscriptionEvents() {
        return TopicBuilder.name(KafkaTopics.SUBSCRIPTION_EVENTS)
                .partitions(4)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,               ms(30, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG,             TopicConfig.CLEANUP_POLICY_COMPACT)
                .config(TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG,  "0.1")
                .config(TopicConfig.SEGMENT_MS_CONFIG,                 ms(1, ChronoUnit.DAYS))
                .build();
    }

    // ── audit.log ──────────────────────────────────────────────────────────────

    @Bean
    public org.apache.kafka.clients.admin.NewTopic auditLog() {
        return TopicBuilder.name(KafkaTopics.AUDIT_LOG)
                .partitions(6)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(90, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    // ── notifications.dlq ──────────────────────────────────────────────────────

    /**
     * Dead-letter queue for notifications that failed after all retry attempts.
     * KafkaConsumerConfig wires DefaultErrorHandler → DeadLetterPublishingRecoverer here.
     */
    @Bean
    public org.apache.kafka.clients.admin.NewTopic notificationsDlq() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATIONS_DLQ)
                .partitions(3)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(14, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    // ── Notification retry ladder ──────────────────────────────────────────────

    @Bean
    public org.apache.kafka.clients.admin.NewTopic notificationsRetry1s() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATIONS_RETRY_1S)
                .partitions(3).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(1, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic notificationsRetry5s() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATIONS_RETRY_5S)
                .partitions(3).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(1, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic notificationsRetry30s() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATIONS_RETRY_30S)
                .partitions(3).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(1, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    /**
     * Terminal DLQ: messages that exhausted all 4 retry attempts land here.
     * Not consumed by any listener; used only for monitoring and manual replay.
     * Long retention (30 days) to allow ops team time to investigate.
     */
    @Bean
    public org.apache.kafka.clients.admin.NewTopic notificationsDlqFinal() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATIONS_DLQ_FINAL)
                .partitions(3).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(30, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    // ── VK notification pipeline ──────────────────────────────────────────────

    @Bean
    public org.apache.kafka.clients.admin.NewTopic vkNotificationsPending() {
        return TopicBuilder.name(KafkaTopics.VK_NOTIFICATIONS_PENDING)
                .partitions(3).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(1, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic vkNotificationsRetry1s() {
        return TopicBuilder.name(KafkaTopics.VK_NOTIFICATIONS_RETRY_1S)
                .partitions(2).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(1, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic vkNotificationsRetry5s() {
        return TopicBuilder.name(KafkaTopics.VK_NOTIFICATIONS_RETRY_5S)
                .partitions(2).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(1, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic vkNotificationsRetry30s() {
        return TopicBuilder.name(KafkaTopics.VK_NOTIFICATIONS_RETRY_30S)
                .partitions(2).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(1, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic vkNotificationsDlq() {
        return TopicBuilder.name(KafkaTopics.VK_NOTIFICATIONS_DLQ)
                .partitions(2).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(7, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic vkNotificationsDlqFinal() {
        return TopicBuilder.name(KafkaTopics.VK_NOTIFICATIONS_DLQ_FINAL)
                .partitions(2).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(30, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    // ── admin.broadcast ────────────────────────────────────────────────────────

    /** One record per recipient; produced by BroadcastController, consumed by BroadcastConsumer. */
    @Bean
    public org.apache.kafka.clients.admin.NewTopic adminBroadcast() {
        return TopicBuilder.name(KafkaTopics.ADMIN_BROADCAST)
                .partitions(3)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG,   ms(1, ChronoUnit.DAYS))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String ms(long amount, ChronoUnit unit) {
        return String.valueOf(Duration.of(amount, unit).toMillis());
    }
}
