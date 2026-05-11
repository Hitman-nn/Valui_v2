package com.valui.admin.dashboard;

import com.valui.admin.dashboard.dto.*;
import com.valui.admin.parsers.dto.BookmakerStatusDto;
import com.valui.admin.system.dto.DbPoolDto;
import com.valui.admin.system.dto.KafkaLagDto;
import com.valui.admin.system.dto.RedisInfoDto;
import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.UserStatus;
import com.valui.parser.health.ParserHealthService;
import com.valui.user.repository.*;
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
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@Slf4j
@Tag(name = "Admin — Dashboard", description = "Агрегированные метрики системы (только ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private static final List<String> CONSUMER_GROUPS = List.of("valui-notify", "valui-broadcast", "valui-audit");

    private final UserRepository               userRepository;
    private final ControllerRepository         controllerRepository;
    private final DetectedEventRepository      eventRepository;
    private final NotificationLogRepository    notifRepository;
    private final MeterRegistry                meterRegistry;
    private final KafkaAdmin                   kafkaAdmin;
    private final RedisConnectionFactory       redisConnectionFactory;
    private final ParserHealthService          parserHealthService;
    private final JvmMetricsHistoryService     jvmHistory;

    @GetMapping("/summary")
    @Operation(summary = "Бизнес-метрики: пользователи, контроллеры, события, подписки")
    public ResponseEntity<DashboardSummaryDto> summary() {
        return ResponseEntity.ok(buildSummary());
    }

    @GetMapping("/jvm")
    @Operation(summary = "JVM и системные метрики")
    public ResponseEntity<DashboardJvmDto> jvm() {
        return ResponseEntity.ok(buildJvm());
    }

    @GetMapping("/jvm-history")
    @Operation(summary = "История JVM-метрик для графиков",
               description = "range: 1h (60 точек, 1 мин), 24h (1440 точек, 1 мин), 7d (168 точек, 1 час), 30d (720 точек, 1 час)")
    public ResponseEntity<List<com.valui.admin.dashboard.dto.JvmDataPointDto>> jvmHistory(
            @RequestParam(defaultValue = "1h") String range) {
        return ResponseEntity.ok(jvmHistory.getHistory(range));
    }

    @GetMapping("/infrastructure")
    @Operation(summary = "Redis, Kafka, HikariCP")
    public ResponseEntity<Map<String, Object>> infrastructure() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("redis", buildRedis());
        result.put("kafka", buildKafkaLag());
        result.put("db", buildDb());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/activity")
    @Operation(summary = "Активность событий и уведомлений по дням")
    public ResponseEntity<List<ActivityPointDto>> activity(
            @RequestParam(defaultValue = "7") int days) {
        List<ActivityPointDto> result = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = days - 1; i >= 0; i--) {
            OffsetDateTime from = now.minusDays(i + 1L).toLocalDate()
                .atStartOfDay().atOffset(ZoneOffset.UTC);
            OffsetDateTime to = from.plusDays(1);
            long ev  = eventRepository.countByDetectedAtBetween(from, to);
            long ntf = notifRepository.countByCreatedAtBetween(from, to);
            result.add(new ActivityPointDto(from.toLocalDate().toString(), ev, ntf));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/full")
    @Operation(summary = "Все секции дашборда одним запросом")
    public ResponseEntity<DashboardFullDto> full(
            @RequestParam(defaultValue = "7") int activityDays) {

        List<ActivityPointDto> activity = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = activityDays - 1; i >= 0; i--) {
            OffsetDateTime from = now.minusDays(i + 1L).toLocalDate()
                .atStartOfDay().atOffset(ZoneOffset.UTC);
            OffsetDateTime to = from.plusDays(1);
            activity.add(new ActivityPointDto(from.toLocalDate().toString(),
                eventRepository.countByDetectedAtBetween(from, to),
                notifRepository.countByCreatedAtBetween(from, to)));
        }

        return ResponseEntity.ok(new DashboardFullDto(
            buildSummary(),
            buildParsers(),
            buildRedis(),
            buildKafkaLag(),
            buildDb(),
            buildJvm(),
            activity
        ));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private DashboardSummaryDto buildSummary() {
        OffsetDateTime now       = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime todayStart = LocalDate.now().atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime weekAgo   = now.minusDays(7);
        OffsetDateTime monthAgo  = now.minusDays(30);
        OffsetDateTime yearAgo   = now.minusDays(365);

        var users = new DashboardSummaryDto.UserStats(
            userRepository.count(),
            userRepository.countByStatus(UserStatus.ACTIVE),
            userRepository.countByStatus(UserStatus.BANNED),
            userRepository.countByCreatedAtAfter(todayStart),
            userRepository.countByCreatedAtAfter(weekAgo)
        );

        var controllers = new DashboardSummaryDto.ControllerStats(
            controllerRepository.count(),
            controllerRepository.countByIsActiveTrue(),
            controllerRepository.countByIsMutedTrue(),
            controllerRepository.countByIsActiveTrueAndLastEventAtBefore(weekAgo)
        );

        var events = new DashboardSummaryDto.PeriodStats(
            eventRepository.countByDetectedAtAfter(todayStart),
            eventRepository.countByDetectedAtAfter(weekAgo),
            eventRepository.countByDetectedAtAfter(monthAgo),
            eventRepository.countByDetectedAtAfter(yearAgo),
            eventRepository.count()
        );

        var notifications = new DashboardSummaryDto.PeriodStats(
            notifRepository.countByCreatedAtAfter(todayStart),
            notifRepository.countByCreatedAtAfter(weekAgo),
            notifRepository.countByCreatedAtAfter(monthAgo),
            notifRepository.countByCreatedAtAfter(yearAgo),
            notifRepository.count()
        );

        return new DashboardSummaryDto(users, controllers, events, notifications);
    }

    private List<BookmakerStatusDto> buildParsers() {
        Map<BookmakerType, ParserHealthService.CircuitBreakerInfo> state =
            parserHealthService.getCurrentState();
        return Arrays.stream(BookmakerType.values())
            .map(bk -> {
                ParserHealthService.CircuitBreakerInfo info = state.get(bk);
                if (info == null) return new BookmakerStatusDto(bk.name(), "UNKNOWN", "yellow", 100f, 0, 0, 0);
                String ind = toIndicator(info.state(), info.successRate());
                return new BookmakerStatusDto(bk.name(), info.state().name(), ind, info.successRate(),
                    info.numberOfSuccessfulCalls(), info.numberOfFailedCalls(), info.numberOfNotPermittedCalls());
            }).toList();
    }

    private RedisInfoDto buildRedis() {
        try {
            Properties info = redisConnectionFactory.getConnection().serverCommands().info("memory");
            long usedBytes   = parseLong(info, "used_memory");
            String usedHuman = info.getProperty("used_memory_human", "n/a");
            long peakBytes   = parseLong(info, "used_memory_peak");
            long maxBytes    = parseLong(info, "maxmemory");
            Properties ks    = redisConnectionFactory.getConnection().serverCommands().info("keyspace");
            long totalKeys   = parseKeyCount(ks);
            return new RedisInfoDto(usedBytes, usedHuman, peakBytes, maxBytes, totalKeys);
        } catch (Exception e) {
            log.warn("[DASHBOARD] Redis info error: {}", e.getMessage());
            return new RedisInfoDto(0, "unavailable", 0, 0, 0);
        }
    }

    private List<KafkaLagDto> buildKafkaLag() {
        List<KafkaLagDto> result = new ArrayList<>();
        Properties props = new Properties();
        props.putAll(kafkaAdmin.getConfigurationProperties());
        try (AdminClient client = AdminClient.create(props)) {
            for (String gid : CONSUMER_GROUPS) {
                try { result.add(computeLag(client, gid)); }
                catch (Exception e) { result.add(new KafkaLagDto(gid, Map.of(), -1L)); }
            }
        } catch (Exception e) {
            log.warn("[DASHBOARD] Kafka error: {}", e.getMessage());
        }
        return result;
    }

    private DbPoolDto buildDb() {
        try {
            return new DbPoolDto(
                (int) gauge("hikaricp.connections.active"),
                (int) gauge("hikaricp.connections.pending"),
                (int) gauge("hikaricp.connections.idle"),
                (int) gauge("hikaricp.connections"),
                (int) gauge("hikaricp.connections.max")
            );
        } catch (Exception e) {
            return new DbPoolDto(0, 0, 0, 0, 0);
        }
    }

    private DashboardJvmDto buildJvm() {
        long heapUsed    = (long) gauge("jvm.memory.used",   "area", "heap");
        long heapMax     = (long) gauge("jvm.memory.max",    "area", "heap");
        long nonHeapUsed = (long) gauge("jvm.memory.used",   "area", "nonheap");
        long threads     = (long) gauge("jvm.threads.live");
        long uptime      = (long) gauge("process.uptime");
        double cpu       = gauge("system.cpu.usage") * 100;
        double diskFree  = safeGauge("disk.free",  "path", ".");
        double diskTotal = safeGauge("disk.total", "path", ".");

        double httpRps    = 0;
        double errorRate  = 0;
        try {
            double reqTotal   = meterRegistry.find("http.server.requests").counters().stream()
                .mapToDouble(c -> c.count()).sum();
            double req5xx     = meterRegistry.find("http.server.requests").tag("status", "5xx")
                .counters().stream().mapToDouble(c -> c.count()).sum();
            if (reqTotal > 0) errorRate = req5xx / reqTotal * 100;
        } catch (Exception ignored) {}

        return new DashboardJvmDto(heapUsed, heapMax, nonHeapUsed, threads, uptime,
            Math.round(cpu * 10.0) / 10.0, httpRps, Math.round(errorRate * 10.0) / 10.0,
            diskFree, diskTotal);
    }

    private double gauge(String name) {
        try { return meterRegistry.get(name).gauge().value(); } catch (Exception e) { return 0; }
    }

    private double gauge(String name, String tagKey, String tagValue) {
        try { return meterRegistry.get(name).tag(tagKey, tagValue).gauge().value(); } catch (Exception e) { return 0; }
    }

    private double safeGauge(String name, String tagKey, String tagValue) {
        try { return meterRegistry.get(name).tag(tagKey, tagValue).gauge().value(); } catch (Exception e) { return 0; }
    }

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
            long end = endOffsets.get(e.getKey()).offset();
            long lag = Math.max(0, end - e.getValue().offset());
            lagByTopic.merge(e.getKey().topic(), lag, Long::sum);
            totalLag += lag;
        }
        return new KafkaLagDto(groupId, lagByTopic, totalLag);
    }

    private static String toIndicator(io.github.resilience4j.circuitbreaker.CircuitBreaker.State state, float rate) {
        if (state == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN ||
            state == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.FORCED_OPEN) return "red";
        if (state == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.HALF_OPEN || rate < 90f) return "yellow";
        return "green";
    }

    private static long parseLong(Properties p, String key) {
        try { return Long.parseLong(p.getProperty(key, "0").trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static long parseKeyCount(Properties keyspace) {
        long total = 0;
        for (String k : keyspace.stringPropertyNames()) {
            if (k.startsWith("db")) {
                for (String part : keyspace.getProperty(k, "").split(",")) {
                    if (part.startsWith("keys=")) {
                        try { total += Long.parseLong(part.substring(5)); } catch (Exception ignored) {}
                    }
                }
            }
        }
        return total;
    }
}
