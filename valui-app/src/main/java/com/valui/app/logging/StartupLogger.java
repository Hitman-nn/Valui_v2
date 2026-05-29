package com.valui.app.logging;

import com.valui.bot.vk.VkLinkService;
import com.valui.monitor.outbox.OutboxEventRepository;
import com.valui.parser.bookmaker.betboom.ws.WsClientBorrowingPool;
import com.valui.parser.health.ParserHealthService;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
public class StartupLogger {

    // ANSI
    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String CYAN   = "\033[36m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String DIM    = "\033[2m";

    private static final String FULL_LINE = "═".repeat(62);
    private static final String SUB_LINE  = "  " + "─".repeat(58);
    private static final int    LABEL_W   = 13;
    private static final int    COL1_W    = 22; // left column width for 2-col rows

    private final Environment                   env;
    private final Flyway                        flyway;
    private final UserRepository                userRepository;
    private final ControllerRepository          controllerRepository;
    private final DataSource                    dataSource;
    private final KafkaListenerEndpointRegistry kafkaRegistry;
    private final OutboxEventRepository         outboxRepository;
    private final WsClientBorrowingPool         wsPool;
    private final ParserHealthService           parserHealth;
    private final VkLinkService                 vkLinkService;

    public StartupLogger(Environment env,
                         Flyway flyway,
                         UserRepository userRepository,
                         ControllerRepository controllerRepository,
                         DataSource dataSource,
                         KafkaListenerEndpointRegistry kafkaRegistry,
                         OutboxEventRepository outboxRepository,
                         WsClientBorrowingPool wsPool,
                         ParserHealthService parserHealth,
                         VkLinkService vkLinkService) {
        this.env                  = env;
        this.flyway               = flyway;
        this.userRepository       = userRepository;
        this.controllerRepository = controllerRepository;
        this.dataSource           = dataSource;
        this.kafkaRegistry        = kafkaRegistry;
        this.outboxRepository     = outboxRepository;
        this.wsPool               = wsPool;
        this.parserHealth         = parserHealth;
        this.vkLinkService        = vkLinkService;
    }

    /** Prints a separator when all beans are initialized but before ApplicationReadyEvent. */
    @EventListener(ApplicationStartedEvent.class)
    public void onStarted() {
        log.info(CYAN + "── ALL BEANS INITIALIZED" + " ─".repeat(18) + RESET);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        String profile  = String.join(", ", env.getActiveProfiles());
        String port     = env.getProperty("server.port", "8080");
        String kafka    = env.getProperty("spring.kafka.bootstrap-servers", "—");
        String redis    = env.getProperty("spring.data.redis.host", "localhost")
                        + ":" + env.getProperty("spring.data.redis.port", "6379");
        String db       = env.getProperty("spring.datasource.url", "—");
        String botUser  = env.getProperty("valui.bot.username", "—");
        String botMode  = env.getProperty("valui.bot.mode", "long_polling");
        String dedupTtl = env.getProperty("valui.notifications.dedup-ttl-minutes", "60");

        String startedIn = event.getTimeTaken() != null
                ? event.getTimeTaken().toSeconds() + "."
                  + String.format("%03d", event.getTimeTaken().toMillisPart()) + "s"
                : "—";
        String heapInfo  = jvmHeap();
        String flyway    = flywayVersion();
        String hikari    = hikariPoolInfo();
        String users     = activeUsersCount();
        String ctrls     = controllersBreakdown();
        String groups    = kafkaConsumerGroups();
        String outbox    = outboxPending();
        String wsInfo    = wsPoolInfo();
        String cbInfo    = circuitBreakersInfo();
        String vkInfo    = vkStatus();
        String swagger   = swaggerUrl(port);

        log.info("\n" + CYAN + BOLD + FULL_LINE + RESET
            + "\n" + CYAN + BOLD + "  ✅  VALUI READY" + RESET
            + "\n" + CYAN + FULL_LINE + RESET

            // ── row 1: profile + started ─────────────────────────────────────
            + "\n  " + lbl("Profile")  + pad(profile, COL1_W)   + lbl("Started")  + startedIn
            + "\n  " + lbl("Port")     + pad(port, COL1_W)      + lbl("JVM heap") + heapInfo
            + "\n" + SUB_LINE

            // ── infrastructure ────────────────────────────────────────────────
            + "\n  " + lbl("Database") + db
            + "\n  " + lbl("  Flyway") + flyway
            + "\n  " + lbl("  Hikari") + hikari
            + "\n  " + lbl("Redis")    + redis
            + "\n  " + lbl("Kafka")    + kafka
            + "\n  " + lbl("  groups") + groups
            + "\n" + SUB_LINE

            // ── bots ──────────────────────────────────────────────────────────
            + "\n  " + lbl("Telegram") + "@" + botUser + "  [" + botMode + "]"
            + "\n  " + lbl("VK")       + vkInfo
            + "\n" + SUB_LINE

            // ── stats ─────────────────────────────────────────────────────────
            + "\n  " + lbl("Users")    + pad(users + " active", COL1_W) + lbl("Outbox")    + outbox
            + "\n  " + lbl("Controllers") + ctrls
            + "\n  " + lbl("WS Pool")  + pad(wsInfo, COL1_W)           + lbl("CBs")        + cbInfo
            + "\n  " + lbl("Dedup TTL") + dedupTtl + " min"
            + "\n" + SUB_LINE

            // ── admin ─────────────────────────────────────────────────────────
            + "\n  " + lbl("Admin API") + swagger
            + "\n" + CYAN + FULL_LINE + RESET);
    }

    /** Prints a visible phase separator — call from @PostConstruct in key components. */
    public static void phase(org.slf4j.Logger logger, String phaseName) {
        logger.info(CYAN + "── " + phaseName + " " + "─".repeat(Math.max(0, 50 - phaseName.length())) + RESET);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static String lbl(String text) {
        return GREEN + String.format("%-" + LABEL_W + "s", text) + RESET;
    }

    private static String pad(String value, int width) {
        if (value == null) return " ".repeat(width);
        return value.length() >= width ? value + "  " : value + " ".repeat(width - value.length());
    }

    private String jvmHeap() {
        long maxMb  = Runtime.getRuntime().maxMemory()   / 1024 / 1024;
        long usedMb = (Runtime.getRuntime().totalMemory()
                     - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        return usedMb + " / " + maxMb + " MB";
    }

    private String swaggerUrl(String port) {
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            return "http://" + host + ":" + port + "/swagger-ui.html";
        } catch (Exception e) {
            return "http://localhost:" + port + "/swagger-ui.html";
        }
    }

    private String flywayVersion() {
        try {
            MigrationInfo current = flyway.info().current();
            if (current == null) return "no migrations";
            long pending = flyway.info().pending().length;
            String v = "V" + current.getVersion() + "  " + current.getDescription();
            return pending > 0 ? v + "  " + YELLOW + "⚠ pending: " + pending + RESET : v;
        } catch (Exception e) {
            log.debug("Flyway version unavailable: {}", e.getMessage());
            return "—";
        }
    }

    private String hikariPoolInfo() {
        try {
            if (dataSource instanceof HikariDataSource hds) {
                return "max=" + hds.getMaximumPoolSize()
                     + "  min-idle=" + hds.getMinimumIdle()
                     + "  pool=" + hds.getPoolName();
            }
        } catch (Exception e) {
            log.debug("HikariCP info unavailable: {}", e.getMessage());
        }
        return "—";
    }

    private String activeUsersCount() {
        try {
            return String.valueOf(userRepository.countActiveUsers());
        } catch (Exception e) {
            log.debug("User count unavailable: {}", e.getMessage());
            return "—";
        }
    }

    private String controllersBreakdown() {
        try {
            List<Object[]> rows = controllerRepository.countActiveGroupedByBookmaker();
            if (rows.isEmpty()) return "0";
            long total = rows.stream().mapToLong(r -> (Long) r[1]).sum();
            String detail = rows.stream()
                    .map(r -> r[0] + ":" + r[1])
                    .collect(Collectors.joining("  "));
            return total + "  " + DIM + detail + RESET;
        } catch (Exception e) {
            log.debug("Controller count unavailable: {}", e.getMessage());
            return "—";
        }
    }

    private String outboxPending() {
        try {
            long count = outboxRepository.countUnsent();
            if (count == 0) return "0 pending";
            String age = outboxRepository.findOldestUnsentCreatedAt()
                    .map(t -> Duration.between(t.toInstant(), Instant.now()).toSeconds() + "s old")
                    .orElse("?");
            return YELLOW + count + " pending  (oldest: " + age + ")" + RESET;
        } catch (Exception e) {
            log.debug("Outbox pending unavailable: {}", e.getMessage());
            return "—";
        }
    }

    private String kafkaConsumerGroups() {
        try {
            List<String> sorted = kafkaRegistry.getListenerContainers().stream()
                    .map(c -> c.getGroupId())
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            if (sorted.isEmpty()) return "—";

            // Wrap groups into lines of ~3 per line
            int count = sorted.size();
            List<String> lines = new ArrayList<>();
            String indent = " ".repeat(LABEL_W + 2 + 2); // align under value
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0 && i % 3 == 0) {
                    lines.add(line.toString());
                    line = new StringBuilder();
                }
                if (line.length() > 0) line.append("  ");
                line.append(DIM).append(sorted.get(i)).append(RESET);
            }
            if (line.length() > 0) lines.add(line.toString());

            StringBuilder sb = new StringBuilder(count + "  ");
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append("\n").append(indent);
                sb.append(lines.get(i));
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Kafka groups unavailable: {}", e.getMessage());
            return "—";
        }
    }

    private String wsPoolInfo() {
        try {
            int avail = wsPool.available();
            int total = wsPool.size();
            String status = avail == total ? GREEN + avail + "/" + total + " ready" + RESET
                                           : YELLOW + avail + "/" + total + " ready" + RESET;
            return status + "  " + DIM + "(BetBoom WS)" + RESET;
        } catch (Exception e) {
            log.debug("WS pool info unavailable: {}", e.getMessage());
            return "—";
        }
    }

    private String circuitBreakersInfo() {
        try {
            Map<?, ParserHealthService.CircuitBreakerInfo> state = parserHealth.getCurrentState();
            if (state.isEmpty()) return "—";
            long open = state.values().stream()
                    .filter(i -> i.state() == CircuitBreaker.State.OPEN
                              || i.state() == CircuitBreaker.State.HALF_OPEN
                              || i.state() == CircuitBreaker.State.FORCED_OPEN)
                    .count();
            if (open == 0) {
                return GREEN + state.size() + " × CLOSED" + RESET;
            }
            String detail = state.entrySet().stream()
                    .filter(e -> e.getValue().state() != CircuitBreaker.State.CLOSED)
                    .map(e -> e.getKey() + "=" + e.getValue().state())
                    .collect(Collectors.joining(", "));
            return YELLOW + open + " OPEN  " + RESET + DIM + "(" + detail + ")" + RESET;
        } catch (Exception e) {
            log.debug("CB info unavailable: {}", e.getMessage());
            return "—";
        }
    }

    private String vkStatus() {
        try {
            if (!vkLinkService.isEnabled()) return DIM + "disabled" + RESET;
            String groupId = env.getProperty("valui.vk.group-id", "0");
            String gid = "0".equals(groupId) || groupId.isBlank() ? "" : "  groupId=" + groupId;
            return "Long Poll" + gid + "  " + GREEN + "enabled" + RESET;
        } catch (Exception e) {
            log.debug("VK status unavailable: {}", e.getMessage());
            return "—";
        }
    }
}
