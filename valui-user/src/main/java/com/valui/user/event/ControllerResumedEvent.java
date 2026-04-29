package com.valui.user.event;

import java.util.UUID;

public record ControllerResumedEvent(UUID controllerId, UUID userId, int pollIntervalSec) {}
