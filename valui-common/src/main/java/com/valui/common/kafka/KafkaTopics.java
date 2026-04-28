package com.valui.common.kafka;

public final class KafkaTopics {

    public static final String SPORT_EVENTS_DETECTED      = "sport.events.detected";
    public static final String USER_NOTIFICATIONS_PENDING = "user.notifications.pending";
    public static final String SUBSCRIPTION_EVENTS        = "subscription.events";
    public static final String AUDIT_LOG                  = "audit.log";

    // ── Notification retry ladder ──────────────────────────────────────────────
    /** First retry: 1-second sleep in RetryTopicConsumer. */
    public static final String NOTIFICATIONS_RETRY_1S  = "notifications.retry.1s";
    /** Second retry: 5-second sleep. */
    public static final String NOTIFICATIONS_RETRY_5S  = "notifications.retry.5s";
    /** Third retry: 30-second sleep. */
    public static final String NOTIFICATIONS_RETRY_30S = "notifications.retry.30s";
    /** Fourth retry (DLQ): 5-minute sleep in DlqConsumer. */
    public static final String NOTIFICATIONS_DLQ       = "notifications.dlq";
    /** Terminal dead-letter: no more retries; monitored by DlqMonitor. */
    public static final String NOTIFICATIONS_DLQ_FINAL = "notifications.dlq.final";

    private KafkaTopics() {}
}
