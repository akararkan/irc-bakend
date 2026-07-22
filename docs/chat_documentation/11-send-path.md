# 11 — The Message Send (WRITE) Path

This is the deep, line-by-line companion to the send flow sketched in
[01-architecture.md](01-architecture.md). It walks the single most important hot
path in the chat system — `MessageService.send(...)` — from the moment the
controller hands over a `SendMessageRequest` to the moment the created message is
returned and the realtime/notification fan-out has been scheduled. Everything
here is grounded in the actual code in
`src/main/java/ak/dev/irc/app/chat/service/MessageService.java` and its
collaborators; where the implementation *refines* or *deviates from* the
nine-step design sketch, that is called out explicitly rather than smoothed over.

The write path has one job and one budget: **accept a message, make it durable
and ordered, and schedule its delivery, doing zero scans, zero joins, and zero
locks.** A direct-message send is roughly *2 Cassandra single-partition writes +
a handful of indexed Postgres statements + a few Redis ops + one non-blocking
publish*, and — crucially — that cost is **independent of how long the
conversation is**. This document explains exactly how each of those pieces is
placed, and why.

---

## 0. Orientation — the classes on the write path

| Concern | Class / file | Role on the send path |
|---|---|---|
| Orchestration | `MessageService` (`chat/service/MessageService.java`) | `send`, `forward`, `persist`, `dispatch`, `ensureRequestAndCap`, `authorizeGroupSend`, `buildMedia`, `resolveMentions`, `previewOf`, `echoExisting` |
| Idempotency | `ChatIdempotencyService` (`chat/service/`) | `claim` (SET NX EX), `existingMessageId`, `release` |
| ID minting | `SnowflakeIdGenerator` (`chat/util/`) | `nextId()` — time-sortable 64-bit id |
| Partitioning | `ChatBuckets` (`chat/util/`) | `bucketOf(messageId)` — Cassandra partition window |
| Permission (direct) | `ChatPermissionEngine` + `ChatRelationshipService` (`chat/permission/`) | `authorizeDirectSend` → `SendDecision` |
| Message log | `MessageByConversationRepository`, `MessageByIdRepository` (Cassandra) | the twin message rows |
| Conversation state | `ConversationRepository` (`chat/repository/`) | `advanceLastMessage` (monotonic inbox pointer) |
| Member/read state | `ConversationMemberRepository` | `bumpUnreadForOthers`, `advanceOwnMarker`, `findReadableMemberIds` |
| Realtime | `ChatRealtimeBroadcaster` (`chat/realtime/`) | `broadcast` / `broadcastTo` (deferred to `afterCommit`) |
| Offline bell | `ChatNotificationService` → `CassandraNotificationService` | `notifyNewMessage`, `notifyMessageRequest` |
| Media proxy | `S3StorageService` | `getPublicUrl(storageKey)` |
| Mentions | `MentionExtractor` + `UserRepository.findAllByUsernameIn` | batched @username → userId resolution |

Sibling docs: the permission model is [03-permissions-and-requests.md](03-permissions-and-requests.md);
the algorithms (Snowflake, bucket walk, unread, fan-out strategy) are
[06-algorithms.md](06-algorithms.md); the realtime transport is
[05-realtime-delivery.md](05-realtime-delivery.md); the schema is
[02-data-model.md](02-data-model.md); the as-built contract is
[09-api-reference.md](09-api-reference.md).

---

## 1. `send()` in full, mapped to the nine-step design flow

Here is the entire method, verbatim, so every later section can quote against it:

```java
@Transactional
public MessageResponse send(UUID conversationId, UUID senderId, SendMessageRequest req) {
    rateLimiter.check("chat-send", senderId, 30, Duration.ofSeconds(10));

    Conversation convo = conversationRepo.findById(conversationId)
            .filter(c -> c.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));

    ConversationMember senderMember = memberRepo.findMember(conversationId, senderId)
            .orElseThrow(() -> new ForbiddenException(
                    "You are not a member of this conversation.", "NOT_A_MEMBER"));

    // Idempotency: mint the id, then claim the nonce. A retry that lost the
    // race returns the already-created message instead of a second row.
    long messageId = snowflake.nextId();
    if (!idempotency.claim(senderId, req.getClientNonce(), messageId)) {
        Long existing = idempotency.existingMessageId(senderId, req.getClientNonce());
        if (existing != null) return echoExisting(existing, senderId, conversationId, req);
    }

    try {
        // Permission — group vs direct.
        SendDecision decision = SendDecision.ALLOW;
        UUID directPeer = null;
        if (convo.isGroup()) {
            authorizeGroupSend(senderMember, convo);
        } else {
            directPeer = otherDirectMember(conversationId, senderId);
            decision = permissionEngine.authorizeDirectSend(senderId, directPeer);
            if (decision == SendDecision.DENY) {
                throw new ForbiddenException("This interaction is not allowed.", "BLOCKED");
            }
        }

        // Stranger first contact → ensure request row + enforce the pre-accept cap.
        boolean requestJustCreated = false;
        MessageRequest request = null;
        if (decision == SendDecision.ROUTE_TO_REQUEST) {
            RequestOutcome ro = ensureRequestAndCap(convo, senderId, directPeer, messageId);
            request = ro.request();
            requestJustCreated = ro.justCreated();
        }

        String type = req.getType() != null ? req.getType().name() : MessageType.TEXT.name();
        List<MediaRef> media = buildMedia(req.getMedia());
        Set<UUID> mentions = resolveMentions(req.getBody());

        MessageResponse response = persist(convo, senderId, messageId, type,
                req.getBody(), media, mentions, req.getReplyToId(), null);

        dispatch(convo, senderId, directPeer, decision, request, requestJustCreated,
                response, previewOf(req.getBody(), type, media));

        return response;
    } catch (RuntimeException e) {
        // Freed so a legitimate retry re-attempts rather than echoing a message
        // that was never written (send rejected before persistence).
        idempotency.release(senderId, req.getClientNonce());
        throw e;
    }
}
```

### The design's nine steps vs. the implementation

The nine-step sketch in [01-architecture.md](01-architecture.md) is a *logical*
ordering. The implementation reorders two of them for correctness — it **mints
the Snowflake before the idempotency check** (because the id is the *value* it
stores in the nonce claim), and it **fuses the "idempotency check" and "cache the
nonce" steps into a single atomic `SET NX EX`** performed *before* persistence
rather than after. The mapping:

| Design step (01) | `send()` code | Notes |
|---|---|---|
| 1. Client → POST | (controller, upstream) | Also: `rateLimiter.check("chat-send", senderId, 30, Duration.ofSeconds(10))` — 30 sends / 10s / user |
| — load + validate | `conversationRepo.findById(...).filter(deletedAt == null)` + `memberRepo.findMember(...)` | not-found → 404; non-member → 403 `NOT_A_MEMBER` |
| 4. Mint Snowflake | `long messageId = snowflake.nextId();` | **moved earlier** — needed as the nonce's stored value |
| 2 **+** 7. Idempotency check **and** cache | `idempotency.claim(senderId, nonce, messageId)` | one `SET NX EX`; on loss → `echoExisting`. See §2 |
| 3. Permission | `authorizeGroupSend(...)` / `permissionEngine.authorizeDirectSend(...)` | → `SendDecision`; `DENY` throws `BLOCKED`. See §4 |
| (3b) Request gating | `ensureRequestAndCap(...)` when `ROUTE_TO_REQUEST` | stranger cap = 3. See §5 |
| 5. Cassandra write | `persist(...)` → `messageRepo.save` + `messageByIdRepo.save` | twin rows. See §6 |
| 6. Postgres updates | inside `persist` (`advanceLastMessage`, `advanceOwnMarker`) + inside `dispatch` (`bumpUnreadForOthers`) | See §6, §7 |
| 8. Publish to bus | inside `dispatch(...)` via `broadcaster` (deferred to commit) | See §8 |
| 9. Return 201 | `return response;` | mapped `MessageResponse`; client reconciles optimistic bubble |

Two whole-method invariants worth internalising before the details:

1. **Everything after `claim()` is wrapped in `try { … } catch (RuntimeException e)`
   that calls `idempotency.release(...)`.** The nonce reservation is optimistic —
   it is taken *before* the work, and undone if the work throws. See §2 and §11.
2. **The method is `@Transactional`, but Cassandra is not enrolled in that
   transaction.** The twin message rows are written outside Postgres's atomicity.
   This is the deliberate partial-write window discussed in §11.

---

## 2. Idempotency: mint → claim → echo → release

### 2.1 What problem it solves

Networks retry. A client that times out on a send, reconnects, or the user who
double-taps *send* must not create two messages. The guarantee is **exactly-once
*effect***: the same `clientNonce` (a UUID the client generates once per message
and reuses across retries) resolves to exactly one stored message. The whole
mechanism is `ChatIdempotencyService`, backed by a single Redis string key:

```java
private static final Duration NONCE_TTL = Duration.ofMinutes(10);

private static String key(UUID userId, String nonce) {
    return "chat:nonce:" + userId + ":" + nonce;
}

public boolean claim(UUID userId, String nonce, long messageId) {
    try {
        Boolean won = redis.opsForValue()
                .setIfAbsent(key(userId, nonce), Long.toString(messageId), NONCE_TTL);
        return !Boolean.FALSE.equals(won); // null (redis down) → allow
    } catch (Exception e) {
        log.debug("[CHAT-IDEMPOTENCY] redis unavailable — allowing send: {}", e.getMessage());
        return true;
    }
}

public void release(UUID userId, String nonce) {
    try {
        redis.delete(key(userId, nonce));
    } catch (Exception ignored) { /* best-effort */ }
}

public Long existingMessageId(UUID userId, String nonce) {
    try {
        String v = redis.opsForValue().get(key(userId, nonce));
        return v == null ? null : Long.parseLong(v);
    } catch (Exception e) {
        return null;
    }
}
```

### 2.2 The four moves, in order

1. **Mint.** `long messageId = snowflake.nextId();` — a fresh, globally-unique,
   time-sortable id (§3). It is minted *before* the claim because it is the value
   stored under the nonce, so a later retry can read *which* message the winning
   attempt created.

2. **Claim.** `claim(...)` issues `SET chat:nonce:{user}:{nonce} <messageId> NX EX 600`.
   Redis `SETNX` is atomic, so across all app instances exactly one concurrent
   attempt sets the key and gets `won == true`; every other attempt gets
   `won == false`. Two subtleties:
   - **Fail-open.** If Redis returns `null` (client couldn't set it) or throws,
     `claim` returns `true` — the send proceeds. Idempotency is a best-effort
     de-dupe guard here, *not* a correctness dependency; a Redis outage degrades
     to "possible duplicate on retry", never to "send blocked".
   - The TTL is **10 minutes** — long enough to cover any realistic retry storm,
     short enough that the keyspace self-cleans.

3. **Echo on replay.** When `claim` returns `false`, some earlier attempt already
   owns the nonce:

   ```java
   if (!idempotency.claim(senderId, req.getClientNonce(), messageId)) {
       Long existing = idempotency.existingMessageId(senderId, req.getClientNonce());
       if (existing != null) return echoExisting(existing, senderId, conversationId, req);
   }
   ```

   `echoExisting` returns the *already-created* message rather than writing a
   second one:

   ```java
   private MessageResponse echoExisting(long existingId, UUID senderId, UUID conversationId, SendMessageRequest req) {
       MessageResponse mapped = mapById(existingId, senderId);
       if (mapped != null) return mapped;
       // Winner still writing — echo from the request so the client can reconcile.
       return new MessageResponse(existingId, conversationId, senderId, null, null,
               req.getType() != null ? req.getType().name() : MessageType.TEXT.name(),
               req.getBody(), List.of(), req.getReplyToId(), null, null, null, List.of(),
               null, false, null, Instant.now());
   }
   ```

   Note the fallback: if the winner has claimed the nonce but **not yet finished
   writing** the Cassandra row (a genuine race window of a few milliseconds),
   `mapById` returns `null`; rather than 500 or block, `echoExisting`
   reconstructs a response *from the request payload itself*, keyed by the winner's
   `existingId`. The client already has the same nonce → messageId mapping and
   reconciles its optimistic bubble either way. Worst case it renders the same
   content it already had.

   > **Edge case — `existing == null` after a lost claim.** If `claim` returned
   > `false` but the stored value has since been `release`d or expired, `existing`
   > is `null` and control falls *through* to the normal write path (with the new
   > `messageId`). This is intentional: a nonce that was released means no message
   > was ever written, so a retry *should* create one.

4. **Release on failure.** The `catch (RuntimeException e)` calls
   `idempotency.release(...)` and rethrows. This deletes the nonce so a legitimate
   retry re-attempts instead of resolving to a message that was never written.

### 2.3 Why release only on *pre-persist* failure (and the honest caveat)

The design intent is stated in the code comment: *"Freed so a legitimate retry
re-attempts rather than echoing a message that was never written (send rejected
**before persistence**)."* The exceptions that realistically fire inside the try
block *are* pre-persist:

- `authorizeGroupSend` throws `READ_ONLY` / `ADMINS_ONLY` / `NOT_A_MEMBER`,
- the direct `DENY` throws `BLOCKED`,
- `ensureRequestAndCap` throws `REQUEST_LIMIT_REACHED`.

All of those happen **before** `persist(...)` is reached — nothing has been
written, so releasing the nonce is exactly right: the user fixes the condition (or
the block is one-sided) and a retry gets a clean attempt rather than an eternal
echo of a phantom message.

**The honest caveat (ties into §11):** the `catch` is `RuntimeException`, so it
*also* fires if something throws *after* `persist(...)` has already written the two
Cassandra rows — e.g. a Postgres error during `advanceLastMessage`, or a failure
inside `dispatch`. In that case:

- The `@Transactional` boundary rolls back **the Postgres writes** (pointer,
  unread, own-marker).
- The **Cassandra rows are *not* rolled back** (Cassandra isn't in the tx).
- `release` still deletes the nonce.

So a retry after a post-Cassandra failure re-mints a new Snowflake and writes a
*second* Cassandra row, orphaning the first (which no Postgres pointer references
and no unread ever counted). This is the accepted partial-write window (§11). It
is rare (requires a failure in the ~1ms between the Cassandra write and commit),
self-healing from the user's perspective (their retry succeeds), and the orphan is
inert — it sorts harmlessly into the log by Snowflake time but is never surfaced
as "latest".

---

## 3. Snowflake mint and why it comes first

`snowflake.nextId()` produces a 64-bit, positive, **time-sortable** id
(`timestamp << 22 | node << 12 | seq`). The full generator is documented in
[06-algorithms.md](06-algorithms.md); the load-bearing property for the write
path is that the id encodes creation time, which gives three things for free with
no coordination:

```java
public synchronized long nextId() {
    long now = System.currentTimeMillis();
    if (now < lastTimestamp) {          // clock stepped back (NTP) → pin, don't rewind
        now = lastTimestamp;
    }
    if (now == lastTimestamp) {
        sequence = (sequence + 1) & MAX_SEQUENCE;   // 12-bit, wraps at 4096/ms
        if (sequence == 0) {
            now = waitNextMillis(lastTimestamp);     // sequence exhausted → spin to next ms
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

- **Ordering is free**: the Cassandra clustering key `message_id DESC` yields
  newest-first with no secondary sort.
- **Bucketing is free**: `ChatBuckets.bucketOf(messageId)` recovers the write
  partition from the id alone (`bucket = timestampOf(id) / 86_400_000 / 10`), so
  the writer in `persist` and any future reader compute the identical partition
  with no stored coupling.
- **Idempotency stores it directly**: minting first means the nonce claim carries
  the concrete id, so a replay can echo the *exact* winning message.

The method is `synchronized` on purpose: the whole cost is a few arithmetic ops on
shared counters, contention is negligible next to a Cassandra write, and it
guarantees strict per-node monotonicity even when a millisecond's 4096-slot
sequence fills. A backwards NTP step is absorbed by pinning to `lastTimestamp`
rather than minting a non-monotonic id.

---

## 4. Permission dispatch per `SendDecision`

Permission is evaluated **before persistence**. There are two disjoint code paths.

### 4.1 Group sends — throw-or-allow

```java
private void authorizeGroupSend(ConversationMember member, Conversation convo) {
    if (member.getStatus() == MemberStatus.LEFT || member.getStatus() == MemberStatus.REMOVED) {
        throw new ForbiddenException("You are not a member of this conversation.", "NOT_A_MEMBER");
    }
    if (member.getStatus() == MemberStatus.RESTRICTED) {
        throw new ForbiddenException("You are restricted from posting here.", "READ_ONLY");
    }
    if (!GroupPermissions.can(member.getRole(), GroupAction.SEND_MESSAGE, null, convo.getGroupSettings())) {
        throw new ForbiddenException("Only admins can send messages here.", "ADMINS_ONLY");
    }
}
```

A group send never yields `ROUTE_TO_REQUEST`, `DELIVER_RESTRICTED`, or `DENY` — it
either throws or leaves `decision == SendDecision.ALLOW` (its initial value) with
`directPeer == null`. Cost: **zero extra DB round-trips** — it operates on the
`senderMember` row already loaded at the top of `send` and the settings already on
the loaded `convo`. See [04-group-chats.md](04-group-chats.md) for the role matrix.

### 4.2 Direct sends — the four-state engine

```java
directPeer = otherDirectMember(conversationId, senderId);
decision = permissionEngine.authorizeDirectSend(senderId, directPeer);
if (decision == SendDecision.DENY) {
    throw new ForbiddenException("This interaction is not allowed.", "BLOCKED");
}
```

`ChatPermissionEngine.authorizeDirectSend` evaluates block → restrict → accepted →
connected → stranger in that fixed order and returns one of four `SendDecision`
values (full logic and truth table in
[03-permissions-and-requests.md](03-permissions-and-requests.md)). `DENY` is the
only one that aborts the send here; the other three flow into `persist` and are
then handled *differently by `dispatch`*.

### 4.3 What each decision does downstream

The single most important table on the write path — how `dispatch` treats each
decision across persistence, unread, realtime, and notifications:

| Decision | Persisted? | Recipients set | Eager unread fan-out | Realtime `message.new` targets | Extra realtime | Offline bell |
|---|---|---|---|---|---|---|
| **ALLOW** | yes | `findReadableMemberIds(convo)` (ACTIVE + RESTRICTED) | yes, **iff `memberCount ≤ 256`** | all `recipients` (incl. sender's own devices) | — | for each non-sender recipient, **iff small & offline** → `NEW_MESSAGE` |
| **ROUTE_TO_REQUEST** | yes | `[directPeer]` only | **no** | `[directPeer, senderId]` | if just-created: `REQUEST_NEW` to `directPeer` | if just-created: one `MESSAGE_REQUEST` to `directPeer` |
| **DELIVER_RESTRICTED** | yes | `[directPeer]` only | **no** | `[directPeer, senderId]` | — | **none** (silent) |
| **DENY** | **no** — throws `BLOCKED` before `persist` | — | — | — | — | — |

The `dispatch` code that implements the table:

```java
private void dispatch(Conversation convo, UUID senderId, UUID directPeer, SendDecision decision,
                      MessageRequest request, boolean requestJustCreated,
                      MessageResponse response, String preview) {
    UUID conversationId = convo.getId();

    List<UUID> recipients = (decision == SendDecision.ROUTE_TO_REQUEST
            || decision == SendDecision.DELIVER_RESTRICTED)
            ? new ArrayList<>(List.of(directPeer))
            : memberRepo.findReadableMemberIds(conversationId);

    boolean smallEnough = convo.getMemberCount() <= LARGE_GROUP_CUTOFF;
    if (smallEnough && decision == SendDecision.ALLOW) {
        memberRepo.bumpUnreadForOthers(conversationId, senderId);
    }

    ChatRealtimeEvent newEvt = ChatRealtimeEvent.builder()
            .eventType(ChatRealtimeEventType.MESSAGE_NEW)
            .conversationId(conversationId).message(response)
            .build();
    if (decision == SendDecision.ROUTE_TO_REQUEST) {
        broadcaster.broadcast(List.of(directPeer, senderId), newEvt);
        if (request != null && requestJustCreated) {
            User requester = userRepository.findById(senderId).orElse(null);
            broadcaster.broadcastTo(directPeer, ChatRealtimeEvent.builder()
                    .eventType(ChatRealtimeEventType.REQUEST_NEW)
                    .conversationId(conversationId)
                    .request(mapper.toMessageRequest(request, requester))
                    .build());
        }
    } else if (decision == SendDecision.DELIVER_RESTRICTED) {
        broadcaster.broadcast(List.of(directPeer, senderId), newEvt);
    } else {
        broadcaster.broadcast(recipients, newEvt);
    }

    if (decision == SendDecision.ROUTE_TO_REQUEST) {
        if (requestJustCreated) {
            chatNotifications.notifyMessageRequest(directPeer, senderId, conversationId, senderLabel(senderId));
        }
    } else if (decision == SendDecision.ALLOW && smallEnough) {
        String label = senderLabel(senderId);
        for (UUID r : recipients) {
            if (r.equals(senderId)) continue;
            unreadBadge.invalidate(r);
            if (!presence.isOnline(r)) {
                chatNotifications.notifyNewMessage(r, senderId, conversationId, label, preview);
            }
        }
    }
}
```

**Why route/restricted collapse to `[directPeer]`:** a pending request or a
restricted thread must not touch the *sender's* badge or the *other members'*
(there are none in a DM), and the realtime event only needs to reach the peer's
Requests/Restricted tray plus the sender's own other devices for multi-device
sync. `RESTRICTED` is silent by design — the sender never learns they were
restricted (no receipts, no push), which is the entire distinction between
*restrict* and *block* (see [03](03-permissions-and-requests.md)).

---

## 5. The stranger message cap (= 3) and its exact off-by-one

When a stranger's direct send is `ROUTE_TO_REQUEST`, `ensureRequestAndCap` creates
or advances the single `MessageRequest` row for the conversation and enforces the
pre-accept cap:

```java
private static final int STRANGER_MESSAGE_CAP = 3;

private RequestOutcome ensureRequestAndCap(Conversation convo, UUID senderId, UUID peerId, long messageId) {
    MessageRequest existing = messageRequestRepo.findByConversationId(convo.getId()).orElse(null);
    if (existing == null) {
        MessageRequest r = MessageRequest.builder()
                .conversationId(convo.getId())
                .requesterId(senderId).recipientId(peerId)
                .status(MessageRequestStatus.PENDING)
                .firstMessageId(messageId).messageCount(1)
                .build();
        return new RequestOutcome(messageRequestRepo.save(r), true);
    }
    if (existing.getStatus() != MessageRequestStatus.PENDING) {
        // Declined/blocked (accepted would not route here) — refuse further sends.
        throw new ForbiddenException("This interaction is not allowed.", "REQUEST_LIMIT_REACHED");
    }
    if (existing.getMessageCount() >= STRANGER_MESSAGE_CAP) {
        throw new ForbiddenException(
                "You've reached the limit before this request is accepted.", "REQUEST_LIMIT_REACHED");
    }
    existing.setMessageCount(existing.getMessageCount() + 1);
    return new RequestOutcome(messageRequestRepo.save(existing), false);
}
```

### The off-by-one, traced exactly

The cap check is `existing.getMessageCount() >= STRANGER_MESSAGE_CAP`, evaluated on
the count **as it stands before this message**, and the first message is created
with `messageCount = 1` (not 0). Trace:

| Send # | `existing` at entry | `count >= 3`? | Action | Delivered? | Resulting `messageCount` |
|---|---|---|---|---|---|
| 1 | `null` | — | create row, `count = 1`, `justCreated = true` | **yes** | 1 |
| 2 | count = 1 | `1 >= 3` → no | `count = 2` | **yes** | 2 |
| 3 | count = 2 | `2 >= 3` → no | `count = 3` | **yes** | 3 |
| 4 | count = 3 | `3 >= 3` → **yes** | throw `REQUEST_LIMIT_REACHED` | **no** | 3 (unchanged) |

So **exactly `STRANGER_MESSAGE_CAP` (3) stranger messages get through**, and the
4th is blocked. The boundary lands precisely at 3 because of the interaction of
two choices:

- The guard is `>=`, not `>`. With `>` the 4th (count 3 → passes) would go through
  and the 5th would be blocked → a cap of 4.
- The row is seeded with `messageCount = 1`, not 0. With a 0 seed the same `>=`
  guard would let 4 through before blocking.

Both choices push in the same direction and together make "cap = 3" mean "3
delivered". Also note the increment is applied to a managed JPA entity and only
flushed on `save` **after** the guard passes — a rejected 4th send never bumps the
counter, so the cap can't drift under retries. A non-`PENDING` existing row
(declined/blocked; an *accepted* row would have been `ALLOW`, not
`ROUTE_TO_REQUEST`) hard-refuses with the same code. Uniqueness of the row is
guaranteed by the `UNIQUE (recipient_id, requester_id)` constraint noted in
[03](03-permissions-and-requests.md).

---

## 6. Persistence: the twin Cassandra writes + the monotonic pointer

`persist` is where durability happens. It writes **two Cassandra rows**, advances
**two Postgres pointers**, and returns the mapped response:

```java
private MessageResponse persist(Conversation convo, UUID senderId, long messageId, String type,
                                String body, List<MediaRef> media, Set<UUID> mentions,
                                Long replyToId, UUID forwardedFrom) {
    int bucket = ChatBuckets.bucketOf(messageId);
    Instant now = Instant.now();

    MessageByConversationEntity row = MessageByConversationEntity.builder()
            .conversationId(convo.getId()).bucket(bucket).messageId(messageId)
            .senderId(senderId).type(type).body(emptyToNull(body))
            .media(media == null || media.isEmpty() ? null : media)
            .replyToId(replyToId).forwardedFrom(forwardedFrom)
            .mentions(mentions == null || mentions.isEmpty() ? null : mentions)
            .deleted(false).createdAt(now)
            .build();
    messageRepo.save(row);
    MessageByIdEntity byId = MessageByIdEntity.builder()
            .messageId(messageId).conversationId(convo.getId()).bucket(bucket)
            .senderId(senderId).type(type).body(emptyToNull(body))
            .media(media == null || media.isEmpty() ? null : media)
            .replyToId(replyToId).forwardedFrom(forwardedFrom)
            .mentions(mentions == null || mentions.isEmpty() ? null : mentions)
            .deleted(false).createdAt(now)
            .build();
    messageByIdRepo.save(byId);

    conversationRepo.advanceLastMessage(convo.getId(), messageId,
            LocalDateTime.ofInstant(now, ZoneOffset.UTC), previewOf(body, type, media));

    memberRepo.advanceOwnMarker(convo.getId(), senderId, messageId);
    unreadBadge.invalidate(senderId);

    chatSearch.indexAsync(byId); // async, best-effort — never blocks the send

    Map<UUID, User> users = loadUsers(Set.of(senderId));
    ReplyPreview replyPreview = replyToId == null ? null
            : mapper.toReplyPreview(messageByIdRepo.findById(replyToId).orElse(null));
    return mapper.toMessage(row, users, List.of(), replyPreview);
}
```

### 6.1 Why two Cassandra rows

The message is written into **two tables with two different partition keys**, both
carrying the identical payload:

| Table (entity) | Partition key | Clustering | Serves |
|---|---|---|---|
| `messages_by_conversation` (`MessageByConversationEntity`) | `(conversationId, bucket)` | `messageId DESC` | the **conversation timeline** — the bucket-walk read in [06](06-algorithms.md) |
| `message_by_id` (`MessageByIdEntity`) | `messageId` | — | **point lookups** by id — edit, delete, react, reply-preview, forward source, receipts |

This is the classic Cassandra "query-driven denormalisation": you cannot read a
single message by id out of the `messages_by_conversation` partition without
knowing its `(conversationId, bucket)`, so the id-keyed copy exists to make
`findById(messageId)` a single-partition point read. The cost is two writes
instead of one — cheap, since Cassandra is write-optimised and both are
single-partition inserts with no read-before-write. Each of `body`, `media`, and
`mentions` is coerced to `null` when empty (`emptyToNull`, the `isEmpty()`
ternaries) so absent fields don't store empty collections/strings.

`bucket` is derived purely from `messageId` (`ChatBuckets.bucketOf`), so the writer
stamps the exact partition a future reader will compute from the same id — no
stored coupling, no lookup. See [02-data-model.md](02-data-model.md).

### 6.2 The monotonic inbox pointer — `advanceLastMessage`

The conversation's preview/pointer is advanced with a **conditional, forward-only**
UPDATE:

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
int advanceLastMessage(@Param("id") UUID id,
                       @Param("messageId") long messageId,
                       @Param("at") LocalDateTime at,
                       @Param("preview") String preview);
```

The `AND (lastMessageId IS NULL OR lastMessageId < :messageId)` guard makes the
write **monotonic**: because Snowflakes are strictly increasing, a message that
loses a concurrent race (older `messageId`) matches 0 rows and silently no-ops
rather than rewinding the inbox preview to an older message. Two simultaneous
sends into the same conversation therefore converge on the newer one as the
"latest", with no lock and no lost-update anomaly — the database predicate is the
arbiter. The returned row count is ignored on the send path (the send doesn't care
*whether* it won the pointer race; it only cares that the pointer never goes
backwards).

### 6.3 The sender's own read marker — `advanceOwnMarker`

```java
@Modifying
@Query("""
    UPDATE ConversationMember m SET m.lastReadMessageId = :mid, m.unreadCount = 0
     WHERE m.id.conversationId = :cid AND m.id.userId = :uid AND m.lastReadMessageId < :mid
    """)
int advanceOwnMarker(@Param("cid") UUID conversationId, @Param("uid") UUID userId, @Param("mid") long messageId);
```

By definition the sender has "read" their own message, so their marker is advanced
to it (and `unreadCount` zeroed) — otherwise the sender's own latest message would
show as unread against them, and `hasUnread` would flip true on send. Also
forward-only (`lastReadMessageId < :mid`) so an out-of-order or duplicate advance
can't rewind the marker. `unreadBadge.invalidate(senderId)` then drops the sender's
cached total so it rebuilds from the fresh Postgres sum. This runs for both `send`
and `forward`.

### 6.4 Cost of `persist`

Per send: **2 Cassandra single-partition writes** + **2 Postgres single-row
UPDATEs** (`advanceLastMessage`, `advanceOwnMarker`) + **1 Redis invalidate** + **1
Postgres read** for `loadUsers(senderId)` (+ **1 Cassandra point read** only if
`replyToId != null`). The search index is `indexAsync` — off the hot path,
best-effort, never blocks or fails the send. All O(1) in the length of the
conversation.

---

## 7. Unread fan-out gating

Unread counts are *maintained*, never *counted* (counting rows in Cassandra is
expensive; see [06](06-algorithms.md)). On send, the counter is bumped for
everyone else — but only under two gates.

```java
boolean smallEnough = convo.getMemberCount() <= LARGE_GROUP_CUTOFF; // 256
if (smallEnough && decision == SendDecision.ALLOW) {
    memberRepo.bumpUnreadForOthers(conversationId, senderId);
}
```

`bumpUnreadForOthers` is one bulk UPDATE that increments every readable member
except the sender:

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

### The two gates and why they exist

1. **`smallEnough` (`memberCount ≤ 256`, `LARGE_GROUP_CUTOFF`).** Eager fan-out is
   *N* single-partition writes hidden inside one bulk UPDATE; that's fine up to a
   few hundred members. Above 256, *N* writes per message would dominate the send
   cost, so eager fan-out is skipped and unread is computed lazily at inbox-open
   from `last_message_id` vs the member's `last_read_message_id` (the large-group
   strategy in [06-algorithms.md](06-algorithms.md), §4). Exactness in a
   5,000-person group is not worth 5,000 writes per message.

2. **`decision == SendDecision.ALLOW`.** A hidden pending request or a muted
   restricted message must **not** inflate the recipient's badge — those decisions
   collapse to `[directPeer]` and deliberately skip the counter. Only a normally
   delivered message increments unread.

The complementary sender-side move (`advanceOwnMarker` + own-badge invalidate) is
in `persist` (§6.3). Note the *own marker* advance is unconditional (it happens for
every send regardless of size/decision), while the *others'* bump is gated — the
sender must never see their own message as unread even in a large group or a
restricted thread.

---

## 8. Realtime dispatch — recipient sets per decision

The realtime `message.new` is built once and fanned out through
`ChatRealtimeBroadcaster`, whose defining property is that **it defers the actual
publish until after the surrounding transaction commits**:

```java
private void runAfterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { action.run(); } catch (Exception e) {
                    log.warn("[CHAT-BROADCAST] post-commit publish failed: {}", e.getMessage());
                }
            }
        });
    } else {
        try { action.run(); } catch (Exception e) {
            log.warn("[CHAT-BROADCAST] publish failed: {}", e.getMessage());
        }
    }
}
```

So a subscriber can **never** observe a message (or a counter delta) that a
rollback would erase — realtime is consistent with the Postgres commit. The
per-decision recipient sets (from §4.3):

| Decision | `broadcast` recipients | Why |
|---|---|---|
| **ALLOW** | `findReadableMemberIds(convo)` — every ACTIVE + RESTRICTED member, **including the sender's own userId** | multi-device sync: the sender's *other* devices also receive `message.new`; clients dedupe by `messageId` |
| **ROUTE_TO_REQUEST** | `[directPeer, senderId]` + (if just-created) a separate `REQUEST_NEW` to `directPeer` | peer sees it in the Requests tray; sender's other devices stay in sync; the `REQUEST_NEW` seeds the peer's request list |
| **DELIVER_RESTRICTED** | `[directPeer, senderId]` | peer's muted Restricted tray + sender's own devices; no one else, no signal back to sender |

Two deliberate details:

- **The sender is *in* the recipient set** (via `findReadableMemberIds` for ALLOW,
  explicitly for route/restricted). This is intentional — the client that sent the
  message already rendered an optimistic bubble and dedupes the echoed
  `message.new` by `messageId`, while the sender's *other* logged-in devices need
  the event to display the new message. The receipt/read paths, by contrast, use
  `broadcastExcept(... senderId ...)` because a sender doesn't need their own
  receipt.
- **`REQUEST_NEW` fires only when `requestJustCreated`** — subsequent stranger
  messages (2nd, 3rd) update the existing request but don't re-emit a new-request
  event or a new bell.

RESTRICTED members are in `findReadableMemberIds`, so a read-only group member
still receives `message.new`, edits, deletes and reactions consistently — they can
*see* the room, they just can't post.

---

## 9. Offline notification gating

The bell (push/in-app) is only rung for `ALLOW` sends into small conversations,
and only to recipients who are **not currently online**:

```java
} else if (decision == SendDecision.ALLOW && smallEnough) {
    String label = senderLabel(senderId);
    for (UUID r : recipients) {
        if (r.equals(senderId)) continue;
        unreadBadge.invalidate(r);
        if (!presence.isOnline(r)) {
            chatNotifications.notifyNewMessage(r, senderId, conversationId, label, preview);
        }
    }
}
```

### 9.1 The gates, layered

1. **Decision + size:** only `ALLOW` and `memberCount ≤ 256`. Restricted sends
   never notify (silent). Requests emit exactly *one* `MESSAGE_REQUEST` to the
   peer, and only when just-created (`notifyMessageRequest`), never a per-message
   `NEW_MESSAGE`.
2. **Skip self:** `if (r.equals(senderId)) continue;` — the sender never notifies
   themselves (also enforced defensively downstream, see 9.3).
3. **Presence:** `if (!presence.isOnline(r))` — an online recipient already has the
   `message.new` on their SSE stream, so ringing the bell too would be redundant
   noise. Presence is a Redis key with a ~30s TTL heartbeat (see
   [05-realtime-delivery.md](05-realtime-delivery.md)); this check is a single
   Redis `EXISTS`-class lookup per recipient.
4. **Badge cache:** every non-sender recipient gets `unreadBadge.invalidate(r)`
   regardless of online state, so the next badge read rebuilds from the fresh
   Postgres sum.

### 9.2 Aggregation via `groupKey`

`ChatNotificationService.notifyNewMessage` hands a `DeliverRequest` to the
platform's `CassandraNotificationService`, keyed by conversation:

```java
public void notifyNewMessage(UUID recipientId, UUID senderId, UUID conversationId,
                             String senderLabel, String preview) {
    notifications.deliverAsync(new DeliverRequest(
            recipientId,
            NotificationKind.NEW_MESSAGE,
            "New message",
            truncate(senderLabel + ": " + (preview == null ? "" : preview), 160),
            senderId,
            "Conversation", conversationId,
            "NEW_MESSAGE:" + conversationId));   // groupKey
}
```

The `groupKey` `NEW_MESSAGE:{conversationId}` makes a burst of messages from the
same conversation **coalesce into a single bell row** ("@alice and 3 others
messaged you") rather than *N* separate notifications — the pipeline routes an
aggregable kind with a non-null `groupKey` through `aggregateInto` instead of
`insertFresh`:

```java
return req.kind().aggregable() && req.groupKey() != null
        ? aggregateInto(req)
        : insertFresh(req);
```

Chat kinds are in-app only (`emailEligible = false`), so an active conversation
never floods a mailbox — email digests are for other notification kinds.

### 9.3 Deferred + doubly-guarded

`deliverAsync` is `@Async` — the whole notification build/insert runs on the
executor, **off the send's request thread**, so the HTTP response isn't blocked by
the bell write. And the pipeline independently re-checks suppression:

```java
private boolean suppressed(DeliverRequest req) {
    if (req.actorId() != null && req.actorId().equals(req.userId())) return true;      // self
    if (req.actorId() != null) {
        try {
            if (userBlockRepo.isBlockedBetween(req.userId(), req.actorId())) return true; // block
        } catch (Exception e) { log.debug("[NOTIF] block check failed: {}", e.getMessage()); }
    }
    return false;
}
```

So even if a self- or blocked-pair notification slipped past the send-side
`continue`, it is dropped at the pipeline. `notifyMessageRequest` uses the same
mechanism with `groupKey = MESSAGE_REQUEST:{conversationId}`.

---

## 10. Media build and @mention resolution

Both run *before* `persist`, turning the request DTO into the stored payload.

### 10.1 Media — `storageKey` → proxy URL

```java
private List<MediaRef> buildMedia(List<MediaRefDto> dtos) {
    if (dtos == null || dtos.isEmpty()) return null;
    List<MediaRef> out = new ArrayList<>(dtos.size());
    for (MediaRefDto d : dtos) {
        out.add(MediaRef.builder()
                .kind(d.getKind())
                .storageKey(d.getStorageKey())
                .url(resolveUrl(d.getUrl(), d.getStorageKey()))
                .thumbnailKey(d.getThumbnailKey())
                .thumbnailUrl(resolveUrl(d.getThumbnailUrl(), d.getThumbnailKey()))
                .mime(d.getMime()).bytes(d.getBytes())
                .width(d.getWidth()).height(d.getHeight())
                .durationMs(d.getDurationMs()).waveform(d.getWaveform())
                .fileName(d.getFileName()).altText(d.getAltText())
                .build());
    }
    return out;
}

private String resolveUrl(String url, String key) {
    if (StringUtils.hasText(url)) return url;
    if (StringUtils.hasText(key)) {
        try { return storageService.getPublicUrl(key); } catch (Exception ignored) { /* fall through */ }
    }
    return null;
}
```

The client uploads media out of band (getting a `storageKey`), and the send only
references it. `resolveUrl` prefers a caller-supplied `url` but otherwise derives a
proxy URL from the `storageKey` via `S3StorageService.getPublicUrl` — and it does
so *defensively* (`try/catch … fall through` to `null`) so a storage hiccup
degrades to "no cached URL" rather than failing the whole send; the `storageKey`
is always persisted, so the URL can be re-derived on read. Both the main asset and
its thumbnail get the same treatment. The resulting `List<MediaRef>` is embedded as
a UDT list on both Cassandra rows (or `null` when empty).

### 10.2 Mentions — one batched query

```java
private Set<UUID> resolveMentions(String body) {
    if (!StringUtils.hasText(body)) return null;
    var parsed = MentionExtractor.extract(body);
    if (parsed.getUsernames().isEmpty()) return null;
    List<User> users = userRepository.findAllByUsernameIn(parsed.getUsernames());
    if (users.isEmpty()) return null;
    return users.stream().map(User::getId).collect(Collectors.toSet());
}
```

`MentionExtractor.extract` pulls the `@username` tokens from the body; **all** of
them are resolved to user ids in **one** `findAllByUsernameIn` round-trip (never
N+1), mirroring the platform's post-mention pattern. The resulting `Set<UUID>` is
stored on both message rows. Short-circuits (`null`) at each step keep the common
"no @ in body" case at zero DB cost.

---

## 11. The transaction boundary and the accepted partial-write window

`send` is annotated `@Transactional`. That boundary covers **Postgres/JPA** work
only:

- `advanceLastMessage`, `advanceOwnMarker`, `bumpUnreadForOthers`, the
  `MessageRequest` insert/update — all roll back together on any thrown
  `RuntimeException`.
- The `ChatRealtimeBroadcaster` registers its publishes with
  `afterCommit`, so realtime fires **only if the tx commits** (§8) — no subscriber
  observes a rolled-back message.
- `chatNotifications.deliverAsync` is `@Async` — it runs on another thread and is
  best-effort.

**Cassandra is not enrolled in that transaction.** There is no XA/2PC across
Postgres and Cassandra; the two `messageRepo.save` / `messageByIdRepo.save` calls
in `persist` commit to Cassandra immediately and independently. This creates a
small, **deliberately accepted** partial-write window:

```
persist:  [Cassandra write ×2] ── [Postgres advanceLastMessage] ── [advanceOwnMarker] ──►
                     ▲                              ▲
             committed to C*                 still inside the JPA tx
             (cannot roll back)             (rolls back on failure)
```

If a `RuntimeException` is thrown *after* the Cassandra writes but *before* commit
(a Postgres error in `advanceLastMessage`, or anything in the pre-commit part of
`dispatch`):

1. The Postgres writes roll back — the conversation pointer, unread, and own
   marker are unchanged, as if the send never happened.
2. Realtime never publishes (it was deferred to `afterCommit`, which won't run).
3. The two Cassandra rows **remain** — an orphan.
4. The `catch` releases the nonce (§2.3), so the client's retry re-mints a fresh
   Snowflake and writes a *new* pair of rows.

The orphan is inert: no Postgres `last_message_id` points at it, no unread counted
it, and no client was ever told about it over realtime. Because Snowflakes are
time-sortable it simply sorts into its place in the bucket during a future timeline
read, indistinguishable from a normal message — worst case a reader sees one extra
line the sender didn't intend to keep, with no dangling reference anywhere. The
platform accepts this over the cost/complexity of a saga or 2PC because the window
is ~1ms wide, requires a Postgres failure in exactly that window, and is invisible
to the user (their retry succeeds). This is consistent with the
"honest about hard parts" principle in the [README](README.md) and the tradeoffs
in [08-scaling-and-roadmap.md](08-scaling-and-roadmap.md).

> **Practical mitigation available but not taken:** writing the twin Cassandra
> rows *after* the Postgres commit (or in the `afterCommit` hook) would eliminate
> the orphan but re-introduce the *opposite* risk — a committed pointer with no
> message row if the Cassandra write then fails. Given the read path tolerates a
> stray row far better than a dangling pointer, the current ordering (Cassandra
> first, monotonic pointer second) is the safer of the two.

---

## 12. End-to-end sequence diagram

```
Client        MessageController      MessageService (@Transactional)        Redis    Postgres    Cassandra   Broadcaster/Notif
  │  POST /conversations/{id}/messages                                        │        │           │             │
  │  {clientNonce,type,body,replyToId?,media[]?}                              │        │           │             │
  ├──────────────►│                                                          │        │           │             │
  │               ├─► send(convId, senderId, req)                            │        │           │             │
  │               │     rateLimiter.check ─────────────────────────────────► │(30/10s)│           │             │
  │               │     conversationRepo.findById / memberRepo.findMember ───┼──────► │(2 reads)  │             │
  │               │     messageId = snowflake.nextId()   (in-memory)         │        │           │             │
  │               │     idempotency.claim(nonce, messageId) ───SET NX EX────► │        │           │             │
  │               │        └─ lost? existingMessageId GET + echoExisting ◄────┤ (replay → STOP)    │             │
  │               │     ┌── try ─────────────────────────────────────────────────────────────────────────────┐ │
  │               │     │  permission: authorizeGroupSend | authorizeDirectSend → SendDecision                │ │
  │               │     │     DENY → throw BLOCKED (release nonce, rollback)                                   │ │
  │               │     │  if ROUTE_TO_REQUEST: ensureRequestAndCap (cap=3) ──────────────► │(read+write)│     │ │
  │               │     │  buildMedia (storageKey→proxy url) ; resolveMentions (1 batched query) ─►│         │ │ │
  │               │     │  persist:                                                                 │         │ │ │
  │               │     │     messageRepo.save (messages_by_conversation) ─────────────────────────┼───────► │ │ │
  │               │     │     messageByIdRepo.save (message_by_id) ────────────────────────────────┼───────► │ │ │
  │               │     │     advanceLastMessage (monotonic) ; advanceOwnMarker ──────► │(2 writes) │         │ │ │
  │               │     │     unreadBadge.invalidate(sender) ─────────────────────────► │           │         │ │ │
  │               │     │     chatSearch.indexAsync (off hot path) ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄► (ES async) │ │
  │               │     │  dispatch:                                                                          │ │
  │               │     │     if small & ALLOW: bumpUnreadForOthers ──────────────────► │(1 write)  │         │ │
  │               │     │     broadcaster.broadcast(recipients, message.new) ┄┄┄ registerAfterCommit ┄┄┄►│    │ │
  │               │     │     offline: for r≠sender, invalidate + (!online) notifyNewMessage ┄┄@Async┄┄►│    │ │
  │               │     └── return response ──────────────────────────────────────────────────────────────┘ │
  │               │   COMMIT ───────────────────────────────────────────────► │(commit)                     │
  │               │        └─ afterCommit → publisher.publish(user:{r}) ┄┄┄ RabbitMQ/Redis ┄┄► SSE streams   │
  │ ◄─────────────┤  201 { message }                                                                         │
  │  (reconcile optimistic bubble by messageId)                                                              │
```

---

## 13. Cost, concurrency, and failure-mode summary

### Round-trips for a small-DM `ALLOW` send

| Store | Operations |
|---|---|
| Redis | rate-limit check, `SET NX` claim, sender badge invalidate, per-recipient badge invalidate, per-recipient presence check |
| Postgres | `findById(convo)`, `findMember(sender)`, `otherDirectMember` (peer lookup), permission reads (block/restrict/accepted/connected via `ChatRelationshipService`), `advanceLastMessage`, `advanceOwnMarker`, `bumpUnreadForOthers`, `findReadableMemberIds`, `loadUsers(sender)`, `senderLabel` |
| Cassandra | `messageRepo.save`, `messageByIdRepo.save` (+ one point read if `replyToId`) |
| Async / deferred | `chatSearch.indexAsync` (ES), `broadcaster` (after commit), `notifyNewMessage` (`@Async`) |

All are single-row / single-partition. **No scans, no joins, no locks**; cost is
O(1) in conversation length and O(R) in recipient count R for a small group
(R ≤ 256, else fan-out is skipped). This is why a DM send costs the same whether
the thread has 5 messages or 5 million.

### Concurrency invariants

| Concern | Mechanism |
|---|---|
| Duplicate send (retry / double-tap / multi-instance) | `SET NX EX` claim → single winner; losers `echoExisting` |
| Concurrent sends racing the inbox pointer | `advanceLastMessage` monotonic predicate `lastMessageId < :messageId` — older loser no-ops |
| Sender marker rewind | `advanceOwnMarker` predicate `lastReadMessageId < :mid` |
| Realtime observing an uncommitted message | `broadcaster` defers publish to `afterCommit` |
| Stranger spam | `ensureRequestAndCap` cap = 3 (`>=` guard, seed = 1) + `UNIQUE(recipient,requester)` |
| Per-user send flood | `rateLimiter.check("chat-send", senderId, 30, 10s)` |

### Failure modes

| Failure | Behaviour |
|---|---|
| Redis down at claim | fail-open — send proceeds; retries may duplicate (accepted) |
| Permission `DENY` / group forbidden / cap reached | throw before persist → nonce released → rollback; retry re-attempts cleanly |
| Postgres error *after* Cassandra write | Postgres rolls back; Cassandra rows orphaned (inert); nonce released → retry writes a fresh pair (§11) |
| ES index failure | `indexAsync` best-effort; send unaffected |
| Notification / broadcast publish failure | logged + swallowed (`@Async` / `afterCommit` try-catch); message still durable |
| Media proxy URL derivation failure | `resolveUrl` returns `null`; `storageKey` still stored → URL re-derivable on read |

---

## 14. `forward()` — the write path's near-twin

`forward(sourceMessageId, senderId, targetConversationId, nonce)` reuses the same
building blocks (permission → claim → `ensureRequestAndCap` → `persist` →
`dispatch`) with four differences. Note the ordering flip: `forward` evaluates
**permission before the nonce claim**, whereas `send` claims the nonce first —
`forward` must read-authorise the source and resolve the target before it is even
worth minting an id, so a blocked forward never touches the idempotency store:

1. It first loads and **read-authorises the source** message
   (`messageByIdRepo.findById(...).filter(not deleted)`, then
   `memberRepo.findMember(src.conversationId, sender).filter(canRead)`), so you can
   only forward what you can see.
2. `persist` copies the source's `type`, `body`, and `media` verbatim and stamps
   `forwardedFrom = src.getConversationId()` (the last positional arg) — this is
   the only send path that sets `forwardedFrom` non-null. `replyToId` and
   `mentions` are not carried across.
3. On an idempotent replay it echoes via `mapById(existing, senderId)` (a full
   mapped lookup) rather than the request-shaped `echoExisting`, because a forward
   has no original `SendMessageRequest` body to reconstruct from.
4. The permission/decision/cap/dispatch semantics are otherwise **identical** to
   `send` — a forward into a stranger's DM still routes to a request and counts
   against the same cap of 3.

Everything else in this document — idempotency, the monotonic pointer, unread
gating, realtime recipient sets, offline gating, and the Cassandra-outside-the-tx
window — applies to `forward` unchanged.
