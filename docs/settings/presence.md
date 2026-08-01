# Online Presence (§7)

**[B] Backend-owned.** Package `settings.presence`. Presence itself already lives
in Redis (the existing `chat.service.PresenceService` — TTL keys, per-viewer
block + last-seen enforcement). This module adds the **three-way policy** the
spec asks for and the **reciprocity** rule.

## Policy

`user_presence_policy (user_id, online_status_policy, last_seen_policy)` where
each policy is `EVERYONE · FRIENDS · NOBODY` (`settings.presence.enums.PresencePolicy`).

`PresencePolicyService`:
- `update(...)` persists the policy and **mirrors `NOBODY` onto the existing
  `ChatUserSettings.lastSeenVisible` boolean**, so the current hot-path
  enforcement immediately honours a full hide.
- `lastSeenVisibleTo(viewer, owner)` applies the owner's policy **plus the
  reciprocity rule**: a user who hides their own last seen may not see anyone
  else's — otherwise hiding becomes a one-way spying advantage. FRIENDS resolves
  via mutual follow (`UserFollowRepository.isFollowing` both directions).

## Two rules that matter (spec §7)

1. **Reciprocity** — enforced by `lastSeenVisibleTo` (and mirrored for read
   receipts by the existing chat settings).
2. **Never send a null-with-a-flag** — when hidden, return `lastSeen: null`, not
   the real timestamp with a "hidden" marker for the client to respect. If the
   true value crosses the wire, it is public. The existing `PresenceService`
   already returns `null` for hidden last-seen.

**Endpoints:** `GET/PUT /api/v1/settings/presence`
`{onlineStatusPolicy, lastSeenPolicy}`.

## Seam

The existing `PresenceService.presenceOf(ids, viewerId)` enforces block +
last-seen boolean. Teaching it the FRIENDS branch is a one-line call to
`PresencePolicyService.lastSeenVisibleTo(viewer, owner)`. Typing/recording
indicators and read/delivery receipts remain owned by the chat subsystem
(`ChatUserSettings`, `TypingService`) — this module does not duplicate them.
