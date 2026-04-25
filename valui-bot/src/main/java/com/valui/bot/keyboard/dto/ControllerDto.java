package com.valui.bot.keyboard.dto;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;

import java.util.UUID;

public record ControllerDto(
    UUID id,
    String title,
    BookmakerType bookmaker,
    ControllerType type,
    boolean active
) {}
