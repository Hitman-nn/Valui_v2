package com.valui.bot.keyboard;

import com.valui.user.dto.SubscriptionPlanDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds a monospace-formatted plan comparison table for Telegram Markdown.
 *
 * <pre>
 * 📋 Планы подписки
 *
 * FREE      | PRO       | PREMIUM
 * 3 ctrl    | 15 ctrl   | 100 ctrl
 * 1 фильтр  | 5 фильтров| 20 фильтров
 * 2 БК      | Все БК    | Все БК
 * 120 сек   | 60 сек    | 30 сек
 * Бесплатно | 299 ₽/мес | 799 ₽/мес
 * </pre>
 */
public final class PlanComparisonMessage {

    private static final int COL_WIDTH = 10;

    private PlanComparisonMessage() {}

    public static String build(List<SubscriptionPlanDto> plans) {
        if (plans.isEmpty()) {
            return "📋 Планы подписки недоступны.";
        }

        var sb = new StringBuilder("📋 *Планы подписки*\n\n```\n");

        sb.append(row(plans, p -> p.name())).append("\n");
        sb.append("-".repeat((COL_WIDTH + 3) * plans.size() - 3)).append("\n");
        sb.append(row(plans, p -> p.maxControllers() + " ctrl")).append("\n");
        sb.append(row(plans, p -> p.maxFilters() + " фильтр")).append("\n");
        sb.append(row(plans, p -> formatBookmakers(p))).append("\n");
        sb.append(row(plans, p -> p.pollIntervalSec() + " сек")).append("\n");
        sb.append(row(plans, p -> formatPrice(p.priceRub()))).append("\n");

        sb.append("```");
        return sb.toString();
    }

    private static String row(List<SubscriptionPlanDto> plans,
                               Function<SubscriptionPlanDto, String> extractor) {
        return plans.stream()
            .map(p -> pad(extractor.apply(p)))
            .collect(Collectors.joining(" | "));
    }

    private static String formatBookmakers(SubscriptionPlanDto plan) {
        int count = plan.allowedBookmakers().size();
        if (count == 0 || count >= 5) return "Все БК";
        return count + " БК";
    }

    private static String formatPrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) return "Бесплатно";
        return price.stripTrailingZeros().toPlainString() + " ₽/мес";
    }

    private static String pad(String s) {
        if (s == null) s = "";
        if (s.length() >= COL_WIDTH) return s.substring(0, COL_WIDTH);
        return s + " ".repeat(COL_WIDTH - s.length());
    }
}
