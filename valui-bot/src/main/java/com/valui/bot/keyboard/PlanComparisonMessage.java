package com.valui.bot.keyboard;

import com.valui.user.dto.SubscriptionPlanDto;

import java.math.BigDecimal;
import java.util.List;

public final class PlanComparisonMessage {

    private PlanComparisonMessage() {}

    public static String build(List<SubscriptionPlanDto> plans) {
        if (plans.isEmpty()) {
            return "💳 Планы подписки недоступны.";
        }

        var sb = new StringBuilder("💳 *Тарифы Valui*\n");

        for (SubscriptionPlanDto plan : plans) {
            sb.append("\n─────────────────────────\n");
            sb.append(planEmoji(plan)).append(" *").append(plan.name()).append("*\n");

            if (plan.monthlyTokenGrant() > 0) {
                sb.append("🪙 ").append(plan.monthlyTokenGrant()).append(" токенов/мес\n");
            } else {
                sb.append("🪙 Токены не начисляются\n");
            }

            sb.append("⏱ Интервал: ").append(plan.pollIntervalSec()).append(" сек\n");

            if (plan.topupDiscountPct() > 0) {
                sb.append("🎁 Скидка на пополнение: ").append(plan.topupDiscountPct()).append("%\n");
            }

            sb.append("💰 ").append(formatPrice(plan.priceRub()));
        }

        return sb.toString();
    }

    private static String planEmoji(SubscriptionPlanDto plan) {
        if (plan.priceRub() == null || plan.priceRub().compareTo(BigDecimal.ZERO) == 0) return "🆓";
        if (plan.priceRub().compareTo(new BigDecimal("500")) < 0) return "⭐";
        return "👑";
    }

    private static String formatPrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) return "Бесплатно";
        return price.stripTrailingZeros().toPlainString() + " ₽/мес";
    }
}
