# 05 — Realtime Delivery

How a message gets from one person's screen to another's in well under a second,
using the SSE + Redis + RabbitMQ layer you already run.

## The single stream

Each logged-in client opens **one** SSE stream and keeps it open:

```
GET /api/v1/messaging/stream?token=<jwt>
Accept: text/event-stream
```

- Token in the query string because `EventSource` can't send headers — the same
  pattern your post/story/notification streams already use.
- The holding instance registers `(userId → thisInstance)` in Redis and
  subscribes to the user's fan-out routing key.
- On reconnect the client sends `Last-Event-ID`; the server replays anything
  missed from a short Redis buffer, and the client also does a **gap sync** (see
  [06](06-algorithms.md)) so nothing is ever lost.

Every realtime signal for that user multiplexes over this one stream, tagged by
`event:` and `conversationId` (catalogue in
[01-architecture.md](01-architecture.md)).

## Fan-out across instances

The sender and the recipient's live stream are usually on **different app
instances**. The bus bridges them:

```
send instance                bus                         holding instance
─────────────    publish   ────────    consume   ─────────────────────────
MessageService ──────────► RabbitMQ ──────────► SSE dispatcher ─► client
                 routing    exchange  per-user     writes event
                 key =      chat.fanout  queue      down stream
                 user:{id}
```

- **RabbitMQ** gives durable, at-least-once delivery and a natural place to hang
  the **push-notification** consumer for offline users.
- **Redis pub/sub** is the low-latency path for ephemeral signals (typing,
  presence) where durability doesn't matter and you want minimum hops.
- Rule of thumb: **durable + must-arrive → RabbitMQ; ephemeral + fine-to-drop →
  Redis pub/sub.** You already run both.

For each recipient of a message, publish once with routing key `user:{recipientId}`.
The dispatcher on whichever instance holds that user's stream delivers it; if no
instance holds it, the message is offline → enqueue push.

## Presence (online / offline / last seen)

Pure Redis, heartbeat + TTL:

```
On stream open:      SET presence:{userId} = "online"  EX 30
Client heartbeat:    every 20s the client pings; server refreshes EX 30
On stream close:     key expires within 30s → user goes offline
last_seen:           on close, write last_seen timestamp to Postgres (throttled)
```

Contacts subscribe to presence changes; when `presence:{userId}` transitions,
publish a `presence` event to that user's connected friends. Don't broadcast
presence to strangers or blocked users.

## Typing indicators

Ephemeral, never stored:

```
POST /api/v1/conversations/{id}/typing   { "isTyping": true }
```

- Server publishes a `typing` event (Redis pub/sub) to the *other* members.
- A Redis key `typing:{conv}:{user}` with `EX 6` auto-clears if the client stops
  sending — no explicit "stopped typing" needed.
- Suppressed for RESTRICTED threads and unaccepted requests (the sender must not
  learn the recipient is around).

## Delivery & read receipts (Messenger's sent / delivered / read)

Three states, tracked cheaply:

| State | When | How |
|-------|------|-----|
| **Sent** | Server accepted + wrote the message | 201 response to the sender |
| **Delivered** | Recipient's device received it over SSE (or push) | Client POSTs `/delivered`; server publishes `receipt.delivered` |
| **Read** | Recipient opened the conversation | Client POSTs `/read` with `lastReadMessageId` |

```
POST /api/v1/conversations/{id}/read   { "lastReadMessageId": 172634... }
```

- Advances `conversation_members.last_read_message_id`.
- Recomputes `unread_count` (→ 0, or count of messages after the new marker).
- Publishes `receipt.read` to the other members so their ticks turn blue.
- For groups, "read by" is the set of members whose `last_read_message_id ≥
  messageId` — computed on demand, not stored per message.

Respect privacy: if a user disables read receipts, you still advance their unread
locally but **don't** publish `receipt.read` to others (and correspondingly hide
others' receipts from them — symmetric, like every messenger).

## Idempotent sends (no duplicates, ever)

The network will retry. Guarantee exactly-once *effect*:

```
1. Client generates clientNonce (UUID) per message, reused across retries.
2. Server: key = nonce:{userId}:{clientNonce}
   SET key <messageId> NX EX 600   (Redis, only-if-absent)
   - if it set: this is the first time → create the message, store the id.
   - if it didn't set: a previous attempt won → return that messageId, don't
     create a second row.
3. Client reconciles: replaces its optimistic bubble with the returned message.
```

This makes send safe to retry on timeouts, reconnections, and duplicate taps.

## Offline path

When fan-out finds no live stream for a recipient:

```
message.new (no holding instance)
   └─► RabbitMQ push queue
         ├─► FCM / APNs worker  → phone push notification (respect mute)
         └─► after N minutes still unread → email digest worker
```

Mute (`muted_until`) suppresses push but **not** the unread count — same as
Messenger. Restricted threads and pending requests never push.

## Latency budget (target)

```
client POST ─(≤30ms)─ permission+snowflake ─(≤15ms)─ Cassandra write
   ─(≤10ms)─ Postgres updates ─(≤5ms)─ publish ─(bus ≤20ms)─
   dispatcher ─(SSE flush)─► recipient screen        ≈ 80–150ms end to end
```

Everything on the hot path is a single-partition write or a non-blocking publish.
There is no scan, no join on the write path, no polling anywhere.
