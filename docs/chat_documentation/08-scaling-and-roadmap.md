# 08 — Scaling, Trade-offs & Roadmap

## Where the limits actually are

| Component | First bottleneck | Lever |
|-----------|------------------|-------|
| Cassandra | Wide partitions if bucketing is wrong | Correct `(conversationId, bucket)` + tune `BUCKET_DAYS`; then add nodes (linear) |
| PostgreSQL | Unread fan-out writes in huge groups | Cap eager fan-out at ≤256 members; lazy/approximate above |
| SSE tier | File descriptors / open connections per instance | Connections are cheap (idle); scale instances horizontally, route via Redis |
| Redis | Presence + pub/sub throughput | Separate Redis for pub/sub vs cache; cluster if needed |
| RabbitMQ | Queue depth on push spikes | Durable queues + more push workers; shard by user hash |

The design has **no single-conversation global lock** and **no cross-partition
read on the hot path**, which are the two things that usually kill chat systems.
That's the property to protect in every future change.

## The ScyllaDB lever (your "fastest" question, answered honestly)

Cassandra is the right call now and it's already in your stack. If you ever hit
its tail-latency ceiling at large scale, **ScyllaDB** is a drop-in replacement:
same CQL, same data model, same driver — rewritten in C++ with a shard-per-core
architecture, typically several times faster on identical schemas. Discord ran
exactly this migration (Cassandra → ScyllaDB) for their message store.

You would change **nothing** in this documentation to adopt it — the schemas in
[02](02-data-model.md) are already Scylla-compatible. So: build on Cassandra, keep
Scylla in your back pocket, don't pay for it before you need it.

## Honest trade-offs (things not to hand-wave)

**End-to-end encryption.** Messenger offers it optionally; Telegram only in
"secret chats"; Signal always. E2EE is fundamentally at odds with three features
you'll want early: **server-side search (Elasticsearch), multi-device sync, and
server-side moderation** — because the server can't read the plaintext. For a
scholarly community platform, transport encryption (TLS) + encryption at rest is
the pragmatic default; treat E2EE as a later, opt-in "secret chat" mode scoped to
1:1 only, not a day-one requirement. Don't promise it in the schema now.

**Exact unread in huge groups.** Called out in [06](06-algorithms.md): exact
per-member unread costs one write per member per message. Above a few hundred
members, switch to an approximate "new messages" indicator. This is what the big
apps do too.

**Ordering under network partition.** Snowflake gives a total order by *send
time*, but two users on skewed clocks or a partitioned bus could interleave
slightly differently for a few milliseconds. The client's sort-by-id +
gap-sync makes the *final* state converge identically for everyone. Accept
eventual consistency of the last second; don't chase perfect global real-time
ordering — it isn't worth the cost and users don't perceive it.

**Read receipts vs privacy.** Make them symmetric and toggleable from day one;
retrofitting a privacy toggle after launch is painful because clients cache the
old behaviour.

## Phased rollout

**Phase 1 — DMs that work.**
- Cassandra `messages_by_conversation` + `message_by_id`; Postgres
  `conversations` + `conversation_members`.
- Snowflake IDs, bucket walk, get-or-create DM.
- Permission engine (block/restrict/connected) + message requests.
- SSE stream, idempotent send, unread counts, read receipts.
- Media via existing R2 proxy (text, image, **voice notes** — you already have
  the voice-note UI).

**Phase 2 — Groups.**
- `type=GROUP`, roles, permission matrix, system messages, member lifecycle.
- Eager unread fan-out (≤256), admins-only mode, invite links.

**Phase 3 — Polish.**
- Reactions, replies, forwards, edit/delete, pinned messages.
- Typing indicators, presence, delivery receipts, mute/archive.
- In-conversation Elasticsearch search.

**Phase 4 — Scale & extras (only if needed).**
- Lazy/approximate unread for large groups; broadcast channels.
- Push notification tuning, email digests.
- Optional 1:1 "secret chat" E2EE; ScyllaDB migration if latency demands.

## One-line summary

Cassandra for the message log (partitioned by conversation + time bucket),
Postgres for who-can-do-what, Redis + RabbitMQ + SSE for push delivery, Snowflake
IDs to make ordering and paging free, and your existing block/restrict/follow
model for the Messenger-style status rules — reusing everything you already run,
adding no new transport, and leaving a clear, unforced path to Scylla and E2EE if
you ever need them.
