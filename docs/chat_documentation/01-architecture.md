# 01 — Architecture

## Component responsibilities

```
                          ┌─────────────────────────────────────────────┐
                          │            Spring Boot app instances         │
                          │  (each holds a share of live SSE streams)    │
   Client (web/mobile)    │                                              │
   ─── POST message ────► │  MessageController ─► PermissionEngine       │
                          │        │                    │                │
   ◄── SSE stream ─────── │        ▼                    ▼                │
                          │   MessageService      SocialStatusService    │
                          │     │     │                                  │
                          └─────┼─────┼──────────────────────────────────┘
                                │     │
        ┌───────────────────────┘     └───────────────────────┐
        ▼                                                      ▼
┌───────────────┐   ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
│   Cassandra   │   │  PostgreSQL   │   │     Redis     │   │   RabbitMQ    │
│  message log  │   │ convo + members│  │ presence/typing│  │  fan-out bus  │
│               │   │ read state     │  │ pubsub / cache │  │  push queue   │
└───────────────┘   └───────────────┘   └───────────────┘   └───────────────┘
                                                                    │
                                                                    ▼
                                                            ┌───────────────┐
                                                            │ Push workers  │
                                                            │ FCM / APNs /  │
                                                            │ email digest  │
                                                            └───────────────┘
```

Each Spring Boot instance is stateless except for the **SSE connections it
currently holds**. Because delivery goes through Redis pub/sub + RabbitMQ, *any*
instance can accept a send and *any* instance can hold the recipient's stream —
they don't have to be the same instance. This is already how your posts/stories
realtime works; chat reuses it.

## The two flows that matter

### A. Sending a message

```
1.  Client → POST /api/v1/conversations/{id}/messages
        headers: Authorization: Bearer <jwt>
        body:    { clientNonce, type, body, replyToId?, media[]? }

2.  Idempotency check
        Redis GET nonce:{userId}:{clientNonce}
        → if present, return the already-created message (retry-safe), STOP.

3.  Permission check  (see 03-permissions-and-requests.md)
        - membership + role/status for GROUP
        - block / restrict / connection status for DIRECT
        → may DENY, or ROUTE-TO-REQUEST, or ALLOW.

4.  Generate Snowflake messageId (time-sortable, globally unique).

5.  Write to Cassandra  (messages_by_conversation)
        partition = (conversationId, bucket(messageId))

6.  Update PostgreSQL in one transaction
        - conversations.last_message_id / last_message_at
        - increment unread_count for each ACTIVE member except sender
          (eager fan-out; see 06 for the large-group exception)

7.  Cache nonce → messageId in Redis (TTL ~10 min) for idempotency.

8.  Publish event to the fan-out bus
        RabbitMQ exchange "chat.fanout", routing key per recipient userId
        payload: message.new delta

9.  Return 201 with the created message (client reconciles its optimistic copy).
```

Steps 5–8 are the hot path. Step 5 is a single-partition insert. Step 6 is a few
indexed Postgres writes. Step 8 is a non-blocking publish. All fast.

### B. Receiving a message

```
1.  On login, client opens ONE SSE stream
        GET /api/v1/messaging/stream?token=<jwt>
        (token in query because EventSource can't set headers — same pattern
         you already use for post/story/notification streams)

2.  The instance registers the connection and subscribes (via Redis) to that
    user's fan-out routing key.

3.  When a message.new event lands on the bus for that user, the holding
    instance writes it down the SSE stream as an event:
        event: message.new
        data:  { conversationId, message, unreadCount }

4.  If the user has NO live stream (offline), the fan-out consumer instead
    enqueues a push notification (FCM/APNs) and, after a delay, an email digest.
```

One stream per user carries **everything**: new messages, edits, deletes,
reactions, typing, read receipts, presence, and group events — multiplexed by
`event:` name and `conversationId`. The client fans them out locally.

## Event catalogue (over the single SSE stream)

| `event:` | Meaning | Payload core |
|----------|---------|--------------|
| `message.new` | New message in a conversation the user is in | message, unreadCount |
| `message.edited` | Body edited | conversationId, messageId, newBody, editedAt |
| `message.deleted` | Soft-deleted (tombstone) | conversationId, messageId |
| `message.reaction` | Reaction added/removed | conversationId, messageId, emoji, userId, added |
| `receipt.read` | Someone read up to a message | conversationId, userId, lastReadMessageId |
| `receipt.delivered` | Delivered to a device | conversationId, userId, messageId |
| `typing` | Ephemeral typing indicator | conversationId, userId, isTyping |
| `presence` | Contact came online/offline | userId, status, lastSeen |
| `conversation.updated` | Title/avatar/settings changed | conversation |
| `member.changed` | Added/removed/promoted/demoted/left | conversationId, memberDelta |
| `request.new` | New message request arrived | request summary |

## Why SSE and not WebSockets

Chat *feels* bidirectional, but the two directions have different needs:

- **Down (server → client):** high volume, must be push. SSE is purpose-built for
  a durable one-way event stream, auto-reconnects with `Last-Event-ID`, and rides
  ordinary HTTP/2 — no special infra, no sticky sessions required because you
  already route through Redis pub/sub.
- **Up (client → server):** low volume, request/response. A plain `POST` per
  message/typing/read is simpler, cache-friendly, and trivially idempotent.

So the architecture is **SSE down, HTTP POST up** — full Messenger-grade chat
with zero WebSocket infrastructure, consistent with your existing eight SSE
streams. WebSockets remain a *possible* future optimisation only if you later add
something genuinely duplex and latency-critical (e.g. voice/video call
signalling); it is not needed for text, media, typing, or receipts. See
[08-scaling-and-roadmap.md](08-scaling-and-roadmap.md).
