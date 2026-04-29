package com.valui.user.event;

import java.util.UUID;

public record ControllerSuspendedEvent(UUID controllerId) {}
