package com.valui.monitor.service;

import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.dto.CreateControllerRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ControllerService {

    /**
     * Adds a controller for {@code telegramId} with notifications sent to {@code notificationChatId}.
     * {@code notificationChatId} is the originating Telegram chat (personal or group).
     */
    ControllerDto addController(CreateControllerRequest req, Long telegramId, Long notificationChatId);

    /** Convenience overload for private chat — notificationChatId defaults to the user's own chat. */
    default ControllerDto addController(CreateControllerRequest req, Long telegramId) {
        return addController(req, telegramId, telegramId);
    }

    void removeController(UUID controllerId, Long telegramId);

    ControllerDto getController(UUID controllerId);

    List<ControllerDto> getUserControllers(Long telegramId);

    /**
     * Like {@link #getUserControllers} but with {@code isMuted} reflecting the subscription
     * state for {@code chatId} instead of the entity-level flag.
     * Use this for list views so muted controllers show 🔕 correctly.
     */
    List<ControllerDto> getUserControllersForChat(Long telegramId, Long chatId);

    /** Returns active controllers whose notifications are routed to the given group chat. */
    List<ControllerDto> getGroupControllers(Long notificationChatId);

    Page<ControllerDto> getUserControllers(Long telegramId, Pageable pageable);

    void muteController(UUID controllerId, Long telegramId);

    void unmuteController(UUID controllerId, Long telegramId);

    ControllerDto updateFilterRule(UUID controllerId, Long telegramId, String rule);

    /** Admin / system use: deactivates the controller without ownership check. */
    void deactivateController(UUID controllerId);

    /**
     * Mutes notifications for chatId (reversible — unmute restores them).
     * If no active subscriptions remain, the controller is unscheduled and reset for warmup.
     */
    void muteForChat(UUID controllerId, Long telegramId, Long chatId);

    /**
     * Unmutes notifications for chatId.
     * If the controller was suspended (unscheduled), it is rescheduled with warmup.
     */
    void unmuteForChat(UUID controllerId, Long telegramId, Long chatId);

    /**
     * Permanently removes the subscription for chatId (irreversible — user must re-add).
     * If no subscriptions remain, the controller is fully deactivated.
     */
    void stopForChat(UUID controllerId, Long telegramId, Long chatId);

    /** Returns the controller DTO with isMuted reflecting the subscription state for chatId. */
    ControllerDto getControllerForChat(UUID controllerId, Long chatId);
}
