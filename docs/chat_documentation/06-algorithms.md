# 06 — Core Algorithms

The precise mechanics behind the fast paths. These are the "best algorithm"
pieces you asked for — each is small, and each exists to avoid a slow operation.

## 1. Snowflake generation

```
CUSTOM_EPOCH = 1_700_000_000_000   // fixed ms; never change once live

generateId(nodeId):
    now   = currentMillis()
    if now == lastMillis:
        seq = (seq + 1) & 0xFFF          // 12-bit sequence, wraps at 4096/ms
        if seq == 0: now = waitNextMillis(lastMillis)   // spin to next ms
    else:
        seq = 0
    lastMillis = now
    return ((now - CUSTOM_EPOCH) << 22) | (nodeId << 12) | seq

timestampOf(id): return (id >> 22) + CUSTOM_EPOCH
```

`nodeId` is the instance id (from config/env). 4096 IDs per millisecond per node
is far beyond any single-node send rate. IDs are monotonic per node and globally
sortable by time — this is what makes ordering and pagination free.

## 2. Bucket computation (Cassandra partition sizing)

```
BUCKET_DAYS = 10

bucketOf(messageId):
    ms   = timestampOf(messageId)
    days = ms / 86_400_000
    return days / BUCKET_DAYS          // integer division
```

Writer and reader both call this — no stored coupling. Tune `BUCKET_DAYS` down for
very high-traffic conversations if partitions approach the size limit; you can
even make it per-conversation-type (smaller for large groups).

## 3. Reading messages — the bucket walk

Fetch `n` messages at or before a cursor, never scanning a partition that can't
hold data. `created_at` and `last_message_id` come from the Postgres conversation
row, so the valid bucket range is known up front.

```
loadPage(conversationId, cursorId or NOW, n):
    startBucket = bucketOf(cursorId ?? last_message_id)
    minBucket   = bucketOf(created_at)          // don't scan before the convo existed
    out = []
    bucket = startBucket

    while len(out) < n and bucket >= minBucket:
        rows = CQL:
            SELECT * FROM messages_by_conversation
            WHERE conversation_id = :c AND bucket = :bucket
              [AND message_id < :cursorId]        // only in the first bucket
            ORDER BY message_id DESC
            LIMIT (n - len(out))
        out += rows
        bucket -= 1                                // step to older window
        cursorId = null                            // subsequent buckets take the whole window

    nextCursor = out.isEmpty ? null : out.last().message_id
    return { messages: out, nextCursor }
```

- **First page**: `cursorId = null`, starts at the newest bucket → newest 50.
- **Scroll up**: pass the previous `nextCursor` → older 50, walking buckets back.
- Each CQL query touches **one partition**. A quiet DM might walk several empty
  buckets, but the range is bounded by `minBucket`, so it terminates fast.

## 4. Unread counts — cheap, no counting in Cassandra

Counting rows in Cassandra is expensive; never do it on the hot path. Maintain a
counter instead.

**On send** (small groups / DMs — eager):

```
for member in activeMembers(conversation) where member != sender:
    UPDATE conversation_members
       SET unread_count = unread_count + 1
     WHERE conversation_id = :c AND user_id = :member
```

**On read**:

```
UPDATE conversation_members
   SET last_read_message_id = :lastReadId,
       unread_count = 0
 WHERE conversation_id = :c AND user_id = :me
```

(If messages can arrive between the client's snapshot and the read, set
`unread_count = number of messages with id > :lastReadId`, which for a DM is
usually 0–few and can be derived from the last page already in memory rather than
a Cassandra count.)

**Total badge**: cache `unread_total:{userId}` in Redis, adjusted by the same
deltas; rebuild lazily from the Postgres sum if the key is missing.

**Large groups (> 256)** — skip eager fan-out (it's N writes per message).
Instead store the group's `last_message_id` and compute a member's unread lazily
when they open their inbox: `unread ≈ position(last_message_id) −
position(last_read_message_id)`, approximated, or simply show a dot ("new
messages") rather than an exact number. Exactness in a 5,000-person group is not
worth 5,000 writes per message.

## 5. Ordering & gap detection (never lose or dupe a message)

Because IDs are time-sortable, the client keeps messages sorted by `messageId` and
can detect holes:

```
onReceive(msg):
    if msg.id <= lastContiguousId: ignore          // duplicate (idempotency safety net)
    if msg.id is the expected next: append, advance lastContiguousId
    else: // gap — we missed something (reconnect, dropped event)
        fetch messages_by_conversation where id in (lastContiguousId, msg.id)
        splice them in, then advance
```

On stream reconnect the client also calls **sync**:

```
GET /api/v1/conversations/{id}/messages?after=<lastKnownId>
```

which returns everything newer than the client's high-water mark. Combined with
idempotent sends, this gives **exactly-once display** even across flaky networks.

## 6. Direct-conversation get-or-create (race-safe)

Two users tapping each other simultaneously must not create two DM rows:

```
directKey = min(a,b) + ':' + max(a,b)
INSERT INTO conversations (id, type, direct_key, ...)
VALUES (:newId, 'DIRECT', :directKey, ...)
ON CONFLICT (direct_key) DO NOTHING
RETURNING id;
-- if no row returned, SELECT the existing one by direct_key
```

The `UNIQUE (direct_key)` constraint makes the database the arbiter — no locks, no
race.

## 7. Fan-out strategy — write vs read

| | Fan-out on write | Fan-out on read |
|---|---|---|
| What | Copy each message into every recipient's inbox | Keep one copy per conversation; readers pull |
| Good for | Feeds/timelines with heavy read, light write | **Chat** — reads are scoped to an open conversation |
| Cost | N writes per message | 1 write per message |

**Chat uses fan-out on read**: one message row per conversation (plus the
`message_by_id` copy), and readers query the conversation partition. The only
"fan-out on write" in chat is the small **unread counter** increment and the
**live event** publish — both cheap, and both skippable for huge groups. This is
why a DM send is ~1 Cassandra write + a couple of indexed Postgres updates, no
matter how active the conversation.

## 8. Soft delete & edit

- **Edit**: update `body` + `edited_at` on both `messages_by_conversation` and
  `message_by_id`; publish `message.edited`. History retained only if you add an
  `message_edits` audit table (optional).
- **Delete**: set `deleted = true` (tombstone), null the `body`/`media`; the row
  stays so ordering/pagination don't break; clients render "message deleted".
  Publish `message.deleted`.

Never hard-delete from the middle of a partition on the hot path — tombstones are
the Cassandra-friendly way.
