package com.valui.admin.dashboard.dto;

public record JvmDataPointDto(
        long ts,        // epoch millis
        long heapMb,
        long heapMaxMb,
        long nonHeapMb,
        int  threads,
        double cpu      // 0–100
) {}
