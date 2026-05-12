package com.valui.admin.events;

import com.valui.admin.events.dto.AdminEventDto;
import com.valui.admin.events.dto.EventStatsDto;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.DetectedEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Admin — Events", description = "Управление обнаруженными событиями (только ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/events")
@RequiredArgsConstructor
public class AdminEventsController {

    private final DetectedEventRepository     eventRepository;
    private final ControllerRepository        controllerRepository;
    private final DetectedEventCleanupService cleanupService;

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Список событий с пагинацией")
    public ResponseEntity<Page<AdminEventDto>> listEvents(
            @RequestParam(required = false) UUID controllerId,
            @ParameterObject @PageableDefault(size = 25, sort = "detectedAt") Pageable pageable) {

        Page<DetectedEventEntity> page = controllerId != null
            ? eventRepository.findByControllerIdOrderByDetectedAtDesc(controllerId, pageable)
            : eventRepository.findAll(pageable);

        return ResponseEntity.ok(page.map(AdminEventDto::from));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    @Operation(summary = "Детали события")
    public ResponseEntity<AdminEventDto> getEvent(@PathVariable UUID id) {
        return eventRepository.findById(id)
            .map(e -> ResponseEntity.ok(AdminEventDto.from(e)))
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить событие")
    @Transactional
    public ResponseEntity<Void> deleteEvent(@PathVariable UUID id) {
        if (!eventRepository.existsById(id)) return ResponseEntity.notFound().build();
        eventRepository.deleteById(id);
        log.info("[EVENTS] Deleted event id={}", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/expired")
    @Operation(summary = "Удалить все истёкшие события")
    public ResponseEntity<Integer> deleteExpired() {
        OffsetDateTime threshold = OffsetDateTime.now();
        int total = 0;
        int deleted;
        do {
            deleted = cleanupService.deleteExpiredBatch(threshold, 1000);
            total += deleted;
        } while (deleted >= 1000);
        log.info("[EVENTS] Deleted {} expired events", total);
        return ResponseEntity.ok(total);
    }

    @GetMapping("/stats")
    @Operation(summary = "Статистика событий по букмекерам")
    public ResponseEntity<EventStatsDto> stats() {
        long total   = eventRepository.count();
        long expired = eventRepository.findExpiredBefore(OffsetDateTime.now()).size();

        List<EventStatsDto.BookmakerCount> byBookmaker = Arrays.stream(
            com.valui.common.domain.BookmakerType.values())
            .map(bk -> new EventStatsDto.BookmakerCount(
                bk.name(),
                controllerRepository.countByBookmakerType(bk)))
            .filter(bc -> bc.count() > 0)
            .toList();

        return ResponseEntity.ok(new EventStatsDto(total, expired, byBookmaker));
    }
}
