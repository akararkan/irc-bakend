# 15 — Data Model Internals: Schema, IDs, and the Transaction Model

This is the deep-dive companion to [02-data-model.md](02-data-model.md). Where
`02` sketches the intended schema, this file documents the **as-built** code:
the actual entity classes, the boot-time schema initializers, the Snowflake bit
math, the bucketing arithmetic, and — most importantly — the **transaction and
concurrency model** that keeps counters correct and DM creation race-free under
load. Every mechanism below is described in terms of the real class + method that
implements it, with the code quoted verbatim. Read this when you need to change
the schema, touch a counter, or reason about what happens when two requests race.

---

## Table of contents

1. [The polyglot split — what lives where and why](#1-the-polyglot-split)
2. [Snowflake IDs — the 41+10+12 layout](#2-snowflake-ids)
3. [Bucketing — bounded Cassandra partitions](#3-bucketing)
4. [The Cassandra tables — verbatim, and the UDT gotchas](#4-the-cassandra-tables)
5. [The Postgres entities — embedded keys, JSONB, audit columns](#5-the-postgres-entities)
6. [Get-or-create DM — race-safety by database constraint](#6-get-or-create-dm-race-safety)
7. [The transaction & concurrency model](#7-the-transaction--concurrency-model)
8. [Schema management — `ddl-auto=update` vs the manual SQL](#8-schema-management)

---

## 1. The polyglot split

Chat spreads its state across four stores. This is **polyglot persistence**: each
datum lives in the engine whose access pattern it matches, not in one database
forced to do everything. The split is not an accident of history — it is the
single most important performance decision in the whole subsystem, because it
lets every hot read hit exactly one bounded structure.

| Data | Store | Concrete artifact | Why this store |
|------|-------|-------------------|----------------|
| The message log (every message ever sent) | **Cassandra** | `messages_by_conversation`, `message_by_id` | Append-heavy, time-series, per-conversation. Cassandra is write-optimised and scales horizontally; a message is written far more often than any single one is re-read. Partitioned so each read touches one partition. |
| Message reactions | **Cassandra** | `reactions_by_message` | One row per (message, user); add/remove is a single-partition upsert/delete. Co-located with the log for the same write-scaling reasons. |
| Conversations, members, roles, per-user read state | **PostgreSQL** | `Conversation`, `ConversationMember`, … | Relational, transactional, moderate cardinality, needs joins (the inbox is a join) and needs **correct atomic counters** (unread, member count). Cassandra has no cross-row transactions; Postgres does. |
| Message requests, invites, pinned messages | **PostgreSQL** | `MessageRequest`, `ConversationInvite`, `ConversationPin` | Small, relational, per-recipient / per-conversation queries with uniqueness constraints that Cassandra cannot enforce cheaply. |
| Aggregate reaction counts, unread badge, presence, typing | **Redis** | `chat:reactions:{messageId}`, unread-badge cache, presence/typing keys | Ephemeral or derived, TTL-based, sub-millisecond. Recomputable from the source of truth, so it can be lost without data loss. |
| Full-text search inside conversations | **Elasticsearch** | chat search index (`chatSearch.indexAsync`) | Relevance ranking + tokenised text search, which neither Cassandra nor Postgres does well. Fed asynchronously off the write path so it never blocks a send. |
| Media bytes (images, voice notes, files) | **R2 / S3** | referenced by `MediaRef.storageKey` | Large binary objects with Range support for audio/video seeking. Chat stores only the object key + a convenience URL; it never stores bytes in a database. |

**The load-bearing rule.** *Anything that must be counted correctly under
concurrency lives in Postgres; anything that is a high-volume append lives in
Cassandra; anything ephemeral lives in Redis.* Unread counts are the clearest
example — counting unread rows in Cassandra would be a partition scan, so instead
the count is an atomic integer column on the Postgres `conversation_members` row
(see §7). The message body those counts refer to still lives in Cassandra.

---

## 2. Snowflake IDs

Source: `ak.dev.irc.app.chat.util.SnowflakeIdGenerator`.

Every message ID is a **64-bit time-sortable Snowflake**, not a UUID and not a
Postgres sequence. This one choice buys ordering, pagination, and bucketing for
free, with no coordination between nodes.

### 2.1 The bit layout

```
 63                    22        12       0
┌──┬────────────────────┬─────────┬─────────┐
│0 │  timestamp (41b)   │node(10b)│ seq(12b)│
└──┴────────────────────┴─────────┴─────────┘
   ms since CUSTOM_EPOCH   │         └─ per-ms sequence (0..4095)
                           └────────── worker / instance id (0..1023)
```

The constants that carve up the 64 bits:

```java
public static final long CUSTOM_EPOCH = 1_700_000_000_000L; // 2023-11-14T22:13:20Z

private static final long NODE_BITS = 10L;
private static final long SEQ_BITS  = 12L;

private static final long MAX_NODE_ID  = (1L << NODE_BITS) - 1; // 1023
private static final long MAX_SEQUENCE = (1L << SEQ_BITS) - 1;  // 4095

private static final long NODE_SHIFT      = SEQ_BITS;             // 12
private static final long TIMESTAMP_SHIFT = SEQ_BITS + NODE_BITS; // 22
```

- **Bit 63 (the sign bit) is always 0.** IDs stay positive so they compare
  correctly as a signed `long` — which is exactly how both Cassandra `bigint` and
  Postgres `bigint` order them. No unsigned trickery, no reversed comparisons.
- **41 bits of timestamp** = ~69 years of milliseconds. Measured from
  `CUSTOM_EPOCH` (2023-11-14), the window runs out around **2093**.
- **10 bits of node** = 1024 distinct instances.
- **12 bits of sequence** = 4096 IDs per millisecond per node ≈ **4.1M IDs/s/node**,
  far beyond any realistic single-node send rate.

### 2.2 `CUSTOM_EPOCH` — why it must never move

The epoch is a fixed offset subtracted from wall-clock millis before the value is
shifted into the high 41 bits. It is chosen to sit safely in the past so the
41-bit window doesn't overflow for ~69 years. The doc comment is emphatic and
correct:

> Fixed millisecond epoch — 2023-11-14T22:13:20Z. **Never change once live:** every
> existing ID's embedded timestamp (and thus every stored message's bucket) is
> measured from here.

If you ever changed `CUSTOM_EPOCH`, `timestampOf` would return a wrong wall-clock
time for **every previously-minted ID**, and — because the Cassandra partition
bucket is derived from that timestamp (§3) — the reader would compute the wrong
bucket and fail to find historical messages. The epoch is effectively part of the
on-disk format.

### 2.3 Why time-sortable makes three things free

Because the creation time lives in the **high** bits, numeric order *is* time
order. That single property collapses three otherwise-separate problems:

1. **Ordering is free.** The Cassandra clustering key is `message_id DESC`
   (§4). Newest-first comes straight out of the clustering order with **zero extra
   columns** and no secondary sort — no `created_at` tiebreak, no server-side ORDER BY.
2. **Pagination is free.** A page cursor is just `message_id < :cursor`. No
   OFFSET, no keyset tuple `(created_at, id)` — one column does it. See the
   bucket-walk in [06-algorithms.md](06-algorithms.md).
3. **Bucketing is free.** The partition bucket is recoverable from the ID alone
   via `timestampOf`, so the writer and reader agree on the partition **without
   storing any coupling** (§3).

The extractor is a single shift + add:

```java
/** Extracts the wall-clock creation time (epoch millis) embedded in an ID. */
public static long timestampOf(long id) {
    return (id >>> TIMESTAMP_SHIFT) + CUSTOM_EPOCH;
}
```

Note the **unsigned** right shift `>>>`. It doesn't matter here because bit 63 is
always 0, but it is the correct defensive choice.

### 2.4 Generation, monotonicity, and clock-backwards handling

`nextId()` is `synchronized`. The comment justifies the lock: the whole body is a
handful of arithmetic ops on shared counters, so contention is negligible next to
the Cassandra write that follows, and the lock guarantees strict per-node
monotonicity even when a burst fills a millisecond's 4096-slot sequence.

```java
public synchronized long nextId() {
    long now = System.currentTimeMillis();

    if (now < lastTimestamp) {
        // Clock moved backwards (NTP step). Rather than mint a non-monotonic
        // id, pin to the last observed time and let the sequence advance —
        // ordering is preserved and the drift is at most a few ms.
        now = lastTimestamp;
    }

    if (now == lastTimestamp) {
        sequence = (sequence + 1) & MAX_SEQUENCE;
        if (sequence == 0) {
            // Sequence exhausted for this ms — spin to the next millisecond.
            now = waitNextMillis(lastTimestamp);
        }
    } else {
        sequence = 0L;
    }

    lastTimestamp = now;

    return ((now - CUSTOM_EPOCH) << TIMESTAMP_SHIFT)
            | (nodeId << NODE_SHIFT)
            | sequence;
}
```

Three edge cases are handled deliberately:

- **Clock runs backwards (NTP step).** Instead of minting a smaller (non-monotonic)
  ID, the generator **pins to `lastTimestamp`** and lets the sequence advance.
  Ordering is preserved; the embedded timestamp drifts by at most a few ms until
  wall-clock catches up. The alternative — throwing, as the classic Twitter
  implementation does — was rejected because a brief backward step should not fail
  a user's send.
- **Sequence exhausted (>4096 IDs in one ms).** `waitNextMillis` busy-spins to the
  next millisecond rather than wrapping into the previous ms's ID space:

  ```java
  private static long waitNextMillis(long lastTimestamp) {
      long now = System.currentTimeMillis();
      while (now <= lastTimestamp) {
          now = System.currentTimeMillis();
      }
      return now;
  }
  ```

- **Node identity.** `nodeId` is set from `app.node-id` / `APP_NODE_ID`; when
  unset (`-1`) it is derived from the hostname hash so a horizontally-scaled
  deployment gets distinct-enough node ids without per-pod config:

  ```java
  private static long resolveNodeId(long configured) {
      if (configured >= 0) {
          return configured & MAX_NODE_ID;
      }
      try {
          String host = InetAddress.getLocalHost().getHostName();
          return (host.hashCode() & 0x7fffffff) % (MAX_NODE_ID + 1);
      } catch (Exception e) {
          return (Thread.currentThread().hashCode() & 0x7fffffff) % (MAX_NODE_ID + 1);
      }
  }
  ```

  **Failure mode / accepted tradeoff:** two pods whose hostnames hash to the same
  node id could theoretically mint the same ID — but only if they also collide on
  the *same millisecond and the same sequence slot*, which is astronomically
  unlikely. For guaranteed uniqueness at large fleet sizes, set `APP_NODE_ID`
  explicitly per pod.

**Complexity:** `nextId()` is O(1), lock-held, no I/O (except the rare spin, which
lasts under 1 ms). `timestampOf` / `nodeOf` are pure O(1) bit ops and are `static`
so callers (e.g. `ChatBuckets`) use them without a bean reference.

---

## 3. Bucketing

Source: `ak.dev.irc.app.chat.util.ChatBuckets`.

### 3.1 The problem: unbounded partitions

A Cassandra partition must stay bounded — the rule of thumb is **under ~100 MB and
~100k rows**. If the partition key were just `conversation_id`, a busy group with
millions of messages would pile them all into one partition, and read latency
would rot as that partition grew. So the partition key is
`(conversation_id, bucket)` and history is sliced into fixed time windows.

### 3.2 The arithmetic

```java
public static final int BUCKET_DAYS = 10;
private static final long MS_PER_DAY = 86_400_000L;

/** Bucket for a Snowflake message id. */
public static int bucketOf(long messageId) {
    return bucketForTimestamp(SnowflakeIdGenerator.timestampOf(messageId));
}

/** Bucket for a raw epoch-millis timestamp (used for the created_at floor). */
public static int bucketForTimestamp(long epochMillis) {
    long days = epochMillis / MS_PER_DAY;
    return (int) (days / BUCKET_DAYS);
}

/** Bucket for the current wall clock — the newest partition to write into. */
public static int currentBucket() {
    return bucketForTimestamp(System.currentTimeMillis());
}
```

`bucket = floor(daysSinceUnixEpoch / 10)`. Note the timestamp fed to
`bucketForTimestamp` is the **raw Unix epoch millis** returned by `timestampOf`
(which already added `CUSTOM_EPOCH` back in), so buckets are measured against the
Unix epoch, not the Snowflake epoch — a consistent absolute grid every node agrees
on. Ten days keeps even a very active DM's partition comfortably under the limit,
while a quiet chat walks only a handful of empty buckets when scrolling back.

### 3.3 Writer == reader agreement (the whole point)

The bucket is a **pure function of the message's Snowflake timestamp**. Nothing is
stored to couple a message to its partition — the writer computes the bucket at
insert time from the ID it just minted, and the reader computes the *identical*
bucket from the same ID (or from a cursor ID) with no extra lookup:

- **Writer** (`MessageService.persist`): `int bucket = ChatBuckets.bucketOf(messageId);`
  then writes into `((conversation_id, bucket), message_id)`.
- **Reader**: given a cursor `message_id`, `bucketOf(cursor)` tells it exactly
  which partition to query; when a bucket is exhausted it steps to `bucket - 1`.
  The valid range `[bucketForTimestamp(created_at) .. currentBucket()]` is always
  known because Postgres holds the conversation's `created_at`. The full walk is in
  [06-algorithms.md](06-algorithms.md).

**Why this matters:** a naïve design would store the bucket on the Postgres row and
join to find it, or would do a token-range scan. Both cost a round-trip or a scan
per read. Deriving the bucket from the ID makes the mapping **free and
stateless** — the defining property of the whole data model.

### 3.4 Tunability and the migration-free guarantee

`BUCKET_DAYS` can be lowered for extreme-traffic conversations **without any data
migration**: only newly-written rows land in finer buckets, and the reader's
`[minBucket..maxBucket]` walk still terminates because it iterates the integer
range regardless of window size. (Old rows keep their old, coarser bucket — which
is fine, since the reader visits every integer bucket in range anyway. The only
observable effect of a change is that future partitions are smaller.)

**Complexity:** every bucket op is O(1) integer arithmetic, zero I/O.

---

## 4. The Cassandra tables

The chat keyspace objects are created explicitly and idempotently at boot by
`ak.dev.irc.app.chat.cassandra.ChatCassandraSchemaInitializer`, a
`@Component` that injects the auto-configured `CqlSession` and runs a
`@PostConstruct`. This mirrors the existing `CassandraCounterTableInitializer`
pattern.

### 4.1 Why an explicit initializer at all (the UDT-before-table ordering)

Chat introduces the project's **first Cassandra UDT**, `media_ref`. A table with a
`list<frozen<media_ref>>` column **fails hard at creation if the type does not yet
exist**. Spring Data's `schema-action: create_if_not_exists` (set in
`application.yaml`) does create user types before the tables that reference them,
but the initializer *removes all doubt* by ordering the statements manually:
`CREATE TYPE IF NOT EXISTS` first, then each `CREATE TABLE IF NOT EXISTS`. Every
statement is idempotent, so it is a harmless no-op once the schema exists and
co-exists safely with the entity-driven schema-action.

```java
@PostConstruct
public void createChatSchema() {
    try {
        session.execute("""
            CREATE TYPE IF NOT EXISTS %s.media_ref (
                kind          text,
                storage_key   text,
                url           text,
                thumbnail_key text,
                thumbnail_url text,
                mime          text,
                bytes         bigint,
                width         int,
                height        int,
                duration_ms   int,
                waveform      text,
                file_name     text,
                alt_text      text
            )""".formatted(keyspace));

        session.execute("""
            CREATE TABLE IF NOT EXISTS %s.messages_by_conversation (
                conversation_id uuid,
                bucket          int,
                message_id      bigint,
                sender_id       uuid,
                type            text,
                body            text,
                media           list<frozen<media_ref>>,
                reply_to_id     bigint,
                forwarded_from  uuid,
                mentions        set<uuid>,
                edited_at       timestamp,
                deleted         boolean,
                system_event    text,
                created_at      timestamp,
                PRIMARY KEY ((conversation_id, bucket), message_id)
            ) WITH CLUSTERING ORDER BY (message_id DESC)""".formatted(keyspace));
        // ... message_by_id and reactions_by_message follow ...
```

**Failure mode handled:** the whole block is wrapped in a `try/catch` that only
*warns*, because a local dev environment may have no Cassandra at all — the app
already tolerates a missing broker, so schema init is non-fatal and a clearer
error surfaces on first real use if the cluster is genuinely misconfigured.

### 4.2 `messages_by_conversation` — the core log

Primary key `PRIMARY KEY ((conversation_id, bucket), message_id)` with
`CLUSTERING ORDER BY (message_id DESC)`:

- **Composite partition key `(conversation_id, bucket)`** — one partition per
  conversation per 10-day window (§3). A single page read touches exactly one
  partition.
- **Clustering key `message_id DESC`** — newest first, so the latest page is the
  head of the partition and a cursor page is a `message_id < ?` slice.

The entity, `MessageByConversationEntity`, maps this with Spring Data Cassandra's
`@PrimaryKeyColumn` annotations, and this is where the **snake_case gotcha** lives
(see §4.5):

```java
@Table("messages_by_conversation")
public class MessageByConversationEntity {

    @PrimaryKeyColumn(name = "conversation_id", type = PrimaryKeyType.PARTITIONED, ordinal = 0)
    private UUID conversationId;

    @PrimaryKeyColumn(name = "bucket", type = PrimaryKeyType.PARTITIONED, ordinal = 1)
    private Integer bucket;

    @PrimaryKeyColumn(name = "message_id", type = PrimaryKeyType.CLUSTERED, ordinal = 2,
                      ordering = Ordering.DESCENDING)
    private Long messageId;

    @Column("sender_id")       private UUID senderId;
    @Column("type")            private String type;
    @Column("body")            private String body;
    @Column("media")           private List<MediaRef> media;
    @Column("reply_to_id")     private Long replyToId;
    @Column("forwarded_from")  private UUID forwardedFrom;
    @Column("mentions")        private Set<UUID> mentions;
    @Column("edited_at")       private Instant editedAt;
    @Column("deleted")         private Boolean deleted;
    @Column("system_event")    private String systemEvent;
    @Column("created_at")      private Instant createdAt;
}
```

`deleted` is a **tombstone flag (soft delete)**: the row stays so ordering and
pagination don't develop holes; an edit/delete flips the flag rather than removing
the clustering position.

### 4.3 `message_by_id` — the by-ID lookup twin

Replies, forwards, "jump to message", and — critically — **edit/delete** need to
find a message by its ID *without knowing its bucket*. A Cassandra mutation of the
log table requires the full primary key `(conversation_id, bucket, message_id)`,
so `message_by_id` supplies exactly those:

```java
@Table("message_by_id")
public class MessageByIdEntity {

    @PrimaryKey
    @Column("message_id")
    private Long messageId;

    @Column("conversation_id") private UUID conversationId;
    @Column("bucket")          private Integer bucket;
    // ... sender_id, type, body, media, reply_to_id, forwarded_from,
    //     mentions, deleted, edited_at, system_event, created_at ...
}
```

This is **denormalisation** — the same message is stored twice — which is *normal
and correct* in Cassandra: you model per query, not per entity. The two rows are
written in the same logical operation. `MessageService.persist` builds both and
saves them back to back (see [11-send-path.md](11-send-path.md) for the full
write path):

```java
messageRepo.save(row);       // messages_by_conversation
messageByIdRepo.save(byId);  // message_by_id
```

**Accepted tradeoff:** these are two separate writes, **not** an atomic Cassandra
`BATCH`. A crash between them could leave a message readable by-ID but missing from
the log page (or vice-versa). Given Snowflake ordering and the fact that both
writes target the same coordinator with the same partition-less semantics, this
window is tiny and the platform accepts it rather than pay the logged-batch cost.

### 4.4 `reactions_by_message` and the `media_ref` UDT

`ReactionByMessageEntity` is partitioned by `message_id`, clustered by `user_id`,
so "who reacted to this message" is a one-partition scan and add/remove is a single
upsert/delete. Display **counts** are cached in Redis
(`chat:reactions:{messageId}` hash of emoji→count) and recomputed lazily — the
Cassandra rows are the source of truth, Redis is the fast aggregate.

`MediaRef` is the **first `@UserDefinedType` in the entire project**. A message
stores a `list<frozen<media_ref>>` so one bubble can carry an album:

```java
@UserDefinedType("media_ref")
public class MediaRef {
    @Column("kind")          private String kind;   // IMAGE | VIDEO | VOICE | FILE
    @Column("storage_key")   private String storageKey; // R2/S3 object key — source of truth
    @Column("url")           private String url;         // proxy URL convenience
    @Column("thumbnail_key") private String thumbnailKey;
    @Column("thumbnail_url") private String thumbnailUrl;
    @Column("mime")          private String mime;
    @Column("bytes")         private Long bytes;
    @Column("width")         private Integer width;
    @Column("height")        private Integer height;
    @Column("duration_ms")   private Integer durationMs; // ms, NOT seconds
    @Column("waveform")      private String waveform;     // base64 peaks for voice notes
    @Column("file_name")     private String fileName;
    @Column("alt_text")      private String altText;
}
```

Media **bytes** never enter Cassandra; they live in R2/S3 and are referenced by
`storageKey`, served via the existing media proxy (`GET /api/v1/media/{storageKey}`,
with Range support for audio/video seeking). `url` is a denormalised convenience so
the client renders without a round-trip. Note `durationMs` is in **milliseconds**,
a deliberate departure from the seconds used elsewhere in the codebase, for
sub-second voice-note waveform alignment.

### 4.5 The `@Column("message_id")` gotcha — Spring Data Cassandra does **not** snake_case

This is the subtle one. **Spring Data Cassandra does not auto-convert camelCase
Java field names to snake_case column names.** Unlike Spring Data JDBC/JPA (which,
with the default naming strategy, would map `messageId` → `message_id`), the
Cassandra mapping uses the field name **verbatim** unless you override it. So a
field `private Long messageId;` with no annotation would map to a column literally
named `messageId` — which does **not** exist in the CQL table (the column is
`message_id`), and the mapping would fail or silently miss.

That is why **every** field in `MessageByConversationEntity`,
`MessageByIdEntity`, `ReactionByMessageEntity`, and `MediaRef` carries an explicit
`@Column("snake_case")` (and the key fields carry `@PrimaryKeyColumn(name=...)` /
`@Column(...)`). It is not decorative — it is required for the entity to bind to
the hand-written CQL schema. If you add a column, you **must** add the explicit
`@Column("...")`; forgetting it is the most likely chat-Cassandra bug.

---

## 5. The Postgres entities

All relational state extends `ak.dev.irc.app.common.BaseAuditEntity` and is managed
by Hibernate/JPA. Types map as: `UUID`→`uuid`, Snowflake ids (`message_id`,
`last_read_message_id`, …)→`bigint`, enums→`varchar` via `@Enumerated(STRING)`,
`LocalDateTime`→`timestamp` (the JVM default timezone is pinned to UTC in
`IrcApplication.main`, which is what keeps stored values in UTC), group
settings→`jsonb`.

### 5.1 `Conversation` — direct-key uniqueness and JSONB

```java
@Entity
@Table(
    name = "conversations",
    uniqueConstraints = @UniqueConstraint(name = "uk_conversation_direct_key", columnNames = "direct_key"),
    indexes = {
        @Index(name = "idx_conversation_last_msg", columnList = "last_message_at DESC"),
        @Index(name = "idx_conversation_owner", columnList = "owner_id")
    }
)
public class Conversation extends BaseAuditEntity {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING) @Column(name = "type", nullable = false, length = 8)
    private ConversationType type;                    // DIRECT | GROUP

    @Column(name = "direct_key", length = 73)
    private String directKey;                         // 'minUserId:maxUserId'; null for GROUP

    @Column(name = "last_message_id")   private Long lastMessageId;   // Snowflake
    @Column(name = "last_message_at")   private LocalDateTime lastMessageAt;
    @Column(name = "last_message_preview", length = 160) private String lastMessagePreview;

    @Column(name = "member_count", nullable = false) @Builder.Default
    private int memberCount = 0;                       // fan-out cutoff (≤256 eager unread)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "group_settings", columnDefinition = "jsonb")
    private GroupSettings groupSettings;               // GROUP knobs; null for DIRECT

    @Column(name = "deleted_at") private LocalDateTime deletedAt; // owner soft-delete
}
```

Two mechanisms to note:

- **`group_settings` as JSONB via `@JdbcTypeCode(SqlTypes.JSON)`.** The
  `GroupSettings` POJO is serialised into a single `jsonb` column. This lets new
  group knobs be added **without a schema migration** — you add a field to the
  DTO, not a column to the table. Postgres `jsonb` is queryable if ever needed, but
  chat treats it as an opaque settings blob. Null for DIRECT conversations.
- **`direct_key` uniqueness with nullable multiple NULLs.** `direct_key` is
  `varchar(73)` (two 36-char UUIDs + a colon) holding `min(a,b):max(a,b)` for
  DIRECT conversations, and **NULL for every GROUP**. The `UNIQUE` constraint makes
  DM creation race-safe (§6). This works *because Postgres permits many NULLs under
  a unique constraint* — every group row has `direct_key = NULL` and they do not
  collide, while every DM pair has exactly one non-null key that must be unique.
  This is the crux of the whole get-or-create design.

`last_message_id` / `last_message_at` / `last_message_preview` are **denormalised
pointers** so the inbox is a pure Postgres join with zero Cassandra touch — the
inbox never reads the message log to render the preview line.

### 5.2 `ConversationMember` — `@EmbeddedId` + `@MapsId`

The membership row carries a **composite key** and also the per-user read/inbox
state (kept in one row rather than a separate read-state table, so the inbox join
and the "advance read marker / bump unread" writes stay single-table).

```java
@Embeddable
public class ConversationMemberId implements Serializable {
    @Column(name = "conversation_id", nullable = false) private UUID conversationId;
    @Column(name = "user_id", nullable = false)         private UUID userId;
}
```

```java
@Entity
@Table(name = "conversation_members", indexes = {
    @Index(name = "idx_member_inbox", columnList = "user_id, archived"),
    @Index(name = "idx_member_conversation", columnList = "conversation_id")
})
public class ConversationMember extends BaseAuditEntity {

    @EmbeddedId
    private ConversationMemberId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("conversationId")
    @JoinColumn(name = "conversation_id",
                foreignKey = @ForeignKey(name = "fk_member_conversation"))
    private Conversation conversation;

    @Column(name = "last_read_message_id", nullable = false) @Builder.Default
    private long lastReadMessageId = 0L;               // high-water read marker (Snowflake)

    @Column(name = "unread_count", nullable = false) @Builder.Default
    private int unreadCount = 0;
    // ... role, status, mutedUntil, pinned, archived, joinedAt ...
}
```

**Why `@EmbeddedId` + `@MapsId`.** The primary key is the pair
`(conversation_id, user_id)`. `@MapsId("conversationId")` ties the
`conversation_id` half of the embedded key to the `Conversation` association, so:

1. The inbox query can `JOIN FETCH m.conversation` and hydrate the conversation
   (and its denormalised preview) **in one round-trip**, no N+1.
2. The `conversation_id` column is stored once — the association and the key share
   it — rather than duplicated as a plain column plus a redundant FK.

The `user_id` half is left as a plain column inside the embedded key: the `User`
entity is *never* needed for a member row, only its id, so there is no association
overhead for it.

`last_read_message_id` is a **high-water mark** — because message ids are
time-sortable Snowflakes, "unread" is conceptually "messages with
`id > last_read_message_id`", and a read receipt just advances this integer and
zeroes `unread_count` (§7).

### 5.3 The other Postgres entities

- **`MessageRequest`** — Messenger-style first-contact inbox. `UNIQUE(recipient_id,
  requester_id)` enforces exactly one pending request per pair (the anti-spam
  backbone). `message_count` caps pre-acceptance spam.
- **`ConversationInvite`** — Telegram-style invite link. Stores only the
  **SHA-256 `token_hash`** (`UNIQUE`), never the plaintext token, so a DB leak
  can't reconstruct working links. `use_count` / `max_uses` / `expires_at` /
  `revoked` gate usability, consumed atomically (§7).
- **`ConversationPin`** — pinned message pointers. `UNIQUE(conversation_id,
  message_id)`. The pinned message **body** lives in Cassandra; this row only
  records *which* ids are pinned so "show pins" is a cheap relational lookup.

### 5.4 `BaseAuditEntity` — the shared audit columns

Every Postgres chat table carries the columns from `BaseAuditEntity`, populated by
Spring Data's `AuditingEntityListener`:

| Column | Source | Notes |
|--------|--------|-------|
| `created_at` | `@CreatedDate` | `updatable=false, nullable=false` |
| `updated_at` | `@LastModifiedDate` | |
| `created_by` / `updated_by` | `@CreatedBy` / `@LastModifiedBy` | acting user UUID |
| `created_by_ip` / `updated_by_ip` | `@PrePersist` / `@PreUpdate` hook | from `X-Forwarded-For` → `X-Real-IP` → `remoteAddr` |
| `created_by_device` / `updated_by_device` | same hook | `User-Agent`, truncated to 300 chars |
| `last_action` / `action_note` | `audit(...)` helper | `AuditAction` enum as varchar |

The IP/device capture reads the current `RequestContextHolder`, and **silently
skips** in non-HTTP contexts (batch jobs, tests) via a `try/catch(Exception
ignored)` — so a scheduled job or a unit test that persists a chat entity does not
NPE on a missing request.

---

## 6. Get-or-create DM race-safety

"Start a DM with user X" must return the *same* conversation no matter how many
times, or how concurrently, it is called — and two simultaneous first-messages
between the same pair must not create two conversations. The solution uses **the
database as the arbiter**, not an application lock.

### 6.1 The deterministic key

`ak.dev.irc.app.chat.util.DirectKeys` computes a key that is identical regardless
of who initiates:

```java
public static String of(UUID a, UUID b) {
    String sa = a.toString();
    String sb = b.toString();
    return (sa.compareTo(sb) <= 0) ? sa + ":" + sb : sb + ":" + sa;
}
```

`min(a,b):max(a,b)` — sorting the two UUID strings means `of(alice, bob)` and
`of(bob, alice)` produce the **same** string. Combined with `UNIQUE(direct_key)`
on `conversations`, at most one row can ever exist for a pair.

### 6.2 The two-bean split and the catch-outside-the-tx

The insert lives in its **own bean**, `ChatConversationFactory`, deliberately
separated from the orchestrating `ConversationService`:

```java
@Service
@RequiredArgsConstructor
public class ChatConversationFactory {

    @Transactional
    public UUID createDirect(UUID creatorId, UUID recipientId, String directKey) {
        Conversation c = conversationRepo.saveAndFlush(Conversation.builder()
                .type(ConversationType.DIRECT)
                .ownerId(creatorId)
                .directKey(directKey)
                .memberCount(2)
                .build());
        memberRepo.save(ConversationMember.of(c, creatorId, MemberRole.MEMBER));
        memberRepo.save(ConversationMember.of(c, recipientId, MemberRole.MEMBER));
        return c.getId();
    }
}
```

The caller, `ConversationService.createDirect`, is **intentionally
non-transactional** and does check-then-insert-then-recover-on-conflict:

```java
String key = DirectKeys.of(creatorId, recipientId);
UUID convId = conversationRepo.findByDirectKey(key).map(Conversation::getId).orElse(null);
if (convId == null) {
    try {
        convId = conversationFactory.createDirect(creatorId, recipientId, key);
    } catch (DataIntegrityViolationException race) {
        convId = conversationRepo.findByDirectKey(key)
                .map(Conversation::getId)
                .orElseThrow(() -> new BadRequestException("Could not create the conversation."));
    }
}
return get(convId, creatorId);
```

**Why the insert must be in a separate bean, and the catch must be outside its
transaction.** When the `UNIQUE(direct_key)` constraint fires, it does so at
**flush/commit** time and it **poisons the persistence context** — a Hibernate
session that has thrown a constraint violation is marked rollback-only and cannot
be reused for further queries. If the `try/catch` were *inside* the same
`@Transactional` method, the recovery re-fetch would run in that same poisoned,
rollback-only transaction and fail. By putting the insert in
`ChatConversationFactory.createDirect` (its own transaction, committed or rolled
back when that method returns through the proxy) and doing the **catch + re-fetch
in the non-transactional caller**, the recovery query runs in a *fresh* context and
cleanly reads the row the winning thread committed. The `saveAndFlush` forces the
constraint to fire *inside* the factory's transaction (not lazily at some later
commit), so the exception is thrown at the boundary the caller can catch.

**The race, step by step:**

1. Threads T1 and T2 both compute the same `key` and both find no existing row.
2. Both call `conversationFactory.createDirect(...)`.
3. One commits first (say T1); its `INSERT` succeeds.
4. T2's `INSERT` violates `UNIQUE(direct_key)` → `DataIntegrityViolationException`.
5. T2 catches it *outside* the factory tx, re-runs `findByDirectKey(key)`, and gets
   T1's committed row.
6. Both threads return the **same** conversation id. No lock, no lost write, no
   duplicate DM.

**Complexity:** the happy path is one `SELECT` + (if needed) one `INSERT`. The
raced path adds one more `SELECT`. No pessimistic lock is ever taken — the unique
index does the serialization.

---

## 7. The transaction & concurrency model

Postgres holds everything that must be **counted or gated correctly** under
concurrency. The techniques below are how it stays correct without pessimistic
locks.

### 7.1 `@Transactional` boundaries and the self-invocation pitfall

Spring's `@Transactional` works by wrapping the bean in a **proxy**; the
transaction only begins when the call crosses the proxy boundary. A method calling
another `@Transactional` method **on the same bean** (`this.foo()`) bypasses the
proxy, so the inner annotation is silently ignored — the classic self-invocation
trap.

Chat's create flow is shaped specifically to avoid it. There is **no** single
`create()` dispatcher on `ConversationService`; the controller calls the concrete
methods directly:

```java
// ConversationController.create(...)
if (req.getType() == ConversationType.GROUP) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(conversationService.createGroup(me, req));   // must engage @Transactional
}
// ...
return ResponseEntity.ok(conversationService.createDirect(me, req.getRecipientId())); // NON-transactional on purpose
```

And the service documents exactly why:

> the controller calls createDirect / createGroup directly (not a single
> dispatcher) so that createGroup's `@Transactional` engages through the Spring
> proxy — a same-bean dispatch would self-invoke and silently drop the
> transaction. createDirect is intentionally non-transactional (race-catch).

`createGroup` **needs** its transaction — it inserts the conversation, the owner
membership, N member rows, a system message, and fires notifications, all of which
must commit atomically. If a same-bean dispatcher called `this.createGroup(...)`,
the proxy would be bypassed, the whole thing would run with autocommit-per-repo,
and a mid-flight failure would leave a half-built group. `createDirect`, by
contrast, **must not** be transactional (see §6). Two methods with opposite
transaction requirements is precisely why they are invoked separately.

### 7.2 Atomic counter updates — `UPDATE ... SET x = x + delta`

Counters are never read-modify-written in Java (which would lose updates under
concurrency). They are single atomic SQL `UPDATE`s issued through `@Modifying`
repository methods, so the database applies the delta under its own row lock.

**Member count** — `ConversationRepository.adjustMemberCount`:

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE Conversation c SET c.memberCount = c.memberCount + :delta WHERE c.id = :id")
void adjustMemberCount(@Param("id") UUID id, @Param("delta") int delta);
```

**Unread fan-out** — `ConversationMemberRepository.bumpUnreadForOthers` bumps every
active/restricted member except the sender in **one** statement:

```java
@Modifying
@Query("""
    UPDATE ConversationMember m
       SET m.unreadCount = m.unreadCount + 1
     WHERE m.id.conversationId = :cid
       AND m.id.userId <> :sender
       AND (m.status = ...ACTIVE OR m.status = ...RESTRICTED)
    """)
int bumpUnreadForOthers(@Param("cid") UUID conversationId, @Param("sender") UUID senderId);
```

This is the "eager unread" write, and it is **only** issued for conversations at or
below `LARGE_GROUP_CUTOFF = 256` members (`convo.getMemberCount() <= LARGE_GROUP_CUTOFF`
in `MessageService.dispatch`). Above the cutoff, one `UPDATE` touching thousands of
rows per message would be a write amplifier, so unread is computed lazily instead.
`member_count` is the cutoff signal — one of the reasons it is a maintained counter.

### 7.3 Monotonic guards — `WHERE ... < :new`

Some updates must only ever move **forward**, even when a stale concurrent request
arrives late. Rather than lock and compare in Java, the guard is baked into the
`WHERE` clause so the update is a no-op when it would move backward.

**Inbox pointer** — `ConversationRepository.advanceLastMessage`:

```java
@Modifying
@Query("""
    UPDATE Conversation c
       SET c.lastMessageId = :messageId,
           c.lastMessageAt = :at,
           c.lastMessagePreview = :preview
     WHERE c.id = :id
       AND (c.lastMessageId IS NULL OR c.lastMessageId < :messageId)
    """)
int advanceLastMessage(...);
```

Because message ids are monotonic Snowflakes, `lastMessageId < :messageId`
guarantees a late-arriving concurrent send **cannot rewind** the preview to an
older message. It returns rows-affected: 0 means a newer message already won the
race, and the caller simply doesn't overwrite. No lock, no compare-and-set loop —
the predicate *is* the compare-and-set.

**Own read marker** — `ConversationMemberRepository.advanceOwnMarker` uses the same
`m.lastReadMessageId < :mid` guard so a sender's own marker (advanced on send so
they're never "unread" on their own message) only moves forward. The user-facing
read receipt in `ConversationService.markRead` applies the same rule in Java
(`if (lastReadMessageId <= me.getLastReadMessageId()) return; // no rewind`).

### 7.4 Guarded increment — closing the check-then-act race

`ConversationInvite.isUsable()` alone leaves a **TOCTOU race**: two users could
both pass the `useCount < maxUses` check, then both increment, exceeding the cap.
The fix folds the check *into* the atomic increment —
`ConversationInviteRepository.consumeUse`:

```java
@Modifying
@Query("""
    UPDATE ConversationInvite i SET i.useCount = i.useCount + 1
     WHERE i.id = :id
       AND i.revoked = false
       AND (i.expiresAt IS NULL OR i.expiresAt > CURRENT_TIMESTAMP)
       AND (i.maxUses IS NULL OR i.useCount < i.maxUses)
    """)
int consumeUse(@Param("id") UUID id);
```

`GroupMemberService.join` reads the return value as the source of truth: "Atomically
consume a use up-front so `maxUses` can't be exceeded under concurrency (guarded
UPDATE; 0 rows affected ⇒ exhausted/expired)":

```java
if (inviteRepo.consumeUse(invite.getId()) == 0) {
    throw new ForbiddenException("This invite link is invalid or has expired.", "INVITE_INVALID");
}
```

Only the thread whose `UPDATE` actually incremented the counter (rows affected = 1)
proceeds to add the member. Everyone else gets 0 and is rejected. The database row
lock serializes the increment; the `WHERE` predicate enforces the cap in the same
statement.

### 7.5 `@Modifying(flushAutomatically = true, clearAutomatically = true)` — why both flags

`adjustMemberCount` and `softDelete` carry **both** flush and clear flags, and the
reason is subtle:

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE Conversation c SET c.memberCount = c.memberCount + :delta WHERE c.id = :id")
void adjustMemberCount(@Param("id") UUID id, @Param("delta") int delta);

@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE Conversation c SET c.deletedAt = :at WHERE c.id = :id")
void softDelete(@Param("id") UUID id, @Param("at") LocalDateTime at);
```

- **`flushAutomatically = true`** pushes any *pending* managed-entity changes to
  the DB **before** the bulk `UPDATE` runs. In `GroupMemberService.addMembers`, a
  member's status was just changed via `memberRepo.save(...)` in the same
  transaction; flushing first ensures those writes hit the DB before the count
  delta, so ordering is deterministic.
- **`clearAutomatically = true`** detaches the persistence context **after** the
  bulk `UPDATE`. A bulk JPQL `UPDATE` bypasses the first-level cache — it changes
  the row in the DB but the *managed `Conversation` entity in the session still
  holds the old `memberCount`*. Without the clear, a subsequent read in the same
  transaction (e.g. the response's `conversationService.get(...)`) would return the
  **stale cached count**. Clearing forces the next read to reload from the DB and
  see the fresh value.

The repository comments state this exactly: *"clearAutomatically detaches the
persistence context so a subsequent read … sees the fresh count rather than a stale
cached row."*

### 7.6 Bulk update vs full-entity `save()` — don't clobber the counter

There is a specific ordering hazard the code calls out: after a bulk count update,
a subsequent **full-entity `save()`** on the same `Conversation` would write the
*entire* managed row back — including its **stale** `memberCount` — overwriting the
atomic delta. `GroupMemberService.leave` avoids this by using the **bulk
`softDelete`** instead of `conversation.setDeletedAt(...)` + `save`:

```java
conversationRepo.adjustMemberCount(conversationId, -1);
// ...
if (soleOwner) {
    // Sole owner leaving retires the group. Use a bulk soft-delete (not a
    // full-entity save) so it doesn't overwrite the atomic count decrement
    // above with a stale managed row.
    conversationRepo.softDelete(conversationId, LocalDateTime.now());
}
```

Because `adjustMemberCount` also cleared the context, the managed entity is stale;
a `save` would resurrect the old count. The bulk `softDelete` touches only
`deleted_at` and leaves the DB's `member_count` untouched. This is the reasoning
behind having a dedicated `softDelete` bulk method at all.

### 7.7 Read-only transactions

Pure reads (`get`, `inbox`, `archived`, `listMembers`) are annotated
`@Transactional(readOnly = true)`, which lets the JDBC driver / DB flag the
transaction read-only (skipping dirty-check flushes and enabling read-only
optimizations). The inbox read is a single `JOIN FETCH` query plus at most one
bulk peer-resolution query — see [02-data-model.md](02-data-model.md) and the inbox
JPQL in `ConversationMemberRepository.findInbox`.

---

## 8. Schema management

Two independent mechanisms create the schema, one per datastore.

### 8.1 Postgres — `ddl-auto=update` (with a manual SQL fallback)

`application.yaml` sets `spring.jpa.hibernate.ddl-auto: update`, so on boot
Hibernate **auto-creates** every chat table, index, and constraint from the entity
mappings. Chat adds only **new** tables — it does not alter or add columns to any
pre-existing table — so there is no destructive migration and nothing to back-fill.

`db/chat_schema.sql` is the **hand-written equivalent**, provided for reproducible
fresh installs / prod bootstrap or environments where `ddl-auto` is disabled. Its
header is explicit that you *probably don't need to run it by hand*. Every
statement is `IF NOT EXISTS`, so re-running is safe, and it must stay in sync with
the entity annotations (the entities are the source of truth under `ddl-auto`).

The file also documents that chat added new **enum values**
(`NEW_MESSAGE`, `MESSAGE_REQUEST`, `ADDED_TO_GROUP`) to existing notification
enums; those are stored as plain `varchar`, so **no `ALTER` is required** — the
columns already accept the new strings.

### 8.2 Cassandra — `schema-action` + the explicit initializer

`application.yaml` sets `spring.cassandra.schema-action: create_if_not_exists`, so
Spring Data would create the entity-mapped tables and the `media_ref` UDT. But as
§4.1 explains, chat **also** runs `ChatCassandraSchemaInitializer` at
`@PostConstruct` to guarantee the **UDT-is-created-before-the-tables** ordering
regardless of what schema-action does, because a `list<frozen<media_ref>>` column
cannot be created before its type exists. Both are idempotent (`IF NOT EXISTS`)
and co-exist safely; the explicit initializer is belt-and-suspenders for the one
ordering constraint Spring's mechanism doesn't let you assert directly.

**Net effect:** on a clean boot with both databases available, all chat schema —
Postgres tables *and* Cassandra tables + the UDT — is created automatically, no
manual step. The SQL file and the CQL comments at the bottom of `db/chat_schema.sql`
exist purely for hand-provisioned or `ddl-auto`-disabled environments.

---

## See also

- [02-data-model.md](02-data-model.md) — the schema overview this file drills into.
- [06-algorithms.md](06-algorithms.md) — the bucket-walk read algorithm, unread
  counting, gap detection.
- [03-permissions-and-requests.md](03-permissions-and-requests.md) — message
  requests and the block/restrict gating that references `MessageRequest`.
- [04-group-chats.md](04-group-chats.md) — roles, the permission matrix, and the
  membership lifecycle that drives `adjustMemberCount`.
- [11-send-path.md](11-send-path.md) — the write path (`MessageService.persist` /
  `dispatch`) that mints the Snowflake, writes the twin Cassandra rows, and fires
  the atomic counter updates described here.
