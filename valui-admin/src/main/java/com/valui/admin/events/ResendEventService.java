package com.valui.admin.events;

import com.valui.admin.events.dto.ResendResultDto;
import com.valui.common.annotation.Audit;
import com.valui.common.entity.ControllerSubscriptionEntity;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.monitor.outbox.OutboxEventRepository;
import com.valui.user.repository.ControllerSubscriptionRepository;
import com.valui.user.repository.DetectedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResendEventService {

    private static final String MONITOR_DEDUP_PREFIX = "dedup:ctrl:";
    private static final String NOTIFY_DEDUP_PREFIX  = "notif:title-dedup:";

    private final DetectedEventRepository          eventRepository;
    private final ControllerSubscriptionRepository subscriptionRepository;
    private final OutboxEventRepository            outboxRepository;
    private final StringRedisTemplate              redis;

    @Audit(action = "RESEND_EVENT", entityType = "DetectedEvent")
    @Transactional
    public ResendResultDto resend(UUID eventId) {
        DetectedEventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NoSuchElementException("Event not found: " + eventId));

        UUID   controllerId    = event.getController().getId();
        String externalId      = event.getEventExternalId();
        String bookmaker       = event.getController().getBookmaker() != null
                                 ? event.getController().getBookmaker().name() : "";
        String title           = event.getTitle();
        String url             = event.getUrl();

        // DB-операции первыми: если они упадут — Redis не будет затронут (нет частичного состояния).
        // Redis не участвует в транзакции, поэтому ставим его последним в цепочке.
        // 1. Удалить outbox-строки — уникальный индекс (external_event_id, controllerId)
        //    иначе заблокирует повторную вставку в ControllerTaskExecutor
        outboxRepository.deleteByExternalEventIdAndControllerId(externalId, controllerId.toString());
        // 2. Удалить из detected_events — FK notification_log.event_id ON DELETE SET NULL (V34),
        //    чтобы DedupSync в 3:00 не вернул externalId в Redis до следующего опроса
        eventRepository.deleteById(eventId);
        // 3. Redis: удаляем после успешного выполнения всех DB-операций
        redis.opsForSet().remove(MONITOR_DEDUP_PREFIX + controllerId, externalId);

        // Clear notify-dedup for all subscribers so they receive a fresh message (not an edit)
        long startEpoch = extractStartEpoch(event.getExtraData());
        List<ControllerSubscriptionEntity> subs = subscriptionRepository.findAllByControllerId(controllerId);
        int notifyDedupCleared = 0;
        for (ControllerSubscriptionEntity sub : subs) {
            String key = notifyDedupKey(sub.getChatId(), bookmaker, url, title, startEpoch);
            if (Boolean.TRUE.equals(redis.delete(key))) notifyDedupCleared++;
        }

        log.info("[RESEND] eventId={} controllerId={} externalId={} notifyDedupCleared={}",
                eventId, controllerId, externalId, notifyDedupCleared);

        return new ResendResultDto(eventId, controllerId, externalId, notifyDedupCleared);
    }

    // ── key helpers (mirrors TitleDedupCacheService logic) ────────────────────

    private static String notifyDedupKey(long chatId, String bookmaker, String url, String title, long startEpoch) {
        String urlBase   = extractUrlBase(url);
        String epochPart = startEpoch > 0 ? ":" + startEpoch : "";
        String input     = bookmaker + ":" + urlBase + ":" + (title == null ? "" : title.strip().toLowerCase()) + epochPart;
        return NOTIFY_DEDUP_PREFIX + chatId + ":" + sha256Hex(input);
    }

    private static long extractStartEpoch(String extraData) {
        if (extraData == null) return 0;
        int idx = extraData.indexOf("\"st\":");
        if (idx < 0) return 0;
        int start = idx + 5, end = start;
        while (end < extraData.length() && Character.isDigit(extraData.charAt(end))) end++;
        if (end == start) return 0;
        try { return Long.parseLong(extraData.substring(start, end)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String extractUrlBase(String url) {
        if (url == null || url.isBlank()) return "";
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        int lastSlash = path.lastIndexOf('/');
        return lastSlash > 0 ? path.substring(0, lastSlash) : path;
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
