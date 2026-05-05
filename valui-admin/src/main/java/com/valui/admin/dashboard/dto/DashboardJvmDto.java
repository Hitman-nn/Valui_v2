package com.valui.admin.dashboard.dto;

public record DashboardJvmDto(
        long heapUsedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        long threadsLive,
        long uptimeSeconds,
        double cpuUsagePct,
        double httpRps,
        double errorRate5xxPct,
        double diskFreeBytes,
        double diskTotalBytes
) {}
