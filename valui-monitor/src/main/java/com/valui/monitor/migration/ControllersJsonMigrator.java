package com.valui.monitor.migration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.UserEntity;
import com.valui.parser.util.ParsedUrlIds;
import com.valui.parser.util.UrlParser;
import com.valui.user.api.ControllerPortService;
import com.valui.user.service.UserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot migration: reads legacy controllers.json, imports records into PostgreSQL,
 * then renames the source file to controllers.json.migrated.
 *
 * Idempotent: duplicate records are skipped via the unique constraint check.
 * If controllers.json is absent the component does nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControllersJsonMigrator {

    private final ControllerPortService controllerPort;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Value("${valui.migration.controllers-json-path:./controllers.json}")
    private String controllersJsonPath;

    @PostConstruct
    @Transactional
    public void migrate() {
        Path source = Paths.get(controllersJsonPath);
        if (!Files.exists(source)) {
            log.debug("controllers.json not found at {} — skipping migration", source.toAbsolutePath());
            return;
        }

        Path done = Paths.get(controllersJsonPath + ".migrated");
        if (Files.exists(done)) {
            log.info("Migration already completed (found {})", done.toAbsolutePath());
            return;
        }

        log.info("Starting controllers.json migration from {}", source.toAbsolutePath());
        int imported = 0, skipped = 0, failed = 0;

        try {
            Root root = objectMapper.readValue(source.toFile(), Root.class);
            List<LegacyController> controllers = root.controller != null ? root.controller : List.of();

            for (LegacyController lc : controllers) {
                try {
                    MigrateResult result = importOne(lc);
                    if (result == MigrateResult.IMPORTED) imported++;
                    else if (result == MigrateResult.SKIPPED) skipped++;
                } catch (Exception e) {
                    log.warn("Failed to migrate controller link={}: {}", lc.link, e.getMessage());
                    failed++;
                }
            }
        } catch (IOException e) {
            log.error("Failed to read {}: {}", source.toAbsolutePath(), e.getMessage(), e);
            return;
        }

        log.info("Migration done: imported={} skipped={} failed={}", imported, skipped, failed);

        try {
            Files.move(source, done);
            log.info("Renamed {} → {}", source.getFileName(), done.getFileName());
        } catch (IOException e) {
            log.warn("Could not rename {} after migration: {}", source.toAbsolutePath(), e.getMessage());
        }
    }

    private MigrateResult importOne(LegacyController lc) {
        if (lc.link == null || lc.link.isBlank()) {
            log.debug("Skipping controller with blank link");
            return MigrateResult.SKIPPED;
        }
        if (lc.chatid == null || lc.chatid.isBlank()) {
            log.debug("Skipping controller with blank chatid, link={}", lc.link);
            return MigrateResult.SKIPPED;
        }

        long telegramId;
        try {
            telegramId = Long.parseLong(lc.chatid.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid chatid '{}' for link={}", lc.chatid, lc.link);
            return MigrateResult.SKIPPED;
        }

        UserEntity user = userService.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            log.warn("User not found for telegramId={}, skipping link={}", telegramId, lc.link);
            return MigrateResult.SKIPPED;
        }

        BookmakerType bookmaker;
        try {
            bookmaker = UrlParser.parseBookmaker(lc.link);
        } catch (IllegalArgumentException e) {
            log.warn("Cannot detect bookmaker from link={}: {}", lc.link, e.getMessage());
            return MigrateResult.SKIPPED;
        }

        if (controllerPort.existsByUserAndBookmakerAndUrl(user.getId(), bookmaker, lc.link)) {
            log.debug("Controller already exists: userId={} bookmaker={} link={}", user.getId(), bookmaker, lc.link);
            return MigrateResult.SKIPPED;
        }

        ControllerType type = resolveType(lc.link, bookmaker);
        String filterRule = (lc.ruleFilter != null && !lc.ruleFilter.isBlank()) ? lc.ruleFilter : null;

        controllerPort.save(
                ControllerEntity.builder()
                        .user(user)
                        .bookmaker(bookmaker)
                        .url(lc.link)
                        .title(lc.title)
                        .type(type)
                        .filterRule(filterRule)
                        .isMuted(false)
                        .isActive(true)
                        .build()
        );
        log.debug("Imported controller: telegramId={} bookmaker={} link={}", telegramId, bookmaker, lc.link);
        return MigrateResult.IMPORTED;
    }

    private static ControllerType resolveType(String url, BookmakerType bookmaker) {
        try {
            ParsedUrlIds ids = UrlParser.extractIds(url, bookmaker);
            return ids.matchId() != null ? ControllerType.MATCH : ControllerType.TOURNAMENT;
        } catch (Exception e) {
            return ControllerType.TOURNAMENT;
        }
    }

    private enum MigrateResult { IMPORTED, SKIPPED }

    // ── JSON model (mirrors ControllersCleaner format) ────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Root {
        public List<String> filter = new ArrayList<>();
        public List<LegacyController> controller = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LegacyController {
        public String chatid;
        public String link;
        public String ruleFilter;
        public String title;
    }
}
