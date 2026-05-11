package com.valui.monitor.migration.dto;

import java.util.Map;

public record DryRunResultDto(
        int toImport,
        int toSkip,
        int toFail,
        Map<String, Integer> byBookmaker
) {}
