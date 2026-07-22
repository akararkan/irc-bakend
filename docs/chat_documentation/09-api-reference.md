# 09 — API Reference (as built)

The complete, implemented endpoint contract for the chat module
(`ak.dev.irc.app.chat`). This is the *as-built* reference — it reflects the
actual controllers, DTOs, permission rules, and realtime events that ship, and
supersedes the design sketch in [07-api-surface.md](07-api-surface.md) wherever
they differ.

---

## Conventions

| Aspect | Rule |
|--------|------|
| **Base path** | `/api/v1` |
| **Auth** | `Authorization: Bearer <accessToken>` on every endpoint. SSE also accepts `?token=<accessToken>` (EventSource can't set headers). Enforced by `@PreAuthorize` (secure-by-default; see `app.security.permit-all`). |
| **Errors** | The shared `ApiErrorResponse` envelope: `{ timestamp, status, error, message, path, errorCode, details, fieldErrors, traceId }`. Switch on `errorCode`. |
| **IDs** | `conversationId`, `userId`, `messageRequestId` are **UUIDs**. `messageId`, `lastMessageId`, `replyToId`, `cursor` are **64-bit Snowflakes** (`bigint`) — send/receive them as JSON numbers (they fit in a JS `number` safely below 2^53 for the current epoch, but treat as opaque sortable integers). |
| **Postgres lists** | `?page=&size=&sort=` → Spring `Page<T>` (`{ content, totalElements, totalPages, number, size, … }`). Default size 20 (members 30). |
| **Cassandra reads** | `?cursor=&limit=` cursor paging; `limit` is clamped to **[1, 100]**. |
| **Timestamps** | UTC ISO-8601 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`. Cassandra-sourced timestamps (`createdAt`, `editedAt`) are instants; Postgres-sourced (`lastMessageAt`, `mutedUntil`) are local-UTC datetimes. |

---

## 1. Conversations

Postgres-backed. `ConversationController` — base `/api/v1/conversations`.

### `GET /conversations`
My inbox — active, non-archived conversations, **pinned first then newest
activity**. Excludes conversations where I'm the recipient of a still-pending
message request (those live in the Requests inbox).

`?page=&size=` → `Page<ConversationResponse>`.

### `GET /conversations/archived`
Same shape, my archived conversations.

### `POST /conversations`
Create a DIRECT (get-or-create, race-safe) or GROUP conversation.

```jsonc
// DIRECT — 200 (existing) or 200 (freshly created; idempotent by direct_key)
{ "type": "DIRECT", "recipientId": "1f3c…" }

// GROUP — 201
{ "type": "GROUP", "title": "Fiqh Study Circle",
  "avatarKey": "chat/avatars/ab12.jpg",         // optional R2 key
  "memberIds": ["…","…"] }                       // optional initial members (≤256)
```
→ `ConversationResponse`. Errors: `BLOCKED` (DM to someone in a block
relationship), `BAD_REQUEST` (self-DM, missing title/recipient).

### `GET /conversations/{id}`
`ConversationResponse` (metadata + **my** member state; `peer` for DIRECT).
Errors: `CONVERSATION_NOT_FOUND`, `NOT_A_MEMBER`.

### `PATCH /conversations/{id}`
Group only — edit title / avatar / settings (nulls left unchanged).
```jsonc
{ "title": "New name", "avatarKey": "chat/avatars/x.jpg",
  "settings": { "sendMode": "ADMINS_ONLY", "whoCanAddMembers": "ADMINS_ONLY",
                "whoCanEditInfo": "ADMINS_ONLY", "whoCanPin": "ADMINS_ONLY",
                "adminsCanPromote": true, "historyVisibleToNewMembers": true } }
```
→ `ConversationResponse`. Title/avatar changes require `EDIT_INFO`, settings
require `CHANGE_SETTINGS` (`ADMINS_ONLY` on failure). Emits `TITLE_CHANGED` /
`AVATAR_CHANGED` system messages + a `conversation.updated` realtime event.

### `DELETE /conversations/{id}`
Owner deletes the group (soft-delete, hidden for everyone) or a user hides a DM
(archives their own side). → 204. Group delete requires owner (`NOT_OWNER`).

### `POST /conversations/{id}/read`
Advance the read marker. `{ "lastReadMessageId": 172634… }` → 200. Zeroes my
`unreadCount`, invalidates my badge, and (unless a request is still pending)
emits `receipt.read` to the other members.

### `POST /conversations/{id}/mute`
`{ "mutedUntil": "2026-08-01T00:00:00.000Z" }` (or `null` to unmute) → 200. Mute
suppresses push, **not** the unread count.

### `POST /conversations/{id}/pin` — `{ "pinned": true }` → 200.
### `POST /conversations/{id}/archive` — `{ "archived": true }` → 200.

---

## 2. Messages

Cassandra-backed reads (cursor); `MessageController` — base `/api/v1`.

### `GET /conversations/{id}/messages?cursor=&limit=`
A page of messages, **newest → older**, via the single-partition bucket walk.
Omit `cursor` for the newest page; pass the previous `nextCursor` to page back.
```jsonc
// → MessagePage<MessageResponse>
{ "items": [ /* MessageResponse, newest first */ ],
  "nextCursor": 172630000000000000,   // pass back as ?cursor= ; null = start of history
  "hasMore": true }
```

### `GET /conversations/{id}/messages/sync?after=<id>&limit=`
Gap sync — everything strictly newer than the client's high-water id, **ascending**.
→ `List<MessageResponse>`. Combined with idempotent sends this gives
exactly-once display across reconnects.

### `GET /conversations/{id}/messages/search?q=&limit=`
In-conversation full-text search. Served by the **Elasticsearch** `irc-chat-messages`
index (BM25 + fuzzy), with an automatic fallback to a bounded single-partition
Cassandra scan when the index is cold/unavailable. → `List<MessageResponse>`.

### `POST /conversations/{id}/messages`
Send a message. **Idempotent** by `clientNonce`.
```jsonc
{ "clientNonce": "c1f…",              // required; reuse across retries of THIS message
  "type": "TEXT",                     // TEXT | IMAGE | VIDEO | VOICE | FILE
  "body": "salam, how's the paper?",  // ≤ 8000 chars; null for pure-media
  "replyToId": 172630000000000000,    // optional
  "media": [                          // optional; keys already uploaded via the media API
    { "kind": "VOICE", "storageKey": "chat/voice/ab12.opus",
      "mime": "audio/opus", "bytes": 40213, "durationMs": 4200, "waveform": "…" } ] }
```
→ **201** `MessageResponse` (or **201** with the already-created message on a
nonce replay). Errors: `BLOCKED`, `NOT_A_MEMBER`, `READ_ONLY`, `ADMINS_ONLY`,
`REQUEST_LIMIT_REACHED`. Rate-limited (30 / 10s per sender).

**Direct-message routing** happens automatically at send time (see
[03](03-permissions-and-requests.md)):
`ALLOW` (write + fan-out), `ROUTE_TO_REQUEST` (creates/advances a message
request; capped at **3** pre-acceptance messages), `DELIVER_RESTRICTED` (quiet
delivery to the recipient's restricted tray), or `DENY` → `BLOCKED`.

### `POST /conversations/{id}/messages/upload` (multipart)
Convenience: uploads raw files through the existing R2 pipeline and sends them as
one message. Parts: `clientNonce`, `body?` (form fields) + `files` (multipart).
Kind is inferred from content-type; rich metadata (waveform, dimensions) should
use the JSON endpoint with pre-uploaded keys. Orphaned uploads are rolled back on
failure. → 201 `MessageResponse`.

### `GET /messages/{messageId}`
Single message (reply/jump/forward target). → `MessageResponse` with full
reaction detail (incl. `reactedByMe`). Errors: `MESSAGE_NOT_FOUND`, `NOT_A_MEMBER`.

### `PATCH /messages/{messageId}` — `{ "body": "…" }`
Edit your own text message. → `MessageResponse`. Emits `message.edited`. System
messages and deleted messages can't be edited (`BAD_REQUEST`); others' messages
`ACCESS_FORBIDDEN`.

### `DELETE /messages/{messageId}`
Soft-delete (tombstone). Own message, or any message if you're a group
admin/owner (`DELETE_ANY_MESSAGE`). → 204. Emits `message.deleted`; reactions
cleared. Idempotent.

### `POST /messages/{messageId}/forward`
```jsonc
{ "targetConversationId": "…", "clientNonce": "…" }
```
Forward into another conversation you belong to; copies type/body/media and sets
`forwardedFrom`. Re-runs full send permission on the **target**. → 201 `MessageResponse`.

### `POST /messages/{messageId}/delivered`
Mark delivered from the recipient device. → 200. Emits `receipt.delivered` to the
other members (suppressed while a request is pending or the thread is restricted).

### Pinned messages
| Method | Path | Auth | Result |
|--------|------|------|--------|
| `POST` | `/conversations/{id}/messages/{messageId}/pin` | group `whoCanPin`; any DM member | 200 — writes a `PINNED` system message, emits `conversation.updated`(`PINNED`) |
| `DELETE` | `/conversations/{id}/messages/{messageId}/pin` | same | 204 — emits `conversation.updated`(`UNPINNED`) |
| `GET` | `/conversations/{id}/pinned` | any readable member | `List<MessageResponse>` (newest pin first) |

Pinning is idempotent; deleting a message also unpins it.

### `GET /messaging/search?q=&limit=`
**Cross-conversation** full-text search over every conversation the caller can
read (the membership scope is enforced inside the Elasticsearch query, so you can
never search a conversation you're not in). → `List<MessageResponse>`.

### Reactions
| Method | Path | Body | Result |
|--------|------|------|--------|
| `POST` | `/messages/{messageId}/react` | `{ "emoji": "👍" }` | `List<ReactionSummary>` (my view) |
| `DELETE` | `/messages/{messageId}/react` | — | `List<ReactionSummary>` |
| `GET` | `/messages/{messageId}/reactions` | — | `List<ReactionSummary>` (full, incl. `reactedByMe`) |

Add/change is a single upsert; both emit `message.reaction`. Counts are
Redis-cached; the timeline (`GET …/messages`) renders counts without
`reactedByMe`, the single-message and reactions endpoints include it.

---

## 3. Group membership

`GroupMemberController` — base `/api/v1/conversations`.

| Method | Path | Body | Auth (matrix) |
|--------|------|------|---------------|
| `GET` | `/{id}/members?page=&size=` | — | any readable member |
| `POST` | `/{id}/members` | `{ "userIds": ["…"] }` | `ADD_MEMBERS` |
| `DELETE` | `/{id}/members/{userId}` | — | `REMOVE_MEMBER` (admin can't act on owner/admin → `CANNOT_ACT_ON_ADMIN`) |
| `POST` | `/{id}/members/{userId}/role` | `{ "role": "ADMIN" }` | promote → `PROMOTE_ADMIN`; demote → owner-only |
| `POST` | `/{id}/members/{userId}/restrict` | `{ "restricted": true }` | `RESTRICT_MEMBER` |
| `POST` | `/{id}/leave` | — | any member (owner must transfer/delete first) |
| `POST` | `/{id}/transfer-owner` | `{ "newOwnerId": "…" }` | owner only (`NOT_OWNER`) |
| `POST` | `/{id}/invite-link` | `{ "expiresInHours": 24, "maxUses": 50 }` (both optional) | `CREATE_INVITE` |
| `DELETE` | `/{id}/invite-link` | — | `CREATE_INVITE` |
| `POST` | `/conversations/join` | `{ "token": "…" }` | invite-token holder |

- **Add** returns 200; each add writes a `MEMBER_ADDED` system message, fires an
  `ADDED_TO_GROUP` notification, and emits `member.changed` (`ADDED`).
- **Invite link** returns `InviteLinkResponse { conversationId, token, expiresAt,
  maxUses, useCount }` — the plaintext **`token` is shown once** (only a SHA-256
  hash is stored); creating a new link revokes prior ones. `INVITE_INVALID` on
  join if expired/revoked/exhausted.
- **Transfer** demotes the old owner to `ADMIN`, promotes the target to `OWNER`,
  and writes `OWNERSHIP_TRANSFERRED`.

Every membership change emits a SYSTEM timeline message and a `member.changed`
realtime event (also delivered directly to the affected user).

---

## 4. Message requests

`MessageRequestController` — base `/api/v1/message-requests`.

| Method | Path | Result |
|--------|------|--------|
| `GET` | `?status=PENDING&page=&size=` | `Page<MessageRequestResponse>` |
| `GET` | `/count` | `{ "count": N }` pending |
| `POST` | `/{id}/accept` | 200 — graduates to a normal chat (now in main inbox); notifies the requester |
| `POST` | `/{id}/decline` | 200 — hidden |
| `POST` | `/{id}/block` | 200 — declines + blocks the requester via the social API |

Only the recipient may act (`ACCESS_FORBIDDEN` otherwise).

---

## 5. Realtime

`MessagingStreamController`.

### `GET /messaging/stream?token=<jwt>`
The **single** SSE stream carrying every chat event for the user, multiplexed by
`event:` name. `Accept: text/event-stream`. Auto-reconnects with `Last-Event-ID`;
pair with the `sync` endpoint for gap recovery. Presence is kept online while any
tab holds this stream (server-side heartbeat refresh) and expires ~30s after the
last tab closes.

Every event's `data` is a **`ChatRealtimeEvent`** (null fields omitted). Counter
values are deliberately absent — apply `+1/-1` locally.

| `event:` | Populated fields | Meaning |
|----------|------------------|---------|
| `message.new` | `conversationId`, `message` | New message (also to sender's other devices; only to the peer for request/restricted threads) |
| `message.edited` | `conversationId`, `messageId`, `body`, `editedAt` | Body edited |
| `message.deleted` | `conversationId`, `messageId` | Tombstoned |
| `message.reaction` | `conversationId`, `messageId`, `userId`, `emoji`, `added` | Reaction added/removed |
| `receipt.read` | `conversationId`, `userId`, `lastReadMessageId` | Someone read up to a message |
| `receipt.delivered` | `conversationId`, `userId`, `messageId` | Delivered to a device |
| `typing` | `conversationId`, `userId`, `isTyping` | Typing indicator |
| `presence` | `userId`, `presenceStatus`, `lastSeenEpochMs` | Contact online/offline |
| `conversation.updated` | `conversationId`, `conversation?`, `memberChange?` | Title/avatar/settings changed, or group deleted / request accepted |
| `member.changed` | `conversationId`, `userId`, `memberChange`, `role` | Added/Removed/Left/Promoted/Demoted/Restricted/Unrestricted |
| `request.new` | `conversationId`, `request` | New message request arrived |
| `connected` / `heartbeat` | — | Stream lifecycle |

### `POST /conversations/{id}/typing` — `{ "isTyping": true }`
Ephemeral; a Redis TTL auto-clears a stale "typing". Suppressed while a request
is pending. → 200 (`NOT_A_MEMBER` if not an active member).

### `GET /presence?userIds=a,b,c`
`List<PresenceResponse> { userId, status: "online"|"offline", lastSeenEpochMs }`.

### `GET /messaging/unread-count`
`{ "count": N }` — total unread badge across all conversations (Redis-cached).

---

## 6. Response schemas

**`ConversationResponse`**
```jsonc
{ "id": "…", "type": "DIRECT|GROUP", "title": null, "avatarKey": null, "avatarUrl": null,
  "ownerId": "…", "memberCount": 2,
  "lastMessageId": 172…, "lastMessageAt": "…Z", "lastMessagePreview": "…",
  "groupSettings": { … } | null,
  "myRole": "OWNER|ADMIN|MEMBER", "myStatus": "ACTIVE|RESTRICTED|LEFT|REMOVED",
  "lastReadMessageId": 172…, "unreadCount": 3,
  "hasUnread": true,                        // exact count for small convos; the ONLY unread signal for large groups
  "mutedUntil": null,
  "pinned": false, "archived": false,
  "peer": { "userId": "…", "username": "…", "fullName": "…" } | null,   // DIRECT only
  "createdAt": "…Z" }
```

**`MessageResponse`**
```jsonc
{ "messageId": 172…, "conversationId": "…",
  "senderId": "…", "senderUsername": "…", "senderFullName": "…",
  "type": "TEXT|IMAGE|VIDEO|VOICE|FILE|SYSTEM",
  "body": "…" | null,
  "media": [ { "kind": "VOICE", "storageKey": "…", "url": "/api/v1/media/…",
               "thumbnailKey": null, "thumbnailUrl": null, "mime": "audio/opus",
               "bytes": 40213, "width": null, "height": null,
               "durationMs": 4200, "waveform": "…", "fileName": null, "altText": null } ],
  "replyToId": 172… | null,
  "replyTo": { "messageId": 172…, "senderId": "…", "type": "TEXT", "snippet": "…", "deleted": false } | null,
  "forwardedFrom": "…" | null,
  "mentions": ["…"] | null,
  "reactions": [ { "emoji": "👍", "count": 3, "reactedByMe": true } ],
  "editedAt": null, "deleted": false,
  "systemEvent": "MEMBER_ADDED" | null,     // for type=SYSTEM
  "createdAt": "…Z" }
```

**`MemberResponse`** `{ userId, username, fullName, role, status, joinedAt }`
**`MessageRequestResponse`** `{ id, conversationId, requesterId, requesterUsername, requesterFullName, status, firstMessageId, messageCount, createdAt }`
**`MessagePage<T>`** `{ items, nextCursor, hasMore }`
**`ReactionSummary`** `{ emoji, count, reactedByMe }`
**`PresenceResponse`** `{ userId, status, lastSeenEpochMs }`

---

## 7. Error codes

| `errorCode` | HTTP | Meaning |
|-------------|------|---------|
| `BLOCKED` | 403 | A block relationship prevents messaging (never reveals who blocked whom). |
| `NOT_A_MEMBER` | 403 | Not a member of the conversation. |
| `READ_ONLY` | 403 | You are restricted from posting in this group. |
| `ADMINS_ONLY` | 403 | Action requires admin/owner (or admins-only send mode). |
| `NOT_OWNER` | 403 | Action requires the owner. |
| `CANNOT_ACT_ON_ADMIN` | 403 | An admin tried to act on the owner or another admin. |
| `REQUEST_LIMIT_REACHED` | 403 | Stranger exceeded the pre-acceptance message cap, or the request was declined. |
| `INVITE_INVALID` | 403 | Invite token expired / revoked / exhausted. |
| `CONVERSATION_NOT_FOUND` | 404 | — |
| `MESSAGE_NOT_FOUND` | 404 | — |
| `ACCESS_FORBIDDEN` | 403 | Generic authorization failure (e.g. editing another's message). |
| `BAD_REQUEST` | 400 | Validation / semantic error. |
| `RATE_LIMIT_EXCEEDED` | 429 | Send rate exceeded. |

---

## 8. As-built notes & deferred work

- **Fan-out**: eager per-member unread + per-recipient realtime for conversations
  ≤ **256** members; above that, the exact unread count is skipped and the client
  relies on the `hasUnread` "new messages" indicator. A true broadcast-channel
  model for very large groups is the documented next lever.
- **Notifications** are in-app only (bell + badge); `NEW_MESSAGE` fires for
  **offline** recipients and coalesces per conversation. FCM/APNs push is a
  future integration point — the enqueue seam is the notification pipeline.
- **Search** is Elasticsearch-backed (`irc-chat-messages`, indexed async on
  send/edit, removed on delete, membership-scoped in the query) with a bounded
  Cassandra-scan fallback for in-conversation search when the index is cold.
- **Read marker**: advancing the marker zeroes the per-conversation `unreadCount`
  (the standard eager-counter model). If a client marks read a *non-latest*
  message, the count can briefly under-report until the next message re-bumps it —
  an accepted approximation (the alternative is a per-read Cassandra count).
- **Read receipts** are always on; a per-user privacy toggle is a clean future
  addition (add a user pref + gate the `receipt.read` broadcast). Receipts and
  typing are already suppressed for pending requests and restricted threads.
- **Durability**: a message is written to Cassandra inside the send's Postgres
  transaction; a rollback after that write leaves a harmless orphaned message row
  (invisible — not referenced by the inbox pointer), consistent with the
  platform's existing tolerate-partial-writes posture.
- **E2EE** is intentionally out of scope (incompatible with server-side search /
  multi-device / moderation) — see [08](08-scaling-and-roadmap.md).
