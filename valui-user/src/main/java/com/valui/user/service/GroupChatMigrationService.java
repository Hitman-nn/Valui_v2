package com.valui.user.service;

import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.ControllerSubscriptionRepository;
import com.valui.user.repository.GlobalFilterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles Telegram basic-group → supergroup migration.
 *
 * When Telegram upgrades a basic group to a supergroup the chat ID changes
 * (e.g. -12345678 → -1001012345678). Every table that stores notification_chat_id
 * or chat_id must be updated so existing controllers, subscriptions and filters
 * remain reachable under the new ID.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatMigrationService {

    private final ControllerRepository             controllerRepository;
    private final ControllerSubscriptionRepository subscriptionRepository;
    private final GlobalFilterRepository           globalFilterRepository;

    @Transactional
    public void migrate(Long oldChatId, Long newChatId) {
        int controllers = controllerRepository.updateNotificationChatId(oldChatId, newChatId);

        // controller_subscriptions has a compound PK (controller_id, chat_id), so we
        // cannot UPDATE chat_id in-place — we INSERT new rows then DELETE the old ones.
        int subsCopied  = subscriptionRepository.migrateSubscriptionsToNewChat(oldChatId, newChatId);
        int subsDeleted = subscriptionRepository.deleteSubscriptionsByChatId(oldChatId);

        int filters = globalFilterRepository.updateChatId(oldChatId, newChatId);

        log.info("[GROUP-MIGRATE] {} → {}: controllers={} subs copied={} deleted={} filters={}",
                oldChatId, newChatId, controllers, subsCopied, subsDeleted, filters);
    }
}
