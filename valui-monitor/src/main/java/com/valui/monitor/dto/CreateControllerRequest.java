package com.valui.monitor.dto;

import com.valui.common.domain.ControllerType;

public record CreateControllerRequest(
        String url,
        String bookmaker,       // optional: auto-detected from URL when null or blank
        String title,           // optional display name
        boolean isMuted,
        ControllerType typeHint // optional: explicit type override (null = auto-detect)
) {
    public CreateControllerRequest(String url, String bookmaker, String title, boolean isMuted) {
        this(url, bookmaker, title, isMuted, null);
    }
}
