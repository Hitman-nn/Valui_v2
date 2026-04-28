package com.valui.bot.handler.callback;

import com.valui.bot.config.BotProperties;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlansSelectHandler implements CallbackHandler {

    private static final String PREFIX = "PLANS:SELECT:";

    private final BotProperties botProperties;

    @Override
    public String callbackPrefix() { return PREFIX; }

    @Override
    public int order() { return 20; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String planCode = ctx.update().getCallbackQuery().getData()
            .substring(PREFIX.length());

        String paymentUrl = botProperties.paymentBaseUrl()
            + "/upgrade?plan=" + planCode + "&chat_id=" + ctx.fromId();

        log.info("Payment upgrade requested: fromId={} plan={}", ctx.fromId(), planCode);

        var keyboard = InlineKeyboardBuilder.create()
            .urlButton("💳 Перейти к оплате →", paymentUrl)
            .row()
            .backButton(CallbackData.PLANS_VIEW)
            .build();

        MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(),
            "💳 Оплата подписки *" + planCode + "*\n\n" +
            "Нажмите кнопку для перехода на страницу оплаты.",
            keyboard);
    }
}
