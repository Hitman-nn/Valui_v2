package com.valui.monitor.migration;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.UserEntity;
import com.valui.monitor.migration.dto.*;
import com.valui.parser.util.ParsedUrlIds;
import com.valui.parser.util.UrlParser;
import com.valui.user.api.ControllerPortService;
import com.valui.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationService {

    private final ControllerPortService     controllerPort;
    private final UserService               userService;

    // ── dry-run ───────────────────────────────────────────────────────────────

    public DryRunResultDto dryRun(MigrationRequest req) {
        Map<String, ChatMappingDto> mappings = indexMappings(req.chatMappings());

        int toImport = 0, toSkip = 0, toFail = 0;
        Map<String, Integer> byBookmaker = new LinkedHashMap<>();

        for (MigrationControllerEntry c : req.controllers()) {
            ChatMappingDto mapping = mappings.get(c.chatId());
            if (mapping == null || mapping.userId() == null) { toFail++; continue; }

            BookmakerType bk;
            try {
                bk = UrlParser.parseBookmaker(c.link());
            } catch (Exception e) {
                toFail++;
                continue;
            }

            if (controllerPort.existsByUserAndBookmakerAndUrl(mapping.userId(), bk, c.link())) {
                toSkip++;
            } else {
                toImport++;
                byBookmaker.merge(bk.name(), 1, Integer::sum);
            }
        }

        return new DryRunResultDto(toImport, toSkip, toFail, byBookmaker);
    }

    // ── execute ───────────────────────────────────────────────────────────────

    @Transactional
    public MigrationResultDto execute(MigrationRequest req) {
        Map<String, ChatMappingDto> mappings = indexMappings(req.chatMappings());

        int imported = 0, skipped = 0, failed = 0;
        Map<String, Integer> byBookmaker = new LinkedHashMap<>();

        for (MigrationControllerEntry c : req.controllers()) {
            try {
                ChatMappingDto mapping = mappings.get(c.chatId());
                if (mapping == null || mapping.userId() == null) { failed++; continue; }

                BookmakerType bk;
                try {
                    bk = UrlParser.parseBookmaker(c.link());
                } catch (Exception e) {
                    log.debug("[MIGRATION] Неизвестный букмекер, пропуск: {}", c.link());
                    failed++;
                    continue;
                }

                if (controllerPort.existsByUserAndBookmakerAndUrl(mapping.userId(), bk, c.link())) {
                    skipped++;
                    continue;
                }

                UserEntity user = userService.findById(mapping.userId());

                String filterRule = (c.ruleFilter() != null && !c.ruleFilter().isBlank())
                        ? c.ruleFilter() : null;

                ControllerEntity entity = controllerPort.save(
                        ControllerEntity.builder()
                                .user(user)
                                .bookmaker(bk)
                                .url(c.link())
                                .title(c.title())
                                .type(resolveType(c.link(), bk))
                                .filterRule(filterRule)
                                .notificationChatId(mapping.notificationChatId())
                                .pollIntervalSec(req.pollIntervalSec())
                                .isMuted(false)
                                .isActive(true)
                                .build()
                );

                Long chatId = mapping.notificationChatId() != null
                        ? mapping.notificationChatId() : user.getTelegramId();
                controllerPort.createSubscription(
                        entity.getId(), chatId, user.getId(), user.getTelegramId());

                // eventIds field is accepted but intentionally ignored:
                // lastCheckedAt=null guarantees a warmup run that silently seeds both
                // Redis dedup and detected_events without sending notifications.
                // Manual Redis seeding (markBatchAsSeen without DB rows) caused the
                // DedupSyncScheduler to treat those entries as phantoms and remove them
                // at 3 AM, triggering a flood on the next poll.

                imported++;
                byBookmaker.merge(bk.name(), 1, Integer::sum);

            } catch (Exception e) {
                log.warn("[MIGRATION] Ошибка импорта {}: {}", c.link(), e.getMessage());
                failed++;
            }
        }

        log.info("[MIGRATION] Завершено: импортировано={} пропущено={} ошибок={}",
                imported, skipped, failed);
        return new MigrationResultDto(imported, skipped, failed, 0, byBookmaker);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ControllerType resolveType(String url, BookmakerType bookmaker) {
        try {
            ParsedUrlIds ids = UrlParser.extractIds(url, bookmaker);
            return ids.matchId() != null ? ControllerType.MATCH : ControllerType.TOURNAMENT;
        } catch (Exception e) {
            return ControllerType.TOURNAMENT;
        }
    }

    private Map<String, ChatMappingDto> indexMappings(List<ChatMappingDto> list) {
        if (list == null) return Map.of();
        return list.stream().collect(
                Collectors.toMap(ChatMappingDto::chatId, m -> m, (a, b) -> b));
    }
}
