# Conversations API — Inbox, Direct & Group Threads

The conversation is the top-level container for a chat: a 1:1 **DIRECT** thread or
a **GROUP**. This surface covers the inbox and archived lists, creating threads
(direct get-or-create / group), reading and editing conversation metadata, and the
caller's personal per-conversation state — read marker, mute, pin, archive, and the
two-faced `DELETE`. The message log itself lives in [messages.md](./messages.md);
group membership in [groups.md](./groups.md).

- **Base path:** `/api/v1/conversations`
- **Auth:** `Authorization: Bearer <jwt>` on every endpoint (secure-by-default).
- **Errors:** unified envelope — switch on `errorCode`. See
  [Error handling](../errors/error-handling.md).
- **Paging:** Postgres-backed lists take `?page=&size=` and return a Spring
  `Page<ConversationResponse>` (`{ content, totalElements, totalPages, number, size, … }`).
  Default `size` is **20**.
- **IDs:** `conversationId` / `userId` are **UUIDs**; `lastMessageId` /
  `lastReadMessageId` are **64-bit Snowflakes** (JSON numbers — treat as opaque
  sortable integers).

**Cross-cutting rules**

- **DIRECT is get-or-create.** `POST /conversations` with `type: DIRECT` is
  idempotent per unordered pair (a stored `direct_key`) and race-safe — two
  simultaneous creates converge on the same thread and return `200`. A fresh GROUP
  returns `201`.
- **Two kinds of delete.** `DELETE /conversations/{id}` means "delete the group for
  everyone" when you own the group, and "delete this conversation **for me**" for
  everyone else — see [§DELETE](#delete-conversationsid--delete--hide). On a
  **channel** id it deletes the channel for everyone (owner) or leaves the channel
  (subscriber). This is distinct from `archive`, which only moves a thread to the
  archived list.
- **`hasUnread` is the durable unread signal.** `unreadCount` is exact for small
  conversations but is not maintained for large groups (> 256 members); `hasUnread`
  is always meaningful — render it as the "new messages" dot when in doubt.
- **Realtime.** Metadata changes and group deletion fan out on the single chat SSE
  stream as `conversation.updated`; the read marker emits `receipt.read`. Event
  payloads carry deltas, not counter values — see [realtime.md](./realtime.md).

Related: [messages.md](./messages.md) · [groups.md](./groups.md) ·
[message-requests.md](./message-requests.md) · [realtime.md](./realtime.md) ·
[search.md](./search.md).

---

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/conversations` | My inbox — active, non-archived threads (pinned first, then newest activity) |
| `GET` | `/conversations/archived` | My archived threads (same shape) |
| `POST` | `/conversations` | Create a DIRECT (get-or-create) or GROUP conversation |
| `GET` | `/conversations/{id}` | One conversation's metadata + **my** member state |
| `PATCH` | `/conversations/{id}` | Group only — edit title / avatar / settings |
| `DELETE` | `/conversations/{id}` | Owner deletes the group; everyone else deletes it **for me** |
| `POST` | `/conversations/{id}/read` | Advance my read marker |
| `POST` | `/conversations/{id}/unread` | Flag the thread **unread** in my inbox (until I next open it) |
| `POST` | `/conversations/{id}/mute` | Mute / unmute (suppresses push, not the count) |
| `POST` | `/conversations/{id}/pin` | Pin / unpin in my inbox |
| `POST` | `/conversations/{id}/archive` | Archive / unarchive in my inbox |
| `POST` | `/conversations/{id}/disappearing` | Set the disappearing-messages timer (`0` = off) |

---

## `GET /conversations` — inbox

Active, non-archived conversations I belong to, **pinned first then newest
activity**. Threads where I'm the recipient of a still-**pending** message request
are excluded — those live in the Requests inbox ([message-requests.md](./message-requests.md)).
Conversations I've "deleted for me" stay hidden until a newer message arrives.

`?page=&size=` → `Page<ConversationResponse>`.

## `GET /conversations/archived` — archived

Same shape and paging, my archived list. Archiving is per-user; the peer/group is
unaffected.

---

## `POST /conversations` — create

Create a DIRECT (get-or-create) or GROUP conversation. The dispatch keys on `type`.

**DIRECT** — idempotent by the stored `direct_key`; returns the existing thread if
one already exists, otherwise creates it. → **200** `ConversationResponse`.

```jsonc
{ "type": "DIRECT", "recipientId": "1f3c…" }   // recipientId required
```

**GROUP** — `title` required; `memberIds` seed the initial roster (creator excluded
automatically; non-existent and blocked ids are silently dropped; cap **256**). Each
member successfully added gets an `ADDED_TO_GROUP` notification and a
`member.changed`(`ADDED`) event; a `GROUP_CREATED` system message is written. → **201**
`ConversationResponse`.

```jsonc
{ "type": "GROUP",
  "title": "Fiqh Study Circle",
  "description": "Weekly readings & discussion",   // optional, ≤ 500 chars
  "avatarKey": "chat/avatars/ab12.jpg",   // optional R2/S3 key
  "memberIds": ["…", "…"] }               // optional, ≤ 256 (creator auto-excluded)
```

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 403 | `BLOCKED` | A block relationship exists with the DIRECT recipient (never reveals the direction) |
| 400 | `BAD_REQUEST` | Self-DM, missing `recipientId` (DIRECT), blank `title` (GROUP), or > 256 members |
| 404 | — | DIRECT `recipientId` is unknown or a deleted user |

---

## `GET /conversations/{id}`

One conversation's metadata plus **my** member state (`peer` populated for DIRECT).
Requires that I'm a readable member.

→ `ConversationResponse`.

**Errors:** `404 CONVERSATION_NOT_FOUND` (missing or deleted);
`403 NOT_A_MEMBER` (I can't read this thread).

---

## `PATCH /conversations/{id}`

**Group only** — edit title / description / avatar / settings. Null fields are left
unchanged; a value equal to the current one is a no-op (no system message).
Title/description/avatar changes require the `EDIT_INFO` permission, `settings`
require `CHANGE_SETTINGS` (both default to admins-only). Applying this to a DIRECT
thread is `400 BAD_REQUEST`.

```jsonc
{ "title": "New name",
  "description": "Weekly readings & discussion",   // ≤ 500 chars; "" clears it
  "avatarKey": "chat/avatars/x.jpg",
  "settings": {
    "sendMode": "ADMINS_ONLY",            // ALL_MEMBERS | ADMINS_ONLY
    "whoCanAddMembers": "ADMINS_ONLY",
    "whoCanEditInfo": "ADMINS_ONLY",
    "whoCanPin": "ADMINS_ONLY",
    "adminsCanPromote": true,
    "historyVisibleToNewMembers": true
  } }
```

→ `ConversationResponse`.

**Side effects:** emits `TITLE_CHANGED` / `DESCRIPTION_CHANGED` / `AVATAR_CHANGED`
system messages for the changed fields, then broadcasts `conversation.updated` (with
the fresh conversation payload) to all active members.

**Errors:** `400 BAD_REQUEST` (not a group); `403 NOT_A_MEMBER` (not an active
member); `403 ADMINS_ONLY` (lacking `EDIT_INFO` / `CHANGE_SETTINGS`);
`404 CONVERSATION_NOT_FOUND`.

---

## `DELETE /conversations/{id}` — delete / hide

→ **204** in every case. What actually happens depends on who you are.

- **Group owner** → **deletes the whole group for everyone.** Soft-delete
  (`deletedAt` set): the thread drops out of every inbox, and sends/reads start
  failing, but the message log is retained. Broadcasts `conversation.updated` with
  `memberChange: "DELETED"`.
- **Channel owner** → **deletes the whole channel for everyone** — same
  soft-delete + `DELETED` broadcast, plus the channel is de-indexed from
  public-channel search. Equivalent to `DELETE /channels/{id}`.
- **Channel subscriber** → **leaves the channel** (membership row removed). The
  chat drops off their list for good — it does **not** resurface on the
  channel's next post. See
  [channels/inbox.md](channels/inbox.md#leaving--deleting-the-channel).
- **Everyone else** (a DM participant, or a non-owner group member) → **"delete
  conversation for me."** The thread is cleared and hidden **on my side only** — it
  leaves both the inbox and the archived list, is unpinned, and its unread state is
  zeroed. It **re-appears automatically** (showing only messages newer than the clear
  point; older ones stay hidden for me) the next time the other side sends. My peer /
  the group is unaffected.

Under the hood this is a per-member `clearedBeforeMessageId` high-water mark: the
inbox query hides the thread while `lastMessageId <= clearedBeforeMessageId`, and the
read path floors every read at that id. There is **no `NOT_OWNER` here** — a non-owner
simply gets the delete-for-me branch (`NOT_OWNER` is reserved for explicit owner-only
group actions like transfer-ownership; see [groups.md](./groups.md)).

**Errors:** `403 NOT_A_MEMBER`; `404 CONVERSATION_NOT_FOUND`.

---

## `POST /conversations/{id}/read`

Advance my read marker to (and including) a Snowflake message id. Rewinds are
ignored (a lower id than my current marker is a no-op).

```jsonc
{ "lastReadMessageId": 172634000000000000 }   // required
```

→ **200**. Zeroes my `unreadCount`, clears any `markedUnread` flag, invalidates my
unread badge, and — unless a message request is still pending, the thread is
restricted, **or either side has read receipts turned off** (see
[settings.md](./settings.md)) — broadcasts `receipt.read` (my "blue tick") to the
other active members.

**Errors:** `400 BAD_REQUEST` (missing `lastReadMessageId`); `403 NOT_A_MEMBER`.

## `POST /conversations/{id}/unread` — mark as unread

Flag the thread **unread** in my inbox without any message being newer — the
WhatsApp/Telegram "Mark as unread". Purely personal (no receipt, nothing broadcast).
The flag surfaces as `markedUnread: true` (and forces `hasUnread: true`) on the
conversation, and is cleared automatically the next time I `POST …/read`.

→ **200** (empty body). `403 NOT_A_MEMBER` if I'm not a member.

## `POST /conversations/{id}/mute`

Mute suppresses push notifications, **not** the unread count.

```jsonc
{ "mutedUntil": "2026-08-01T00:00:00.000Z" }   // null to unmute
```

→ **200**. `403 NOT_A_MEMBER` if I'm not a member.

## `POST /conversations/{id}/pin`

```jsonc
{ "pinned": true }
```

→ **200** — pins the thread to the top of **my** inbox. `403 NOT_A_MEMBER` otherwise.

## `POST /conversations/{id}/archive`

```jsonc
{ "archived": true }
```

→ **200** — moves the thread to (or out of) **my** archived list; still visible there
(unlike delete-for-me). `403 NOT_A_MEMBER` otherwise.

## `POST /conversations/{id}/disappearing` — disappearing-messages timer

Set (or clear) the **disappearing-messages** timer for the whole conversation. Every
message sent *after* this call is written with a Cassandra TTL of `seconds` and is
gone once it elapses; existing messages are unaffected. The setting is
conversation-wide (not per-user), like WhatsApp/Signal/Telegram auto-delete.

```jsonc
{ "seconds": 604800 }   // 0 = off. Common presets: 86400 (24h), 604800 (7d), 7776000 (90d)
```

→ **200**. Writes a `DISAPPEARING_CHANGED` system message ("… set disappearing
messages to 7d" / "… turned off disappearing messages") and broadcasts
`conversation.updated` with the fresh payload (new `disappearingSeconds`). In a
**group** this needs the `CHANGE_SETTINGS` permission (admins-only by default); in a
DM either participant may set it.

**Errors:** `400 BAD_REQUEST` (`seconds` < 0); `403 NOT_A_MEMBER`;
`403 ADMINS_ONLY` (group, lacking `CHANGE_SETTINGS`); `404 CONVERSATION_NOT_FOUND`.

---

## `ConversationResponse` schema

Metadata plus the caller's own member state — the exact payload the inbox, the
archived list, and the thread header all render from.

```jsonc
{
  "id": "…",                               // UUID
  "type": "DIRECT",                        // "DIRECT" | "GROUP"
  "title": null,                           // null for DIRECT
  "description": null,                      // GROUP only, ≤ 500 chars; null when unset
  "avatarKey": null,                       // R2/S3 key (GROUP)
  "avatarUrl": null,                       // resolved URL, when set
  "ownerId": "…",                          // GROUP owner; null for DIRECT
  "memberCount": 2,
  "lastMessageId": 172630000000000000,     // Snowflake; null if no messages yet
  "lastMessageAt": "2026-07-20T14:36:00.000Z",
  "lastMessagePreview": "salam, how's the paper?",
  "groupSettings": { /* GroupSettings */ } | null,   // null for DIRECT
  "disappearingSeconds": 0,                // 0 = off; TTL applied to new messages

  // ── my member state ──
  "myRole": "OWNER",                       // OWNER | ADMIN | MEMBER
  "myStatus": "ACTIVE",                    // ACTIVE | RESTRICTED | LEFT | REMOVED
  "lastReadMessageId": 172629000000000000, // my read marker
  "lastDeliveredMessageId": 172629500000000000, // my delivered marker (double-tick support)
  "unreadCount": 3,                        // exact for small convos; not maintained for large groups
  "hasUnread": true,                       // messages past my read marker — the ONLY unread signal for large groups
  "markedUnread": false,                   // I flagged the thread unread via POST …/unread
  "mutedUntil": null,                      // datetime while muted
  "pinned": false,
  "archived": false,

  // ── DIRECT only: the other participant + their markers ──
  "peer": { "userId": "…", "username": "…", "fullName": "…" } | null,
  "peerLastReadMessageId": 172628000000000000,      // how far the peer has read (my blue tick); null if hidden
  "peerLastDeliveredMessageId": 172629000000000000, // how far the peer has received (my grey double-tick); null if hidden

  "createdAt": "2026-07-18T10:00:00.000Z"
}
```

| Field | Type | Notes |
|-------|------|-------|
| `type` | string | `DIRECT` \| `GROUP` |
| `title` / `description` / `avatarKey` / `avatarUrl` / `ownerId` / `groupSettings` | — | GROUP fields; `null` on a DIRECT thread |
| `lastMessageId` | Snowflake\|null | `null` before the first message |
| `disappearingSeconds` | int | `0` = off; else the per-message TTL applied to future messages |
| `myRole` | string | `OWNER` \| `ADMIN` \| `MEMBER` |
| `myStatus` | string | `ACTIVE` \| `RESTRICTED` \| `LEFT` \| `REMOVED` |
| `lastDeliveredMessageId` | Snowflake | My own delivered high-water mark |
| `unreadCount` | int | Exact for small conversations; not maintained past 256 members |
| `hasUnread` | boolean | Always meaningful — messages exist past my read marker (or `markedUnread`) |
| `markedUnread` | boolean | I flagged this thread unread; cleared on next `read` |
| `peer` | object\|null | `{ userId, username, fullName }`; DIRECT only (avatars hydrated via the user API) |
| `peerLastReadMessageId` | Snowflake\|null | DIRECT only — the peer's read marker; **`null` when either side has read receipts off** ([settings.md](./settings.md)) |
| `peerLastDeliveredMessageId` | Snowflake\|null | DIRECT only — the peer's delivered marker; same privacy gate |

`GroupSettings` (mirrors the `PATCH` body above): `sendMode`, `whoCanAddMembers`,
`whoCanEditInfo`, `whoCanPin` (each `ALL_MEMBERS` | `ADMINS_ONLY`),
`adminsCanPromote`, `historyVisibleToNewMembers`.

---

## Error codes

Beyond the shared envelope ([error-handling.md](../errors/error-handling.md)), the
codes that surface on this API:

| `errorCode` | HTTP | Meaning |
|-------------|------|---------|
| `BLOCKED` | 403 | A block relationship prevents starting the DIRECT thread (never reveals who blocked whom). |
| `NOT_A_MEMBER` | 403 | You are not a (readable/active) member of the conversation. |
| `ADMINS_ONLY` | 403 | A `PATCH` field required admin/owner permission you don't hold. |
| `NOT_OWNER` | 403 | Reserved for owner-only group actions (transfer/ownership — see [groups.md](./groups.md)); `DELETE` here never raises it. |
| `CONVERSATION_NOT_FOUND` | 404 | The conversation is missing or has been deleted. |
| `BAD_REQUEST` | 400 | Self-DM, missing `recipientId`/`title`, `PATCH` on a DIRECT thread, a missing `lastReadMessageId`, or a negative disappearing `seconds`. |
