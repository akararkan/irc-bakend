# Chat Messages API — Send, Read, Edit, React, Forward, Pin

The message log itself: cursor-paged reads over the Cassandra bucket walk, gap
sync for reconnects, idempotent send (JSON + multipart), edit / soft-delete,
emoji reactions, forward, delivery receipts, and pinned messages. This is the
`MessageController` surface (base path `/api/v1`); conversation-level state
(inbox, read markers, mute, archive) lives in [Conversations](./conversations.md).

- **Base path:** `/api/v1`
- **Auth:** `Authorization: Bearer <jwt>` on every endpoint (the realtime stream
  also accepts `?token=` — see [Realtime](./realtime.md)).
- **Errors:** unified `ApiErrorResponse` envelope — switch on `errorCode`. Full
  code list in [Error handling](../errors/error-handling.md).
- **IDs:** `conversationId` is a **UUID**; `messageId` / `cursor` / `replyToId`
  are **64-bit Snowflakes** — JSON numbers, opaque but sortable.
- **Cursor limits:** every `limit` is clamped into **1..100** (`Pages.clamp`).

**Cross-cutting rules**

- **Idempotent send.** Every send carries a client-generated `clientNonce`
  reused across retries of *that* message; a duplicate delivery returns the
  already-created `MessageResponse` (still `201`), never a second row. Pair with
  `/sync` on reconnect for exactly-once display.
- **Delta-model realtime.** The `message.*` SSE events carry the event, not fresh
  counter values — apply reaction ±1 locally (see [Realtime](./realtime.md)).
- **Cleared-history floor.** A per-member "delete for me" high-water mark
  (`clearedBeforeMessageId`) floors *all* reads here — page, sync, single-fetch
  and search never return messages at or below your clear point.

Related: [Conversations](./conversations.md) · [Groups](./groups.md) ·
[Message requests](./message-requests.md) · [Realtime](./realtime.md) ·
[Search](./search.md).

---

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/conversations/{id}/messages` | Cursor page, newest → older |
| `GET` | `/conversations/{id}/messages/sync` | Gap sync — strictly newer than an id, ascending |
| `POST` | `/conversations/{id}/messages` | Send (idempotent, JSON) |
| `POST` | `/conversations/{id}/messages/upload` | Send with raw file uploads (multipart) |
| `GET` | `/messages/{messageId}` | Single message (reply / jump / forward target) |
| `PATCH` | `/messages/{messageId}` | Edit own text body |
| `DELETE` | `/messages/{messageId}?scope=` | Delete — `everyone` (tombstone) or `me` (hide for me) |
| `POST` | `/messages/{messageId}/react` | Add / change my reaction |
| `DELETE` | `/messages/{messageId}/react` | Remove my reaction |
| `GET` | `/messages/{messageId}/reactions` | Full reaction detail (incl. `reactedByMe`) |
| `POST` | `/messages/{messageId}/star` | Star / bookmark for me |
| `DELETE` | `/messages/{messageId}/star` | Unstar |
| `GET` | `/messaging/starred` | My starred messages, newest-starred first (paged) |
| `GET` | `/messages/{messageId}/seen-by` | Who has read this message (group read receipts) |
| `POST` | `/messages/{messageId}/forward` | Copy into another conversation |
| `POST` | `/messages/{messageId}/delivered` | Delivery receipt from the recipient device |
| `POST` | `/conversations/{id}/messages/schedule` | Queue a send-later message |
| `GET` | `/conversations/{id}/scheduled` | My pending scheduled messages in this thread |
| `DELETE` | `/messaging/scheduled/{scheduledId}` | Cancel a pending scheduled message |
| `POST` | `/conversations/{id}/messages/{messageId}/pin` | Pin a message |
| `DELETE` | `/conversations/{id}/messages/{messageId}/pin` | Unpin a message |
| `GET` | `/conversations/{id}/pinned` | List pinned messages, newest pin first |

In-conversation and cross-conversation full-text search
(`GET /conversations/{id}/messages/search`, `GET /messaging/search`) live on the
same controller but are documented in [Search](./search.md).

---

## 1. Reading messages

### 1.1 `GET /conversations/{id}/messages` — cursor page

```
GET /conversations/{id}/messages?cursor=&limit=50
```

**Auth:** any active member. A page of messages **newest → older** via the
single-partition bucket walk. Omit `cursor` for the newest page; pass the
previous response's `nextCursor` to page back into history.

| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Path — the conversation |
| `cursor` | Snowflake | Optional — `nextCursor` from the prior page; omit for newest |
| `limit` | int | Default `50`, clamped 1..100 |

**Response `200` (`MessagePage<MessageResponse>`):**

```jsonc
{
  "items": [ /* MessageResponse, newest first */ ],
  "nextCursor": 172630000000000000,   // pass back as ?cursor= ; null = start of history reached
  "hasMore": true
}
```

Timeline rows render reaction **counts without `reactedByMe`** (use §1.2 or the
reactions endpoint for the viewer-relative flag).

**Errors:** `NOT_A_MEMBER`, `CONVERSATION_NOT_FOUND`.

---

### 1.2 `GET /conversations/{id}/messages/sync` — gap sync

```
GET /conversations/{id}/messages/sync?after=<messageId>&limit=100
```

**Auth:** any active member. Everything **strictly newer** than the client's
high-water id, in **ascending** order. Combined with idempotent sends this gives
exactly-once display across reconnects: after the SSE stream drops, sync from the
last id you rendered.

| Param | Type | Description |
|-------|------|-------------|
| `after` | Snowflake | **Required** — the newest `messageId` the client already has |
| `limit` | int | Default `100`, clamped 1..100 |

**Response `200`:** `List<MessageResponse>` (ascending).

**Errors:** `NOT_A_MEMBER`, `CONVERSATION_NOT_FOUND`.

---

### 1.3 `GET /messages/{messageId}` — single message

```
GET /messages/{messageId}
```

**Auth:** a member of the message's conversation. The reply / jump-to / forward
target — returns the full `MessageResponse` **with `reactedByMe` populated** on
each reaction bucket.

**Response `200`:** `MessageResponse`.

**Errors:** `MESSAGE_NOT_FOUND`, `NOT_A_MEMBER`.

---

## 2. Sending

### 2.1 `POST /conversations/{id}/messages` — send (JSON)

```
POST /conversations/{id}/messages
Content-Type: application/json
```

**Auth:** send-eligible member (see the routing note below). **Idempotent** by
`clientNonce`. **Rate-limited: 30 sends / 10 s** per sender → `RATE_LIMIT_EXCEEDED`.

**Request body (`SendMessageRequest`):**

```jsonc
{
  "clientNonce": "c1f8a2e0-…",         // required, ≤64 chars; reuse across retries of THIS message
  "type": "TEXT",                       // required — TEXT | IMAGE | VIDEO | VOICE | FILE
  "body": "salam, how's the paper?",    // ≤ 8000 chars; null for pure-media
  "replyToId": 172630000000000000,      // optional — Snowflake of the quoted message
  "media": [                            // optional, ≤ 10 attachments; keys already uploaded via the media API
    { "kind": "VOICE", "storageKey": "chat/voice/ab12.opus",
      "mime": "audio/opus", "bytes": 40213, "durationMs": 4200, "waveform": "…" }
  ]
}
```

**Response `201`:** the created `MessageResponse` — **or `201` with the
already-created message** on a nonce replay.

**Side effects:** Cassandra message row written inside the send transaction;
per-member unread bumped and `message.new` fanned out to recipients (only to the
peer for request / restricted threads); Elasticsearch indexed async; offline,
**non-muted** recipients get a coalesced `NEW_MESSAGE` notification. In a
CHANNEL, the bell is `CHANNEL_NEW_POST` instead — fanned to every active,
non-muted subscriber at any channel size (skipped entirely for `silent` posts) —
see [notifications](../notifications/notifications.md#chat-channel--live-notifications).
Members `@`-mentioned in the body get a dedicated `MESSAGE_MENTION` bell instead
of the generic one — it fires **through mute, presence and the group-size cutoff**
(never on `silent` sends) — see
[mentions](../platform/mentions.md#chat--channel-mentions).

**Direct-message routing** is applied automatically at send time:
`ALLOW` (write + fan-out) · `ROUTE_TO_REQUEST` (creates/advances a
[message request](./message-requests.md), capped at **3** pre-acceptance
messages) · `DELIVER_RESTRICTED` (quiet delivery to the recipient's restricted
tray) · `DENY` → `BLOCKED`.

**Errors** (send-specific `errorCode`s):

| Status | `errorCode` | When |
|--------|-------------|------|
| 403 | `BLOCKED` | A block relationship prevents messaging (never reveals direction), or DM routing returned `DENY`. |
| 403 | `NOT_A_MEMBER` | Caller is not an active member of the conversation. |
| 403 | `READ_ONLY` | Caller is a restricted member of the group. |
| 403 | `ADMINS_ONLY` | Group `sendMode` is `ADMINS_ONLY` and caller is not an admin/owner. |
| 403 | `REQUEST_LIMIT_REACHED` | Stranger exceeded the 3-message pre-acceptance cap, or the request was declined. |
| 404 | `MESSAGE_NOT_FOUND` | `replyToId` points at a message that doesn't exist / is out of scope. |
| 429 | `RATE_LIMIT_EXCEEDED` | More than 30 sends in 10 s. |
| 400 | `BAD_REQUEST` | Missing `clientNonce`/`type`, body > 8000, or > 10 attachments. |

---

### 2.2 `POST /conversations/{id}/messages/upload` — send (multipart)

```
POST /conversations/{id}/messages/upload
Content-Type: multipart/form-data
```

**Auth:** same as §2.1. Convenience path that uploads raw files through the R2
media pipeline and sends them as **one** message.

| Part | Type | Description |
|------|------|-------------|
| `clientNonce` | form field | **Required** — idempotency key |
| `body` | form field | Optional caption |
| `files` | multipart | Zero or more files; `kind` inferred from content-type |

At least one of `body` / `files` must be present, else `400 BAD_REQUEST`. The
message `type` is inferred from the first file's kind (`image/*` → `IMAGE`,
`video/*` → `VIDEO`, `audio/*` → `VOICE`, else `FILE`; no files → `TEXT`). Rich
metadata (voice waveform, image dimensions) should instead use §2.1 with
pre-uploaded storage keys. **Orphaned uploads are rolled back** (best-effort
delete) if the send then fails.

**Response `201`:** `MessageResponse`. Same routing / rate-limit / `errorCode`s
as §2.1.

---

### 2.3 `POST /messages/{messageId}/forward` — forward

```
POST /messages/{messageId}/forward
Content-Type: application/json
```

**Auth:** member of both source (readable) and target conversations. Copies
`type` / `body` / `media` into another conversation and sets `forwardedFrom` to
the original sender. **Full send permission is re-evaluated on the target** — a
forward into a group you can't post to fails exactly like a fresh send.

**Request body (`ForwardMessageRequest`):**

```jsonc
{ "targetConversationId": "1f3c…", "clientNonce": "fwd-9a2…" }   // both required
```

**Response `201`:** the new `MessageResponse` in the target conversation (its
`forwardedFrom` set, a fresh `messageId`).

**Errors:** `MESSAGE_NOT_FOUND` (source), plus every send `errorCode` from §2.1
evaluated against the **target** (`BLOCKED`, `NOT_A_MEMBER`, `READ_ONLY`,
`ADMINS_ONLY`, `REQUEST_LIMIT_REACHED`, `RATE_LIMIT_EXCEEDED`).

---

## 3. Editing & deleting

### 3.1 `PATCH /messages/{messageId}` — edit own text

```
PATCH /messages/{messageId}
Content-Type: application/json
```

**Auth:** the message's **sender only**.

**Request body (`EditMessageRequest`):**

```jsonc
{ "body": "salam — fixed the typo" }   // required, ≤ 8000 chars
```

**Response `200`:** the updated `MessageResponse` (`editedAt` now set).

**Side effects:** broadcasts `message.edited` (`conversationId`, `messageId`,
`body`, `editedAt`); the search index is re-indexed.

**Errors:** `ACCESS_FORBIDDEN` (not your message); `BAD_REQUEST` (a SYSTEM
message or an already-deleted message can't be edited); `MESSAGE_NOT_FOUND`.

---

### 3.2 `DELETE /messages/{messageId}` — delete (two scopes)

```
DELETE /messages/{messageId}?scope=everyone   # default
DELETE /messages/{messageId}?scope=me
```

Two WhatsApp-style deletes, selected by the `?scope=` query param (default
`everyone`):

**`scope=everyone` — delete for everyone (tombstone).** **Auth:** the sender, **or**
a group admin/owner (`DELETE_ANY_MESSAGE`). Soft-deletes (tombstones) the message:
`deleted: true`, body/media stripped on read, reactions cleared, any star dropped,
and the row removed from the search index. The message stays in the timeline as a
"message deleted" stub for **all** members. **Idempotent** — re-deleting a tombstone
is a no-op. **Side effects:** broadcasts `message.deleted` (`conversationId`,
`messageId`). **Errors:** `ACCESS_FORBIDDEN` (not yours and not an admin),
`MESSAGE_NOT_FOUND`.

**`scope=me` — delete for me (hide).** **Auth:** any member who can read the
message. Hides the message from **my** view only — it is filtered out of my page,
sync, single-fetch, search and starred results. Everyone else still sees it, and no
tombstone/broadcast is emitted. **Idempotent** — hiding an already-hidden message is
a no-op. Works on **any** readable message (mine or someone else's). **Errors:**
`NOT_A_MEMBER`, `MESSAGE_NOT_FOUND`.

**Response `204 No Content`** in both cases.

---

## 4. Reactions

Chat reactions are a **single per-user emoji** (unlike the post module's
single-`LIKE` model). Adding when one already exists **changes** it — one row per
`(message, user)`. Add and change both emit `message.reaction` with `added:true`;
remove emits it with `added:false`. Counts are Redis-cached.

### 4.1 `POST /messages/{messageId}/react` — add / change

```
POST /messages/{messageId}/react
Content-Type: application/json
```

**Auth:** a member of the conversation.

**Request body (`ReactRequest`):**

```jsonc
{ "emoji": "👍" }   // required, ≤ 16 chars (a single grapheme)
```

**Response `200`:** `List<ReactionSummary>` — the caller's view of the message's
reaction buckets.

**Side effects:** single upsert on the reactions table; broadcasts
`message.reaction` (`conversationId`, `messageId`, `userId`, `emoji`,
`added:true`).

**Errors:** `NOT_A_MEMBER`, `MESSAGE_NOT_FOUND`, `BAD_REQUEST` (missing / oversize emoji).

---

### 4.2 `DELETE /messages/{messageId}/react` — remove

```
DELETE /messages/{messageId}/react
```

**Auth:** a member of the conversation. Removes the caller's reaction (no-op if
none).

**Response `200`:** `List<ReactionSummary>` (post-removal). Broadcasts
`message.reaction` with `added:false`.

---

### 4.3 `GET /messages/{messageId}/reactions` — full detail

```
GET /messages/{messageId}/reactions
```

**Auth:** a member of the conversation. Returns every bucket **with
`reactedByMe`** populated (the timeline page omits that flag for cost).

**Response `200`:** `List<ReactionSummary>`.

**Errors:** `NOT_A_MEMBER`, `MESSAGE_NOT_FOUND`.

---

## 5. Delivery & read receipts

### 5.1 `POST /messages/{messageId}/delivered`

```
POST /messages/{messageId}/delivered
```

**Auth:** a recipient member. Marks the message delivered from the calling
device, and advances my per-conversation **delivered marker**
(`lastDeliveredMessageId`) so DMs can render the grey **double-tick**.

**Response `200`.**

**Side effects:** broadcasts `receipt.delivered` (`conversationId`, `userId`,
`messageId`) to the other members — **suppressed** while a message request is
pending, the thread is restricted, **or either side has read receipts off**
(see [settings.md](./settings.md)).

(The read marker — which zeroes unread and emits `receipt.read` (blue tick) — is a
conversation-level action: `POST /conversations/{id}/read`, see
[Conversations](./conversations.md). For a DM, the peer's read/delivered markers ride
on `ConversationResponse.peerLastReadMessageId` / `peerLastDeliveredMessageId`.)

### 5.2 `GET /messages/{messageId}/seen-by` — who has read it (groups)

```
GET /messages/{messageId}/seen-by
```

**Auth:** a member who can read the message. Returns the members who have read up
to (or past) this message — the Messenger/Telegram "Seen by" list — **excluding the
sender**.

**Response `200`:** `List<ParticipantSummary>` (`{ userId, username, fullName }`;
avatars hydrated via the user API). Empty when nobody else has read it yet.

**Privacy (symmetric).** This honours read receipts both ways: it returns **empty**
if *you* have read receipts turned off, and it omits any reader who has theirs off.
So you only ever see who's seen your message if you also share your own receipts.
See [settings.md](./settings.md).

**Errors:** `NOT_A_MEMBER`, `MESSAGE_NOT_FOUND`.

---

## 6. Starred (bookmarked) messages

Per-user bookmarks — WhatsApp "Starred messages" / Telegram "Saved". A star is
private (nobody else can see it), survives edits, and is dropped when the message is
deleted for everyone. Whether a message is starred is surfaced as the **`starred`**
boolean on every `MessageResponse` the caller reads.

### 6.1 `POST /messages/{messageId}/star` — star

**Auth:** a member who can read the message. **Idempotent** — starring twice is a
no-op. → **200** (empty body). **Errors:** `NOT_A_MEMBER`, `MESSAGE_NOT_FOUND`.

### 6.2 `DELETE /messages/{messageId}/star` — unstar

Removes my star (no-op if not starred). → **204 No Content**.

### 6.3 `GET /messaging/starred` — my starred messages

```
GET /messaging/starred?page=0&size=30
```

**Auth:** required. My starred messages across **all** conversations,
**most-recently-starred first**, fully hydrated (reactions, `reactedByMe`, `starred:
true`). Messages I've "deleted for me" or that were deleted for everyone are filtered
out.

**Response `200`:** `List<MessageResponse>` (Postgres-paged, default size **30**).

---

## 7. Scheduled (send-later) messages

Queue a message now, deliver it later — Telegram "Schedule message". The queued row
lives in Postgres; a poller fires due messages every ~15 s through the **normal send
path**, so a scheduled send gets the same permission checks, idempotency, fan-out and
realtime as a live one. If permission changed in the meantime (you were blocked,
removed, or restricted — or the conversation was deleted) the fire fails cleanly and
the row is marked `FAILED`; it is never delivered against a closed door. A *transient*
failure (rate limit, a datastore timeout) is **not** terminal — the row stays
`PENDING` and the next poll retries it, so a fully-permitted message is never dropped.

### 7.1 `POST /conversations/{id}/messages/schedule` — queue

```
POST /conversations/{id}/messages/schedule
Content-Type: application/json
```

**Auth:** an active member (checked now **and** re-checked at fire time).

**Request body (`ScheduleMessageRequest`):**

```jsonc
{
  "scheduledAt": "2026-07-23T09:00:00.000",  // required, must be in the future
  "clientNonce": "sched-9a2…",                // required, ≤ 64 chars (idempotency at fire time)
  "type": "TEXT",                             // required — TEXT | IMAGE | VIDEO | VOICE | FILE
  "body": "Reminder: halaqa at 9",            // ≤ 8000 chars
  "replyToId": null,                          // optional Snowflake
  "media": [ /* MediaRefDto, ≤ 10 */ ]        // optional, pre-uploaded keys
}
```

**Response `201`:** `ScheduledMessageResponse` (status `PENDING`).

**Errors:** `400 BAD_REQUEST` (`scheduledAt` missing/in the past, missing
`clientNonce`/`type`); `403 NOT_A_MEMBER`; `404 CONVERSATION_NOT_FOUND`.

### 7.2 `GET /conversations/{id}/scheduled` — my pending queue

**Auth:** required. My own still-`PENDING` scheduled messages for this conversation,
soonest first.

**Response `200`:** `List<ScheduledMessageResponse>`.

### 7.3 `DELETE /messaging/scheduled/{scheduledId}` — cancel

**Auth:** the message's author only. Cancels it if still `PENDING` (marks
`CANCELLED`); a no-op if it already fired. → **204 No Content**.

**Errors:** `403 ACCESS_FORBIDDEN` (not yours); `404` (unknown id).

**`ScheduledMessageResponse`:**

```jsonc
{
  "id": "…",                          // UUID of the scheduled row
  "conversationId": "1f3c…",
  "type": "TEXT",
  "body": "Reminder: halaqa at 9",
  "media": [ /* MediaRefResponse */ ],
  "replyToId": null,
  "scheduledAt": "2026-07-23T09:00:00.000",
  "status": "PENDING",                // PENDING | SENT | CANCELLED | FAILED
  "sentMessageId": null,              // the delivered messageId once status == SENT
  "createdAt": "2026-07-22T14:00:00.000Z"
}
```

---

## 8. Pinned messages

| Method | Path | Auth | Result |
|--------|------|------|--------|
| `POST` | `/conversations/{id}/messages/{messageId}/pin` | group `whoCanPin`; any DM member | `200` |
| `DELETE` | `/conversations/{id}/messages/{messageId}/pin` | same | `204` |
| `GET` | `/conversations/{id}/pinned` | any readable member | `List<MessageResponse>`, newest pin first |

**Pin** writes a `PINNED` system message into the timeline and broadcasts
`conversation.updated` with `memberChange: PINNED`. **Unpin** broadcasts
`conversation.updated` with `UNPINNED`. Pinning is **idempotent**, and
soft-deleting a message also unpins it.

**Errors:** `ADMINS_ONLY` (group requires `whoCanPin` and caller isn't
eligible), `NOT_A_MEMBER`, `MESSAGE_NOT_FOUND`, `CONVERSATION_NOT_FOUND`.

---

## 9. Schemas

**`MessageResponse`** — one rendered message.

```jsonc
{
  "messageId": 172630000000000000,
  "conversationId": "1f3c…",
  "senderId": "41ee…",
  "senderUsername": "akar.arkanf19",
  "senderFullName": "Akar Arkan",
  "type": "TEXT",                      // TEXT | IMAGE | VIDEO | VOICE | FILE | SYSTEM
  "body": "salam, how's the paper?",   // null for pure-media / deleted
  "media": [ /* MediaRefResponse */ ],
  "replyToId": 172629000000000000,     // null when not a reply
  "replyTo": { /* ReplyPreview */ },   // null when not a reply
  "forwardedFrom": "9c1f…",            // original sender UUID, or null
  "mentions": ["…"],                   // Set<UUID>, or null
  "reactions": [ /* ReactionSummary */ ],
  "editedAt": null,                    // ISO-8601 instant once edited
  "deleted": false,
  "starred": false,                    // true if I've starred/bookmarked this message
  "systemEvent": null,                 // e.g. "MEMBER_ADDED" when type == SYSTEM
  "createdAt": "2026-07-20T14:32:00.000Z"
}
```

**`MessagePage<T>`** — cursor page for the message log.

```jsonc
{
  "items": [ /* T, newest first */ ],
  "nextCursor": 172630000000000000,   // Snowflake of the oldest row; pass back as ?cursor=
  "hasMore": true                       // false / nextCursor null = start of history reached
}
```

**`ReactionSummary`** — one aggregated reaction bucket.

```jsonc
{ "emoji": "👍", "count": 3, "reactedByMe": true }
```

`reactedByMe` is `false`/omitted on timeline (`GET …/messages`) rows and
populated on the single-message and reactions endpoints.

**`ReplyPreview`** — compact quoted stub of the replied-to message.

```jsonc
{ "messageId": 172629000000000000, "senderId": "…", "type": "TEXT",
  "snippet": "the earlier line, truncated…", "deleted": false }
```

**`MediaRefResponse`** — one outbound attachment. `url` / `thumbnailUrl` resolve
through the media proxy so the client renders without extra round-trips.

```jsonc
{
  "kind": "VOICE",                     // IMAGE | VIDEO | VOICE | FILE
  "storageKey": "chat/voice/ab12.opus",
  "url": "/api/v1/media/…",
  "thumbnailKey": null, "thumbnailUrl": null,
  "mime": "audio/opus",
  "bytes": 40213,
  "width": null, "height": null,       // populated for IMAGE / VIDEO
  "durationMs": 4200,                  // VOICE / VIDEO
  "waveform": "…",                     // VOICE
  "fileName": null,                    // FILE
  "altText": null
}
```

---

## 10. As-built notes

- **Reaction detail is tiered by cost.** The timeline page renders counts only;
  fetch `reactedByMe` from `GET /messages/{id}` or `GET …/reactions` when you need
  the viewer-relative flag.
- **Durability.** A message is written to Cassandra inside the send's Postgres
  transaction; a rollback after that write leaves a harmless orphaned row
  (invisible — never referenced by the inbox pointer).
- **Notifications** are in-app only today; `NEW_MESSAGE` fires for **offline**
  recipients and coalesces per conversation. Members whose `mutedUntil` is in
  the future are skipped (mute silences the bell, not the unread count).
  Channel posts use the separate `CHANNEL_NEW_POST` fan-out — not gated on
  presence or the 256-member cutoff.
- **`starred` is per-viewer.** It reflects **your** star, computed at read time via a
  bulk lookup — the same row shows `starred: true` to you and `false` to everyone else.
- **Disappearing messages** are enforced at write time: when the conversation's
  `disappearingSeconds > 0`, each new row is stored with a Cassandra TTL and simply
  ceases to exist when it elapses (no sweeper, no tombstone). Nothing lingers in the
  secondary stores: the body is **not** indexed in Elasticsearch; the inbox
  `lastMessagePreview` and the push notification show a neutral
  "🕓 Disappearing message" placeholder (never the text); and an **edit re-applies the
  remaining TTL** so the edited body expires with the rest of the row. See
  [conversations.md](./conversations.md#post-conversationsiddisappearing--disappearing-messages-timer).
- **Scheduled sends re-check permission at fire time**, so a message queued before you
  were blocked/removed never leaks through — it lands as `FAILED`, not delivered (only
  permission/validation failures are terminal; transient errors retry next poll).
