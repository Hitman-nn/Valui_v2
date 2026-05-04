package com.valui.admin.monitoring.dto;

import com.valui.common.domain.ControllerType;
import com.valui.monitor.dto.ControllerDto;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.UUID;

/**
 * HATEOAS-aware projection of {@link ControllerDto} for REST responses.
 * Extends {@link RepresentationModel} so _links are included in JSON.
 */
@Schema(description = "Контроллер мониторинга букмекера")
@Relation(collectionRelation = "controllers", itemRelation = "controller")
public final class ControllerApiDto extends RepresentationModel<ControllerApiDto> {

    @Schema(description = "UUID контроллера")
    public final UUID id;

    @Schema(description = "Код букмекера", example = "XBET")
    public final String bookmaker;

    @Schema(description = "Отслеживаемый URL", example = "https://1xbet.kz/line/football/123")
    public final String url;

    @Schema(description = "Название контроллера", example = "Лига чемпионов")
    public final String title;

    @Schema(description = "Regex-фильтр событий", example = "Реал|Барса", nullable = true)
    public final String filterRule;

    @Schema(description = "Уведомления заглушены")
    public final boolean isMuted;

    @Schema(description = "Контроллер активен")
    public final boolean isActive;

    @Schema(description = "Время последней проверки")
    public final Instant lastCheckedAt;

    @Schema(description = "Время последнего обнаруженного события")
    public final Instant lastEventAt;

    @Schema(description = "Количество обнаруженных событий", example = "42")
    public final int detectedEventsCount;

    @Schema(description = "Тип контроллера")
    public final ControllerType type;

    @Schema(description = "Telegram Chat ID для уведомлений", example = "-1001234567890")
    public final Long notificationChatId;

    @Schema(description = "Telegram ID владельца", example = "123456789")
    public final Long ownerTelegramId;

    public ControllerApiDto(ControllerDto dto) {
        this.id                  = dto.id();
        this.bookmaker           = dto.bookmaker();
        this.url                 = dto.url();
        this.title               = dto.title();
        this.filterRule          = dto.filterRule();
        this.isMuted             = dto.isMuted();
        this.isActive            = dto.isActive();
        this.lastCheckedAt       = dto.lastCheckedAt();
        this.lastEventAt         = dto.lastEventAt();
        this.detectedEventsCount = dto.detectedEventsCount();
        this.type                = dto.type();
        this.notificationChatId  = dto.notificationChatId();
        this.ownerTelegramId     = dto.ownerTelegramId();
    }
}
