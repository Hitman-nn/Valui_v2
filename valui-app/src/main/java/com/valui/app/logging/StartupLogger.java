package com.valui.app.logging;

import com.valui.monitor.outbox.OutboxEventRepository;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
public class StartupLogger {

    private static final String CYAN   = "\033[36m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String LINE   = "═".repeat(60);

    private final Environment                    env;
    private final Flyway                         flyway;
    private final UserRepository                 userRepository;
    private final ControllerRepository           controllerRepository;
    private final DataSource                     dataSource;
    private final KafkaListenerEndpointRegistry  kafkaRegistry;
    private final OutboxEventRepository          outboxRepository;

    public StartupLogger(Environment env,
                         Flyway flyway,
                         UserRepository userRepository,
                         ControllerRepository controllerRepository,
                         DataSource dataSource,
                         KafkaListenerEndpointRegistry kafkaRegistry,
                         OutboxEventRepository outboxRepository) {
        this.env                  = env;
        this.flyway               = flyway;
        this.userRepository       = userRepository;
        this.controllerRepository = controllerRepository;
        this.dataSource           = dataSource;
        this.kafkaRegistry        = kafkaRegistry;
        this.outboxRepository     = outboxRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        String port    = env.getProperty("server.port", "8080");
        String profile = String.join(", ", env.getActiveProfiles());
        String kafka   = env.getProperty("spring.kafka.bootstrap-servers", "—");
        String redis   = env.getProperty("spring.data.redis.host", "localhost")
                       + ":" + env.getProperty("spring.data.redis.port", "6379");
        String db      = env.getProperty("spring.datasource.url", "—");
        String botUser = env.getProperty("valui.bot.username", "—");
        String botMode = env.getProperty("valui.bot.mode", "long_polling");
        String dedupTtl = env.getProperty("valui.notifications.dedup-ttl-minutes", "60");

        String startupTime = event.getTimeTaken() != null
                ? event.getTimeTaken().toSeconds() + "." +
                  String.format("%03d", event.getTimeTaken().toMillisPart()) + "s"
                : "—";

        String flywayVersion = flywayVersion();
        String poolInfo      = hikariPoolInfo();
        String usersCount    = activeUsersCount();
        String controllers   = controllersBreakdown();
        String kafkaGroups   = kafkaConsumerGroups();
        String outboxInfo    = outboxPending();

        log.info("\n" + CYAN + BOLD + LINE + RESET
            + "\n" + CYAN + BOLD + "  ✅  VALUI READY" + RESET
            + "\n" + CYAN + LINE + RESET
            + "\n  " + GREEN + "Профиль      " + RESET + profile
            + "\n  " + GREEN + "Порт         " + RESET + port
            + "\n  " + GREEN + "Старт        " + RESET + startupTime
            + "\n  " + GREEN + "БД           " + RESET + db
            + "\n  " + GREEN + "  Flyway     " + RESET + YELLOW + flywayVersion + RESET
            + "\n  " + GREEN + "  HikariCP   " + RESET + poolInfo
            + "\n  " + GREEN + "Redis        " + RESET + redis
            + "\n  " + GREEN + "Kafka        " + RESET + kafka
            + "\n  " + GREEN + "  consumer   " + RESET + kafkaGroups
            + "\n  " + GREEN + "Telegram     " + RESET + "@" + botUser + " [" + botMode + "]"
            + "\n  " + GREEN + "Пользователи " + RESET + usersCount + " активных"
            + "\n  " + GREEN + "Контроллеры  " + RESET + controllers
            + "\n  " + GREEN + "Outbox       " + RESET + outboxInfo
            + "\n  " + GREEN + "Dedup TTL    " + RESET + dedupTtl + " мин"
            + "\n  " + GREEN + "Swagger      " + RESET + "http://localhost:" + port + "/swagger-ui.html"
            + "\n" + CYAN + LINE + RESET);
    }

    /** Prints a visible phase separator — call from @PostConstruct in key components. */
    public static void phase(org.slf4j.Logger logger, String phaseName) {
        logger.info(CYAN + "── " + phaseName + " " + "─".repeat(Math.max(0, 50 - phaseName.length())) + RESET);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String flywayVersion() {
        try {
            MigrationInfo current = flyway.info().current();
            if (current == null) return "нет миграций";
            long pending = flyway.info().pending().length;
            String v = "V" + current.getVersion() + " (" + current.getDescription() + ")";
            return pending > 0 ? v + "  ⚠ pending: " + pending : v;
        } catch (Exception e) {
            log.debug("Flyway version unavailable: {}", e.getMessage());
            return "—";
        }
    }

    private String hikariPoolInfo() {
        try {
            DataSource unwrapped = dataSource;
            if (unwrapped instanceof HikariDataSource hds) {
                return "max=" + hds.getMaximumPoolSize()
                     + ", min-idle=" + hds.getMinimumIdle()
                     + ", pool=" + hds.getPoolName();
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
                    .collect(Collectors.joining(", "));
            return total + " (" + detail + ")";
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
                    .map(t -> Duration.between(t.toInstant(), Instant.now()).toSeconds() + "s ago")
                    .orElse("?");
            return YELLOW + count + " pending (oldest: " + age + ")" + RESET;
        } catch (Exception e) {
            log.debug("Outbox pending unavailable: {}", e.getMessage());
            return "—";
        }
    }

    private String kafkaConsumerGroups() {
        try {
            String groups = kafkaRegistry.getListenerContainers().stream()
                    .map(c -> c.getGroupId())
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(", "));
            return groups.isBlank() ? "—" : groups;
        } catch (Exception e) {
            log.debug("Kafka groups unavailable: {}", e.getMessage());
            return "—";
        }
    }
}
