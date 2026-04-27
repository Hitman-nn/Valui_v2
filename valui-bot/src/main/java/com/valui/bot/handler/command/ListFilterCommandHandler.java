package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.menu.FilterMenuBuilder;
import com.valui.common.entity.GlobalFilterEntity;
import com.valui.user.service.GlobalFilterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ListFilterCommandHandler implements CommandHandler {

    private final GlobalFilterService globalFilterService;

    @Override
    public String command() { return "/listfilter"; }

    @Override
    public int order() { return 5; }

    @Override
    public void handle(BotUpdateContext ctx) {
        List<GlobalFilterEntity> filters = globalFilterService.getFilters(ctx.chatId());
        var menu = FilterMenuBuilder.build(filters);
        MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(), menu.text(), menu.keyboard());
    }
}
