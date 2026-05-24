-- Backfill vk_peer_id for subscriptions created before VK was linked.
-- For each row where vk_peer_id IS NULL, copy the most recent non-NULL peer ID
-- for the same (user_id, chat_id) combination (same logic as createSubscription).
UPDATE controller_subscriptions cs
SET vk_peer_id = (
    SELECT cs2.vk_peer_id
    FROM controller_subscriptions cs2
    WHERE cs2.user_id  = cs.user_id
      AND cs2.chat_id  = cs.chat_id
      AND cs2.vk_peer_id IS NOT NULL
    ORDER BY cs2.created_at DESC
    LIMIT 1
)
WHERE cs.vk_peer_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM controller_subscriptions cs3
    WHERE cs3.user_id  = cs.user_id
      AND cs3.chat_id  = cs.chat_id
      AND cs3.vk_peer_id IS NOT NULL
  );
