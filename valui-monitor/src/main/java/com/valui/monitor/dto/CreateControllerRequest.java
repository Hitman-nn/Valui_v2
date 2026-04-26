package com.valui.monitor.dto;

public record CreateControllerRequest(
        String url,
        String bookmaker,  // optional: auto-detected from URL when null or blank
        String title,      // optional display name
        boolean isMuted
) {}
