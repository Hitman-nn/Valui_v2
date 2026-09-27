package com.valui.user.event;

import com.valui.common.domain.BookmakerType;

import java.util.UUID;

public record ControllerResumedEvent(UUID controllerId, UUID userId, int pollIntervalSec, BookmakerType bookmaker) {}
