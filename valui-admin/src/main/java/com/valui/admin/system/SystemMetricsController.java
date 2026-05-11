package com.valui.admin.system;

import com.valui.admin.system.dto.DbPoolDto;
import com.valui.admin.system.dto.KafkaLagDto;
import com.valui.admin.system.dto.RedisInfoDto;
import com.valui.admin.system.dto.SystemOverviewDto;
import com.valui.common.domain.UserStatus;
import com.valui.user.repository.DetectedEventRepository;
import com.valui.user.repository.NotificationLogRepository;
import com.valui.user.repository.UserRepository;
import com.valui.user.api.ControllerPortService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Slf4j
@Tag(name = "Admin — System", description = "Системные метрики (только ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/system")
@RequiredArgsConstructor
public class SystemMetricsController {

    private static final List<String> CONSUMER_GROUPS = List.of(
            "valui-notify", "valui-broadcast", "valui-audit"
    );

    private final UserRepository userRepository;
    private final DetectedEventRepository detectedEventRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final ControllerPortService controllerPortService;
    private final MeterRegistry meterRegistry;
    private final KafkaAdmin kafkaAdmin;
    private final RedisConnectionFactory redisConnectionFactory;

    @GetMapping("/overview")
    @Operation(summary = "Обзорные метрики: пользователи, контроллеры, события, уведомления")
    public ResponseEntity<SystemOverviewDto> overview() {
        OffsetDateTime todayStart = LocalDate.now().atStartOfDay().atOffset(ZoneOffset.UTC);
        long totalUsers         = userRepository.count();
        long activeUsers        = userRepository.countByStatus(UserStatus.ACTIVE);
        long activeControllers  = controllerPortService.findAllActive().size();
        long eventsToday        = detectedEventRepository.countByDetectedAtAfter(todayStart);
        long notificationsToday = notificationLogRepository.countByCreatedAtAfter(todayStart);
        return ResponseEntity.ok(new SystemOverviewDto(
                totalUsers, activeUsers, activeControllers, eventsToday, notificationsToday));
    }

    @GetMapping("/kafka")
    @Operation(summary = "Kafka consumer group lag по группам")
    public ResponseEntity<List<KafkaLagDto>> kafkaLag() {
        List<KafkaLagDto> result = new ArrayList<>();
        Properties props = new Properties();
        props.putAll(kafkaAdmin.getConfigurationProperties());

        try (AdminClient client = AdminClient.create(props)) {
            for (String groupId : CONSUMER_GROUPS) {
                try {
                    result.add(computeLag(client, groupId));
                } catch (Exception e) {
                    log.warn("[SYSTEM] Failed to get lag for group={}: {}", groupId, e.getMessage());
                    result.add(new KafkaLagDto(groupId, Map.of(), -1L));
                }
            }
        } catch (Exception e) {
            log.error("[SYSTEM] Kafka AdminClient error: {}", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/redis")
    @Operation(summary = "Redis memory usage")
    public ResponseEntity<RedisInfoDto> redisInfo() {
        try {
            Properties info = redisConnectionFactory.getConnection()
                    .serverCommands()
                    .info("memory");

            long usedBytes     = parseLong(info, "used_memory");
            String usedHuman   = info.getProperty("used_memory_human", "n/a");
            long peakBytes     = parseLong(info, "used_memory_peak");
            long maxBytes      = parseLong(info, "maxmemory");

            Properties keyspaceInfo = redisConnectionFactory.getConnection()
                    .serverCommands()
                    .info("keyspace");
            long totalKeys = parseKeyCount(keyspaceInfo);

            return ResponseEntity.ok(new RedisInfoDto(usedBytes, usedHuman, peakBytes, maxBytes, totalKeys));
        } catch (Exception e) {
            log.error("[SYSTEM] Redis info error: {}", e.getMessage());
            return ResponseEntity.ok(new RedisInfoDto(0, "unavailable", 0, 0, 0));
        }
    }

    @GetMapping("/db")
    @Operation(summary = "HikariCP connection pool stats")
    public ResponseEntity<DbPoolDto> dbPool() {
        try {
            int active  = (int) gaugeValue("hikaricp.connections.active");
            int pending = (int) gaugeValue("hikaricp.connections.pending");
            int idle    = (int) gaugeValue("hikaricp.connections.idle");
            int total   = (int) gaugeValue("hikaricp.connections");
            int max     = (int) gaugeValue("hikaricp.connections.max");
            return ResponseEntity.ok(new DbPoolDto(active, pending, idle, total, max));
        } catch (Exception e) {
            log.error("[SYSTEM] DB pool metrics error: {}", e.getMessage());
            return ResponseEntity.ok(new DbPoolDto(0, 0, 0, 0, 0));
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private KafkaLagDto computeLag(AdminClient client, String groupId) throws Exception {
        ListConsumerGroupOffsetsResult committed = client.listConsumerGroupOffsets(groupId);
        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committedOffsets =
                committed.partitionsToOffsetAndMetadata().get();

        Map<TopicPartition, OffsetSpec> endOffsetRequest = new HashMap<>();
        committedOffsets.keySet().forEach(tp -> endOffsetRequest.put(tp, OffsetSpec.latest()));

        Map<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                client.listOffsets(endOffsetRequest).all().get();

        Map<String, Long> lagByTopic = new HashMap<>();
        long totalLag = 0;
        for (Map.Entry<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> e
                : committedOffsets.entrySet()) {
            TopicPartition tp = e.getKey();
            long end = endOffsets.get(tp).offset();
            long lag = Math.max(0, end - e.getValue().offset());
            lagByTopic.merge(tp.topic(), lag, Long::sum);
            totalLag += lag;
        }
        return new KafkaLagDto(groupId, lagByTopic, totalLag);
    }

    private double gaugeValue(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private static long parseLong(Properties info, String key) {
        try { return Long.parseLong(info.getProperty(key, "0").trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static long parseKeyCount(Properties keyspace) {
        long total = 0;
        for (String key : keyspace.stringPropertyNames()) {
            if (key.startsWith("db")) {
                String val = keyspace.getProperty(key, "");
                for (String part : val.split(",")) {
                    if (part.startsWith("keys=")) {
                        try { total += Long.parseLong(part.substring(5)); } catch (Exception ignored) {}
                    }
                }
            }
        }
        return total;
    }
}
