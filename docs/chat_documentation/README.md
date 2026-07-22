# IRC Platform — Chat & Messaging

Design documentation for the **Messenger/Telegram-style chat system** on the IRC
platform. Written to slot into the existing backend (Spring Boot + Cassandra +
PostgreSQL + Redis + RabbitMQ + Elasticsearch + R2/S3) and to reuse the existing
`follow / block / restrict` social model and SSE realtime layer — **no new
transport, no WebSockets**, consistent with the rest of the platform.

## Why this design

You asked for the *fastest* stack and the *best mechanism*. The honest answer is
that you already have it. The chat system does not need a new database — it needs
the right **data model on top of the databases you already run**. Speed in chat
comes almost entirely from three decisions:

1. **Partition messages correctly in Cassandra** so every read hits exactly one
   bounded partition (this is the single most important decision).
2. **Time-sortable message IDs (Snowflake)** so ordering and pagination are free
   and never require a secondary sort.
3. **Fan-out over Redis/RabbitMQ to SSE streams** so delivery is push, not poll.

Cassandra is the correct choice for the message log — it is write-optimised,
horizontally scalable, and stores time-series rows per conversation extremely
well. It is *not* the right store for relational, transactional data (who is in a
group, who is an admin, unread counters). That goes in PostgreSQL. This split is
called **polyglot persistence** and it is exactly what your platform already does.

## The database split at a glance

| Data | Store | Why |
|------|-------|-----|
| Messages (the log) | **Cassandra** | High write volume, append-only, time-series, per-conversation partitions |
| Conversations, members, roles | **PostgreSQL** | Relational, transactional, moderate cardinality, needs joins |
| Per-user read state, unread counts | **PostgreSQL** (hot values cached in Redis) | Needs correctness + cheap increments |
| Message requests inbox | **PostgreSQL** | Small, relational, per-recipient queries |
| Presence, typing, unread badge cache | **Redis** | Ephemeral, TTL-based, sub-millisecond |
| Cross-instance fan-out of events | **RabbitMQ + Redis pub/sub** | Durable delivery + live push to SSE |
| Media (images, voice notes, files) | **R2 / S3** via your media proxy | Already built, Range support for audio/video |
| In-conversation search | **Elasticsearch** | Already built |

> If you ever outgrow Cassandra's latency at very high scale, **ScyllaDB** is a
> drop-in, Cassandra-compatible, C++ rewrite that is dramatically faster on the
> same data model — this is the exact path Discord took. You would not have to
> redesign anything in this document. Treat it as a future lever, not a day-one
> decision.

## Document map

| File | Covers |
|------|--------|
| [01-architecture.md](01-architecture.md) | Components, responsibilities, the send/receive request flows end to end |
| [02-data-model.md](02-data-model.md) | Full Cassandra + PostgreSQL schemas, Snowflake IDs, bucketing |
| [03-permissions-and-requests.md](03-permissions-and-requests.md) | Friends/block/restrict gating, message requests, the permission engine |
| [04-group-chats.md](04-group-chats.md) | Roles, permission matrix, admin mechanics, system messages |
| [05-realtime-delivery.md](05-realtime-delivery.md) | SSE + Redis + RabbitMQ, presence, typing, receipts, idempotency |
| [06-algorithms.md](06-algorithms.md) | Snowflake, bucket walk, unread counts, ordering, gap detection, fan-out strategy |
| [07-api-surface.md](07-api-surface.md) | Endpoint catalogue in your existing conventions (design sketch) |
| [08-scaling-and-roadmap.md](08-scaling-and-roadmap.md) | Scaling limits, ScyllaDB path, encryption honesty, phased rollout |
| [09-api-reference.md](09-api-reference.md) | **The as-built API contract** — every implemented endpoint, request/response schema, realtime event payloads, and error codes |

### Backend implementation deep-dives (the shipped code, explained)

| File | Covers |
|------|--------|
| [10-implementation-map.md](10-implementation-map.md) | The whole module — layered architecture, every class, the bean graph, the polyglot store map, a reading guide |
| [11-send-path.md](11-send-path.md) | The write path in full: idempotency, permission dispatch, the two Cassandra writes, unread fan-out, realtime + notifications |
| [12-read-path.md](12-read-path.md) | The read path: the bucket-walk pagination, gap-sync, hidden-history floors, bulked hydration, reactions, search |
| [13-realtime-internals.md](13-realtime-internals.md) | SSE + Redis pub/sub, the emitter lifecycle, the after-commit broadcaster, presence, typing, delivery guarantees |
| [14-permissions-internals.md](14-permissions-internals.md) | The DM truth table, the group permission matrix, message requests, restrict/block privacy |
| [15-data-model-internals.md](15-data-model-internals.md) | Snowflake + bucketing, the Cassandra + Postgres schemas, the get-or-create race, the transaction/concurrency model |
| [16-groups-search-notifications.md](16-groups-search-notifications.md) | Group lifecycle, invite links, pinned messages, Elasticsearch search, the notification integration |

> **Implemented.** The design in files 01–08 is now shipped under
> `ak.dev.irc.app.chat`. [09-api-reference.md](09-api-reference.md) is the
> authoritative client contract; **[10-implementation-map.md](10-implementation-map.md)
> is the entry point to the backend code** and links to the deep-dives 11–16.

## Design principles

- **Reuse, don't reinvent.** The social graph, media proxy, SSE layer, error
  envelope, JWT auth, and cursor paging already exist. Chat is a new *topic* on
  the same rails.
- **One partition per read.** Any message read that touches more than one
  Cassandra partition is a design bug.
- **Push, never poll.** Clients hold one SSE stream; the server pushes deltas.
- **Idempotent sends.** Every send carries a client nonce so retries never
  duplicate.
- **Permission before persistence.** A message is authorised *before* it is
  written, using the existing block/restrict/follow status.
- **Honest about hard parts.** End-to-end encryption, unread counts in huge
  groups, and ordering under partition are called out explicitly rather than
  hand-waved (see [08](08-scaling-and-roadmap.md)).
