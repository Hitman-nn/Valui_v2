package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.keyboard.PlanComparisonMessage;
import com.valui.user.dto.SubscriptionPlanDto;
import com.valui.user.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlansViewHandler implements CallbackHandler {

    private final SubscriptionService subscriptionService;

    @Override
    public String callbackPrefix() { return CallbackData.PLANS_VIEW; }

    @Override
    public int order() { return 20; }

    @Override
    public void handle(BotUpdateContext ctx) {
        var cq = ctx.update().getCallbackQuery();
        int messageId = cq.getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), cq.getId());

        List<SubscriptionPlanDto> plans = subscriptionService.getAllActivePlans();

        var builder = InlineKeyboardBuilder.create();
        for (SubscriptionPlanDto plan : plans) {
            boolean isPaid = plan.priceRub() != null
                && plan.priceRub().compareTo(BigDecimal.ZERO) > 0;
            if (isPaid) {
                builder.button("🔼 Перейти на " + plan.name(),
                    CallbackData.plansSelect(plan.code()));
                builder.row();
            }
        }
        builder.backButton(CallbackData.MENU_MAIN);

        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            PlanComparisonMessage.build(plans),
            builder.build());
    }
}
