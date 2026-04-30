package com.valui.bot.handler.callback;

import com.valui.bot.config.BotWizardProperties;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.menu.BookmakerMenuBuilder;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ControllerByBookmakerCallback implements CallbackHandler {

    private final ControllerService   controllerService;
    private final BotWizardProperties wizardProps;

    private static final String PREFIX = "CTRL:BK:";

    @Override
    public String callbackPrefix() { return PREFIX; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data      = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        String remainder = data.substring(PREFIX.length()); // "LIST" | "XBET" | "XBET:PAGE:1"

        List<ControllerDto> all = ctx.isGroupChat()
            ? controllerService.getGroupControllers(ctx.chatId())
            : controllerService.getUserControllersForChat(ctx.fromId(), ctx.chatId());

        if (CallbackData.CTRL_BK_LIST.equals(data)) {
            var menu = BookmakerMenuBuilder.buildSelection(all);
            MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
                menu.text(), menu.keyboard());
            return;
        }

        // parse bookmaker and optional page
        String bm;
        int page;
        if (remainder.contains(":PAGE:")) {
            bm   = remainder.substring(0, remainder.indexOf(":PAGE:"));
            page = parsePage(remainder.substring(remainder.lastIndexOf(':') + 1));
        } else {
            bm   = remainder;
            page = 0;
        }

        List<ControllerDto> filtered = all.stream()
            .filter(c -> bm.equalsIgnoreCase(c.bookmaker()))
            .toList();

        var menu = BookmakerMenuBuilder.buildControllerList(bm, filtered, page, wizardProps.getStaleThresholdDays());
        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            menu.text(), menu.keyboard());
    }

    private static int parsePage(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
