package com.valui.monitor.scheduler;

import com.valui.monitor.config.MonitorProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists {@link MonitorProperties} overrides in the {@code scheduler_config} table.
 *
 * <p>Spring guarantees that this bean's {@link #loadFromDb()} runs <em>before</em>
 * {@link MonitorScheduler#init()} because {@code MonitorScheduler} declares this as a
 * constructor dependency — the lifecycle order follows the dependency graph.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerConfigStore {

    private final JdbcTemplate      jdbc;
    private final MonitorProperties props;

    @PostConstruct
    void loadFromDb() {
        try {
            jdbc.query("SELECT key, value FROM scheduler_config", rs -> {
                apply(rs.getString("key"), rs.getString("value"));
            });
            log.info("[SchedulerConfigStore] Config loaded from DB");
        } catch (Exception e) {
            log.warn("[SchedulerConfigStore] Could not load config from DB (using defaults): {}", e.getMessage());
        }
    }

    @Transactional
    public void save() {
        upsert("maxConcurrentTasks",     String.valueOf(props.getMaxConcurrentTasks()));
        upsert("defaultPollIntervalSec", String.valueOf(props.getDefaultPollIntervalSec()));
        upsert("fetchBudgetMs",          String.valueOf(props.getFetchBudgetMs()));
        upsert("deferBaseMs",            String.valueOf(props.getDeferBaseMs()));
        upsert("deferJitterMs",          String.valueOf(props.getDeferJitterMs()));
        upsert("defaultUserWeight",      String.valueOf(props.getDefaultUserWeight()));
        log.info("[SchedulerConfigStore] Config saved to DB");
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void upsert(String key, String value) {
        jdbc.update("""
                INSERT INTO scheduler_config (key, value, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (key) DO UPDATE
                  SET value = EXCLUDED.value, updated_at = now()
                """, key, value);
    }

    private void apply(String key, String value) {
        try {
            switch (key) {
                case "maxConcurrentTasks"     -> props.setMaxConcurrentTasks(Integer.parseInt(value));
                case "defaultPollIntervalSec" -> props.setDefaultPollIntervalSec(Integer.parseInt(value));
                case "fetchBudgetMs"          -> props.setFetchBudgetMs(Integer.parseInt(value));
                case "deferBaseMs"            -> props.setDeferBaseMs(Integer.parseInt(value));
                case "deferJitterMs"          -> props.setDeferJitterMs(Integer.parseInt(value));
                case "defaultUserWeight"      -> props.setDefaultUserWeight(Integer.parseInt(value));
            }
        } catch (NumberFormatException e) {
            log.warn("[SchedulerConfigStore] Skipping invalid config entry {}={}: {}", key, value, e.getMessage());
        }
    }
}
