package com.valui.user.dto;

import com.valui.common.entity.SubscriptionPlanEntity;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record SubscriptionPlanDto(
    UUID id,
    String code,
    String name,
    int maxControllers,
    int maxFilters,
    int pollIntervalSec,
    List<String> allowedBookmakers,
    List<String> notifyChannels,
    BigDecimal priceRub
) {
    public static SubscriptionPlanDto from(SubscriptionPlanEntity e) {
        return new SubscriptionPlanDto(
            e.getId(),
            e.getCode(),
            e.getName(),
            e.getMaxControllers(),
            e.getMaxFilters(),
            e.getPollIntervalSec(),
            toList(e.getAllowedBookmakers()),
            toList(e.getNotifyChannels()),
            e.getPriceRub()
        );
    }

    private static List<String> toList(String[] arr) {
        return arr != null ? List.copyOf(Arrays.asList(arr)) : List.of();
    }
}
