# 02 — Data Model

This is the most important file. Chat performance lives and dies here.

## Message IDs — Snowflake

Every message gets a 64-bit **Snowflake** ID, not a UUID and not an
auto-increment. A Snowflake encodes the creation time in its high bits, so:

- IDs are **globally unique** without coordination,
- IDs are **time-sortable** — sorting by ID == sorting by time,
- the **timestamp is derivable from the ID**, which lets you compute a message's
  Cassandra bucket without reading any extra column,
- pagination is a simple `messageId < cursor` — no offset, no secondary sort.

```
 63                    22        12       0
┌──┬────────────────────┬─────────┬─────────┐
│0 │  timestamp (41b)   │node(10b)│ seq(12b)│
└──┴────────────────────┴─────────┴─────────┘
   millis since custom epoch  │        │
                              │        └─ per-ms sequence counter
                              └────────── worker/instance id
```

Reuse the same Snowflake generator you already use for Cassandra cursor paging on
feeds. `timestamp(id) = (id >> 22) + CUSTOM_EPOCH`.

---

## Cassandra — the message log

### `messages_by_conversation`

The core table. **One partition per `(conversationId, bucket)`**, ordered so the
newest message is first.

```cql
CREATE TABLE messages_by_conversation (
    conversation_id   uuid,
    bucket            int,        -- time bucket, see below
    message_id        bigint,     -- Snowflake, time-sortable
    sender_id         uuid,
    type              text,       -- TEXT | IMAGE | VIDEO | VOICE | FILE | SYSTEM
    body              text,       -- null for pure-media messages
    media             list<frozen<media_ref>>,
    reply_to_id       bigint,     -- Snowflake of the replied message, nullable
    forwarded_from    uuid,       -- source conversation, nullable
    mentions          set<uuid>,
    edited_at         timestamp,
    deleted           boolean,    -- tombstone flag (soft delete)
    system_event      text,       -- for SYSTEM messages: MEMBER_ADDED, etc.
    created_at        timestamp,
    PRIMARY KEY ((conversation_id, bucket), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);

CREATE TYPE media_ref (
    kind        text,     -- image | video | audio | file
    storage_key text,     -- R2/S3 object key (served via your media proxy)
    mime        text,
    bytes       bigint,
    width       int,
    height      int,
    duration_ms int,      -- for voice notes / video
    waveform    text      -- base64 peaks for voice-note rendering
);
```

**Why the bucket exists.** Cassandra partitions must stay bounded (rule of thumb:
under ~100 MB and ~100k rows). A busy group with millions of messages in one
partition would rot performance. The bucket splits each conversation's history
into fixed time windows so no single partition grows without limit.

```
bucket = floor( daysSinceEpoch(timestamp(message_id)) / BUCKET_DAYS )
BUCKET_DAYS = 10   // tunable; smaller for very busy conversations
```

Because the timestamp comes straight out of the Snowflake, both **writer and
reader compute the same bucket with no extra lookup**.

**Reading the latest page** (single partition per query):

```cql
SELECT * FROM messages_by_conversation
WHERE conversation_id = ? AND bucket = ?
ORDER BY message_id DESC
LIMIT 50;
```

**Reading older messages (cursor = oldest messageId currently shown):**

```cql
SELECT * FROM messages_by_conversation
WHERE conversation_id = ? AND bucket = ? AND message_id < ?
LIMIT 50;
```

If a bucket returns fewer rows than the page size, the reader steps to
`bucket - 1` and continues — the exact **bucket-walk** algorithm is in
[06-algorithms.md](06-algorithms.md). The reader never scans blindly: PostgreSQL
holds the conversation's `created_at` and `last_message_id`, so the valid bucket
range `[bucket(created_at) .. bucket(now)]` is always known.

### `message_by_id` (lookup by single ID)

Replies, forwards, and "jump to message" need a message by ID without knowing its
bucket. Duplicate the row keyed by ID:

```cql
CREATE TABLE message_by_id (
    message_id       bigint PRIMARY KEY,
    conversation_id  uuid,
    bucket           int,
    sender_id        uuid,
    type             text,
    body             text,
    media            list<frozen<media_ref>>,
    reply_to_id      bigint,
    deleted          boolean,
    edited_at        timestamp,
    created_at       timestamp
);
```

Written in the same batch as the main insert. (Denormalisation is normal and
correct in Cassandra — you model per query, not per entity.)

### `reactions_by_message`

```cql
CREATE TABLE reactions_by_message (
    message_id  bigint,
    user_id     uuid,
    emoji       text,
    created_at  timestamp,
    PRIMARY KEY ((message_id), user_id)
);
```

One row per user per message (a user's latest reaction), so add/remove is a single
upsert/delete. Aggregate counts for display are cached in Redis
(`reactions:{messageId}` hash of emoji→count) and recomputed lazily.

---

## PostgreSQL — relational state

### `conversations`

```sql
CREATE TABLE conversations (
    id                 UUID PRIMARY KEY,
    type               VARCHAR(8) NOT NULL,      -- DIRECT | GROUP
    title              VARCHAR(120),             -- null for DIRECT
    avatar_key         VARCHAR(255),             -- R2/S3 key, GROUP only
    created_by         UUID NOT NULL,
    -- deterministic key for DIRECT convos to prevent duplicates:
    direct_key         VARCHAR(73),              -- 'minUserId:maxUserId', unique
    last_message_id    BIGINT,                   -- Snowflake of newest message
    last_message_at    TIMESTAMPTZ,
    last_message_preview VARCHAR(140),           -- denormalised for inbox list
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (direct_key)
);
CREATE INDEX idx_conv_last_msg ON conversations (last_message_at DESC);
```

`direct_key = min(a,b) || ':' || max(a,b)` guarantees a single 1:1 conversation
per pair — the `UNIQUE` constraint makes "get or create DM" race-safe.

### `conversation_members`

```sql
CREATE TABLE conversation_members (
    conversation_id       UUID NOT NULL,
    user_id               UUID NOT NULL,
    role                  VARCHAR(8)  NOT NULL DEFAULT 'MEMBER', -- OWNER|ADMIN|MEMBER
    status                VARCHAR(10) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE|RESTRICTED|LEFT|REMOVED
    -- per-user read + inbox state (kept here to avoid an extra table/join):
    last_read_message_id  BIGINT DEFAULT 0,
    unread_count          INT    NOT NULL DEFAULT 0,
    muted_until           TIMESTAMPTZ,           -- null = not muted
    pinned                BOOLEAN NOT NULL DEFAULT false,
    archived              BOOLEAN NOT NULL DEFAULT false,
    joined_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);
-- the inbox query: "my conversations, most recent first"
CREATE INDEX idx_member_inbox
    ON conversation_members (user_id, archived)
    INCLUDE (conversation_id, unread_count);
```

The **inbox / conversation list** is a Postgres join, not Cassandra:

```sql
SELECT c.*, m.unread_count, m.pinned, m.muted_until
FROM conversation_members m
JOIN conversations c ON c.id = m.conversation_id
WHERE m.user_id = :me AND m.status = 'ACTIVE' AND m.archived = false
ORDER BY m.pinned DESC, c.last_message_at DESC
LIMIT :size OFFSET :page * :size;   -- page/size, matching your Postgres convention
```

### `message_requests`

The Messenger-style "Message Requests" inbox for messages from people you're not
connected to.

```sql
CREATE TABLE message_requests (
    id               UUID PRIMARY KEY,
    conversation_id  UUID NOT NULL,
    requester_id     UUID NOT NULL,     -- who sent the first message
    recipient_id     UUID NOT NULL,     -- who must accept
    status           VARCHAR(10) NOT NULL DEFAULT 'PENDING', -- PENDING|ACCEPTED|DECLINED|BLOCKED
    first_message_id BIGINT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (recipient_id, requester_id)
);
CREATE INDEX idx_requests_inbox ON message_requests (recipient_id, status, created_at DESC);
```

### Read state, delivery, and receipts

`last_read_message_id` lives on `conversation_members` (above). A read receipt
just advances it and recomputes `unread_count`. Because message IDs are
time-sortable Snowflakes, "unread" is conceptually "messages with
`id > last_read_message_id`" — see [06](06-algorithms.md) for how the counter is
maintained cheaply without counting in Cassandra.

---

## What lives where — final map

| Question the app asks | Answered by |
|-----------------------|-------------|
| Give me the last 50 messages in this chat | Cassandra `messages_by_conversation` (1 partition) |
| Give me 50 older messages before cursor X | Cassandra, same table, bucket-walk |
| Show this one message (reply/jump/forward) | Cassandra `message_by_id` |
| Who reacted to this message | Cassandra `reactions_by_message` + Redis counts |
| List my conversations, newest first | PostgreSQL join |
| Who is in this group and what role | PostgreSQL `conversation_members` |
| How many unread do I have | PostgreSQL counter (Redis-cached badge) |
| Show my pending message requests | PostgreSQL `message_requests` |
| Is this person online / typing | Redis |
| Search text inside a conversation | Elasticsearch |
