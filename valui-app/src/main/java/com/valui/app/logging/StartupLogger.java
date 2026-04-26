package com.valui.app.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StartupLogger {

    private static final String CYAN  = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String RESET = "\033[0m";
    private static final String BOLD  = "\033[1m";
    private static final String LINE  = "═".repeat(60);

    private final Environment env;

    public StartupLogger(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String port    = env.getProperty("server.port", "8080");
        String profile = String.join(", ", env.getActiveProfiles());
        String kafka   = env.getProperty("spring.kafka.bootstrap-servers", "—");
        String redis   = env.getProperty("spring.data.redis.host", "localhost")
                       + ":" + env.getProperty("spring.data.redis.port", "6379");
        String db      = env.getProperty("spring.datasource.url", "—");
        String botUser = env.getProperty("valui.bot.username", "—");
        String botMode = env.getProperty("valui.bot.mode", "long_polling");

        log.info("\n" + CYAN + BOLD + LINE + RESET
            + "\n" + CYAN + BOLD + "  ✅  VALUI READY" + RESET
            + "\n" + CYAN + LINE + RESET
            + "\n  " + GREEN + "Профиль  " + RESET + profile
            + "\n  " + GREEN + "Порт     " + RESET + port
            + "\n  " + GREEN + "БД       " + RESET + db
            + "\n  " + GREEN + "Redis    " + RESET + redis
            + "\n  " + GREEN + "Kafka    " + RESET + kafka
            + "\n  " + GREEN + "Telegram " + RESET + "@" + botUser + " [" + botMode + "]"
            + "\n  " + GREEN + "Swagger  " + RESET + "http://localhost:" + port + "/swagger-ui.html"
            + "\n" + CYAN + LINE + RESET);
    }

    /** Prints a visible phase separator — call from @PostConstruct in key components. */
    public static void phase(org.slf4j.Logger logger, String phaseName) {
        logger.info(CYAN + "── " + phaseName + " " + "─".repeat(Math.max(0, 50 - phaseName.length())) + RESET);
    }
}
