package com.valui.admin.scheduler.dto;

public record ControllerJobDto(
        String  controllerId,
        String  userId,
        int     pollIntervalSec,
        String  nextRunAt,
        String  lastStartedAt,
        String  lastFinishedAt,
        boolean inFlight,
        long    overdueSec,
        String  status,          // IDLE | IN_FLIGHT | LATE
        String  bookmaker,       // nullable — из реестра контроллеров
        String  controllerTitle, // nullable
        String  username         // nullable — имя пользователя-владельца
) {}
