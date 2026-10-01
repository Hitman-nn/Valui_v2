package com.valui.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@SpringBootApplication(
    scanBasePackages = "com.valui",
    exclude = UserDetailsServiceAutoConfiguration.class
)
@ConfigurationPropertiesScan("com.valui")
public class ValuiApplication {

    public static void main(String[] args) {
        Instant startedAt = Instant.now();

        // Safety net for any thread that escapes without its own try/catch — DrrDispatcher's
        // worker pool, FonbetEndpointPool's probe threads etc. already wrap their own work, but
        // this exists for whatever the NEXT one is that doesn't. Without it, an uncaught
        // exception on a raw thread just prints to stderr via the JVM's built-in default
        // handler — captured by journald, but never through logback, so it's invisible to
        // anything that greps the structured valui-app.log file specifically (which is most
        // post-incident analysis, including everything done to diagnose the 30.09 incident).
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) ->
                log.error("[FATAL] Uncaught exception on thread '{}' (daemon={}) — this thread is now dead",
                        thread.getName(), thread.isDaemon(), ex));

        // 30.09 incident: figuring out WHEN and THAT a shutdown was even triggered required
        // inferring it from the first incidental Spring-internal log line (Kafka listener
        // containers stopping) — there was no explicit "we are shutting down" marker anywhere.
        // Registered directly via Runtime (not a Spring @PreDestroy/ContextClosedEvent, which
        // only fire once bean destruction is already underway) so this is one of the very first
        // things to run the moment SIGTERM/systemctl stop arrives — before any bean starts
        // closing, not after.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Duration uptime = Duration.between(startedAt, Instant.now());
            String message = String.format("[SHUTDOWN] Termination signal received — uptime was %dh%dm%ds",
                    uptime.toHours(), uptime.toMinutesPart(), uptime.toSecondsPart());
            // JVM shutdown hooks run concurrently with no defined relative order (see
            // Runtime.addShutdownHook javadoc) — Spring Boot registers its own hook that closes
            // the ApplicationContext, and LoggingApplicationListener tears down Logback as part of
            // that close. If that wins the race against this hook, log.warn below is silently
            // swallowed (no exception) — confirmed in production: this line was missing from
            // valui-app.log for a real restart even though the rest of the shutdown sequence
            // logged normally. System.err bypasses Logback entirely, so it always reaches
            // journald/the console regardless of which hook finishes first.
            System.err.println(Instant.now() + " " + message);
            log.warn(message);
        }, "shutdown-signal-logger"));

        SpringApplication.run(ValuiApplication.class, args);
    }
}
