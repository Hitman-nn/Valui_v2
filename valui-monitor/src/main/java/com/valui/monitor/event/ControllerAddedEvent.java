package com.valui.monitor.event;

import com.valui.common.domain.BookmakerType;

import java.util.UUID;

/** Published by ControllerServiceImpl after a controller is persisted. */
public record ControllerAddedEvent(
        UUID controllerId,
        UUID userId,
        Long telegramId,
        BookmakerType bookmaker,
        int pollIntervalSec
) {}
