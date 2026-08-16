package com.valui.common.kafka;

/**
 * One record per recipient chat — ChatDigestScheduler fanout produces N of these.
 * Consumed by ChatDigestConsumer in valui-notify.
 */
public record ChatDigestMessage(
        /** Target Telegram chat ID (negative — group chats only, per the digest's scope). */
        Long chatId,
        /** Pre-formatted, MarkdownV2-escaped message body. */
        String text
) {}
