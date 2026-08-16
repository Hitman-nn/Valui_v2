package com.valui.admin.digest;

/** Rendered digest text for one chat — preview-only, never published to Kafka. */
public record ChatDigestPreviewDto(Long chatId, String text) {}
