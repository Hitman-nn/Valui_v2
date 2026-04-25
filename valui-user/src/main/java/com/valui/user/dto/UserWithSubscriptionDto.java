package com.valui.user.dto;

import com.valui.common.entity.SubscriptionEntity;
import com.valui.common.entity.SubscriptionPlanEntity;
import com.valui.common.entity.UserEntity;

public record UserWithSubscriptionDto(
    UserEntity user,
    SubscriptionPlanEntity plan,
    SubscriptionEntity subscription
) {}
