package com.valui.common.kafka;

public final class KafkaTopics {

    public static final String SPORT_EVENTS_DETECTED     = "sport.events.detected";
    public static final String USER_NOTIFICATIONS_PENDING = "user.notifications.pending";
    public static final String SUBSCRIPTION_EVENTS       = "subscription.events";
    public static final String AUDIT_LOG                 = "audit.log";
    public static final String NOTIFICATIONS_DLQ         = "notifications.dlq";

    private KafkaTopics() {}
}
