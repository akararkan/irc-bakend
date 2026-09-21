-- Clear-chat / delete-for-me split (2026-09-07).
--
-- The new column itself needs no DDL here: `ddl-auto: update` adds
-- conversation_members.deleted_at (nullable timestamp) on the first boot of
-- the new build. What ddl-auto CANNOT do is the backfill below, and skipping
-- it is a visible regression: under the old single-column scheme,
-- cleared_before_message_id > 0 alone meant "deleted for me", and the inbox
-- filter now keys hiding on deleted_at instead — so every conversation a user
-- ever deleted-for-me would pop back into their inbox as an empty row.
--
-- Run ONCE, after the first boot of the new build, before opening it to users:

UPDATE conversation_members
   SET deleted_at = updated_at
 WHERE cleared_before_message_id > 0
   AND deleted_at IS NULL;

-- No new index: findInbox/findArchived still scan idx_member_inbox
-- (user_id, archived); deleted_at is a residual filter on the handful of rows
-- that survive it, exactly as cleared_before_message_id was before.
