package com.valui.bot.handler.callback;

import com.valui.bot.config.BotProperties;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.menu.ControllerMenuBuilder;
import com.valui.bot.service.ControllerSortPreferenceService;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ControllerListCallback implements CallbackHandler {

    private final ControllerService             controllerService;
    private final BotProperties                 botProperties;
    private final ControllerSortPreferenceService sortPreference;

    @Override
    public String callbackPrefix() { return CallbackData.CTRL_LIST; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId     = ctx.update().getCallbackQuery().getMessage().getMessageId();
        long chatId       = ctx.chatId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        int    page;
        String sort;

        if (data.contains(":SORT:")) {
            // CTRL:LIST:SORT:DATE or CTRL:LIST:SORT:NAME — switch sort, reset to page 0
            sort = data.substring(data.lastIndexOf(':') + 1).toUpperCase();
            sortPreference.save(chatId, sort);
            page = 0;
        } else {
            sort = sortPreference.load(chatId);
            page = 0;
            if (data.contains(":PAGE:")) {
                try { page = Integer.parseInt(data.substring(data.lastIndexOf(':') + 1)); }
                catch (NumberFormatException ignored) {}
            }
        }

        List<ControllerDto> controllers = ctx.isGroupChat()
            ? controllerService.getGroupControllers(chatId)
            : controllerService.getUserControllersForChat(ctx.fromId(), chatId);

        var menu = ControllerMenuBuilder.build(controllers, page, botProperties.staleThresholdDays(), sort);
        ctx.tracker().replaceAndTrack(ctx.sender(), chatId, messageId, menu.text(), menu.keyboard());
    }
}
