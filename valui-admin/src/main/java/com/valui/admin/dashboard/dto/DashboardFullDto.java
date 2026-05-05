package com.valui.admin.dashboard.dto;

import com.valui.admin.parsers.dto.BookmakerStatusDto;
import com.valui.admin.system.dto.DbPoolDto;
import com.valui.admin.system.dto.KafkaLagDto;
import com.valui.admin.system.dto.RedisInfoDto;

import java.util.List;

public record DashboardFullDto(
        DashboardSummaryDto summary,
        List<BookmakerStatusDto> parsers,
        RedisInfoDto redis,
        List<KafkaLagDto> kafka,
        DbPoolDto db,
        DashboardJvmDto jvm,
        List<ActivityPointDto> activity
) {}
