package com.valui.admin.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Kafka consumer group lag")
public record KafkaLagDto(
        @Schema(description = "Название группы") String groupId,
        @Schema(description = "Lag по топикам (topic → lag)") Map<String, Long> lagByTopic,
        @Schema(description = "Суммарный lag по всем топикам") long totalLag
) {}
