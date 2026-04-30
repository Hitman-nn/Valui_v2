package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.common.domain.ControllerType;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ControllerDetailCallback implements CallbackHandler {

    private static final String PREFIX = "CTRL:DETAIL:";

    private final ControllerService controllerService;
    private final BotMessageSource messageSource;

    @Override public String callbackPrefix() { return PREFIX; }
    @Override public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String callbackId = ctx.update().getCallbackQuery().getId();
        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        UUID controllerId;
        try {
            controllerId = UUID.fromString(ctx.update().getCallbackQuery().getData().substring(PREFIX.length()));
        } catch (Exception e) { return; }

        ControllerDto c;
        try {
            c = controllerService.getControllerForChat(controllerId, ctx.chatId());
        } catch (Exception e) {
            log.warn("Controller not found: {}", controllerId);
            return;
        }

        MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            buildDetailText(c, ctx.chatId()),
            buildDetailKeyboard(c, ctx.fromId(), ctx.chatId()));
    }

    public static String buildDetailText(ControllerDto c, Long chatId) {
        String status;
        if (!c.isActive()) {
            status = "🔴 Остановлен";
        } else if (c.isMuted()) {
            status = "🔕 Замьючен";
        } else {
            status = "🟢 Активен";
        }
        String typeLabel = c.type() == ControllerType.SPORT ? "Все турниры" : "Турнир";

        StringBuilder sb = new StringBuilder();
        sb.append("📡 *").append(c.bookmaker()).append("*");
        if (c.title() != null && !c.title().isBlank()) sb.append(" — ").append(c.title());
        sb.append("\n");
        sb.append("Тип: ").append(typeLabel).append("\n");
        sb.append("Статус: ").append(status).append("\n");
        sb.append("📊 Событий: ").append(c.detectedEventsCount());
        if (c.type() == ControllerType.SPORT) {
            sb.append("\n🔍 Фильтр: ");
            sb.append(c.filterRule() != null && !c.filterRule().isBlank()
                ? "`" + c.filterRule() + "`" : "не задан");
        }
        return sb.toString();
    }

    public static InlineKeyboardMarkup buildDetailKeyboard(ControllerDto c, Long fromId, Long chatId) {
        boolean isGroupChat = chatId != null && chatId < 0;
        boolean isOwner = !isGroupChat
            || c.ownerTelegramId() == null
            || c.ownerTelegramId().equals(fromId);

        var builder = InlineKeyboardBuilder.create();

        if (c.isActive() && isOwner) {
            builder.button("🛑 Остановить", CallbackData.ctrlStop(c.id()));
            if (c.isMuted()) {
                builder.button("🔔 Размьютить", CallbackData.ctrlUnmute(c.id()));
            } else {
                builder.button("🔕 Замьютить", CallbackData.ctrlMute(c.id()));
            }
            builder.row();
        }

        if (c.type() == ControllerType.SPORT && isOwner) {
            builder.button("✏️ Изменить фильтр", CallbackData.ctrlFilterEdit(c.id()));
            builder.row();
        }

        builder.button("← К списку", CallbackData.ctrlByBookmaker(c.bookmaker()));
        return builder.build();
    }
}
