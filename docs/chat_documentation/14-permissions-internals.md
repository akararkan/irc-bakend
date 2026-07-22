# 14 — Permission & Message-Request Internals

This is the *as-built* deep dive behind the design sketch in
[03-permissions-and-requests.md](03-permissions-and-requests.md) and
[04-group-chats.md](04-group-chats.md). Where those docs give you the model, this
one gives you the code: every branch of the send-permission engine, every entry
in the group matrix, and the full message-request lifecycle — quoted verbatim
from the source, with the *why*, the *cost*, the *concurrency behaviour*, and the
*failure modes* spelled out for each. If you are changing anything that touches
who-can-message-whom, read this first.

The whole subsystem lives under two packages:

| Concern | Type | File |
|---|---|---|
| DIRECT send decision (pure) | `ChatPermissionEngine` | `src/main/java/ak/dev/irc/app/chat/permission/ChatPermissionEngine.java` |
| Social-graph → chat-state derivation | `ChatRelationshipService` | `src/main/java/ak/dev/irc/app/chat/permission/ChatRelationshipService.java` |
| GROUP authority matrix (pure) | `GroupPermissions` | `src/main/java/ak/dev/irc/app/chat/permission/GroupPermissions.java` |
| Decision outcomes | `SendDecision` | `src/main/java/ak/dev/irc/app/chat/enums/SendDecision.java` |
| Group action verbs | `GroupAction` | `src/main/java/ak/dev/irc/app/chat/enums/GroupAction.java` |
| Send hot path / enforcement | `MessageService` | `src/main/java/ak/dev/irc/app/chat/service/MessageService.java` |
| Request inbox actions | `MessageRequestService` | `src/main/java/ak/dev/irc/app/chat/service/MessageRequestService.java` |
| Request row | `MessageRequest` | `src/main/java/ak/dev/irc/app/chat/entity/MessageRequest.java` |

The two authority functions — `ChatPermissionEngine.authorizeDirectSend` and
`GroupPermissions.can` — are deliberately **pure** and side-effect-free so they
can be unit-tested against a truth table with no database. All I/O (the graph
reads) is isolated in `ChatRelationshipService`, and all *enforcement* (throwing,
persisting, broadcasting) lives in `MessageService`. Keep that separation: a
decision function that reads the DB or throws is a decision function you cannot
test cheaply.

---

## 1. The DIRECT send truth table

### 1.1 What it does

Every DIRECT message is authorised **before it is persisted**. The engine takes
`(senderId, recipientId)` and returns exactly one of four outcomes from
`SendDecision`:

```java
public enum SendDecision {
    /** Normal write + fan-out + push (subject to mute). */
    ALLOW,
    /** Stranger's first contact — create the thread but quarantine it as a
     *  pending message request; no push, no receipts/typing/presence leaked. */
    ROUTE_TO_REQUEST,
    /** Recipient has restricted the sender — write normally but the recipient's
     *  client files it in a muted tray; the sender gets no delivery/read signal. */
    DELIVER_RESTRICTED,
    /** A block relationship exists — the message is not written. Never reveals
     *  who blocked whom. */
    DENY
}
```

### 1.2 The code (verbatim)

`ChatPermissionEngine.authorizeDirectSend` is the entire decision — five ordered
checks, first match wins:

```java
public SendDecision authorizeDirectSend(UUID senderId, UUID recipientId) {
    // 1. Hard block wins over everything.
    if (relationships.isBlockedEitherWay(senderId, recipientId)) {
        return SendDecision.DENY;
    }
    // 2. Restrict: accepted but quarantined into the recipient's muted tray.
    if (relationships.isRestrictedBy(recipientId, senderId)) {
        return SendDecision.DELIVER_RESTRICTED;
    }
    // 3. Already talking? Straight through.
    if (relationships.hasAcceptedThread(senderId, recipientId)) {
        return SendDecision.ALLOW;
    }
    // 4. Connected (mutual follow)? Straight through.
    if (relationships.isConnected(senderId, recipientId)) {
        return SendDecision.ALLOW;
    }
    // 5. Stranger's first contact → Message Request.
    return SendDecision.ROUTE_TO_REQUEST;
}
```

### 1.3 The truth table

| # | `sender → recipient` | `recipient → sender` | Prior DIRECT thread | Outcome |
|---|---|---|---|---|
| 1 | blocked | any | any | `DENY` |
| 2 | any | blocked | any | `DENY` |
| 3 | (not blocked) | restricted sender | any | `DELIVER_RESTRICTED` |
| 4 | (not blocked/restricted) | — | accepted (or no request row) | `ALLOW` |
| 5 | mutual follow | — | none / pending | `ALLOW` |
| 6 | one-way / none | — | none / pending | `ROUTE_TO_REQUEST` |

Rows 1–2 collapse because block is **symmetric** (see §2.1): the engine never
needs to know *which* side blocked.

### 1.4 Why this exact order

The ordering is not cosmetic — each check is placed where it is because a
higher-priority relationship must **override** the ones below it:

- **Block is first** because it is the strongest, most privacy-sensitive verdict.
  If a block exists, we must not even *look* at whether they are connected or have
  a thread — and we must return the same generic `DENY` regardless of direction so
  nothing leaks about who blocked whom (§6.3). Short-circuiting first also makes
  the cheapest possible decision for the most abusive case: one query, then stop.

- **Restrict is second — above `hasAcceptedThread` and `isConnected`.** This is
  the subtle one. A restricted sender may well *also* be a mutual follow with an
  already-accepted thread. If we checked "accepted thread → ALLOW" first, a
  restrict applied *after* a normal thread existed would be silently ignored and
  the message would push loudly. Placing restrict above both means: **the moment
  the recipient restricts someone, every subsequent message goes quiet**,
  regardless of prior connection state. Restrict is a downgrade that must win over
  any pre-existing "we're friends" signal.

- **Accepted thread before connected** is a pure cost optimisation with identical
  semantics — both yield `ALLOW`. `hasAcceptedThread` is checked first because an
  already-graduated thread is the common hot case (people keep messaging people
  they've messaged before), and it also correctly handles the case where two users
  have an accepted thread but are *not* mutual follows (e.g. one accepted a
  request then unfollowed).

- **Stranger is the fall-through default.** Anything that is not blocked, not
  restricted, not an existing thread, and not a mutual follow is by definition a
  first contact from someone you don't know → it must be quarantined as a request.

### 1.5 Complexity & round-trips

The engine issues **one to six Postgres reads** (the block check always runs for a
DIRECT send), short-circuiting as early as
possible. Called inside the send transaction (`MessageService.send` is
`@Transactional`), so every read joins the same transaction/snapshot.

| Reached branch | Reads issued (cumulative) |
|---|---|
| `DENY` (block) | 1 — `isBlockedBetween` |
| `DELIVER_RESTRICTED` | 2 — + `isRestricting` |
| `ALLOW` (accepted thread) | 3–4 — + `findByDirectKey`, `findByConversationId` |
| `ALLOW` (connected) | 5–6 — + `isFollowing(a,b)`, `isFollowing(b,a)` |
| `ROUTE_TO_REQUEST` | 6 — all of the above |

Every query is an indexed point/count lookup (`COUNT(...) > 0`, unique-key
lookups) — no scans, no joins over collections. The most expensive verdict
(stranger) is 6 tiny reads, which is acceptable because it happens once per
first-contact, not on the steady-state hot path (an established thread is
`ALLOW` at 3 reads and the connection state is cacheable — see §7).

---

## 2. Deriving the four chat states from the social graph

### 2.1 What & why

Chat does **not** own a relationship table. The four states the engine needs —
*blocked*, *restricted*, *connected*, *accepted thread* — are all derived from the
platform's existing `follow / block / restrict` graph by
`ChatRelationshipService`. The rationale is stated in the class Javadoc: *"Keeping
the derivation in one place means that if 'connected' ever changes (e.g. an
explicit friendship edge is added) only this class changes."* The engine stays
pure; the mapping stays swappable.

| Chat state | Derivation | Symmetry |
|---|---|---|
| **Blocked** | either side blocked the other | symmetric |
| **Restricted** | recipient restricted the sender | asymmetric, silent |
| **Connected** | mutual follow | symmetric |
| **Accepted thread** | a DIRECT conversation exists with no request row, or an `ACCEPTED` one | symmetric |

### 2.2 Block — symmetric, one query

```java
@Transactional(readOnly = true)
public boolean isBlockedEitherWay(UUID a, UUID b) {
    return socialGuard.isBlockedBetween(a, b);
}
```

This delegates straight to the platform-wide `SocialGuard.isBlockedBetween`, which
guards self-pairs and calls `UserBlockRepository.isBlockedBetween`:

```java
@Query("""
    SELECT COUNT(ub) > 0 FROM UserBlock ub
    WHERE (ub.blocker.id = :a AND ub.blocked.id = :b)
       OR (ub.blocker.id = :b AND ub.blocked.id = :a)
    """)
boolean isBlockedBetween(@Param("a") UUID a, @Param("b") UUID b);
```

The `OR` makes it direction-agnostic in a single round-trip — this is why the
truth table's rows 1 and 2 are one check. Reusing `SocialGuard` (the same guard
every post/comment/react path uses) means chat inherits the platform's block
semantics for free and can never drift from them.

### 2.3 Restrict — asymmetric, silent, one query

```java
/** True when {@code recipient} has restricted {@code sender}. */
@Transactional(readOnly = true)
public boolean isRestrictedBy(UUID recipient, UUID sender) {
    return restrictionRepo.isRestricting(recipient, sender);
}
```

Note the **argument order carries the asymmetry**: `isRestrictedBy(recipient,
sender)` asks "has the recipient restricted the sender?" — restrict is
directional. The engine calls it as `isRestrictedBy(recipientId, senderId)`.
Underneath, `UserRestrictionRepository.isRestricting` is a `COUNT(...) > 0` on
`(restrictor, restricted)`. Restrict is deliberately *not* symmetric: A
restricting B must not stop B seeing A's messages if A never restricted B.

### 2.4 Connected — mutual follow, two queries

```java
/** Connected = mutual follow. */
@Transactional(readOnly = true)
public boolean isConnected(UUID a, UUID b) {
    if (a == null || b == null || a.equals(b)) return false;
    return followRepo.isFollowing(a, b) && followRepo.isFollowing(b, a);
}
```

"Connected" is the platform's definition of *friends*: **both** directions of
`UserFollowRepository.isFollowing` must be true. The `&&` short-circuits — if the
first direction is not a follow, the second query never runs, so a one-way follow
costs one read, not two. The null/self guard returns `false` early (you are never
"connected" to yourself for messaging purposes; self-DMs are not modelled here).

If the platform ever introduces an explicit friendship edge, **this method is the
only thing that changes** — the engine, the group matrix, and the request
lifecycle are all untouched.

### 2.5 Accepted thread — the graduation check

```java
@Transactional(readOnly = true)
public boolean hasAcceptedThread(UUID a, UUID b) {
    Conversation convo = conversationRepo.findByDirectKey(DirectKeys.of(a, b)).orElse(null);
    if (convo == null) return false;
    MessageRequest req = messageRequestRepo.findByConversationId(convo.getId()).orElse(null);
    return req == null || req.getStatus() == MessageRequestStatus.ACCEPTED;
}
```

Two reads:

1. Resolve the DIRECT conversation by its deterministic `DirectKeys.of(a, b)` —
   the canonical `min(a,b):max(a,b)` string with a `UNIQUE(direct_key)`
   constraint, so the same pair always maps to the same row regardless of who
   initiated. No `convo` → the two have never talked → not accepted.
2. If a conversation exists, look for its request row.
   `req == null` means the thread was created between connected users and never
   needed a request → it counts as accepted. `req.getStatus() == ACCEPTED` means a
   stranger request that the recipient has since accepted → graduated.

A still-`PENDING`, `DECLINED`, or `BLOCKED` request is therefore **not** an
accepted thread — which is exactly what keeps a pending stranger on the
`ROUTE_TO_REQUEST` path rather than `ALLOW`.

### 2.6 Transaction & concurrency notes

Every method above is `@Transactional(readOnly = true)`. Called standalone (e.g.
from `markDelivered`) each opens its own read-only transaction; called from within
`MessageService.send`'s writable `@Transactional`, they join it (default
propagation `REQUIRED`), so all the graph reads for one send observe a single
consistent snapshot. There is no locking on the read path — the derivation is
pure reads over indexed columns, and staleness is bounded by the fact that a
block/restrict/follow change and a concurrent in-flight send race at most one
message's worth of window.

---

## 3. The GROUP permission matrix

Groups ignore the connection graph entirely. Authority is a function of
`(actorRole, action, targetRole, settings)` and nothing else, resolved by the
single pure function `GroupPermissions.can`. The class Javadoc states the
governing invariant up front: *"an admin can never act on the owner or on another
admin — only on plain members. The owner can act on anyone."*

### 3.1 The switch (verbatim)

```java
public static boolean can(MemberRole actor, GroupAction action, MemberRole target, GroupSettings settings) {
    GroupSettings s = settings != null ? settings : GroupSettings.defaults();
    boolean actorIsOwner = actor == MemberRole.OWNER;
    boolean actorIsAdmin = actor == MemberRole.ADMIN;
    boolean actorIsStaff = actorIsOwner || actorIsAdmin;
    boolean targetIsPlainMember = target == MemberRole.MEMBER;

    return switch (action) {
        case SEND_MESSAGE ->
                s.getSendMode() != MemberScope.ADMINS_ONLY || actorIsStaff;

        case ADD_MEMBERS ->
                actorIsStaff || s.getWhoCanAddMembers() == MemberScope.ALL_MEMBERS;

        case EDIT_INFO ->
                actorIsStaff || s.getWhoCanEditInfo() == MemberScope.ALL_MEMBERS;

        case PIN_MESSAGE ->
                actorIsStaff || s.getWhoCanPin() == MemberScope.ALL_MEMBERS;

        // Admin may act on plain members only; owner on anyone.
        case REMOVE_MEMBER, RESTRICT_MEMBER ->
                actorIsOwner || (actorIsAdmin && targetIsPlainMember);

        case PROMOTE_ADMIN ->
                actorIsOwner || (actorIsAdmin && s.isAdminsCanPromote() && targetIsPlainMember);

        case CHANGE_SETTINGS, DELETE_ANY_MESSAGE, CREATE_INVITE ->
                actorIsStaff;

        case DEMOTE_ADMIN, TRANSFER_OWNERSHIP, DELETE_GROUP ->
                actorIsOwner;
    };
}
```

### 3.2 The full matrix

`✔` = allowed, `~` = **settings-gated** (allowed only when the named setting
permits), `✘` = denied. `target` is the role of the member being acted on;
`—` means the action has no target (`target == null`). Group defaults from
`GroupSettings.defaults()` are: `sendMode = ALL_MEMBERS`,
`whoCanAddMembers = ALL_MEMBERS`, `whoCanEditInfo = ADMINS_ONLY`,
`whoCanPin = ADMINS_ONLY`, `adminsCanPromote = false`.

| Action | Target | OWNER | ADMIN | MEMBER | Gate |
|---|---|---|---|---|---|
| `SEND_MESSAGE` | — | ✔ | ✔ | ~ | `sendMode != ADMINS_ONLY` |
| `ADD_MEMBERS` | — | ✔ | ✔ | ~ | `whoCanAddMembers == ALL_MEMBERS` (default: yes) |
| `EDIT_INFO` | — | ✔ | ✔ | ~ | `whoCanEditInfo == ALL_MEMBERS` (default: **no**) |
| `PIN_MESSAGE` | — | ✔ | ✔ | ~ | `whoCanPin == ALL_MEMBERS` (default: **no**) |
| `REMOVE_MEMBER` | MEMBER | ✔ | ✔ | ✘ | admin→member only |
| `REMOVE_MEMBER` | ADMIN/OWNER | ✔ | ✘ | ✘ | owner-only on staff |
| `RESTRICT_MEMBER` | MEMBER | ✔ | ✔ | ✘ | admin→member only |
| `RESTRICT_MEMBER` | ADMIN/OWNER | ✔ | ✘ | ✘ | owner-only on staff |
| `PROMOTE_ADMIN` | MEMBER | ✔ | ~ | ✘ | admin needs `adminsCanPromote` (default: no) |
| `PROMOTE_ADMIN` | ADMIN/OWNER | ✔ | ✘ | ✘ | (already staff) |
| `CHANGE_SETTINGS` | — | ✔ | ✔ | ✘ | staff |
| `DELETE_ANY_MESSAGE` | — | ✔ | ✔ | ✘ | staff |
| `CREATE_INVITE` | — | ✔ | ✔ | ✘ | staff |
| `DEMOTE_ADMIN` | any | ✔ | ✘ | ✘ | owner-only |
| `TRANSFER_OWNERSHIP` | — | ✔ | ✘ | ✘ | owner-only |
| `DELETE_GROUP` | — | ✔ | ✘ | ✘ | owner-only |

### 3.3 How the invariant is encoded

"An admin can never act on the owner or another admin" is not a separate check —
it falls out of the boolean algebra:

- `REMOVE_MEMBER` / `RESTRICT_MEMBER`: `actorIsOwner || (actorIsAdmin &&
  targetIsPlainMember)`. `targetIsPlainMember` is `target == MemberRole.MEMBER`,
  so when an admin targets another admin or the owner it is `false`, and since the
  actor is not the owner the whole expression is `false`. Only the owner
  (`actorIsOwner`) bypasses the target-role gate.
- `PROMOTE_ADMIN`: same shape plus the `adminsCanPromote` setting — an admin can
  only promote a plain member, and only if the group allows admins to promote at
  all.
- `DEMOTE_ADMIN`, `TRANSFER_OWNERSHIP`, `DELETE_GROUP`: `actorIsOwner` with no
  admin clause — structurally impossible for an admin. Demotion being owner-only
  is the flip side of the invariant: if admins could demote admins, one admin
  could strip another, violating "can't act on another admin."

Because `target` can be `null` for target-less actions, `targetIsPlainMember`
evaluates to `false` there — harmless, since those actions (`SEND_MESSAGE`,
`ADD_MEMBERS`, …) don't read it.

### 3.4 Settings-gated actions

Five actions defer part of their answer to the JSONB `GroupSettings` stored on the
conversation (persisted as a single `jsonb` column so new knobs need no
migration):

- **`SEND_MESSAGE` — `sendMode`.** `ALL_MEMBERS` (default) lets everyone post;
  `ADMINS_ONLY` turns the group into a broadcast channel where only staff post.
  This is the same knob `authorizeGroupSend` enforces on the hot path (§4).
- **`ADD_MEMBERS` — `whoCanAddMembers`.** Default `ALL_MEMBERS`: any member can
  invite. Set to `ADMINS_ONLY` for curated groups.
- **`EDIT_INFO` — `whoCanEditInfo`.** Default `ADMINS_ONLY`: only staff rename the
  group or change its avatar.
- **`PIN_MESSAGE` — `whoCanPin`.** Default `ADMINS_ONLY`: only staff pin.
- **`PROMOTE_ADMIN` — `adminsCanPromote`.** Default `false`: promotion is
  owner-only unless the owner has explicitly delegated it to admins.

Staff (`actorIsStaff`) always satisfy the settings-gated read actions regardless
of the scope, because the scope only ever *widens* permission to plain members —
it never restricts staff.

### 3.5 Where `can()` is called, and what it deliberately does *not* know

The matrix is the single funnel. It is invoked from `GroupMemberService`
(`ADD_MEMBERS`, `REMOVE_MEMBER`, `PROMOTE_ADMIN`/`DEMOTE_ADMIN`,
`RESTRICT_MEMBER`, `TRANSFER_OWNERSHIP`, `CREATE_INVITE`), `ConversationService`
(`EDIT_INFO`, `CHANGE_SETTINGS`, `DELETE_GROUP`), and `MessageService`
(`SEND_MESSAGE`, `PIN_MESSAGE`, `DELETE_ANY_MESSAGE`) — see
[04-group-chats.md](04-group-chats.md).

`can()` reasons purely about **roles**, not identities. It does not know *who* the
actor is, only their role, so identity-level invariants — you can't remove
yourself as the last owner, the owner can't demote themselves while sole owner,
etc. — are enforced by the calling `GroupMemberService`, not here. This is a
deliberate division: keep `can()` a pure, exhaustively-switchable function
(the compiler enforces that every `GroupAction` is handled), and let the service
layer own stateful/identity guards.

**Complexity:** `can()` is O(1), branch-free apart from the switch, and touches no
database. Its inputs (`actor`/`target` roles, `settings`) are already loaded by
the caller from the member row and the conversation, so it adds zero round-trips.

---

## 4. `authorizeGroupSend` — the group hot path

Sending to a group does not call `GroupPermissions.can` alone; it first screens
**membership status**, then defers the role/mode question to the matrix:

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

Three ordered gates, each with a distinct error code the client can branch on:

1. **`NOT_A_MEMBER`** — a `LEFT` (voluntarily left) or `REMOVED` (kicked) status.
   `MemberStatus` is orthogonal to `MemberRole`: a former owner who left is still
   `NOT_A_MEMBER`.
2. **`READ_ONLY`** — status `RESTRICTED`. This is the *group* form of restrict (an
   admin muted this member): they retain read access (`canRead()` is true for
   `RESTRICTED`, §7) but cannot post. Checked before the role gate because a muted
   admin must still be blocked from sending even though their role would otherwise
   allow it.
3. **`ADMINS_ONLY`** — the group is in `sendMode == ADMINS_ONLY` and the actor is a
   plain member. This is the only gate that consults the matrix.

The membership row itself is fetched once in `MessageService.send`
(`memberRepo.findMember(conversationId, senderId)`, one indexed lookup on the
composite PK) and passed in — `authorizeGroupSend` issues **zero additional
reads**. `convo.getGroupSettings()` is already hydrated on the `Conversation`
entity loaded at the top of `send`.

Note this is the *screen-and-throw* counterpart to the DIRECT engine's
*return-a-decision* shape: groups have no "route to request" or "deliver quietly"
outcome, so a failed group send is simply a `403` with a specific code, whereas a
DIRECT send always succeeds-with-a-decision unless blocked.

---

## 5. The message-request lifecycle

### 5.1 The row and its anti-spam constraint

```java
@Table(
    name = "message_requests",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_message_request_pair",
        columnNames = {"recipient_id", "requester_id"}
    ),
    indexes = @Index(name = "idx_request_inbox", columnList = "recipient_id, status, created_at DESC")
)
```

The `UNIQUE(recipient_id, requester_id)` constraint is *the anti-spam backbone*:
at most one request row per ordered pair, enforced by the database, not by
application logic. The `idx_request_inbox` index makes the recipient's Requests
list — "my requests, by status, newest first" — a single indexed range scan.

The row also carries the two anti-spam counters:

```java
@Column(name = "first_message_id", nullable = false)
private long firstMessageId;

/**
 * Count of messages the stranger has sent into this un-accepted thread — used
 * to cap pre-acceptance spam (further sends blocked once the cap is hit).
 */
@Column(name = "message_count", nullable = false)
@Builder.Default
private int messageCount = 1;
```

### 5.2 Creation on first stranger contact + the 3-message cap

When the engine returns `ROUTE_TO_REQUEST`, `MessageService.send` calls
`ensureRequestAndCap` *before* persisting the message:

```java
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

The cap is a constant on `MessageService`:

```java
/** Messages a stranger may send into an unaccepted thread before it's blocked. */
private static final int STRANGER_MESSAGE_CAP = 3;
```

Step by step:

1. **No row yet** → create a `PENDING` request, `messageCount = 1`, and return
   `justCreated = true`. This is the very first message. The caller uses
   `justCreated` to decide whether to emit a `REQUEST_NEW` realtime event and a
   single `MESSAGE_REQUEST` bell notification (§5.4).
2. **Existing non-`PENDING` row** (`DECLINED` / `BLOCKED`; `ACCEPTED` never reaches
   here because it would have been `ALLOW`ed by `hasAcceptedThread`) → the stranger
   was already turned down; refuse with `REQUEST_LIMIT_REACHED`.
3. **Existing `PENDING` row at the cap** (`messageCount >= 3`) → the stranger has
   used their three pre-acceptance messages; refuse with `REQUEST_LIMIT_REACHED`
   until the recipient acts.
4. **Existing `PENDING` row under the cap** → increment `messageCount` and return
   `justCreated = false` (no second notification, no second `REQUEST_NEW`).

So an unknown sender gets **exactly three messages** to make their case; after
that they are silent until accepted, declined, or blocked. This is the
Instagram/Messenger "message request" contract.

### 5.3 Inbox exclusion — the `NOT EXISTS` pending subquery

A pending request must appear in the recipient's **Requests** inbox but *not*
pollute their main conversation list. That separation is enforced entirely in the
main-inbox query, `ConversationMemberRepository.findInbox`:

```java
@Query(value = """
    SELECT m FROM ConversationMember m
    JOIN FETCH m.conversation c
    WHERE m.id.userId = :uid
      AND m.status = ak.dev.irc.app.chat.enums.MemberStatus.ACTIVE
      AND m.archived = false
      AND c.deletedAt IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM MessageRequest r
          WHERE r.conversationId = c.id
            AND r.recipientId = :uid
            AND r.status = ak.dev.irc.app.chat.enums.MessageRequestStatus.PENDING)
    ORDER BY m.pinned DESC, c.lastMessageAt DESC NULLS LAST
    """,
    countQuery = """ ... same NOT EXISTS ... """)
Page<ConversationMember> findInbox(@Param("uid") UUID userId, Pageable pageable);
```

The correlated `NOT EXISTS` hides a conversation from the main inbox **only while
there is a `PENDING` request for which `:uid` is the recipient**. Two important
asymmetries follow directly from the predicate:

- **The requester is never affected.** The subquery keys on `r.recipientId =
  :uid`. The stranger who *sent* the request sees the thread in their own normal
  inbox from message one — only the recipient's view quarantines it. This matches
  Messenger: you don't know your message went to someone's Requests folder.
- **Only `PENDING` is filtered.** The predicate excludes exclusively the
  `PENDING` status. The instant the request flips to `ACCEPTED`, `DECLINED`, or
  `BLOCKED`, the `NOT EXISTS` no longer matches and the conversation becomes
  eligible for the main inbox again. For `ACCEPTED` that is precisely the intended
  "graduation" (§5.4). For `DECLINED`/`BLOCKED` it is a subtlety worth knowing:
  the thread is no longer *hidden by this subquery* — what keeps a declined/blocked
  stranger from mattering is that `ensureRequestAndCap` refuses all further sends
  (branch 2 above) and, for block, the engine returns `DENY` outright. The row's
  history (up to three received messages) remains.

The count query carries the identical `NOT EXISTS` so the page count and the page
contents agree — a classic paginated-count divergence bug that is avoided here by
duplicating the predicate verbatim.

### 5.4 Accept / decline / block

All three inbox actions live in `MessageRequestService` and share a recipient
guard:

```java
private MessageRequest requireRecipient(UUID requestId, UUID userId) {
    MessageRequest r = requestRepo.findById(requestId)
            .orElseThrow(() -> new ResourceNotFoundException("MessageRequest", "id", requestId));
    if (!r.getRecipientId().equals(userId)) {
        throw new ForbiddenException("This request is not addressed to you.", "ACCESS_FORBIDDEN");
    }
    return r;
}
```

Only the addressed recipient may act — the requester cannot accept their own
request. The three verbs (exposed as `POST /api/v1/message-requests/{id}/accept`,
`/decline`, `/block` — see [09-api-reference.md](09-api-reference.md)):

**Accept** — graduate the thread:

```java
@Transactional
public void accept(UUID requestId, UUID userId) {
    MessageRequest r = requireRecipient(requestId, userId);
    r.setStatus(MessageRequestStatus.ACCEPTED);
    requestRepo.save(r);
    // Let the requester know the thread graduated (their client refetches).
    broadcaster.broadcastTo(r.getRequesterId(), ChatRealtimeEvent.builder()
            .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
            .conversationId(r.getConversationId())
            .memberChange("REQUEST_ACCEPTED")
            .build());
}
```

After accept: `hasAcceptedThread` now returns `true` (status is `ACCEPTED`), so
future sends are plain `ALLOW`; the `NOT EXISTS PENDING` filter no longer excludes
it, so it moves from Requests into the recipient's main inbox; and the requester
gets a `CONVERSATION_UPDATED / REQUEST_ACCEPTED` realtime nudge so their client
refetches and unlocks receipts/typing/presence (which had been suppressed — §6.1).

**Decline** — status only, no broadcast:

```java
@Transactional
public void decline(UUID requestId, UUID userId) {
    MessageRequest r = requireRecipient(requestId, userId);
    r.setStatus(MessageRequestStatus.DECLINED);
    requestRepo.save(r);
}
```

No realtime event is sent — the requester is intentionally *not* told they were
declined (privacy: same principle as §6). Further stranger sends are then refused
by `ensureRequestAndCap` branch 2.

**Block** — decline-and-block, reusing the social API:

```java
@Transactional
public void block(UUID requestId, UUID userId) {
    MessageRequest r = requireRecipient(requestId, userId);
    r.setStatus(MessageRequestStatus.BLOCKED);
    requestRepo.save(r);
    // Reuse the existing social block (runs in the recipient's security context).
    socialService.block(r.getRequesterId());
}
```

`UserSocialService.block` is the *same* platform block used everywhere else: it
creates a symmetric `UserBlock`, `deleteAllBetween` removes any follow edges, and
it supersedes any existing restriction. Because it runs in the recipient's
security context (`authenticatedUserId()` inside the impl is the acting
recipient), no extra plumbing is needed. From then on `authorizeDirectSend`
short-circuits to `DENY` at check #1 for anything the requester tries.

### 5.5 Concurrency & failure modes of the request lifecycle

- **Duplicate-pending race.** Two devices of the same stranger firing a first
  message concurrently both read `existing == null` and both attempt an `INSERT`.
  The `uk_message_request_pair` unique constraint lets exactly one commit; the
  loser's `save` throws a constraint violation, which propagates out of
  `MessageService.send`'s `try`, hits its `catch (RuntimeException e)` block —
  `idempotency.release(...)` frees the nonce and the exception rethrows. Net
  effect: the DB guarantees a single pending row; one of the two concurrent sends
  fails and can be retried by the client. No duplicate request is ever created.
- **`messageCount` is a read-modify-write, not atomic.** Branch 4 does
  `existing.setMessageCount(existing.getMessageCount() + 1)` then `save`. Under two
  genuinely concurrent under-cap sends from the same stranger (two devices), both
  can read `messageCount = 1` and both write `2` — a lost update, so the effective
  cap can be exceeded by roughly the number of concurrently in-flight sends. This
  is an accepted tradeoff: the platform-wide send rate limiter
  (`rateLimiter.check("chat-send", senderId, 30, 10s)` at the top of `send`) plus
  the fact that a human types serially make the practical over-count negligible,
  and the repository *does* expose an atomic
  `@Modifying UPDATE ... SET messageCount = messageCount + 1` (`incrementMessageCount`)
  if the cap is ever tightened to a hard guarantee. As written, the cap is a
  strong deterrent, not a cryptographic bound.
- **Block-when-already-blocked.** If the recipient already blocks the requester,
  `socialService.block` throws `DuplicateResourceException`; since it runs inside
  `MessageRequestService.block`'s `@Transactional`, the whole unit rolls back —
  including the `status = BLOCKED` write. The row stays in its prior state, which
  is correct (nothing to do; they're already blocked).
- **Everything is idempotency-safe on the send side.** The request row is written
  inside the same `@Transactional` as the message; if persistence fails after
  `ensureRequestAndCap`, the transaction rolls the request row back too, so a
  never-delivered message never leaves a phantom request or a bumped count.

---

## 6. Restrict & block privacy — no existence leak, quiet delivery

The subsystem's privacy guarantee is that **restrict and block never leak signal
to the other party**. Three mechanisms enforce it.

### 6.1 `suppressEphemeral` — no receipts / typing / presence leak

Delivered receipts, typing indicators, and presence must not flow while a thread
is unsettled, or a restricted/pending peer would learn the other person is
online, typing, or has read their message. `ChatRelationshipService.suppressEphemeral`
is the single gate:

```java
@Transactional(readOnly = true)
public boolean suppressEphemeral(UUID conversationId, UUID actorId) {
    MessageRequest req = messageRequestRepo.findByConversationId(conversationId).orElse(null);
    if (req != null && req.getStatus() == MessageRequestStatus.PENDING) return true;

    Conversation c = conversationRepo.findById(conversationId).orElse(null);
    if (c != null && c.getType() == ConversationType.DIRECT) {
        UUID peer = memberRepo.findAllByConversation(conversationId).stream()
                .map(m -> m.getId().getUserId())
                .filter(id -> !id.equals(actorId))
                .findFirst().orElse(null);
        if (peer != null && (isRestrictedBy(peer, actorId) || isRestrictedBy(actorId, peer))) {
            return true;
        }
    }
    return false;
}
```

It returns `true` (suppress) when **either**:

- the conversation has a still-`PENDING` request (the thread hasn't been
  accepted), **or**
- it's a DIRECT thread and a restrict exists in *either* direction between the two
  parties.

It is consulted on the ephemeral hot paths — e.g. `MessageService.markDelivered`:

```java
// Don't leak presence/receipts on a pending request or a restricted thread.
if (relationships.suppressEphemeral(m.getConversationId(), userId)) return;
```

Note the restrict check is bidirectional here (`isRestrictedBy(peer, actor) ||
isRestrictedBy(actor, peer)`): if A restricted B, then *neither* A's nor B's
receipts should flow across that thread — otherwise the presence of receipts in
one direction would betray the restrict. **Cost:** 1 read for the request lookup;
if not pending and DIRECT, +1 for the conversation, +1 for the members, +1–2 for
the restrict checks — all indexed, only on receipt/typing events (not on every
message).

### 6.2 `DELIVER_RESTRICTED` — quiet delivery

When the engine returns `DELIVER_RESTRICTED`, the message *is* written normally,
but `MessageService.dispatch` routes it to a minimal recipient set and skips every
loud signal. The relevant selection:

```java
List<UUID> recipients = (decision == SendDecision.ROUTE_TO_REQUEST
        || decision == SendDecision.DELIVER_RESTRICTED)
        ? new ArrayList<>(List.of(directPeer))
        : memberRepo.findReadableMemberIds(conversationId);
...
boolean smallEnough = convo.getMemberCount() <= LARGE_GROUP_CUTOFF;
if (smallEnough && decision == SendDecision.ALLOW) {
    memberRepo.bumpUnreadForOthers(conversationId, senderId);
}
...
} else if (decision == SendDecision.DELIVER_RESTRICTED) {
    // The peer's restricted tray + the sender's own devices (the sender
    // never learns they were restricted).
    broadcaster.broadcast(List.of(directPeer, senderId), newEvt);
}
```

Contrast the three outcomes at dispatch time:

| | Recipients for `MESSAGE_NEW` | Unread bumped? | Offline bell? |
|---|---|---|---|
| `ALLOW` | all readable members | yes (if small) | yes (offline members) |
| `ROUTE_TO_REQUEST` | `[directPeer, senderId]` | **no** | one `MESSAGE_REQUEST`, only if `justCreated` |
| `DELIVER_RESTRICTED` | `[directPeer, senderId]` | **no** | **no** |

So a restricted message lands silently in the recipient's muted tray, the
sender's own other devices stay in sync (multi-device), and — critically — the
sender receives **no** delivery/read receipt and **no** signal that they were
restricted. The unread badge is not inflated, and no offline push fires. This is
the entire point of restrict versus block: it is *quiet*, not *walled*.

### 6.3 `DENY` — no existence leak

For `DENY`, `MessageService.send` throws a **generic** error:

```java
if (decision == SendDecision.DENY) {
    throw new ForbiddenException("This interaction is not allowed.", "BLOCKED");
}
```

The message code is a flat `"This interaction is not allowed."` — it does **not**
say who blocked whom, nor even confirm the other account exists in a
distinguishable way, matching `SocialGuard`'s platform-wide rule *"never leak who
blocked whom."* Because block is symmetric and the check is direction-agnostic
(§2.2), the sender cannot infer direction from the response. Nothing is persisted,
no conversation is touched, no realtime event fires.

---

## 7. Read permission

Reading is simpler and uniform across DIRECT and GROUP: you may read a
conversation iff you are an `ACTIVE` **or** `RESTRICTED` member. The predicate is
a one-liner on `ConversationMember`:

```java
public boolean isActive()      { return status == MemberStatus.ACTIVE; }
public boolean canRead()       { return status == MemberStatus.ACTIVE || status == MemberStatus.RESTRICTED; }
```

`canRead()` is the read gate; `isActive()` is the *interaction* gate. The
distinction matters:

- **`canRead()` (ACTIVE ∪ RESTRICTED)** gates reads and receiving fan-out. A
  RESTRICTED (admin-muted) group member still sees new messages, edits, deletes,
  and reactions — hence `dispatch` fans out to `findReadableMemberIds`
  (ACTIVE + RESTRICTED) for `MESSAGE_NEW`/edits/deletes, and `requireReadableMessage`
  / `forward`'s source check both filter on `ConversationMember::canRead`.
- **`isActive()` (ACTIVE only)** gates *doing* things. Reacting, for instance, is
  an interaction, not a read — `requirePostableMessage` rejects a non-active
  member with `READ_ONLY`: *"reacting is an interaction, not a read."* Likewise
  delivered-receipt fan-out uses `findActiveMemberIds` (ACTIVE only), because a
  muted member shouldn't emit receipts.

`LEFT` and `REMOVED` members are neither `canRead()` nor `isActive()`, so read
access stops **immediately** on leave/removal — the server simply stops serving
new reads and fan-out to them; any already-delivered copies on their device are
the client's concern, not the server's. There is no soft "grace" window.

---

## 8. Truth table as a test spec

The DIRECT engine is pure, so it should be unit-tested by stubbing
`ChatRelationshipService` and asserting the outcome for every relationship
combination. This table is the canonical spec — one row per equivalence class,
ordered by the engine's short-circuit priority:

| Case | `isBlockedEitherWay` | `isRestrictedBy(recip, sender)` | `hasAcceptedThread` | `isConnected` | **Expected** |
|---|---|---|---|---|---|
| Blocked either way | `true` | — | — | — | `DENY` |
| Recipient restricted sender | `false` | `true` | — | — | `DELIVER_RESTRICTED` |
| Restrict overrides existing thread | `false` | `true` | `true` | `true` | `DELIVER_RESTRICTED` |
| Accepted / graduated thread | `false` | `false` | `true` | `false` | `ALLOW` |
| Mutual follow, no thread yet | `false` | `false` | `false` | `true` | `ALLOW` |
| Mutual follow **and** accepted | `false` | `false` | `true` | `true` | `ALLOW` |
| Stranger, one-way follow | `false` | `false` | `false` | `false` | `ROUTE_TO_REQUEST` |
| Stranger, no follow | `false` | `false` | `false` | `false` | `ROUTE_TO_REQUEST` |

Key assertions the spec pins down (regression traps):

1. **Block dominates.** Row 1 must be `DENY` even if the two are also connected
   with an accepted thread — the engine must never reach the lower checks.
2. **Restrict beats a prior thread.** Row 3 is the ordering regression guard: a
   restrict applied *after* a normal thread existed must still yield
   `DELIVER_RESTRICTED`, not `ALLOW`. If someone reorders the `isRestrictedBy`
   check below `hasAcceptedThread`, this row flips and the test fails.
3. **Accepted OR connected → `ALLOW`.** Rows 4–6 cover both `ALLOW` origins,
   including the case where a thread is accepted but the users are *not* mutual
   follows (row 4, `isConnected == false`).
4. **Fall-through is a request.** Rows 7–8 confirm the default: any not-blocked,
   not-restricted, no-thread, non-mutual pair routes to a request.

The companion GROUP matrix (§3.2) is the test spec for `GroupPermissions.can` —
assert every `(actorRole × action × targetRole × settings)` cell; because `can()`
is a total switch over `GroupAction`, adding a new action without a case is a
compile error, and adding a new *row* to the matrix table should mean adding a new
assertion.

---

## See also

- [03-permissions-and-requests.md](03-permissions-and-requests.md) — the original
  design model these internals implement.
- [04-group-chats.md](04-group-chats.md) — roles, `MemberStatus`, and where each
  `GroupAction` is invoked from the service layer.
- [05-realtime-delivery.md](05-realtime-delivery.md) — the SSE/fan-out layer that
  `dispatch`, `REQUEST_NEW`, and the ephemeral (receipt/typing/presence) signals
  ride on.
- [09-api-reference.md](09-api-reference.md) — the wire contract for the
  message-request endpoints and the realtime event payloads referenced above.
