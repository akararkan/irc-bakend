# Chat Realtime API — SSE Stream, Typing, Presence, Unread Badge

The live layer of chat: a **single** per-user Server-Sent Events stream that
multiplexes every conversation event (new messages, edits, deletes, reactions,
receipts, typing, presence, group changes, incoming requests), plus the small
REST calls that ride alongside it — publish a typing indicator, batch-look-up
presence, and read the global unread badge.

- **Base path:** `/api/v1`
- **Auth:** `Authorization: Bearer <jwt>` on every endpoint. The SSE stream also
  accepts `?token=<jwt>` because `EventSource` can't set headers.
- **Errors:** the shared `ApiErrorResponse` envelope — see
  [Error handling](../errors/error-handling.md). Switch on `errorCode`.

**Delta-not-counts model.** Every realtime event carries the event *type* and its
subject ids — **never** fresh counter values. Clients apply `+1/-1` to their local
unread/reaction counters when an event arrives, avoiding stale re-reads. (This is
the platform-wide chat/post convention; Q&A is the deliberate exception that ships
absolute counts.)

**Suppression for private threads.** Receipts (`receipt.read` /
`receipt.delivered`), `typing`, and `presence` are **suppressed** whenever the
relationship isn't fully open:

- a **pending message request** (the stranger must not learn the recipient is
  around, reading, or typing),
- a **RESTRICTED** thread (the restricted member is delivered quietly), and
- any **block relationship** (presence always reports `offline`, no last-seen).

`message.new` still flows in those cases (quietly, peer-only) so the recipient
sees the message — it's only the "someone is here / reading / typing" signals that
go dark.

**User privacy switches (symmetric).** On top of the relationship rules above, each
user has three toggles ([settings.md](./settings.md)): read receipts, last-seen
visibility, and typing. Read receipts and last-seen are **reciprocal** — turn yours
off and you neither send **nor** receive that signal. Concretely: with read receipts
off you emit no `receipt.read` / `receipt.delivered` and see none; with last-seen
hidden the `presence` event still reports online/offline but carries no
`lastSeenEpochMs`; with typing off you emit no `typing`.

Related: [Conversations](./conversations.md) · [Messages](./messages.md) ·
[Groups](./groups.md) · [Message requests](./message-requests.md) ·
[Settings](./settings.md) · [Search](./search.md). Platform-wide SSE conventions:
[../realtime/overview.md](../realtime/overview.md).

---

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/messaging/stream?token=` | The single SSE stream for all of my chat events |
| `POST` | `/conversations/{id}/typing` | Publish an ephemeral typing indicator |
| `GET` | `/presence?userIds=a,b,c` | Batch online/offline + last-seen for a set of users |
| `GET` | `/messaging/unread-count` | Total unread badge across all my conversations |

---

## 1. `GET /messaging/stream` — the SSE stream

```
GET /api/v1/messaging/stream?token=<jwt>
Accept: text/event-stream
```

**Auth:** required — via the `Authorization` header **or** `?token=<jwt>` (an
`ACCESS`-type token; a `REFRESH` token is rejected). Missing/invalid credentials
close the connection with `401` and a plain-text hint rather than opening a zombie
stream.

The **one** stream that carries every chat event for the caller, across all of
their conversations, multiplexed by `event:` name and disambiguated by
`conversationId` inside the payload. One user may hold several tabs/devices; a push
fans out to all of them (capped at **5** emitters per user, LRU-evicted).

**Connection properties**

- **24-hour** server-side timeout (finite, never `0`); browsers auto-reconnect
  with `Last-Event-ID` — pair a reconnect with the [messages `sync`](./messages.md)
  endpoint for gap recovery so nothing is ever lost.
- A **`connected`** handshake fires immediately on subscribe; a **`heartbeat`**
  fires every **15 s** to keep proxies from idling the socket.
- Cross-instance fan-out is handled by Redis pub/sub — an event raised on any app
  instance reaches the tab on whichever instance holds the stream.
- **Presence is driven by this stream**: opening it marks the user online, each
  heartbeat refreshes a 30 s Redis TTL, and presence expires ~30 s after the last
  tab closes — no explicit offline signal needed.

### Lifecycle events

**`connected`** (once, on subscribe):

```json
{
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "timestamp": "2026-07-22T14:00:00.000",
  "tabs": 2,
  "message": "Chat stream active"
}
```

**`heartbeat`** (every 15 s):

```json
{ "timestamp": "2026-07-22T14:00:15.000" }
```

### Event catalogue

Every event's `data` is a single **`ChatRealtimeEvent`** shape — all fields
nullable, dispatched on `event:`, with null fields omitted from the wire
(`@JsonInclude(NON_NULL)`). The `event:` name is the wire value below (dotted
lowercase, e.g. `message.new`), **not** the enum constant.

| `event:` | Populated fields | Meaning |
|----------|------------------|---------|
| `message.new` | `conversationId`, `message` | New message. Fans out to every member's stream (and the sender's other devices); for request/restricted threads it reaches **only the peer**. |
| `message.edited` | `conversationId`, `messageId`, `body`, `editedAt` (also `message`) | A text message's body was edited. |
| `message.deleted` | `conversationId`, `messageId` | Message tombstoned (soft-delete); reactions cleared. |
| `message.reaction` | `conversationId`, `messageId`, `userId`, `emoji`, `added` | Reaction added (`added: true`) or removed (`added: false`). |
| `receipt.read` | `conversationId`, `userId`, `lastReadMessageId` | `userId` has read up to `lastReadMessageId` — turn their ticks blue. *Suppressed for pending/restricted threads, or if either side has read receipts off.* |
| `receipt.delivered` | `conversationId`, `userId`, `messageId` | Message reached `userId`'s device (their delivered marker advances — grey double-tick). *Same suppression as `receipt.read`.* |
| `typing` | `conversationId`, `userId`, `isTyping` | `userId` is (`true`) / is no longer (`false`) typing. Ephemeral. *Suppressed while a request is pending, or if `userId` has typing off.* |
| `presence` | `userId`, `presenceStatus` (`"online"`/`"offline"`), `lastSeenEpochMs` | A contact came online / went offline. *Never sent across a block; `lastSeenEpochMs` omitted when last-seen is hidden.* |
| `conversation.updated` | `conversationId`, `conversation?`, `memberChange?` | Title/avatar/settings edited, message pinned/unpinned, group deleted (`memberChange: "DELETED"`), or a request accepted. |
| `member.changed` | `conversationId`, `userId`, `memberChange`, `role` | Group membership change: `ADDED` / `REMOVED` / `LEFT` / `PROMOTED` / `DEMOTED` / `RESTRICTED` / `UNRESTRICTED`. Also delivered directly to the affected user. |
| `request.new` | `conversationId`, `request` | A new inbound message request arrived (surface it in the Requests inbox). |
| `connected` / `heartbeat` | — (lifecycle payloads above) | Stream open handshake / keepalive. |

### `ChatRealtimeEvent` payload schema

One class covers every event; clients dispatch on `eventType` and read whichever
fields are populated. `conversationId` is present on all conversation-scoped
events; `presence` carries only `userId` + presence fields.

```jsonc
{
  "eventType": "MESSAGE_REACTION",      // enum constant; the wire event: name is "message.reaction"
  "conversationId": "1f3c…",

  // message.new (+ message.edited convenience copy)
  "message": { /* MessageResponse — see messages.md §Response schemas */ },

  // message.edited / message.deleted / message.reaction / receipt.delivered
  "messageId": 172630000000000000,
  "body": "edited text",                // message.edited
  "editedAt": "2026-07-22T14:01:00.000Z",

  // message.reaction
  "emoji": "👍",
  "added": true,

  // receipt.read / typing / presence / member.changed — the subject user
  "userId": "9c1f…",
  "lastReadMessageId": 172630000000000000,  // receipt.read
  "isTyping": true,                          // typing
  "presenceStatus": "online",                // presence: "online" | "offline"
  "lastSeenEpochMs": 1753192800000,          // presence (offline)

  // conversation.updated
  "conversation": { /* ConversationResponse */ },

  // member.changed
  "memberChange": "ADDED",              // ADDED|REMOVED|LEFT|PROMOTED|DEMOTED|RESTRICTED|UNRESTRICTED (or DELETED on conversation.updated)
  "role": "MEMBER",

  // request.new
  "request": { /* MessageRequestResponse */ },

  "timestamp": "2026-07-22T14:01:00.000Z"
}
```

Note there are **no counter fields** — apply `+1/-1` locally per the delta model
above. `MessageResponse`, `ConversationResponse`, and `MessageRequestResponse`
shapes live in [Messages](./messages.md), [Conversations](./conversations.md), and
[Message requests](./message-requests.md).

### Client handling notes

- Treat `message.new` on **your own** send as a de-dup opportunity (it also reaches
  your other devices) — reconcile by `clientNonce`/`messageId`.
- On `conversation.updated` with `memberChange: "DELETED"`, drop the conversation
  from the inbox and tear down its view.
- On reconnect, replay via `Last-Event-ID` then run the messages `sync` gap-fill;
  don't assume the buffer held everything.

**Errors:** `401` (plain text) when no valid credential is presented; the stream
never opens.

---

## 2. `POST /conversations/{id}/typing` — typing indicator

```
POST /api/v1/conversations/{id}/typing
Content-Type: application/json
```

**Auth:** required. Publishes a `typing` event to the **other** members of the
conversation over Redis pub/sub. Purely ephemeral — never stored.

| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Path — the conversation |

**Request body (`TypingRequest`):**

```jsonc
{ "isTyping": true }
```

`isTyping: false` is optional — a Redis TTL (~6 s) auto-clears a stale "typing" if
the client simply stops sending, so you don't have to send an explicit stop.

**Response:** `200 OK` (empty body).

**Side effects:** emits a `typing` event to the other members. **Suppressed while a
message request is still pending** (the requester must not learn the recipient is
present), and **when the caller has turned typing indicators off**
([settings.md](./settings.md)).

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 403 | `NOT_A_MEMBER` | Caller isn't an active member of the conversation |

---

## 3. `GET /presence` — batch presence lookup

```
GET /api/v1/presence?userIds=a,b,c
```

**Auth:** required. Batch online/offline + last-seen for a set of users — call it
once with a conversation's member ids to paint every avatar's presence dot.

| Param | Type | Description |
|-------|------|-------------|
| `userIds` | UUID[] | Query — comma-separated user ids to look up |

Presence is resolved from **the caller's vantage point**: any user in a block
relationship with the caller is always reported `offline` with a `null`
`lastSeenEpochMs`, so presence is never leaked across a block. The block set is
fetched once (cached), not per id.

Last-seen is also **symmetrically gated** by the privacy setting
([settings.md](./settings.md)): a user who hid their last-seen comes back with
`lastSeenEpochMs: null` (status still resolves), and if **you** hid yours, every
result is stripped of its last-seen too. Online/offline `status` is never hidden by
this switch.

**Response `200`** (`List<PresenceResponse>`):

```json
[
  { "userId": "9c1f1a2b-3344-5566-7788-99aabbccddee", "status": "online",  "lastSeenEpochMs": null },
  { "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690", "status": "offline", "lastSeenEpochMs": 1753192800000 }
]
```

| Field | Type | Description |
|-------|------|-------------|
| `userId` | UUID | The looked-up user |
| `status` | string | `"online"` while any of their tabs holds the stream (30 s TTL), else `"offline"` |
| `lastSeenEpochMs` | long \| null | Epoch millis of last activity; `null` while online |

**Errors:** `401 AUTH_UNAUTHORIZED` without a JWT.

---

## 4. `GET /messaging/unread-count` — global unread badge

```
GET /api/v1/messaging/unread-count
```

**Auth:** required. The single number for the app-icon / nav badge: total unread
across every conversation, Redis-cached (kept live by the eager per-member unread
counters the send path maintains).

**Response `200`:**

```json
{ "count": 7 }
```

Keep this in sync locally off the stream: apply `+1` per inbound `message.new` in a
conversation you haven't read, and reset a conversation's contribution when you
`POST /conversations/{id}/read` (see [Conversations](./conversations.md)).

**Errors:** `401 AUTH_UNAUTHORIZED` without a JWT.

