package com.valui.common.kafka;

/**
 * One record per recipient — admin broadcast fanout produces N of these.
 * Consumed by BroadcastConsumer in valui-notify.
 */
public record AdminBroadcastMessage(
        /** Target Telegram chat ID (user's personal chat = their telegramId). */
        Long chatId,
        /** Plain-text or Markdown message body. */
        String text
) {}
