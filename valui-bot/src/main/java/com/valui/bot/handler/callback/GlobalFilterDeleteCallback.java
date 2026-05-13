package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.menu.FilterMenuBuilder;
import com.valui.common.entity.GlobalFilterEntity;
import com.valui.user.service.GlobalFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class GlobalFilterDeleteCallback implements CallbackHandler {

    private static final String PREFIX = "FILTER:DELETE:";

    private final GlobalFilterService globalFilterService;

    @Override
    public String callbackPrefix() { return PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        try {
            UUID filterId = UUID.fromString(data.substring(PREFIX.length()));
            globalFilterService.deleteFilter(ctx.fromId(), ctx.chatId(), filterId);
        } catch (Exception e) {
            log.warn("Failed to delete global filter for fromId={}: {}", ctx.fromId(), e.getMessage());
        }

        List<GlobalFilterEntity> filters = globalFilterService.getFilters(ctx.fromId(), ctx.chatId());
        var menu = FilterMenuBuilder.build(filters, ctx.chatId());
        ctx.tracker().replaceAndTrack(ctx.sender(), ctx.chatId(), messageId, menu.text(), menu.keyboard());
    }
}
