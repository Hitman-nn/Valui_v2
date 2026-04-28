---
name: Kafka Infrastructure Iteration (Sprint 4)
description: Kafka topics, producer/consumer configs, Avro schemas — completed April 2026
type: project
---

Реализована полная Kafka инфраструктура (итерация 4, апрель 2026).

**Why:** Перевод с ad-hoc топика `valui.match.discovered` на полноценную многотопиковую архитектуру с типизированными сообщениями и DLQ.

**What was done:**
- Топик `valui.match.discovered` → `sport.events.detected` (renamed, updated MonitorEventListener + MatchEventConsumer)
- 5 топиков через KafkaAdmin+TopicBuilder в KafkaTopicsConfig (valui-app)
- 4 Avro схемы (.avsc) + Java records в valui-common/src/main/avro + valui-common/kafka/*
- KafkaProducerConfig (valui-monitor): acks=all, idempotence, type headers
- KafkaConsumerConfig (valui-notify): 2 factories (notify concurrency=3, audit concurrency=2), DLQ via DefaultErrorHandler+DeadLetterPublishingRecoverer
- application.yml: добавлен top-level `kafka.topics.replication-factor` (1 local/docker, 3 prod), `kafka.schema-registry.url`, `spring.kafka.admin.properties` SASL для prod
- EmbeddedKafka тест в valui-notify/src/test/.../kafka/KafkaIntegrationTest.java

**Key decisions:**
- JSON serialization (не Avro) т.к. нет Schema Registry; Avro .avsc файлы — как документация + future Schema Registry
- `KafkaTemplate<String, Object>` generic вместо typed (один бин на весь монолит)
- Type headers (`__TypeId__`) включены: producer пишет, consumer читает → автоматическая десериализация в нужный класс
- `kafka:` top-level namespace в YAML отдельно от `spring.kafka.*` чтобы не конфликтовать с Spring Boot autoconfigure

**How to apply:** При добавлении нового Kafka топика: 1) добавить константу в KafkaTopics.java, 2) добавить @Bean NewTopic в KafkaTopicsConfig, 3) создать .avsc + Java record в valui-common/kafka.
