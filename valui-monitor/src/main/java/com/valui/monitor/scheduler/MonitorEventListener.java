package com.valui.monitor.scheduler;

// Replaced by com.valui.monitor.kafka.SportEventKafkaProducer.
// The new producer uses @TransactionalEventListener(AFTER_COMMIT) + the transactional outbox
// pattern (OutboxSenderService) for at-least-once Kafka delivery.
// This file is intentionally empty — delete it once the replacement is confirmed in prod.
