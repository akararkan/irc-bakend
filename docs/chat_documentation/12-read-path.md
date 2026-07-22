# 12 — The Read Path & Pagination

Everything about how a client *reads* messages: paging a conversation
newest→older, gap-syncing after a reconnect, jumping to a single message,
listing reactions, in-conversation and cross-conversation search, and the pinned
list. The design goal is the same one that governs the whole chat subsystem —
**one bounded Cassandra partition per query, no N+1 hydration, no blind scans** —
and this document traces exactly how the code holds that line. The write side is
covered in [11-send-path.md](11-send-path.md); the storage model those reads walk
over is [02-data-model.md](02-data-model.md); the fast-path pseudocode summary is
[06-algorithms.md](06-algorithms.md); the as-built HTTP contract is
[09-api-reference.md](09-api-reference.md).

All of the read logic lives in one class,
`ak.dev.irc.app.chat.service.MessageQueryService`
(`src/main/java/ak/dev/irc/app/chat/service/MessageQueryService.java`), backed by
two Cassandra repositories, a Redis-backed reaction service, and Elasticsearch.
Every public method is `@Transactional(readOnly = true)`.

---

## 0. The two storage shapes a read can touch

The read path is only understandable once you internalise that a message is
stored **twice** in Cassandra (denormalised on write — see
[11-send-path.md](11-send-path.md)):

| Table / entity | Primary key | Clustering | What it's for |
|---|---|---|---|
| `messages_by_conversation` (`MessageByConversationEntity`) | partition `(conversation_id, bucket)` | `message_id DESC` | **Range reads** — a page is a single-partition slice, newest first |
| `message_by_id` (`MessageByIdEntity`) | `message_id` | — | **Point / bulk-id reads** — get-one, reply previews, and every `IN`-list hydration |

The partition key of the log table is `(conversation_id, bucket)`, and `bucket`
is derived *purely from the Snowflake timestamp* by `ChatBuckets`
(`src/main/java/ak/dev/irc/app/chat/util/ChatBuckets.java`):

```java
public static final int BUCKET_DAYS = 10;
private static final long MS_PER_DAY = 86_400_000L;

public static int bucketOf(long messageId) {
    return bucketForTimestamp(SnowflakeIdGenerator.timestampOf(messageId));
}
public static int bucketForTimestamp(long epochMillis) {
    long days = epochMillis / MS_PER_DAY;
    return (int) (days / BUCKET_DAYS);
}
public static int currentBucket() {
    return bucketForTimestamp(System.currentTimeMillis());
}
```

**Why it matters for reads:** the *reader* recomputes the identical bucket the
*writer* used, from the id alone, with zero stored coupling and zero extra
lookup. Given a cursor id, the reader knows precisely which partition that
message lives in — so pagination is a walk over a *known, finite* range of
partitions rather than an open-ended scan. `timestampOf` is the inverse of the
Snowflake layout (`SnowflakeIdGenerator`, high 41 bits = ms since
`CUSTOM_EPOCH`, shifted by 22):

```java
public static long timestampOf(long id) {
    return (id >>> TIMESTAMP_SHIFT) + CUSTOM_EPOCH;   // TIMESTAMP_SHIFT = 22
}
```

---

## 1. The bucket walk — `loadPage`

`loadPage` is the primary read: *"give me `limit` messages at or before `cursor`,
newest first."* Passing `cursor == null` means *"from the newest message."*
Passing back the previous page's `nextCursor` walks older. Here it is verbatim:

```java
@Transactional(readOnly = true)
public MessagePage<MessageResponse> loadPage(UUID conversationId, UUID userId, Long cursor, int limit) {
    Conversation convo = requireConversation(conversationId);
    ConversationMember me = requireReadableMember(conversationId, userId);

    long minBucketTsFloor = historyFloorMillis(convo, me);
    int minBucket = ChatBuckets.bucketForTimestamp(minBucketTsFloor);
    Long floorId = floorMessageId(convo, me);

    int startBucket = (cursor != null)
            ? ChatBuckets.bucketOf(cursor)
            : (convo.getLastMessageId() != null
                ? ChatBuckets.bucketOf(convo.getLastMessageId())
                : ChatBuckets.currentBucket());

    List<MessageByConversationEntity> out = new ArrayList<>(limit);
    int bucket = startBucket;
    Long cur = cursor;
    while (out.size() < limit && bucket >= minBucket) {
        int need = limit - out.size();
        List<MessageByConversationEntity> rows = (cur != null)
                ? messageRepo.pageBefore(conversationId, bucket, cur, need)
                : messageRepo.firstPage(conversationId, bucket, need);
        for (MessageByConversationEntity r : rows) {
            if (floorId != null && r.getMessageId() < floorId) continue; // hidden pre-join history
            out.add(r);
        }
        bucket--;
        cur = null; // subsequent buckets take the whole window
    }

    boolean hasMore = out.size() >= limit;
    Long nextCursor = out.isEmpty() ? null : out.get(out.size() - 1).getMessageId();
    return new MessagePage<>(hydrate(out, userId), nextCursor, hasMore);
}
```

### 1.1 Deriving the three range endpoints

Before the loop runs, three quantities pin down the walk:

- **`startBucket`** — the *newest* partition to look in.
  - If the caller gave a `cursor`, it is `bucketOf(cursor)` — start in the
    cursor's own partition.
  - Otherwise (first page), it is `bucketOf(convo.getLastMessageId())` — jump
    straight to the partition holding the conversation's newest message, read
    from the denormalised `last_message_id` on the Postgres `Conversation` row
    (no Cassandra probe needed to find "the latest bucket").
  - If the conversation has *no* messages yet (`lastMessageId == null`), it falls
    back to `currentBucket()` (today's partition), which will simply return
    nothing and terminate.
- **`minBucket`** — the *oldest* partition worth touching. Derived from
  `historyFloorMillis` (§4): never before the conversation was created, and for
  a hidden-history group, never before this member joined. This is the loop's
  lower bound — the reason the walk **terminates instead of scanning to bucket 0**.
- **`floorId`** — the smallest `message_id` this member may see (hidden pre-join
  history), or `null` for everyone else (§4). Applied *per row* inside the loop.

### 1.2 The loop, cursor semantics, and "exactly one partition per query"

The `while` loop accumulates rows until the page is full (`out.size() >= limit`)
or it runs out of legal partitions (`bucket < minBucket`). Each iteration issues
**exactly one** Cassandra query, and each query binds the **full partition key**
`(conversation_id, bucket)` — so it is a single-partition slice, never a
multi-partition scatter. The two queries it chooses between
(`MessageByConversationRepository`) are:

```java
/** Newest page within a bucket (clustering is message_id DESC). */
@Query("SELECT * FROM messages_by_conversation " +
       "WHERE conversation_id = :cid AND bucket = :bucket LIMIT :limit")
List<MessageByConversationEntity> firstPage(UUID cid, int bucket, int limit);

/** Older page within a bucket — everything strictly before the cursor id. */
@Query("SELECT * FROM messages_by_conversation " +
       "WHERE conversation_id = :cid AND bucket = :bucket AND message_id < :cursor LIMIT :limit")
List<MessageByConversationEntity> pageBefore(UUID cid, int bucket, long cursor, int limit);
```

- **First iteration** uses `cur` (`= cursor`). If a cursor was supplied, it calls
  `pageBefore` → `message_id < :cursor`, i.e. *strictly older* than the cursor.
  The strict `<` is what makes pagination **non-overlapping and duplicate-free**:
  the cursor is the oldest id already shown, so the next page begins just below
  it. If no cursor was supplied, it calls `firstPage` → the newest rows in the
  newest bucket.
- **`cur = null`** at the bottom of the loop means every *subsequent* bucket uses
  `firstPage` and takes its **whole window** (`LIMIT need`). This is correct
  because once you drop into an older partition, *all* of its rows are older than
  the cursor by construction (buckets are time-ordered), so there is no need to
  re-apply the `message_id <` predicate.
- Because the table clusters `message_id DESC`, both queries return **newest-first
  within the bucket**, and the walk visits buckets in descending order, so `out`
  is globally sorted newest→oldest with no extra sort.

**Termination.** Each iteration either fills the page or decrements `bucket`, and
the loop stops the instant `bucket < minBucket`. A *quiet* conversation may walk
several empty/underfull buckets before collecting `limit` rows (each an
inexpensive single-partition read that returns few or zero rows), but the range
`[minBucket, startBucket]` is finite and small, so it always terminates fast. A
*busy* conversation typically satisfies the whole page from `startBucket` alone —
one Cassandra query.

### 1.3 The `MessagePage` cursor contract

The response is a `MessagePage<T>` record
(`ak.dev.irc.app.chat.dto.response.MessagePage`):

```java
public record MessagePage<T>(List<T> items, Long nextCursor, boolean hasMore) {}
```

- **`nextCursor = out.get(out.size() - 1).getMessageId()`** — the **oldest** id
  in the page (last element, because `out` is newest→oldest). The client passes
  it back as `cursor` to fetch the next-older page. When the page is empty,
  `nextCursor == null`, signalling **start of history reached**.
- **`hasMore = out.size() >= limit`** — a *heuristic*: "we filled the page, so
  there is probably more." It is deliberately the cheap heuristic rather than the
  fetch-`limit+1` trick. **Edge case / accepted cost:** if *exactly* `limit`
  messages remain, `hasMore` is `true` but the next `loadPage` call returns an
  empty `items` list with a `null` `nextCursor`. The client simply sees one empty
  trailing page — harmless, and avoids an extra row read on every page.

### 1.4 Membership gate & preconditions

Two guards run first, both against Postgres:

```java
private Conversation requireConversation(UUID conversationId) {
    return conversationRepo.findById(conversationId)
            .filter(c -> c.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
}
private ConversationMember requireReadableMember(UUID conversationId, UUID userId) {
    return memberRepo.findMember(conversationId, userId)
            .filter(ConversationMember::canRead)
            .orElseThrow(() -> new ForbiddenException(
                    "You are not a member of this conversation.", "NOT_A_MEMBER"));
}
```

A soft-deleted conversation reads as 404; a non-member (or a member whose
`status` is neither `ACTIVE` nor `RESTRICTED` — `canRead()` in
`ConversationMember`) gets `403 NOT_A_MEMBER`. Permission is therefore enforced
*before* any Cassandra query, consistent with the "permission before
persistence / before disclosure" principle. See
[03-permissions-and-requests.md](03-permissions-and-requests.md).

### 1.5 The HTTP surface & limit clamping

`MessageController.messages` (`GET /api/v1/conversations/{id}/messages`) exposes
it, defaulting `limit` to 50 and clamping every read to `[1, 100]` via
`Pages.clamp`:

```java
return ResponseEntity.ok(messageQueryService.loadPage(id, requireId(user), cursor, Pages.clamp(limit)));
```

`Pages.clamp` = `Math.max(1, Math.min(requested, MAX_PAGE_SIZE))`, `MAX_PAGE_SIZE
= 100`. This caps how many rows any single walk can pull, bounding the hydration
fan-out below.

---

## 2. Gap-sync — `sync` and *why ascending*

After a dropped SSE stream (see [05-realtime-delivery.md](05-realtime-delivery.md))
the client holds a high-water id and needs **everything strictly newer** to fill
the hole. That is `sync`:

```java
@Transactional(readOnly = true)
public List<MessageResponse> sync(UUID conversationId, UUID userId, long afterId, int limit) {
    requireConversation(conversationId);
    requireReadableMember(conversationId, userId);

    int fromBucket = ChatBuckets.bucketOf(afterId);
    int toBucket = ChatBuckets.currentBucket();
    List<MessageByConversationEntity> out = new ArrayList<>();
    for (int bucket = fromBucket; bucket <= toBucket && out.size() < limit; bucket++) {
        out.addAll(messageRepo.pageAfter(conversationId, bucket, afterId, limit - out.size()));
    }
    // pageAfter returns DESC within a bucket; present ascending for append.
    out.sort(Comparator.comparingLong(MessageByConversationEntity::getMessageId));
    return hydrate(out, userId);
}
```

It walks buckets **forward** — from the high-water id's bucket up to the current
bucket — accumulating rows via `pageAfter`. The endpoint is
`GET /api/v1/conversations/{id}/messages/sync?after=<id>` (default `limit` 100,
clamped to 100).

### 2.1 Why `ORDER BY message_id ASC` is load-bearing

The repository query for the gap:

```java
/**
 * Gap-sync within a bucket — the OLDEST rows strictly newer than the high-water
 * id. ORDER BY message_id ASC (reversing the table's default DESC clustering) is
 * essential: without it the LIMIT would return the newest rows and silently skip
 * the middle of a large gap.
 */
@Query("SELECT * FROM messages_by_conversation " +
       "WHERE conversation_id = :cid AND bucket = :bucket AND message_id > :after " +
       "ORDER BY message_id ASC LIMIT :limit")
List<MessageByConversationEntity> pageAfter(UUID cid, int bucket, long after, int limit);
```

Consider a client that missed **500** messages but calls `sync` with `limit =
100`. The table's natural clustering is `DESC`. If the query kept that default,
`message_id > :after LIMIT 100` would return the **newest** 100 messages of the
gap and skip the 400 in the middle — the client would append them and believe it
was caught up, leaving a permanent hole. By forcing `ASC`, the `LIMIT` slices the
**oldest** 100 messages *immediately after* the high-water id — the contiguous
next chunk. The client applies them, advances its high-water id to the last one,
and calls `sync` again; it drains the gap in order, 100 at a time, never skipping.

### 2.2 The client ordering / gap-detection contract

`sync` is the server half of the ordering protocol in
[06-algorithms.md](06-algorithms.md). The client keeps its buffer sorted by
`messageId` (== send order, because Snowflakes are time-sortable), treats a
received event whose id isn't the expected successor as a *gap*, and calls `sync`
to backfill. Because sends are idempotent and ids are monotonic, this yields
**exactly-once display** even across flaky networks. The final
`out.sort(comparingLong(messageId))` presents the batch **ascending** so the
client can append it to the bottom of the timeline directly. (Rows arrive DESC
within each bucket and buckets are iterated ascending, so the explicit sort is
the single authority that guarantees a clean ascending merge across bucket
boundaries.)

### 2.3 Why `sync` needs no history floor (edge case)

Unlike `loadPage`, `sync` does **not** apply `floorId`. This is *safe by
construction*: `afterId` is a message the client already received, which — for a
hidden-history group — must itself be post-join. Everything strictly newer is
therefore post-join too, so no hidden pre-join history can leak through `sync`.
`sync` also does not filter tombstoned or `SYSTEM` rows: those legitimately
belong in the live timeline (a delete renders as "message deleted"; system events
render as join/leave/title-change notices), so `hydrate` (§5) passes them
through.

---

## 3. Single message & reaction detail — `getOne`, `reactions`

For replies, forwards, "jump to message", and deep links, the client fetches one
message by id — a **point read** against `message_by_id`:

```java
@Transactional(readOnly = true)
public MessageResponse getOne(long messageId, UUID userId) {
    MessageByIdEntity m = messageByIdRepo.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
    requireReadableMember(m.getConversationId(), userId);
    Map<UUID, User> users = loadUsers(Set.of(m.getSenderId()));
    ReplyPreview reply = m.getReplyToId() == null ? null
            : mapper.toReplyPreview(messageByIdRepo.findById(m.getReplyToId()).orElse(null));
    return mapper.toMessage(m, users, reactionService.detailFor(messageId, userId), reply);
}
```

Note the row carries its own `conversation_id`, so the membership gate can run
*after* the point lookup with no extra Cassandra probe. `getOne` uses
`detailFor` (§6) — the **exact, per-viewer** reaction detail, because a single
opened message wants an accurate `reactedByMe`.

**Edge case worth flagging:** `getOne` enforces `canRead` membership but does
**not** apply the hidden-history `floorId`. A member of a hidden-history group who
already knows a pre-join message id could fetch that single message directly. The
list/search/scan paths all honour the floor; the by-id point read does not. This
is an observed gap, not a documented feature.

The reaction-list endpoint (`GET /api/v1/messages/{id}/reactions`) is the same
shape — verify membership, then return `detailFor`:

```java
@Transactional(readOnly = true)
public List<ReactionSummary> reactions(long messageId, UUID userId) {
    MessageByIdEntity m = messageByIdRepo.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
    requireReadableMember(m.getConversationId(), userId);
    return reactionService.detailFor(messageId, userId);
}
```

---

## 4. Hidden-history groups — the join floor

A group whose `GroupSettings.historyVisibleToNewMembers == false` must hide every
message sent **before** a member joined. Three private helpers implement this,
all verbatim:

```java
private boolean hidesHistory(Conversation convo) {
    return convo.isGroup() && convo.getGroupSettings() != null
            && !convo.getGroupSettings().isHistoryVisibleToNewMembers();
}

/** Epoch-ms floor of the scan range — never before the conversation existed,
 *  and (for hidden-history groups) never before the member joined. */
private long historyFloorMillis(Conversation convo, ConversationMember me) {
    long convoFloor = convo.getCreatedAt() != null
            ? convo.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli()
            : SnowflakeIdGenerator.CUSTOM_EPOCH;
    if (hidesHistory(convo) && me.getJoinedAt() != null) {
        return Math.max(convoFloor, me.getJoinedAt().toInstant(ZoneOffset.UTC).toEpochMilli());
    }
    return convoFloor;
}

/** The lowest message id this member may see (hidden pre-join history), or null. */
private Long floorMessageId(Conversation convo, ConversationMember me) {
    if (!hidesHistory(convo) || me.getJoinedAt() == null) return null;
    long joinMs = me.getJoinedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
    return (joinMs - SnowflakeIdGenerator.CUSTOM_EPOCH) << 22;
}
```

Two derived values, at two granularities:

- **`historyFloorMillis` → `minBucket`** is the coarse, *partition-level* floor.
  For a hidden-history group it is raised to the join instant, so the bucket walk
  never even **touches** partitions that are entirely pre-join — it saves whole
  Cassandra reads.
- **`floorMessageId` → `floorId`** is the fine, *row-level* floor for the
  boundary bucket (the one straddling the join moment), which contains both
  pre- and post-join rows.

**The `<< 22` trick.** A Snowflake is
`((ms - CUSTOM_EPOCH) << 22) | (node << 12) | seq`. Setting `node = 0, seq = 0`
yields the **smallest possible id** at a given millisecond:
`(joinMs - CUSTOM_EPOCH) << 22` (22 = `TIMESTAMP_SHIFT`). Any message actually
sent at or after `joinMs` has an id `>=` this floor (the node/seq bits only add).
So the test `r.getMessageId() < floorId` is *exactly* "sent strictly before I
joined" — a pure integer comparison, no timestamp decode per row, no stored
per-member marker. (Boundary nuance: same-millisecond messages compare as
post-join, i.e. at most one millisecond of messages may be counted as visible at
the join edge — negligible.)

`floorId` is applied in:

- **`loadPage`** — `if (floorId != null && r.getMessageId() < floorId) continue;`
- **`scanSearch`** — same skip (§7.2).
- **`search`** — as the scalar floor argument to `hydrateByIds` (§7.1).
- **`searchAll`** — as a **per-conversation** floor inside the visibility
  predicate (§7.3), because a single scalar floor is wrong across conversations.

**Not applied in** `getOne` (§3) or `pinnedMessages` (§8) — both pass a `null`
floor. For pinned messages this is arguably intentional (a pinned message is
deliberately surfaced group-wide); for `getOne` it is the gap noted in §3.

---

## 5. Hydration — bulked, no N+1

A raw Cassandra row is not a `MessageResponse`; it needs the sender's
username/name, its reaction summary, and (if it's a reply) a preview of the
replied-to message. The naïve approach — one lookup per row — would be a classic
N+1. Both hydrators bulk every dependency into **one call each**.

### 5.1 `hydrate` (for `loadPage` / `sync`)

```java
private List<MessageResponse> hydrate(List<MessageByConversationEntity> rows, UUID viewerId) {
    if (rows.isEmpty()) return List.of();

    Set<UUID> senderIds = rows.stream().map(MessageByConversationEntity::getSenderId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
    Map<UUID, User> users = loadUsers(senderIds);                              // 1 Postgres query

    List<Long> ids = rows.stream().map(MessageByConversationEntity::getMessageId).toList();
    Map<Long, List<ReactionSummary>> reactions = reactionService.countsFor(ids); // Redis, bounded

    Set<Long> replyIds = rows.stream().map(MessageByConversationEntity::getReplyToId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
    Map<Long, MessageByIdEntity> replies = replyIds.isEmpty() ? Map.of()
            : messageByIdRepo.findAllByMessageIdIn(replyIds).stream()           // 1 Cassandra IN
                .collect(Collectors.toMap(MessageByIdEntity::getMessageId, e -> e));

    List<MessageResponse> out = new ArrayList<>(rows.size());
    for (MessageByConversationEntity r : rows) {
        ReplyPreview reply = r.getReplyToId() == null ? null
                : mapper.toReplyPreview(replies.get(r.getReplyToId()));
        out.add(mapper.toMessage(r, users, reactions.getOrDefault(r.getMessageId(), List.of()), reply));
    }
    return out;
}
```

Three bulk loads, regardless of page size:

1. **Senders** — `loadUsers` → `userRepository.findActiveByIdIn(senderIds)`, one
   Postgres round-trip for the whole distinct sender set:

   ```java
   private Map<UUID, User> loadUsers(Set<UUID> ids) {
       if (ids == null || ids.isEmpty()) return Map.of();
       return userRepository.findActiveByIdIn(ids).stream()
               .collect(Collectors.toMap(User::getId, u -> u));
   }
   ```
   Only *active* users resolve; a soft-deleted sender maps to `null`, and
   `ChatMapper.toMessage` renders `senderUsername/senderFullName` as `null`.

2. **Reactions** — `reactionService.countsFor(ids)`, Redis only (§6).

3. **Reply previews** — one `findAllByMessageIdIn` `IN`-load of *all* replied-to
   ids in the page. A deleted target maps to a `ReplyPreview` with `deleted =
   true` and null snippet (`ChatMapper.toReplyPreview`).

`hydrate` **passes tombstoned and SYSTEM rows through** — the mapper nulls a
deleted row's body/media and sets `deleted = true`, so the timeline shows the
tombstone in place (ordering intact). This is the correct behaviour for a live
timeline and differs from `hydrateByIds`, which drops them (§5.2).

### 5.2 `hydrateByIds` (for search / pinned)

`hydrateByIds` takes a **ranked list of ids** (ES relevance order, or pin order)
and preserves it. It does everything `hydrate` does **plus** a bulk load of the
messages themselves (the id lists come from ES/Postgres, not from Cassandra rows),
and it applies a `visible` predicate and drops deleted/system rows:

```java
private List<MessageResponse> hydrateByIds(List<Long> ids, UUID viewerId,
                                           java.util.function.Predicate<MessageByIdEntity> visible) {
    if (ids == null || ids.isEmpty()) return List.of();
    Map<Long, MessageByIdEntity> byId = messageByIdRepo.findAllByMessageIdIn(ids).stream()   // 1 Cassandra IN
            .collect(Collectors.toMap(MessageByIdEntity::getMessageId, e -> e, (a, b) -> a));

    Set<UUID> senderIds = byId.values().stream().map(MessageByIdEntity::getSenderId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
    Map<UUID, User> users = loadUsers(senderIds);                                            // 1 Postgres
    Map<Long, List<ReactionSummary>> reactions = reactionService.countsFor(ids);             // Redis

    Set<Long> replyIds = byId.values().stream().map(MessageByIdEntity::getReplyToId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
    Map<Long, MessageByIdEntity> replies = replyIds.isEmpty() ? Map.of()
            : messageByIdRepo.findAllByMessageIdIn(replyIds).stream()                        // 1 Cassandra IN
                .collect(Collectors.toMap(MessageByIdEntity::getMessageId, e -> e, (a, b) -> a));

    List<MessageResponse> out = new ArrayList<>(ids.size());
    for (Long id : ids) {                       // preserve rank / pin order
        MessageByIdEntity m = byId.get(id);
        if (m == null) continue;
        if (!visible.test(m)) continue;
        if (Boolean.TRUE.equals(m.getDeleted())) continue;
        if (MessageType.SYSTEM.name().equals(m.getType())) continue;
        ReplyPreview reply = m.getReplyToId() == null ? null
                : mapper.toReplyPreview(replies.get(m.getReplyToId()));
        out.add(mapper.toMessage(m, users, reactions.getOrDefault(id, List.of()), reply));
    }
    return out;
}
```

The iteration is driven by the **input `ids`** list, so ES rank / pin order is
preserved exactly. `toMap(..., (a, b) -> a)` de-dups any duplicate id defensively.
The scalar-floor overload just wraps a predicate:

```java
private List<MessageResponse> hydrateByIds(List<Long> ids, UUID viewerId, Long floorId) {
    return hydrateByIds(ids, viewerId, m -> floorId == null || m.getMessageId() >= floorId);
}
```

---

## 6. Reactions — Redis counts vs. Cassandra detail

`ReactionService`
(`src/main/java/ak/dev/irc/app/chat/service/ReactionService.java`) has **two**
read paths, used in different places, backed by two stores:

| Method | Source | `reactedByMe`? | Used by | Cost |
|---|---|---|---|---|
| `countsFor(ids)` | Redis hash `chat:reactions:{id}` | always `false` | timeline hydration (`hydrate`, `hydrateByIds`) | 1 Redis `HGETALL` per id |
| `detailFor(id, viewer)` | Cassandra `reactions_by_message` partition | accurate | `getOne`, `reactions` endpoint | 1 Cassandra partition scan |

The Cassandra row per `(message, user)` is the **source of truth**; a Redis hash
`chat:reactions:{messageId}` (`emoji → count`) is the **hot read** for rendering a
timeline without scanning Cassandra once per message. It is delta-maintained on
every react/unreact.

**`countsFor` — the bulk timeline path:**

```java
public Map<Long, List<ReactionSummary>> countsFor(Collection<Long> messageIds) {
    Map<Long, List<ReactionSummary>> result = new HashMap<>();
    for (Long id : messageIds) {
        try {
            Map<Object, Object> hash = redis.opsForHash().entries(HASH_PREFIX + id);
            if (hash == null || hash.isEmpty()) continue;
            List<ReactionSummary> list = new ArrayList<>(hash.size());
            for (Map.Entry<Object, Object> e : hash.entrySet()) {
                long count = parse(e.getValue());
                if (count > 0) list.add(new ReactionSummary(String.valueOf(e.getKey()), count, false));
            }
            if (!list.isEmpty()) result.put(id, list);
        } catch (Exception ignored) { /* cold/unavailable cache → no reactions rendered */ }
    }
    return result;
}
```

It sets `reactedByMe = false` unconditionally — the timeline shows aggregate
counts, and the client tracks its *own* reactions optimistically (the realtime
model carries **deltas**, not authoritative per-viewer state; the client applies
its own ±1 locally). Exact per-viewer state is only fetched on demand via
`detailFor`. This is one `HGETALL` per message — an `O(page)` sequence of Redis
round-trips, not a single pipelined call, but each is sub-millisecond and, unlike
a per-row *Cassandra* point read, does not touch the message store at all. It is
a deliberate cheapness/latency trade, bounded by the ≤100 page clamp.

**`detailFor` — exact, per-viewer:**

```java
public List<ReactionSummary> detailFor(long messageId, UUID viewerId) {
    List<ReactionByMessageEntity> rows = reactionRepo.findByMessage(messageId);
    Map<String, Long> counts = new LinkedHashMap<>();
    Set<String> mine = new HashSet<>();
    for (ReactionByMessageEntity r : rows) {
        counts.merge(r.getEmoji(), 1L, Long::sum);
        if (r.getUserId().equals(viewerId)) mine.add(r.getEmoji());
    }
    List<ReactionSummary> out = new ArrayList<>(counts.size());
    counts.forEach((emoji, count) -> out.add(new ReactionSummary(emoji, count, mine.contains(emoji))));
    return out;
}
```

This reads the authoritative Cassandra partition
`reactions_by_message WHERE message_id = ?` (one partition, one query), counts by
emoji, and records which emojis the *viewer* used → accurate `reactedByMe`. It is
the recount-from-truth path used when a single message is opened.

**Failure mode:** if Redis is cold or down, `countsFor` swallows the exception and
simply renders **no** reaction counts for that message — the messages still load.
The counts repopulate only on the next react/unreact — `adjust()` does the
`HINCRBY` that brings the hash back to life. `detailFor` recounts straight from the
Cassandra `reactions_by_message` partition to build its own accurate response but
**never writes back to Redis**, so opening a single message does not warm the cold
timeline hash. (The `ReactionService` class Javadoc line "lazily rebuilt from
Cassandra when cold" is aspirational — there is no read-path repopulation in the
code; only the write path rebuilds the hash.)

---

## 7. Search

### 7.1 In-conversation — `search` (ES-first, Cassandra fallback)

```java
@Transactional(readOnly = true)
public List<MessageResponse> search(UUID conversationId, UUID userId, String q, int limit) {
    Conversation convo = requireConversation(conversationId);
    ConversationMember me = requireReadableMember(conversationId, userId);
    if (!StringUtils.hasText(q)) return List.of();

    try {
        List<Long> ids = chatSearch.searchMessageIds(List.of(conversationId), q, limit);
        if (!ids.isEmpty()) {
            Long floorId = floorMessageId(convo, me);
            return hydrateByIds(ids, userId, floorId);
        }
    } catch (Exception e) {
        log.debug("[CHAT-SEARCH] ES unavailable, falling back to scan: {}", e.getMessage());
    }
    return scanSearch(convo, me, q, limit);
}
```

`GET /api/v1/conversations/{id}/messages/search?q=…` (default `limit` 20). Two
tiers:

1. **Elasticsearch** (`ChatSearchService.searchMessageIds`) — a bool query:
   `match` with `fuzziness("AUTO")` on `body` (BM25 + typo tolerance), a
   `matchPhrasePrefix` `should` (boosted 1.5× for typeahead), a **`terms` filter
   on `conversationId`** (membership scope — the caller can only ever hit their
   own conversations), and a `mustNot` term on `type = SYSTEM`. It returns ranked
   ids. `hydrateByIds` then applies the single-conversation `floorId` (correct
   because there is exactly one conversation here).
2. **Bounded Cassandra scan** (`scanSearch`) — runs when ES returns *no hits*
   **or** throws a *hard* failure (index-not-found returns an empty list rather
   than throwing, so a cold index also drops to the scan). This keeps search
   working before the index is warm.

### 7.2 The fallback scan — `scanSearch` (bounded)

```java
private List<MessageResponse> scanSearch(Conversation convo, ConversationMember me, String q, int limit) {
    String needle = q.toLowerCase(Locale.ROOT);

    int minBucket = ChatBuckets.bucketForTimestamp(historyFloorMillis(convo, me));
    Long floorId = floorMessageId(convo, me);
    int startBucket = convo.getLastMessageId() != null
            ? ChatBuckets.bucketOf(convo.getLastMessageId()) : ChatBuckets.currentBucket();

    List<MessageByConversationEntity> matches = new ArrayList<>();
    int scanned = 0, bucketsWalked = 0;
    for (int bucket = startBucket; bucket >= minBucket
            && bucketsWalked < SEARCH_MAX_BUCKETS
            && scanned < SEARCH_MAX_SCANNED
            && matches.size() < limit; bucket--, bucketsWalked++) {
        List<MessageByConversationEntity> rows = messageRepo.firstPage(convo.getId(), bucket, 500);
        for (MessageByConversationEntity r : rows) {
            scanned++;
            if (floorId != null && r.getMessageId() < floorId) continue;
            if (Boolean.TRUE.equals(r.getDeleted())) continue;
            if (MessageType.SYSTEM.name().equals(r.getType())) continue;
            if (r.getBody() != null && r.getBody().toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(r);
                if (matches.size() >= limit) break;
            }
        }
    }
    return hydrate(matches, me.getId().getUserId());
}
```

It is a bucket walk like `loadPage`, but each bucket pulls up to **500** rows and
does a case-insensitive **substring** match on `body`, skipping the join floor,
tombstones, and system rows. Three hard stops bound it — `SEARCH_MAX_BUCKETS = 24`
(≈240 days at `BUCKET_DAYS = 10`), `SEARCH_MAX_SCANNED = 3000` rows, and
`matches.size() >= limit`. So the worst case is a **constant** ≤24 single-partition
reads examining ≤3000 rows: a recent-history "good enough" fallback, not a full
history search (that's ES's job).

### 7.3 Cross-conversation — `searchAll` (ES-only) and the leak that was fixed

```java
@Transactional(readOnly = true)
public List<MessageResponse> searchAll(UUID userId, String q, int limit) {
    if (!StringUtils.hasText(q)) return List.of();
    List<UUID> myConversations = memberRepo.findMyConversationIds(userId);
    if (myConversations.isEmpty()) return List.of();

    List<Long> ids;
    try {
        ids = chatSearch.searchMessageIds(myConversations, q, limit);
    } catch (Exception e) {
        log.debug("[CHAT-SEARCH] cross-conversation ES unavailable: {}", e.getMessage());
        return List.of();
    }
    if (ids.isEmpty()) return List.of();

    // Resolve per-conversation visibility: exclude soft-deleted conversations
    // and, for hidden-history groups, any message sent before I joined. Each
    // conversation has its OWN join floor, so a single scalar floor is wrong here.
    Map<UUID, Conversation> live = conversationRepo.findAllById(myConversations).stream()
            .filter(c -> c.getDeletedAt() == null)
            .collect(Collectors.toMap(Conversation::getId, c -> c));
    if (live.isEmpty()) return List.of();
    Map<UUID, Long> floorByConv = new java.util.HashMap<>();
    for (ConversationMember m : memberRepo.findMyMembershipsIn(userId, live.keySet())) {
        UUID cid = m.getId().getConversationId();
        Long fl = floorMessageId(live.get(cid), m);
        if (fl != null) floorByConv.put(cid, fl);
    }

    return hydrateByIds(ids, userId, msg -> {
        Conversation c = live.get(msg.getConversationId());
        if (c == null) return false;                       // soft-deleted / not a live membership
        Long fl = floorByConv.get(msg.getConversationId());
        return fl == null || msg.getMessageId() >= fl;     // honour this conversation's join floor
    });
}
```

`GET /api/v1/messaging/search?q=…` searches **every** conversation the caller can
read. It is **ES-only — there is no scan fallback**, because a cross-conversation
Cassandra scan would be unbounded (potentially thousands of partitions across all
of a user's conversations); if ES is unavailable it returns an empty list.

The membership scope is enforced **inside** the ES query (the `terms` filter over
`findMyConversationIds` — `ACTIVE`/`RESTRICTED` memberships only), so a user
physically cannot retrieve a hit from a conversation they aren't in.

**The info-leak that was fixed — per-conversation floors.** The in-conversation
`search` uses **one scalar** `floorId` because it has one conversation. Applying a
single scalar floor across a *cross-conversation* result set is **wrong**: every
conversation has its own join instant, so conversation A's floor is meaningless
for conversation B — it would either hide legitimate matches or, worse, leak
another conversation's pre-join history. The fix builds a **`Map<conversationId,
floorId>`** (`floorByConv`) from the caller's per-conversation memberships
(`findMyMembershipsIn`) and passes a **predicate** into `hydrateByIds` that, per
matched message, looks up *that message's* conversation floor. The predicate also
excludes **soft-deleted** conversations (`c == null` → the conversation isn't in
the `live` map) — defence-in-depth against an ES hit for a conversation that has
since been deleted but not yet de-indexed. This is a second enforcement layer on
top of the ES `terms` filter.

---

## 8. Pinned messages — `pinnedMessages`

```java
@Transactional(readOnly = true)
public List<MessageResponse> pinnedMessages(UUID conversationId, UUID userId) {
    requireConversation(conversationId);
    requireReadableMember(conversationId, userId);
    List<Long> ids = pinRepo.findByConversationIdOrderByPinnedAtDesc(conversationId).stream()
            .map(ConversationPin::getMessageId).toList();
    return hydrateByIds(ids, userId, (Long) null);
}
```

`GET /api/v1/conversations/{id}/pinned`. Pins are relational rows in **Postgres**
(`ConversationPin`), so the listing is a single indexed Postgres query ordered
**newest pin first**; the ids feed straight into `hydrateByIds`, which preserves
that pin order and drops deleted/system rows. A `null` floor is passed — pinned
messages are surfaced regardless of the hidden-history join floor (see the note in
§4).

---

## 9. Complexity, round-trips, concurrency

### 9.1 Big-O and round-trip counts

Let **P** = page size (≤100 after `Pages.clamp`), **B** = buckets walked,
**R** = distinct replied-to ids in the page.

| Operation | Cassandra | Postgres | Redis | Elasticsearch |
|---|---|---|---|---|
| **`loadPage`** | `B` slice reads (usually 1) + (`R>0` ? 1 `IN` : 0) | 1 (users) + 2 (conversation, member gate) | `P` `HGETALL` | — |
| **`sync`** | ≤(`toBucket−fromBucket`+1) slices + reply `IN` | 1 + 2 | `P` | — |
| **`getOne`** | 1 point + (reply ? 1 point) + 1 reaction partition (`detailFor`) | 1 (users) + 1 (member gate) | — | — |
| **`search` (ES)** | 1 message `IN` + reply `IN` | 1 + 2 | `≤P` | 1 |
| **`search` (scan)** | ≤24 slices (≤3000 rows) + reply `IN` | 1 + 2 | `≤P` | 1 (failed/empty) |
| **`searchAll`** | 1 message `IN` + reply `IN` | 1 scope + 1 `findAllById` + 1 memberships + 1 users | `≤P` | 1 |
| **`pinnedMessages`** | 1 message `IN` + reply `IN` | 1 pins + 1 users + 2 gate | `≤P` | — |

- **Page read: `O(B)` single-partition Cassandra reads + `O(1)` Postgres +
  `O(P)` Redis.** For a busy conversation `B = 1`, so a page is *one* partition
  slice plus a handful of bulk hydration calls. The walk is bounded by
  `[minBucket, startBucket]`, so even a quiet conversation is a small constant.
  Crucially there is **no per-row Cassandra point read** — hydration is fully
  bulked.
- **Search: ES path is `O(1)` w.r.t. buckets** (Elasticsearch ranks; Cassandra is
  touched only by two `IN` loads). The scan fallback is a hard-bounded constant
  (≤24 partitions, ≤3000 rows examined). Neither ever degrades into an unbounded
  scan.

### 9.2 Transaction & concurrency behaviour

Every read method is `@Transactional(readOnly = true)`. That read-only JPA
transaction governs **only** the Postgres access (conversation row, member gate,
pins, memberships, users). **Cassandra, Redis, and Elasticsearch are separate
datasources outside the Spring/JPA transaction** — there is no distributed
transaction and no locking anywhere on the read path.

Consequences, all intentional:

- **No write on the read path.** Reads never mutate; there is nothing to
  serialise. Concurrent reads scale horizontally.
- **Eventually-consistent snapshot.** A message written concurrently with a page
  read simply appears on the client's next SSE event or `sync`. A reaction whose
  Redis delta is mid-flight may show a count that's off by one for a beat; the
  authoritative recount is `detailFor`. Cassandra reads at the cluster's
  configured consistency, so a just-written row could momentarily be invisible on
  a lagging replica — Snowflake ordering plus client gap-sync (§2.2) recovers it.
- **Membership snapshot.** The gate reads membership once at the top; a member
  removed mid-page still completes the in-flight read but is rejected on the next
  call.

### 9.3 Failure modes at a glance

| Dependency down | Effect |
|---|---|
| **Redis** | `countsFor` swallows the error → timeline renders with **no** reaction counts; messages still load. `detailFor` (Cassandra) is unaffected. |
| **Elasticsearch** | In-conversation `search` → bounded Cassandra `scanSearch`. Cross-conversation `searchAll` → **empty** list (no fallback by design). |
| **Cassandra** | Hard failure — reads error out (no fallback store for the message log itself). |
| **Postgres** | The membership/conversation gate errors before any Cassandra query runs. |

---

## 10. Cross-references

- **[11-send-path.md](11-send-path.md)** — how the two Cassandra copies, the
  `last_message_id` denormalisation, the reaction rows, and the ES index that
  these reads consume are written; idempotent send.
- **[02-data-model.md](02-data-model.md)** — full schemas, Snowflake layout, and
  bucketing rationale.
- **[06-algorithms.md](06-algorithms.md)** — the bucket-walk, ordering, and
  gap-detection pseudocode this document implements.
- **[05-realtime-delivery.md](05-realtime-delivery.md)** — the SSE + reconnect
  flow that triggers `sync`, and the reaction-delta model that lets `countsFor`
  skip `reactedByMe`.
- **[03-permissions-and-requests.md](03-permissions-and-requests.md)** — the
  `canRead` / membership gating the read path enforces up front.
- **[09-api-reference.md](09-api-reference.md)** — the as-built request/response
  contract for every endpoint referenced here.
