package com.valui.admin.parsers.dto;

import jakarta.validation.constraints.NotBlank;

public record TestParseRequest(
        @NotBlank String url
) {}
