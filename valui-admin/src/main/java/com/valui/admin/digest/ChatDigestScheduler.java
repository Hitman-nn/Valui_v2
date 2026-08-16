package com.valui.admin.digest;

import com.valui.common.kafka.ChatDigestMessage;
import com.valui.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Weekly per-chat digest — fans out one {@link ChatDigestMessage} per eligible group chat
 * through Kafka (rate-limited, at-least-once delivery on the consumer side in valui-notify),
 * same shape as {@code BroadcastAdminController}'s admin broadcast.
 *
 * <p>Off by default ({@link #digestEnabled}, {@code valui.digest.enabled}): this reaches every
 * group chat with an active controller at once, with zero prior production validation — see
 * {@code ChatDigestAdminController}'s preview/trigger endpoints for checking real output before
 * flipping it on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatDigestScheduler {

    private final ChatDigestAggregationService aggregationService;
    private final ChatDigestMessageFormatter   formatter;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${valui.digest.enabled:false}")
    private boolean digestEnabled;

    @Value("${valui.digest.stale-days:30}")
    private int staleDays;

    @Value("${valui.digest.window-days:7}")
    private int windowDays;

    @Scheduled(cron = "${valui.digest.cron:0 0 10 * * MON}")
    public void runWeeklyDigest() {
        if (!digestEnabled) {
            log.debug("[DIGEST] Disabled — skipping scheduled run");
            return;
        }
        int sent = publishAll();
        log.info("[DIGEST] Weekly run enqueued {} chat digests", sent);
    }

    /** Publishes to Kafka for every eligible chat; used by both the cron job and the admin
     *  manual-trigger endpoint (dryRun=false path). Returns the number of chats enqueued. */
    public int publishAll() {
        List<ChatDigestStatsDto> digests = aggregationService.buildDigests(staleDays, windowDays);
        for (ChatDigestStatsDto stats : digests) {
            String text = formatter.format(stats);
            kafkaTemplate.send(KafkaTopics.CHAT_DIGEST, String.valueOf(stats.chatId()),
                            new ChatDigestMessage(stats.chatId(), text))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("[DIGEST] Failed to enqueue digest for chatId={}: {}", stats.chatId(), ex.getMessage());
                        }
                    });
        }
        return digests.size();
    }
}
