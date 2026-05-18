package com.valui.bot.handler.callback;

import com.valui.bot.config.BotProperties;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.menu.BookmakerMenuBuilder;
import com.valui.bot.keyboard.menu.ControllerMenuBuilder;
import com.valui.bot.service.ControllerSortPreferenceService;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ControllerByBookmakerCallback implements CallbackHandler {

    private static final String PREFIX = "CTRL:BK:";

    private final ControllerService             controllerService;
    private final BotProperties                 botProperties;
    private final ControllerSortPreferenceService sortPreference;

    @Override
    public String callbackPrefix() { return PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();
        long   chatId     = ctx.chatId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        String remainder = data.substring(PREFIX.length()); // "LIST" | "XBET" | "XBET:PAGE:1" | "XBET:SORT:NAME"

        List<ControllerDto> all = ctx.isGroupChat()
            ? controllerService.getGroupControllers(chatId)
            : controllerService.getUserControllersForChat(ctx.fromId(), chatId);

        if (CallbackData.CTRL_BK_LIST.equals(data)) {
            var menu = BookmakerMenuBuilder.buildSelection(all);
            ctx.tracker().replaceAndTrack(ctx.sender(), chatId, messageId,
                menu.text(), menu.keyboard());
            return;
        }

        // parse bookmaker, optional page, optional sort
        String bm;
        int    page;
        String sort;

        if (remainder.contains(":SORT:")) {
            bm   = remainder.substring(0, remainder.indexOf(":SORT:"));
            sort = remainder.substring(remainder.lastIndexOf(':') + 1).toUpperCase();
            sortPreference.save(chatId, sort);
            page = 0;
        } else if (remainder.contains(":PAGE:")) {
            bm   = remainder.substring(0, remainder.indexOf(":PAGE:"));
            page = parsePage(remainder.substring(remainder.lastIndexOf(':') + 1));
            sort = sortPreference.load(chatId);
        } else {
            bm   = remainder;
            page = 0;
            sort = sortPreference.load(chatId);
        }

        List<ControllerDto> filtered = all.stream()
            .filter(c -> bm.equalsIgnoreCase(c.bookmaker()))
            .toList();

        var menu = BookmakerMenuBuilder.buildControllerList(
                bm, filtered, page, botProperties.staleThresholdDays(), sort);
        ctx.tracker().replaceAndTrack(ctx.sender(), chatId, messageId,
            menu.text(), menu.keyboard());
    }

    private static int parsePage(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
