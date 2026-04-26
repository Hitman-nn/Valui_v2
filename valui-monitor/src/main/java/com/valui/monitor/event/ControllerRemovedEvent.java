package com.valui.monitor.event;

import java.util.UUID;

/** Published by ControllerServiceImpl when a controller is deactivated. */
public record ControllerRemovedEvent(UUID controllerId, UUID userId) {}
