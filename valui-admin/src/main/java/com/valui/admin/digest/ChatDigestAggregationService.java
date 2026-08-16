package com.valui.admin.digest;

import com.valui.user.repository.ControllerSubscriptionRepository;
import com.valui.user.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds per-chat digest stats for every group chat with ≥1 active controller, in a fixed small
 * number of batched queries (4 total) regardless of how many chats are eligible — avoids an
 * N+1 across a potentially large chat population.
 */
@Service
@RequiredArgsConstructor
public class ChatDigestAggregationService {

    private final ControllerSubscriptionRepository subscriptionRepository;
    private final NotificationLogRepository notificationLogRepository;

    public List<ChatDigestStatsDto> buildDigests(int staleDays, int windowDays) {
        OffsetDateTime now             = OffsetDateTime.now();
        OffsetDateTime staleCutoff     = now.minusDays(staleDays);
        OffsetDateTime windowStart     = now.minusDays(windowDays);
        OffsetDateTime prevWindowStart = now.minusDays((long) windowDays * 2);

        // The eligible-chat set IS the key set of this map — a chat with zero active controllers
        // never appears here (the underlying query has no LEFT JOIN/COALESCE to zero it out).
        Map<Long, long[]> base = new LinkedHashMap<>();
        for (Object[] row : subscriptionRepository.aggregateDigestStatsByChat()) {
            base.put((Long) row[0], new long[]{
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue(),
            });
        }

        Map<Long, Long> stale     = toMap(subscriptionRepository.countStaleControllersByChat(staleCutoff));
        Map<Long, Long> thisWeek  = toMap(notificationLogRepository.countNotificationsByChatBetween(windowStart, now));
        Map<Long, Long> lastWeek  = toMap(notificationLogRepository.countNotificationsByChatBetween(prevWindowStart, windowStart));

        List<ChatDigestStatsDto> result = new ArrayList<>(base.size());
        for (Map.Entry<Long, long[]> entry : base.entrySet()) {
            Long chatId = entry.getKey();
            long[] b = entry.getValue();
            result.add(new ChatDigestStatsDto(
                    chatId,
                    b[0], b[1], b[2], b[3],
                    thisWeek.getOrDefault(chatId, 0L),
                    lastWeek.getOrDefault(chatId, 0L),
                    stale.getOrDefault(chatId, 0L)));
        }
        return result;
    }

    private static Map<Long, Long> toMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>(rows.size() * 2);
        for (Object[] row : rows) {
            map.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }
}
