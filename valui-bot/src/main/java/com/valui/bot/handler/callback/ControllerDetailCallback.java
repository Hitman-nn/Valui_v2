package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.betting.BettingChatResolver;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.common.domain.ControllerType;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import com.valui.user.api.DetectedEventPortService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ControllerDetailCallback implements CallbackHandler {

    private static final String PREFIX          = "CTRL:DETAIL:";
    private static final int    EVENTS_PER_PAGE = 10;

    private final ControllerService        controllerService;
    private final BotMessageSource         messageSource;
    private final DetectedEventPortService detectedEventPort;
    private final BettingChatResolver      chatResolver;

    @Override public String callbackPrefix() { return PREFIX; }
    @Override public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.answerCallback(ctx.sender(), callbackId);

        // Parse controllerId and optional events page: "{uuid}" or "{uuid}:P:{page}"
        String payload = data.substring(PREFIX.length());
        UUID controllerId;
        int eventsPage = 0;
        if (payload.contains(":P:")) {
            try {
                controllerId = UUID.fromString(payload.substring(0, payload.indexOf(":P:")));
                eventsPage   = Integer.parseInt(payload.substring(payload.lastIndexOf(':') + 1));
            } catch (Exception e) { return; }
        } else {
            try { controllerId = UUID.fromString(payload); }
            catch (Exception e) { return; }
        }

        ControllerDto c;
        try {
            c = controllerService.getControllerForChat(controllerId, chatResolver.resolveOrPhysical(ctx));
        } catch (Exception e) {
            log.warn("Controller not found: {}", controllerId);
            return;
        }

        Page<DetectedEventEntity> events = detectedEventPort.findRecentByControllerId(
                controllerId, PageRequest.of(eventsPage, EVENTS_PER_PAGE));

        String text = buildDetailText(c, ctx.chatId());
        if (events.isEmpty() && eventsPage == 0) {
            text += "\n\n_Событий пока нет_";
        }

        ctx.tracker().replaceAndTrack(ctx.sender(), ctx.chatId(), messageId,
                text, buildKeyboard(c, ctx.fromId(), allowManagement(ctx), events, eventsPage));
    }

    /**
     * Management (stop/mute/filter) stays a physical-group-only affair — but DM browsing of the
     * caller's OWN controllers (personal list, no linked group picked) has always allowed it too,
     * and that must keep working. It's only DM browsing of a linked GROUP's shared list (via
     * {@link BettingChatResolver}) that must stay read-only + bet-placing, since {@code
     * ControllerStopCallback}/{@code ControllerMuteCallback}/{@code ControllerFilterEditCallback}
     * aren't resolver-aware and would otherwise act on the wrong (personal) subscription row.
     */
    private boolean allowManagement(BotUpdateContext ctx) {
        return ctx.isGroupChat() || !chatResolver.isResolved(ctx);
    }

    // ── Keyboard with events ──────────────────────────────────────────────────

    private static InlineKeyboardMarkup buildKeyboard(ControllerDto c, Long fromId, boolean allowManagement,
                                                       Page<DetectedEventEntity> events, int eventsPage) {
        boolean isOwner = allowManagement && (c.ownerTelegramId() == null || c.ownerTelegramId().equals(fromId));

        var builder = InlineKeyboardBuilder.create();

        if (c.isActive() && isOwner) {
            builder.button("🛑 Остановить", CallbackData.ctrlStop(c.id()));
            builder.button(c.isMuted() ? "🔔 Размьютить" : "🔕 Замьютить",
                    c.isMuted() ? CallbackData.ctrlUnmute(c.id()) : CallbackData.ctrlMute(c.id()));
            builder.row();
        }
        if (c.type() == ControllerType.SPORT && isOwner) {
            builder.button("✏️ Изменить фильтр", CallbackData.ctrlFilterEdit(c.id())).row();
        }

        builder.button("← К списку", CallbackData.ctrlByBookmaker(c.bookmaker()));
        builder.button("🔍 Поиск", CallbackData.ctrlEvtSrch(c.id()));
        builder.row();

        for (DetectedEventEntity event : events.getContent()) {
            builder.button("🎯 " + truncate(event.getTitle(), 55),
                    CallbackData.ctrlEvtBet(event.getId())).row();
        }

        if (events.getTotalPages() > 1) {
            if (eventsPage > 0) {
                builder.button("‹", CallbackData.ctrlDetailPage(c.id(), eventsPage - 1));
            }
            builder.button((eventsPage + 1) + "/" + events.getTotalPages(), CallbackData.NOOP);
            if (eventsPage < events.getTotalPages() - 1) {
                builder.button("›", CallbackData.ctrlDetailPage(c.id(), eventsPage + 1));
            }
            builder.row();
        }

        return builder.build();
    }

    // ── Static helpers (used by WizardTextHandler after filter edit) ──────────

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
        sb.append("📊 Событий: ").append(c.detectedEventsCount()).append("\n");
        sb.append("🔄 Интервал: каждые ").append(c.pollIntervalSec()).append(" сек\n");
        sb.append("⏱ Последний опрос: ").append(formatLastChecked(c.lastCheckedAt()));
        if (c.type() == ControllerType.SPORT) {
            sb.append("\n🔍 Фильтр: ");
            sb.append(c.filterRule() != null && !c.filterRule().isBlank()
                ? "`" + c.filterRule() + "`" : "не задан");
        }
        return sb.toString();
    }

    /**
     * Backward-compat: keyboard WITHOUT events section (used by WizardTextHandler after filter
     * edit and by the mute/cancel follow-up screens). {@code allowManagement} — see
     * {@link #allowManagement}; callers without a {@link BotUpdateContext} handy should pass
     * {@code ctx.isGroupChat() || !chatResolver.isResolved(ctx)}.
     */
    public static InlineKeyboardMarkup buildDetailKeyboard(ControllerDto c, Long fromId, boolean allowManagement) {
        boolean isOwner = allowManagement && (c.ownerTelegramId() == null || c.ownerTelegramId().equals(fromId));

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

    private static String formatLastChecked(Instant lastCheckedAt) {
        if (lastCheckedAt == null) return "ещё не запускался";
        long secs = Duration.between(lastCheckedAt, Instant.now()).getSeconds();
        if (secs < 60)   return secs + " сек назад";
        if (secs < 3600) return (secs / 60) + " мин назад";
        return (secs / 3600) + " ч назад";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
