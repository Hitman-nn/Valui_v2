package com.valui.monitor.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stores the last N poll executions per controller in Redis.
 * Key: "poll:history:{controllerId}" — Redis List, newest at index 0.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PollHistoryService {

    static final String KEY_PREFIX = "poll:history:";
    static final int    MAX_SIZE   = 5;

    private final StringRedisTemplate redis;
    private final ObjectMapper        mapper;

    public void record(UUID controllerId, Instant startedAt, long durationMs,
                       int eventsFound, String status) {
        try {
            String json = mapper.writeValueAsString(
                    new PollHistoryEntry(startedAt, durationMs, eventsFound, status));
            String key = key(controllerId);
            redis.opsForList().leftPush(key, json);
            redis.opsForList().trim(key, 0, MAX_SIZE - 1);
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialize poll history entry for {}: {}", controllerId, e.getMessage());
        }
    }

    public List<PollHistoryEntry> getLast(UUID controllerId) {
        List<String> raw = redis.opsForList().range(key(controllerId), 0, MAX_SIZE - 1);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        return raw.stream()
                .map(this::deserialize)
                .filter(e -> e != null)
                .collect(Collectors.toList());
    }

    private PollHistoryEntry deserialize(String json) {
        try {
            return mapper.readValue(json, PollHistoryEntry.class);
        } catch (JsonProcessingException e) {
            log.debug("Failed to deserialize poll history entry: {}", e.getMessage());
            return null;
        }
    }

    static String key(UUID controllerId) {
        return KEY_PREFIX + controllerId;
    }
}
