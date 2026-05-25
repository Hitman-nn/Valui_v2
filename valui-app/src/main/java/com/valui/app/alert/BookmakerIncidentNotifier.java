package com.valui.app.alert;

import com.valui.bot.listener.ParserAvailabilityRegistry;
import com.valui.common.domain.BookmakerType;
import com.valui.parser.health.ParserRecoveredEvent;
import com.valui.parser.health.ParserUnavailableEvent;
import com.valui.user.repository.ControllerSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notifies affected Telegram chats when a bookmaker parser becomes unavailable or recovers.
 * One message per chat per incident (deduplication via notifiedChats map).
 * Uses ParserAvailabilityRegistry so the bot shows ⚠️ on the BK selection keyboard
 * and returns a toast instead of processing the selection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookmakerIncidentNotifier {

    private final AbsSender                       bot;
    private final ControllerSubscriptionRepository subscriptionRepo;
    private final ParserAvailabilityRegistry       availabilityRegistry;

    // bm → set of chatIds that received "unavailable" message for the current incident
    private final ConcurrentHashMap<BookmakerType, Set<Long>> notifiedChats = new ConcurrentHashMap<>();

    @Async
    @EventListener
    public void onParserUnavailable(ParserUnavailableEvent event) {
        BookmakerType bm = event.getBookmaker();

        availabilityRegistry.markUnavailable(bm);

        // Atomic claim: only the first caller for this incident proceeds to notify chats.
        // Repeat alerts (consecutive=12,24) update the registry but skip chat notifications.
        Set<Long> slot = Collections.newSetFromMap(new ConcurrentHashMap<>());
        if (notifiedChats.putIfAbsent(bm, slot) != null) return;

        // Spring Data repository methods are @Transactional by default — no wrapper needed here
        List<Long> chatIds = subscriptionRepo.findActiveChatIdsByBookmaker(bm);
        log.info("[BK-INCIDENT] {} unavailable — notifying {} chats", bm, chatIds.size());

        String text = "⚠️ *" + bm.name() + "* временно недоступна. Мониторинг может задерживаться.";
        for (Long chatId : chatIds) {
            try {
                bot.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .parseMode("Markdown")
                        .build());
                slot.add(chatId);
            } catch (TelegramApiException e) {
                log.warn("[BK-INCIDENT] Failed to notify chatId={} bm={}: {}", chatId, bm, e.getMessage());
            }
        }
    }

    @Async
    @EventListener
    public void onParserRecovered(ParserRecoveredEvent event) {
        BookmakerType bm = event.getBookmaker();

        availabilityRegistry.markAvailable(bm);

        Set<Long> chats = notifiedChats.remove(bm);
        if (chats == null || chats.isEmpty()) return;

        log.info("[BK-INCIDENT] {} recovered — notifying {} chats", bm, chats.size());
        String text = "✅ *" + bm.name() + "* снова доступна. Мониторинг возобновлён.";
        for (Long chatId : chats) {
            try {
                bot.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .parseMode("Markdown")
                        .build());
            } catch (TelegramApiException e) {
                log.warn("[BK-INCIDENT] Failed to send recovery to chatId={} bm={}: {}", chatId, bm, e.getMessage());
            }
        }
    }
}
