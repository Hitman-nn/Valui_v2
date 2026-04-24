package BotValui;

import BotValui.Service.fonbet.FonbetCache;
import BotValui.Service.xstavka.Parser1XStavkaAPI;
import BotValui.Service.ThreadPoolManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

@Slf4j
@SpringBootApplication
public class Main {
    // Список обязательных переменных окружения
    private static final String[] REQUIRED_ENV_VARS = {
            "TIMEWEB_API_TOKEN",
            "PROXY_ENABLED"
    };

    public static void main(String[] args) {
        checkEnvironment();
        SpringApplication.run(Main.class, args);
    }

    /**
     * Проверка обязательных переменных окружения
     */
    private static void checkEnvironment() {
        log.info("Checking environment variables...");
        Map<String, String> env = System.getenv();

        for (String varName : REQUIRED_ENV_VARS) {
            if (!env.containsKey(varName)) {
                String errorMsg = String.format("Missing required environment variable: %s", varName);
                log.error(errorMsg);
                throw new IllegalStateException(errorMsg);
            }
            log.info("Env var {} is set: {}", varName, maskSensitiveValue(varName, env.get(varName)));
        }
    }

    /**
     * Маскировка чувствительных данных в логах
     */
    private static String maskSensitiveValue(String varName, String value) {
        if (varName.contains("TOKEN") || varName.contains("PASSWORD") || varName.contains("SECRET")) {
            return value.substring(0, Math.min(2, value.length())) + "*****";
        }
        return value;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing application...");
        logApplicationInfo();
        initProxy();
        initThreadPool();
        initFonbetCache();
    }

    /**
     * Логирование информации о приложении и окружении
     */
    private void logApplicationInfo() {
        log.info("Java version: {}", System.getProperty("java.version"));
        log.info("OS: {} {}", System.getProperty("os.name"), System.getProperty("os.version"));
        log.info("Working directory: {}", System.getProperty("user.dir"));
    }

    private void initProxy() {
        log.info("Configuring proxy settings...");
        if (Boolean.parseBoolean(System.getenv("PROXY_ENABLED"))) {
            Parser1XStavkaAPI.initProxyConfig();
        } else {
            log.info("Proxy disabled by configuration");
        }
    }

    private void initThreadPool() {
        log.info("Initializing thread pool...");
        ThreadPoolManager manager = ThreadPoolManager.getInstance();
    }

    private void initFonbetCache() {
        log.info("Starting Fonbet API cache refresher...");
        FonbetCache.start();
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Shutting down application gracefully...");
        try {
            FonbetCache.stop();
            ThreadPoolManager.getInstance().shutdown();
            log.info("Thread pool shutdown completed");
        } catch (Exception e) {
            log.error("Error during shutdown", e);
        }
    }
}
