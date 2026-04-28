package com.valui.user.dto;

import com.valui.common.entity.SubscriptionPlanEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    BigDecimal priceRub,
    int tokenReward
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
            e.getPriceRub(),
            e.getTokenReward() != null ? e.getTokenReward() : 0
        );
    }

    private static List<String> toList(String[] arr) {
        // ArrayList: Jackson can reconstruct this via @class; List.copyOf / List.of return
        // package-private JDK internal types (ImmutableCollections$*) that Jackson cannot instantiate.
        return arr != null ? new ArrayList<>(Arrays.asList(arr)) : new ArrayList<>();
    }
}
