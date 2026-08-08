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

        // controller_subscriptions has a compound PK (controller_id, chat_id), so we cannot
        // UPDATE chat_id in-place — INSERT new rows then DELETE only the ones that were copied.
        int subsCopied  = subscriptionRepository.migrateSubscriptionsToNewChat(oldChatId, newChatId);
        int subsDeleted = subscriptionRepository.deleteOldSubscriptionsAfterMigration(oldChatId, newChatId);

        int filters = globalFilterRepository.updateChatId(oldChatId, newChatId);

        // chat_members stores group participants for the betting-journal picker (no JPA entity).
        int membersCopied  = subscriptionRepository.migrateChatMembersToNewChat(oldChatId, newChatId);
        int membersDeleted = subscriptionRepository.deleteOldChatMembersAfterMigration(oldChatId, newChatId);

        if (controllers == 0 && subsCopied == 0 && filters == 0 && membersCopied == 0) {
            // Every migrated-row count is zero — either this chat had nothing to migrate
            // (plausible) or oldChatId was wrong / the chat was already migrated (a real
            // problem). INFO would bury this as if it were a routine success.
            log.warn("[GROUP-MIGRATE] {} → {}: nothing migrated (chat may already be migrated, " +
                    "or oldChatId incorrect)", oldChatId, newChatId);
        } else {
            log.info("[GROUP-MIGRATE] {} → {}: controllers={} subs={}/{} filters={} members={}/{}",
                    oldChatId, newChatId, controllers,
                    subsCopied, subsDeleted, filters,
                    membersCopied, membersDeleted);
        }
    }
}
