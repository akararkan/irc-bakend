# 16 — Group Lifecycle, Search & Notifications

This document is the as-built reference for the second half of the chat backend:
everything that happens **around** the message hot path. It covers the full
group-membership lifecycle (create → add → promote → restrict → transfer →
leave), the `SystemMessageService` that writes lifecycle events inline into the
timeline, Telegram-style invite links, pinned messages, the Elasticsearch
full-text search layer with its Cassandra-scan fallback, the pure-Postgres inbox
query, and how chat events feed the platform's existing notification pipeline.

Where the earlier docs describe the *shape* of the system, this one quotes the
*actual* code — the classes under `ak.dev.irc.app.chat.service`,
`…chat.search`, `…chat.repository`, and the notification enums — and for each
mechanism spells out WHAT it does, HOW (step by step, with real method names and
file paths), WHY that design was chosen over the alternative, the COMPLEXITY
(round-trips / partitions touched), and the CONCURRENCY / EDGE-CASE behaviour.

Cross-references: the permission matrix is defined in
[04-group-chats.md](04-group-chats.md); realtime delivery in
[05-realtime-delivery.md](05-realtime-delivery.md); the read/write message paths
in [06-algorithms.md](06-algorithms.md) and [11-send-path.md](11-send-path.md);
the data model in [02-data-model.md](02-data-model.md).

---

## 0. The cast of files

| Concern | Class | File |
|---|---|---|
| Group membership lifecycle + invite links | `GroupMemberService` | `chat/service/GroupMemberService.java` |
| Conversation lifecycle + inbox + personal toggles | `ConversationService` | `chat/service/ConversationService.java` |
| SYSTEM timeline messages | `SystemMessageService` | `chat/service/SystemMessageService.java` |
| Pin/unpin + delete-unpins + ES index writes | `MessageService` | `chat/service/MessageService.java` |
| Read path + search fallback + pinned read | `MessageQueryService` | `chat/service/MessageQueryService.java` |
| The single pure authority function | `GroupPermissions` | `chat/permission/GroupPermissions.java` |
| ES index/query for messages | `ChatSearchService` | `chat/search/service/ChatSearchService.java` |
| ES document mapping | `ChatMessageDocument` | `chat/search/document/ChatMessageDocument.java` |
| Boot-time index provisioning | `ElasticsearchIndexInitializer` | `common/search/ElasticsearchIndexInitializer.java` |
| Chat → notification bridge | `ChatNotificationService` | `chat/service/ChatNotificationService.java` |
| Global unread-badge cache | `UnreadBadgeCache` | `chat/service/UnreadBadgeCache.java` |
| Invite entity + row | `ConversationInvite` | `chat/entity/ConversationInvite.java` |
| Pin entity + row | `ConversationPin` | `chat/entity/ConversationPin.java` |
| Realtime event shape | `ChatRealtimeEvent` | `chat/realtime/ChatRealtimeEvent.java` |

Two invariants thread through the whole file and are worth stating up front:

1. **Every authority decision runs through `GroupPermissions.can(...)`.** No
   service re-implements "can an admin kick the owner?" — they ask the matrix.
2. **Most state changes emit two side effects: a SYSTEM timeline message and a
   `member.changed` (or `conversation.updated`) realtime event**, both deferred
   until after the DB transaction commits. The one deliberate exception is
   restrict/unrestrict, which emits *only* the `member.changed` event and writes
   **no** SYSTEM message (a mute is not narrated on the timeline) — see the §2.7
   table and [05-realtime-delivery.md](05-realtime-delivery.md).

---

## 1. Group creation

**WHAT.** `POST /api/v1/conversations` with a `GROUP` payload creates a group
with the caller as `OWNER`, seeds the requested members as `MEMBER`, writes a
`GROUP_CREATED` SYSTEM message, and fires an `ADDED_TO_GROUP` notification +
`member.changed` event to every seeded member.

**HOW.** `ConversationService.createGroup(UUID creatorId, CreateConversationRequest req)`:

```java
@Transactional
public ConversationResponse createGroup(UUID creatorId, CreateConversationRequest req) {
    if (!StringUtils.hasText(req.getTitle())) {
        throw new BadRequestException("A group requires a title.");
    }
    // De-dupe, drop the creator, drop non-existent / blocked users.
    List<UUID> requested = req.getMemberIds() == null ? List.of() : req.getMemberIds();
    LinkedHashSet<UUID> candidates = new LinkedHashSet<>(requested);
    candidates.remove(creatorId);
    if (candidates.size() > MAX_GROUP_INITIAL_MEMBERS) {
        throw new BadRequestException("A group can start with at most " + MAX_GROUP_INITIAL_MEMBERS + " members.");
    }
    Map<UUID, User> users = candidates.isEmpty() ? Map.of()
            : userRepository.findActiveByIdIn(candidates).stream().collect(Collectors.toMap(User::getId, u -> u));
    List<UUID> toAdd = candidates.stream()
            .filter(users::containsKey)
            .filter(id -> !relationships.isBlockedEitherWay(creatorId, id))
            .toList();

    Conversation c = conversationRepo.save(Conversation.builder()
            .type(ConversationType.GROUP)
            .title(req.getTitle().trim())
            .avatarKey(req.getAvatarKey())
            .ownerId(creatorId)
            .groupSettings(GroupSettings.defaults())
            .memberCount(1 + toAdd.size())
            .build());

    memberRepo.save(ConversationMember.of(c, creatorId, MemberRole.OWNER));
    for (UUID id : toAdd) memberRepo.save(ConversationMember.of(c, id, MemberRole.MEMBER));

    String creatorLabel = label(creatorId, users);
    systemMessages.write(c.getId(), SystemEventType.GROUP_CREATED, creatorId,
            creatorLabel + " created the group");

    // Notify + realtime the added members.
    for (UUID id : toAdd) {
        chatNotifications.notifyAddedToGroup(id, creatorId, c.getId(), c.getTitle(), creatorLabel);
        broadcaster.broadcastTo(id, ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MEMBER_CHANGED)
                .conversationId(c.getId())
                .userId(id).memberChange("ADDED").role(MemberRole.MEMBER.name())
                .build());
    }
    return get(c.getId(), creatorId);
}
```

Step by step:

1. **Title required.** A group without a title is rejected up front —
   `DIRECT` conversations have no title (the peer *is* the title), so this is a
   group-only invariant.
2. **Candidate cleanup.** `req.getMemberIds()` is wrapped in a
   `LinkedHashSet` — this **de-dupes while preserving insertion order** so the
   SYSTEM message and member rows read in the order the creator picked. The
   creator is removed from the candidate set (they get the `OWNER` row, not a
   duplicate `MEMBER` row).
3. **Bounded fan-out.** `MAX_GROUP_INITIAL_MEMBERS = 256` caps the initial
   seed. This is the same cutoff `MessageService` uses for eager-vs-lazy unread
   fan-out (`LARGE_GROUP_CUTOFF`), so a freshly created group is always in the
   "eager" regime.
4. **Existence + block filter in bulk.** `userRepository.findActiveByIdIn` is
   **one** Postgres round-trip that returns only *active* (non-deleted) users;
   `toAdd` further drops anyone blocked either way. A member id that doesn't
   resolve to an active user, or is blocked, is silently dropped — the group is
   created with whoever's left. This is deliberately lenient: a stale client
   member list shouldn't fail the whole create.
5. **`memberCount` is computed, not counted.** `1 + toAdd.size()` — the owner
   plus the survivors. It is stored denormalised on the `Conversation` row so
   the send path can read it in O(1) to decide eager-vs-lazy unread fan-out
   without a `COUNT(*)`.
6. **Owner first, then members.** The creator gets a `ConversationMember.of(c,
   creatorId, MemberRole.OWNER)` row; every survivor an `OWNER`→`MEMBER` row.
7. **One SYSTEM message.** `GROUP_CREATED` is written *once* (not one per
   member) — see §3.
8. **Per-member notify + realtime.** Each added member gets an
   `ADDED_TO_GROUP` bell notification (§8) and a `member.changed / ADDED`
   event delivered **directly to them** (`broadcastTo`), because they are not
   yet subscribed to the group's realtime fan-out and need the nudge to open
   the new thread.

**WHY.** Why bulk-resolve users instead of looping `findById`? Because a 256-way
loop of point reads is 256 round-trips; `findActiveByIdIn` is one. Why
`broadcastTo(id, …)` per member rather than a single `broadcast(memberIds, …)`?
Because at create time each new member is an *individual* recipient who is not
in the group yet from their client's perspective — the event is really "you were
added", targeted, not "the roster changed" broadcast to an existing room.

**COMPLEXITY.** For N seeded members: 1 `INSERT` conversation + (1 + K) member
inserts (K = survivors), 1 bulk user lookup, up to N block checks, 1 SYSTEM
message (2 Cassandra writes + 1 conversation update — §3), K async notification
tasks, K realtime publishes. All the member inserts share one transaction. The
block check `relationships.isBlockedEitherWay` is per-candidate; for very large
seeds this dominates and is the reason the 256 cap exists.

**CONCURRENCY / TX.** The whole method is `@Transactional`. Note the comment in
the class: the controller calls `createGroup` / `createDirect` **directly** (not
via a shared dispatcher) specifically so the `@Transactional` proxy engages — a
same-bean self-invocation would bypass the Spring proxy and silently drop the
transaction. The SYSTEM message's Cassandra writes are **not** in the Postgres
transaction (Cassandra has no XA here), so a Postgres rollback after
`systemMessages.write` would orphan a SYSTEM row; in practice `write` is the last
mutating call before the read-back `get`, so the window is negligible. Realtime
publishes are deferred to `afterCommit` by `ChatRealtimeBroadcaster`.

**EDGE CASES.** Empty member list → a group of one (just the owner) is valid.
All members blocked/deleted → group of one. `memberCount` never counts dropped
candidates because `toAdd` is filtered before it is used for the count.

---

## 2. Member lifecycle

All of these live in `GroupMemberService`. Each method follows the same skeleton:

```
requireGroup(conversationId)                    → load a non-deleted GROUP
requireActiveMember(conversationId, actorId)    → the actor must be ACTIVE
memberRepo.findMember(conversationId, targetId) → load the target
GroupPermissions.can(actor.role, ACTION, target.role, settings)  → authorise
<mutate the target row>
systemMessages.write(...)                       → timeline entry
emitMemberChange(...)                           → member.changed event
```

The permission matrix (`GroupPermissions.can`) is quoted in full in
[04-group-chats.md](04-group-chats.md); the one invariant to remember is **an
admin can act on plain members only; the owner can act on anyone**:

```java
case REMOVE_MEMBER, RESTRICT_MEMBER ->
        actorIsOwner || (actorIsAdmin && targetIsPlainMember);
```

### 2.1 Add / re-add members

**WHAT.** `addMembers(conversationId, actorId, userIds)` adds new members, or
**re-activates** members who previously `LEFT` or were `REMOVED` — but never
silently lifts a `RESTRICTED` member's restriction.

```java
for (UUID id : candidates) {
    if (!users.containsKey(id)) continue;                      // no such active user
    if (relationships.isBlockedEitherWay(actorId, id)) continue; // abuse guard
    ConversationMember existing = memberRepo.findMember(conversationId, id).orElse(null);
    // Already a member (ACTIVE) — or RESTRICTED, which must NOT be silently
    // lifted by an add — so skip. Only truly-departed members re-join.
    if (existing != null && !isRejoinable(existing)) continue;
    if (existing != null) {                                     // re-activate a LEFT/REMOVED member
        existing.setStatus(MemberStatus.ACTIVE);
        existing.setRole(MemberRole.MEMBER);
        memberRepo.save(existing);
    } else {
        memberRepo.save(ConversationMember.of(c, id, MemberRole.MEMBER));
    }
    added++;
    systemMessages.write(conversationId, SystemEventType.MEMBER_ADDED, actorId,
            actorLabel + " added " + label(id, users));
    chatNotifications.notifyAddedToGroup(id, actorId, conversationId, c.getTitle(), actorLabel);
    emitMemberChange(conversationId, id, "ADDED", MemberRole.MEMBER, true);
}
if (added > 0) conversationRepo.adjustMemberCount(conversationId, added);
```

The rejoin rule is a two-line helper and it is the crux of the method:

```java
/** Only genuinely-departed members re-join; ACTIVE and RESTRICTED are left as-is. */
private static boolean isRejoinable(ConversationMember m) {
    return m.getStatus() == MemberStatus.LEFT || m.getStatus() == MemberStatus.REMOVED;
}
```

- **Permission:** `GroupAction.ADD_MEMBERS` — `actorIsStaff || whoCanAddMembers == ALL_MEMBERS`.
- **System message:** `MEMBER_ADDED`, one per member actually added.
- **Event:** `member.changed / ADDED`, broadcast to the whole group *and* the added user.

**WHY the `RESTRICTED` carve-out.** A `RESTRICTED` member is read-only by an
admin's deliberate action. If "add member" blanket-set status to `ACTIVE`, an
admin (or worse, a member with `ALL_MEMBERS` add rights) could **launder a
restriction away** by re-adding the muted user. So `isRejoinable` returns
`false` for `RESTRICTED` (and for `ACTIVE`, which is just a no-op), and the loop
`continue`s past them. Only `LEFT` / `REMOVED` are genuinely "departed" and get
re-activated as a fresh `MEMBER`.

**COMPLEXITY.** One bulk `findActiveByIdIn` for all candidates, then per
survivor: 1 `findMember`, 1 block check, 1 `save`, 1 SYSTEM message, 1 async
notify, 1 broadcast. `adjustMemberCount` is a **single** atomic delta write for
the whole batch (`+added`), not one per member — important so the count can't be
clobbered by concurrent adds.

**EDGE CASES.** Re-adding an already-`ACTIVE` member is a silent no-op (not an
error, not double-counted — `added` isn't incremented). Adding a blocked or
deleted user is skipped. Role on re-activation is reset to `MEMBER` — a
previously-`ADMIN` member who left and is re-added comes back as a plain member.

### 2.2 Remove (kick)

```java
@Transactional
public void removeMember(UUID conversationId, UUID actorId, UUID targetId) {
    Conversation c = requireGroup(conversationId);
    ConversationMember actor = requireActiveMember(conversationId, actorId);
    ConversationMember target = memberRepo.findMember(conversationId, targetId)
            .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", targetId));
    if (target.isOwner()) throw new ForbiddenException("The owner cannot be removed.", "NOT_OWNER");
    if (!GroupPermissions.can(actor.getRole(), GroupAction.REMOVE_MEMBER, target.getRole(), c.getGroupSettings())) {
        throw new ForbiddenException("You cannot remove this member.",
                target.isAdminOrOwner() ? "CANNOT_ACT_ON_ADMIN" : "ADMINS_ONLY");
    }
    target.setStatus(MemberStatus.REMOVED);
    memberRepo.save(target);
    conversationRepo.adjustMemberCount(conversationId, -1);
    systemMessages.write(conversationId, SystemEventType.MEMBER_REMOVED, actorId,
            label(actorId, Map.of()) + " removed " + label(targetId, Map.of()));
    emitMemberChange(conversationId, targetId, "REMOVED", target.getRole(), true);
}
```

- **Owner is untouchable:** `target.isOwner()` short-circuits before the matrix
  even runs, with error code `NOT_OWNER`.
- **Error-code nuance:** the `ForbiddenException` code branches on
  `target.isAdminOrOwner()` — an admin trying to kick another admin gets
  `CANNOT_ACT_ON_ADMIN` (informative), a member trying to kick anyone gets
  `ADMINS_ONLY`.
- **Status → `REMOVED`** (a soft state, not a row delete): read access stops
  immediately (`canRead()` is false for `REMOVED`), but the row survives so the
  member can be re-added later (§2.1) and so their historical messages still
  resolve their sender.
- **`adjustMemberCount(-1)`** and **`MEMBER_REMOVED`** SYSTEM message.
- **Event:** `member.changed / REMOVED`.

### 2.3 Promote / demote

```java
@Transactional
public void changeRole(UUID conversationId, UUID actorId, UUID targetId, MemberRole newRole) {
    if (newRole != MemberRole.ADMIN && newRole != MemberRole.MEMBER) {
        throw new BadRequestException("role must be ADMIN or MEMBER.");
    }
    ...
    if (target.isOwner()) throw new ForbiddenException("The owner's role cannot be changed.", "NOT_OWNER");
    boolean promote = newRole == MemberRole.ADMIN;
    GroupAction action = promote ? GroupAction.PROMOTE_ADMIN : GroupAction.DEMOTE_ADMIN;
    if (!GroupPermissions.can(actor.getRole(), action, target.getRole(), c.getGroupSettings())) {
        throw new ForbiddenException("You cannot change this member's role.", "ADMINS_ONLY");
    }
    if (target.getRole() == newRole) return; // no-op
    target.setRole(newRole);
    memberRepo.save(target);
    systemMessages.write(conversationId, SystemEventType.ROLE_CHANGED, actorId,
            label(actorId, Map.of()) + (promote ? " made " : " removed ")
                    + label(targetId, Map.of()) + (promote ? " an admin" : " as admin"));
    emitMemberChange(conversationId, targetId, promote ? "PROMOTED" : "DEMOTED", newRole, true);
}
```

- **`newRole` is validated** to `ADMIN` or `MEMBER` — you cannot "promote to
  owner" here (that's transfer-ownership, §2.6).
- **Asymmetric permissions**: `PROMOTE_ADMIN` is
  `actorIsOwner || (actorIsAdmin && s.isAdminsCanPromote() && targetIsPlainMember)`
  — an admin can only promote if the group's `adminsCanPromote` setting is on
  and the target is a plain member. `DEMOTE_ADMIN` is **owner-only**
  (`actorIsOwner`). So an admin can never demote another admin.
- **No-op guard:** if the target already has `newRole`, return before writing
  anything (no spurious SYSTEM message).
- **Event:** `member.changed / PROMOTED` or `DEMOTED`, carrying the new role.

### 2.4 Restrict / unrestrict

```java
@Transactional
public void restrictMember(UUID conversationId, UUID actorId, UUID targetId, boolean restricted) {
    ...
    if (target.isOwner()) throw new ForbiddenException("The owner cannot be restricted.", "NOT_OWNER");
    if (!GroupPermissions.can(actor.getRole(), GroupAction.RESTRICT_MEMBER, target.getRole(), c.getGroupSettings())) {
        throw new ForbiddenException("You cannot restrict this member.",
                target.isAdminOrOwner() ? "CANNOT_ACT_ON_ADMIN" : "ADMINS_ONLY");
    }
    target.setStatus(restricted ? MemberStatus.RESTRICTED : MemberStatus.ACTIVE);
    memberRepo.save(target);
    emitMemberChange(conversationId, targetId, restricted ? "RESTRICTED" : "UNRESTRICTED", target.getRole(), true);
}
```

- **`RESTRICTED` is read-only:** `canRead()` stays true, so the member keeps
  receiving messages, edits, deletes and reactions over realtime and still
  counts as a `findReadableMemberIds` recipient — but the send path's
  `authorizeGroupSend` throws `READ_ONLY` for them, and reaction endpoints
  require `isActive()` (via `requirePostableMessage`). See
  [11-send-path.md](11-send-path.md).
- **No SYSTEM message.** This is the one lifecycle action that is *deliberately
  silent on the timeline* — restricting a member is a moderation action, not a
  social one, so it does not announce itself to the whole group. It only emits
  the `member.changed / RESTRICTED` event (so clients update the roster badge)
  and the affected user's own client sees `UNRESTRICTED` when lifted.
- **`adjustMemberCount` is NOT called** — a restricted member is still a member
  and still counted, unlike remove/leave.

### 2.5 Leave (with owner-transfer requirement and sole-owner retirement)

```java
@Transactional
public void leave(UUID conversationId, UUID userId) {
    Conversation c = requireGroup(conversationId);
    ConversationMember me = requireActiveMember(conversationId, userId);
    if (me.isOwner() && c.getMemberCount() > 1) {
        throw new BadRequestException("Transfer ownership before leaving, or delete the group.");
    }
    boolean soleOwner = me.isOwner();
    MemberRole roleBefore = me.getRole();
    me.setStatus(MemberStatus.LEFT);
    memberRepo.save(me);
    conversationRepo.adjustMemberCount(conversationId, -1);
    systemMessages.write(conversationId, SystemEventType.MEMBER_LEFT, userId,
            label(userId, Map.of()) + " left");
    emitMemberChange(conversationId, userId, "LEFT", roleBefore, false);
    if (soleOwner) {
        // Sole owner leaving retires the group. Use a bulk soft-delete (not a
        // full-entity save) so it doesn't overwrite the atomic count decrement
        // above with a stale managed row.
        conversationRepo.softDelete(conversationId, LocalDateTime.now());
    }
}
```

Three distinct cases:

1. **A non-owner leaves** → status `LEFT`, count −1, `MEMBER_LEFT` message,
   `member.changed / LEFT`.
2. **An owner with other members present tries to leave** → **rejected** with
   "Transfer ownership before leaving, or delete the group." The owner cannot
   orphan a populated group; they must `transferOwnership` (§2.6) or
   `delete` (owner-only, in `ConversationService.delete`).
3. **The sole owner (last member) leaves** → the group is *retired*. Their row
   goes `LEFT`, the count decrements to 0, and the conversation is **soft-deleted**.

**The `emitMemberChange(..., false)` detail.** Note the final argument is
`false` (`toGroup = false`). For every other lifecycle action it is `true`. On
leave, the person leaving is the *only* one who needs the event broadcast to the
group would be wrong because they've just stopped being an active member — the
helper still delivers directly to the affected user (see §2.7), which is exactly
what a leaver's other devices need.

**Why `softDelete` and not `c.setDeletedAt(...); save(c)`.** This is the load-
bearing comment in the method. `adjustMemberCount(-1)` is a **JPQL bulk update**
that has already hit the row in the DB, but the in-memory managed `Conversation`
entity `c` still has the *old* `memberCount`. If we then did
`c.setDeletedAt(now); conversationRepo.save(c)`, JPA would flush the whole
entity and **overwrite the DB's freshly-decremented count with the stale
in-memory value**. `softDelete(conversationId, now)` is another targeted bulk
`UPDATE … SET deletedAt = ?` that touches only the one column, so the count
delta survives. This is the same class of bug the group-delete path avoids by
never mixing a bulk delta with a full-entity save in the same transaction.

**EDGE CASES.** `requireActiveMember` means a `LEFT`/`REMOVED`/`RESTRICTED`
member calling leave gets `NOT_A_MEMBER` (a restricted member can't "leave" —
they were already muted; they'd need to be a full member). Soft-deleting the
group does not tombstone the Cassandra messages — they age out with the
partition; the conversation simply stops resolving in `findById(...).filter(deletedAt == null)`.

### 2.6 Transfer ownership

```java
@Transactional
public void transferOwnership(UUID conversationId, UUID actorId, UUID newOwnerId) {
    Conversation c = requireGroup(conversationId);
    ConversationMember actor = memberRepo.findMember(conversationId, actorId)
            .orElseThrow(() -> new ForbiddenException("You are not a member.", "NOT_A_MEMBER"));
    if (!GroupPermissions.can(actor.getRole(), GroupAction.TRANSFER_OWNERSHIP, null, c.getGroupSettings())) {
        throw new ForbiddenException("Only the owner can transfer ownership.", "NOT_OWNER");
    }
    ConversationMember target = memberRepo.findMember(conversationId, newOwnerId)
            .filter(ConversationMember::isActive)
            .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", newOwnerId));

    actor.setRole(MemberRole.ADMIN);
    target.setRole(MemberRole.OWNER);
    memberRepo.save(actor);
    memberRepo.save(target);
    c.setOwnerId(newOwnerId);
    conversationRepo.save(c);
    systemMessages.write(conversationId, SystemEventType.OWNERSHIP_TRANSFERRED, actorId,
            label(actorId, Map.of()) + " transferred ownership to " + label(newOwnerId, Map.of()));
    emitMemberChange(conversationId, newOwnerId, "PROMOTED", MemberRole.OWNER, true);
    emitMemberChange(conversationId, actorId, "DEMOTED", MemberRole.ADMIN, true);
}
```

- **Permission:** `TRANSFER_OWNERSHIP` is **owner-only** (`actorIsOwner`).
- **The new owner must be an ACTIVE member** (`.filter(isActive())`) — you
  cannot hand the group to someone who left, was removed, or is restricted.
- **Atomic swap within one transaction:** the old owner is demoted to `ADMIN`
  (not `MEMBER` — an ex-owner keeps admin standing), the new owner is promoted
  to `OWNER`, and `Conversation.ownerId` is updated so the denormalised pointer
  stays consistent with the member rows.
- **Two events**: `PROMOTED → OWNER` for the new owner, `DEMOTED → ADMIN` for
  the old — both broadcast to the group.
- **`OWNERSHIP_TRANSFERRED`** SYSTEM message. `memberCount` is unchanged (no one
  joined or left).

Because all four writes (two member roles, the conversation `ownerId`, plus the
SYSTEM message pointer) happen inside one `@Transactional`, a crash mid-transfer
rolls back cleanly — there is never a window with two owners or zero owners in
Postgres.

### 2.7 The `member.changed` event — `emitMemberChange`

Every lifecycle mutation ends by calling this helper:

```java
private void emitMemberChange(UUID conversationId, UUID userId, String change, MemberRole role, boolean toGroup) {
    ChatRealtimeEvent evt = ChatRealtimeEvent.builder()
            .eventType(ChatRealtimeEventType.MEMBER_CHANGED)
            .conversationId(conversationId).userId(userId)
            .memberChange(change).role(role == null ? null : role.name())
            .build();
    if (toGroup) {
        broadcaster.broadcast(memberRepo.findActiveMemberIds(conversationId), evt);
    }
    // Always deliver to the affected user so their client updates even if
    // they've just lost active membership (removed/left).
    broadcaster.broadcastTo(userId, evt);
}
```

- **`memberChange`** is a string discriminator:
  `ADDED | REMOVED | LEFT | PROMOTED | DEMOTED | RESTRICTED | UNRESTRICTED`
  (documented on `ChatRealtimeEvent`). `role` carries the *new* role so the
  client can re-render the roster without a refetch.
- **`toGroup` gate:** when true, the event is broadcast to all **active**
  members (`findActiveMemberIds` — one indexed Postgres query). The affected
  user is *always* also delivered to directly via `broadcastTo`, even when
  `toGroup` is false and even when they've just been removed/left — because a
  removed user is no longer in `findActiveMemberIds`, yet their own client still
  needs to hear "you were removed" to close the thread. `broadcastTo` targets a
  single userId's SSE stream regardless of room membership (the SSE layer only
  checks identity — see [05-realtime-delivery.md](05-realtime-delivery.md)).
- **After-commit:** `ChatRealtimeBroadcaster` registers a
  `TransactionSynchronization` and publishes in `afterCommit`, so a subscriber
  never sees a roster change a rollback would erase.

The mapping of lifecycle action → SYSTEM event → `member.changed` change string:

| Action | Permission (`GroupAction`) | SYSTEM event | `member.changed` | count Δ |
|---|---|---|---|---|
| Add / re-add | `ADD_MEMBERS` | `MEMBER_ADDED` | `ADDED` | +N |
| Remove (kick) | `REMOVE_MEMBER` | `MEMBER_REMOVED` | `REMOVED` | −1 |
| Promote | `PROMOTE_ADMIN` | `ROLE_CHANGED` | `PROMOTED` | 0 |
| Demote | `DEMOTE_ADMIN` | `ROLE_CHANGED` | `DEMOTED` | 0 |
| Restrict | `RESTRICT_MEMBER` | *(none)* | `RESTRICTED` | 0 |
| Unrestrict | `RESTRICT_MEMBER` | *(none)* | `UNRESTRICTED` | 0 |
| Leave | *(self)* | `MEMBER_LEFT` | `LEFT` | −1 |
| Transfer | `TRANSFER_OWNERSHIP` | `OWNERSHIP_TRANSFERRED` | `PROMOTED` + `DEMOTED` | 0 |
| Join via invite | *(link consumed)* | `MEMBER_ADDED` | `ADDED` | +1 |

---

## 3. `SystemMessageService` — lifecycle events as ordinary log rows

**WHAT.** Every group lifecycle event that should appear in the timeline
("@aram created the group", "@sara left", "@lana pinned a message") is written
as a real `MessageType.SYSTEM` message into the Cassandra log — not into a
separate side channel.

```java
public long write(UUID conversationId, SystemEventType event, UUID actorId, String text) {
    long id = snowflake.nextId();
    int bucket = ChatBuckets.bucketOf(id);
    Instant now = Instant.now();

    MessageByConversationEntity row = MessageByConversationEntity.builder()
            .conversationId(conversationId).bucket(bucket).messageId(id)
            .senderId(actorId).type(MessageType.SYSTEM.name())
            .body(text).systemEvent(event.name())
            .deleted(false).createdAt(now)
            .build();
    messageRepo.save(row);
    messageByIdRepo.save(MessageByIdEntity.builder()
            .messageId(id).conversationId(conversationId).bucket(bucket)
            .senderId(actorId).type(MessageType.SYSTEM.name())
            .body(text).systemEvent(event.name())
            .deleted(false).createdAt(now)
            .build());

    conversationRepo.advanceLastMessage(conversationId, id,
            LocalDateTime.ofInstant(now, ZoneOffset.UTC), text);

    List<UUID> recipients = memberRepo.findReadableMemberIds(conversationId);
    Map<UUID, User> users = actorId == null ? Map.of()
            : userRepository.findActiveByIdIn(List.of(actorId)).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));

    ChatRealtimeEvent evt = ChatRealtimeEvent.builder()
            .eventType(ChatRealtimeEventType.MESSAGE_NEW)
            .conversationId(conversationId)
            .message(mapper.toMessage(row, users, List.of(), null))
            .build();
    broadcaster.broadcast(recipients, evt);
    return id;
}
```

**HOW.** It is a miniature of the send path:

1. **Mint a Snowflake id** and compute its bucket — a SYSTEM message sorts and
   paginates by id exactly like a user message, so it lands in the timeline at
   the correct chronological position (see [06-algorithms.md](06-algorithms.md)
   for bucketing and the bucket walk).
2. **Write the twin Cassandra rows** — `messages_by_conversation` (the log
   partition, read by the bucket walk) and `message_by_id` (the point-lookup
   copy, read by reply-previews / pin hydration / forward). Both carry
   `type = SYSTEM` and a `systemEvent` discriminator (`GROUP_CREATED`,
   `MEMBER_ADDED`, …) so the client can render them centred and non-interactive.
3. **`advanceLastMessage`** updates the Postgres conversation row's
   `lastMessageId` / `lastMessageAt` / `lastMessagePreview`, so a lifecycle
   event **bumps the conversation to the top of everyone's inbox** and shows as
   the preview — "@sara left" is a perfectly good last-activity line.
4. **Broadcast a `message.new`** to `findReadableMemberIds` (ACTIVE +
   RESTRICTED). The event carries a fully mapped `MessageResponse` so the client
   just appends it — no refetch. The actor is resolved in one bulk lookup for
   the `@username` label.
5. Returns the Snowflake id (callers ignore it today, but pin/other flows could
   reference it).

**WHY inline instead of a separate `system_events` table + a special event
type.** Three reasons, all cost savings: (a) system messages **paginate for
free** in the existing bucket walk — no union query, no merge-sort of two
sources; (b) they **update the inbox preview** through the same
`advanceLastMessage` path, so "last activity" is correct without special casing;
(c) they **broadcast over the same `message.new`** channel, so clients need zero
new event handling — a SYSTEM message is just a message with `type=SYSTEM`. The
cost is one extra row per lifecycle event in Cassandra, which is trivially cheap
in an append-only log.

**COMPLEXITY.** 2 Cassandra writes + 1 Postgres update + 1 bulk user lookup + 1
broadcast per call. O(1) writes; the broadcast is O(readable members).

**CONCURRENCY / EDGE CASES.** `actorId` may be null (system-initiated events) —
the label map is then empty and the mapper renders a generic actor. SYSTEM
messages are **excluded from search** (`ChatSearchService.indexAsync` skips
`type == SYSTEM`) and are **never editable** (`MessageService.edit` throws
"System messages cannot be edited"). They *are* subject to the history floor —
a member who joined a hidden-history group won't see SYSTEM messages from before
they joined, same as any other message.

---

## 4. Invite links

**WHAT.** A group can have a Telegram-style opaque invite link. Anyone holding
the link (and not blocked / restricted) can join. Only a **SHA-256 hash** of the
token is stored; the plaintext is shown exactly once at creation. Creating a link
**rotates** (revokes) any previous link, and joining **atomically consumes** a
use so `maxUses` can't be exceeded under concurrency.

### 4.1 The `ConversationInvite` row

```java
/** SHA-256 hash of the opaque token. */
@Column(name = "token_hash", nullable = false, length = 64)
private String tokenHash;
...
@Column(name = "max_uses")   private Integer maxUses;   // null = unlimited
@Column(name = "use_count")  private int     useCount = 0;
@Column(name = "expires_at") private LocalDateTime expiresAt; // null = never
@Column(name = "revoked")    private boolean revoked = false;

public boolean isUsable() {
    if (revoked) return false;
    if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) return false;
    if (maxUses != null && useCount >= maxUses) return false;
    return true;
}
```

`token_hash` carries a `UNIQUE` constraint (`uk_invite_token_hash`).
`isUsable()` is the **read-side** check (cheap, used to reject obviously-dead
links early); the **write-side** guard is `consumeUse` (§4.3), and the two
together close the check-then-act race.

### 4.2 Create (rotate + hash-once)

```java
@Transactional
public InviteLinkResponse createInvite(UUID conversationId, UUID actorId, CreateInviteLinkRequest req) {
    Conversation c = requireGroup(conversationId);
    ConversationMember actor = requireActiveMember(conversationId, actorId);
    if (!GroupPermissions.can(actor.getRole(), GroupAction.CREATE_INVITE, null, c.getGroupSettings())) {
        throw new ForbiddenException("You cannot create invite links here.", "ADMINS_ONLY");
    }
    // Rotate: revoke any existing links, mint a fresh token.
    inviteRepo.revokeAllForConversation(conversationId);

    String token = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    LocalDateTime expiresAt = req.getExpiresInHours() == null ? null
            : LocalDateTime.now().plusHours(req.getExpiresInHours());
    ConversationInvite invite = inviteRepo.save(ConversationInvite.builder()
            .conversationId(conversationId)
            .tokenHash(sha256(token))
            .createdByUser(actorId)
            .expiresAt(expiresAt)
            .maxUses(req.getMaxUses())
            .build());
    return new InviteLinkResponse(conversationId, token, invite.getExpiresAt(), invite.getMaxUses(), invite.getUseCount());
}
```

- **Permission:** `CREATE_INVITE` is `actorIsStaff` (owner or admin).
- **Rotation:** `revokeAllForConversation` flips `revoked = true` on every
  live link for the conversation in **one bulk `UPDATE`** *before* the new one
  is minted. So a group has **at most one active link** at a time, and
  regenerating instantly invalidates the old URL (the classic "reset invite
  link" behaviour).
- **Token:** two concatenated UUIDs, dash-stripped → a 64-hex-char, ~256-bit
  opaque string. Unguessable.
- **Hash-once:** only `sha256(token)` is persisted. The **plaintext `token` is
  returned in the response and never stored** — a database leak yields only
  hashes, from which working links can't be reconstructed.

```java
private static String sha256(String s) {
    try {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(d);
    } catch (Exception e) {
        throw new BadRequestException("Could not process the invite token.");
    }
}
```

`revokeInvite` is the same permission gate followed by a bare
`revokeAllForConversation` — it kills the link without minting a replacement.

### 4.3 Join (the guarded-atomic `consumeUse` race fix)

```java
@Transactional
public ConversationResponse join(UUID userId, String token) {
    ConversationInvite invite = inviteRepo.findByTokenHash(sha256(token))
            .filter(ConversationInvite::isUsable)
            .orElseThrow(() -> new ForbiddenException("This invite link is invalid or has expired.", "INVITE_INVALID"));
    Conversation c = conversationRepo.findById(invite.getConversationId())
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(() -> new ForbiddenException("This invite link is invalid or has expired.", "INVITE_INVALID"));

    ConversationMember existing = memberRepo.findMember(c.getId(), userId).orElse(null);
    // Already a member (ACTIVE) or RESTRICTED (read-only) → idempotent no-op;
    // a restricted member can't use an invite link to lift their restriction.
    if (existing != null && !isRejoinable(existing)) {
        return conversationService.get(c.getId(), userId);
    }
    // Atomically consume a use up-front so maxUses can't be exceeded under
    // concurrency (guarded UPDATE; 0 rows affected ⇒ exhausted/expired).
    if (inviteRepo.consumeUse(invite.getId()) == 0) {
        throw new ForbiddenException("This invite link is invalid or has expired.", "INVITE_INVALID");
    }
    if (existing != null) {
        existing.setStatus(MemberStatus.ACTIVE);
        existing.setRole(MemberRole.MEMBER);
        memberRepo.save(existing);
    } else {
        memberRepo.save(ConversationMember.of(c, userId, MemberRole.MEMBER));
    }
    conversationRepo.adjustMemberCount(c.getId(), 1);
    systemMessages.write(c.getId(), SystemEventType.MEMBER_ADDED, userId,
            label(userId, Map.of()) + " joined via invite link");
    emitMemberChange(c.getId(), userId, "ADDED", MemberRole.MEMBER, true);
    return conversationService.get(c.getId(), userId);
}
```

The **race fix** is the heart of this method. A naive implementation would read
`useCount`, check `< maxUses`, then `useCount++` and save — a classic
check-then-act that lets two concurrent joiners both pass the check and both
increment, blowing past `maxUses`. Instead the counter is bumped by a **single
guarded `UPDATE`** in the repository that re-checks every usability condition
in the `WHERE` clause:

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

Because the increment and the bound-check are one atomic statement, the database
serialises the two joiners: the first `UPDATE` affects 1 row, the second affects
**0** (the `useCount < maxUses` predicate now fails), and the second join is
rejected with `INVITE_INVALID`. The consume happens **up-front**, before the
membership write, so we never add a member and *then* discover the link was
exhausted.

Other join details:

- **Idempotent for existing members:** an `ACTIVE` member re-clicking the link
  is a no-op that just returns the conversation. A `RESTRICTED` member is
  *also* treated as non-rejoinable — the same anti-laundering rule as §2.1: you
  can't use an invite link to escape a restriction.
- **Re-join for `LEFT`/`REMOVED`:** their existing row is re-activated as a
  fresh `MEMBER` (rather than inserting a duplicate, which would violate the
  member PK).
- **Deleted group guard:** the conversation is re-loaded with a
  `deletedAt == null` filter — a link to a retired group fails.
- **Side effects:** `adjustMemberCount(+1)`, a `MEMBER_ADDED` SYSTEM message
  ("… joined via invite link"), and a `member.changed / ADDED` event.

**COMPLEXITY.** Join is: 1 `findByTokenHash` (unique-indexed), 1 conversation
load, 1 `findMember`, 1 guarded `consumeUse` UPDATE, 1 member write, 1 count
delta, 1 SYSTEM message, 1 broadcast, plus the read-back `get`. No locks — the
`WHERE`-guarded update is the only concurrency primitive needed.

---

## 5. Pinned messages

**WHAT.** Any message can be pinned (Telegram-style). The pin is a small
Postgres row (`ConversationPin`); the message body stays in Cassandra. Multiple
messages can be pinned per conversation; pinned lists come back newest-pin-first.
Pinning writes a `PINNED` SYSTEM message and a `conversation.updated` event.
Deleting a message auto-unpins it.

### 5.1 The `ConversationPin` row

```java
@Table(name = "conversation_pins",
    uniqueConstraints = @UniqueConstraint(name = "uk_pin_conversation_message",
                                          columnNames = {"conversation_id", "message_id"}),
    indexes = @Index(name = "idx_pin_conversation", columnList = "conversation_id"))
```

- **`(conversation_id, message_id)` is unique** — a message can't be pinned
  twice, enforced at the schema level (belt-and-braces with the idempotency
  check in `pinMessage`).
- The row records `pinnedBy` and `pinnedAt`; the body is **not** duplicated
  here — "show pinned messages" is a cheap relational lookup of ids, then a
  bulk Cassandra hydrate by id (§5.3).

### 5.2 Pin / unpin (permission via `whoCanPin`)

Both live in `MessageService`. The gate is `requirePinnable`:

```java
/** Active membership + (for groups) the {@code whoCanPin} permission. */
private Conversation requirePinnable(UUID conversationId, UUID userId) {
    Conversation convo = conversationRepo.findById(conversationId)
            .filter(c -> c.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
    ConversationMember me = memberRepo.findMember(conversationId, userId)
            .filter(ConversationMember::isActive)
            .orElseThrow(() -> new ForbiddenException("You are not an active member of this conversation.", "NOT_A_MEMBER"));
    if (convo.isGroup()
            && !GroupPermissions.can(me.getRole(), GroupAction.PIN_MESSAGE, null, convo.getGroupSettings())) {
        throw new ForbiddenException("You cannot pin messages in this group.", "ADMINS_ONLY");
    }
    return convo;
}
```

`PIN_MESSAGE` resolves to `actorIsStaff || s.getWhoCanPin() == ALL_MEMBERS`. The
default `GroupSettings.whoCanPin` is `ADMINS_ONLY`, so out of the box only
admins/owner pin; a group can open it to everyone. **DIRECT conversations skip
the group check** — either participant can pin (the `convo.isGroup()` guard).

```java
@Transactional
public void pinMessage(UUID conversationId, long messageId, UUID userId) {
    Conversation convo = requirePinnable(conversationId, userId);
    MessageByIdEntity m = messageByIdRepo.findById(messageId)
            .filter(x -> conversationId.equals(x.getConversationId()) && !Boolean.TRUE.equals(x.getDeleted()))
            .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
    if (pinRepo.findByConversationIdAndMessageId(conversationId, messageId).isPresent()) return; // idempotent
    pinRepo.save(ConversationPin.builder()
            .conversationId(conversationId).messageId(m.getMessageId()).pinnedBy(userId)
            .build());
    systemMessages.write(conversationId, SystemEventType.PINNED, userId, senderLabel(userId) + " pinned a message");
    broadcaster.broadcast(memberRepo.findReadableMemberIds(conversationId), ChatRealtimeEvent.builder()
            .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
            .conversationId(conversationId).messageId(messageId).memberChange("PINNED")
            .build());
}
```

- **Message must belong to the conversation and not be deleted** — the
  `.filter(conversationId.equals(...) && !deleted)` prevents pinning a message
  from another room or a tombstone.
- **Idempotent:** an already-pinned message returns silently.
- **`PINNED` SYSTEM message** ("@x pinned a message") lands inline in the log.
- **Realtime:** a `conversation.updated` event with `memberChange = "PINNED"`
  and the `messageId`, so clients refresh the pinned bar. (Pins reuse
  `conversation.updated` rather than a bespoke event type — the pinned set is
  conversation-level metadata.)

Unpin is the mirror, keyed on the delete count so it only broadcasts if
something was actually removed:

```java
@Transactional
public void unpinMessage(UUID conversationId, long messageId, UUID userId) {
    requirePinnable(conversationId, userId);
    if (pinRepo.deletePin(conversationId, messageId) > 0) {
        broadcaster.broadcast(memberRepo.findReadableMemberIds(conversationId), ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
                .conversationId(conversationId).messageId(messageId).memberChange("UNPINNED")
                .build());
    }
}
```

`deletePin` is a bulk `DELETE … WHERE conversation_id = ? AND message_id = ?`
returning rows affected; unpinning a non-pinned message is a silent no-op (0
rows, no event). Note unpin does **not** write a SYSTEM message — removing a pin
is quieter than adding one.

### 5.3 Delete-unpins

In `MessageService.delete`, after tombstoning the message:

```java
messageRepo.tombstone(m.getConversationId(), m.getBucket(), messageId);
messageByIdRepo.tombstone(messageId);
reactionService.clear(messageId);
chatSearch.deleteAsync(messageId);
pinRepo.deletePin(m.getConversationId(), messageId); // a deleted message can't stay pinned
```

A deleted message **cannot remain pinned** — the pin row is removed in the same
transaction as the tombstone. This keeps `pinnedMessages` from ever surfacing a
"message deleted" placeholder in the pin bar. (The `hydrateByIds` reader also
defends against it — it drops deleted/system rows — but the pin row is cleaned
proactively so the count is correct too.)

### 5.4 Reading pins

`MessageQueryService.pinnedMessages` is a pure id-lookup then bulk hydrate:

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

One Postgres query for the ids (newest pin first), one bulk `message_by_id`
fetch to hydrate, plus bulked reaction counts and reply previews. Deleted/SYSTEM
ids are dropped by `hydrateByIds`. `whoCanPin` gates *pinning*; *reading* pins
only requires readable membership.

---

## 6. Elasticsearch search

**WHAT.** Full-text message search backed by an ES index (`irc-chat-messages`),
kept eventually-consistent with Cassandra by async writes on send/edit and
removal on delete. Every query is **membership-scoped inside the ES query** so a
user can never search a room they aren't in. In-conversation search falls back to
a bounded Cassandra scan when ES is cold; cross-conversation search is ES-only.

### 6.1 The document

```java
@Document(indexName = "irc-chat-messages", createIndex = false)
public class ChatMessageDocument {
    @Id private String id;                                   // String form of the Snowflake id
    @Field(type = FieldType.Long)    private Long   messageId;
    @Field(type = FieldType.Keyword) private String conversationId;   // scope filter
    @Field(type = FieldType.Keyword) private String senderId;
    @Field(type = FieldType.Text)    private String body;             // the searchable field
    @Field(type = FieldType.Keyword) private String type;             // filters out SYSTEM
    @Field(type = FieldType.Date, format = DateFormat.date_time) private Instant createdAt;
    public static String idOf(long messageId) { return Long.toString(messageId); }
}
```

- **`createIndex = false`** — Spring Data does **not** auto-create the index on
  startup, so a missing/unreachable ES instance never crashes boot. Provisioning
  is deferred to `ElasticsearchIndexInitializer` (§6.5).
- **The ES `_id` is the Snowflake message id as a string** (`idOf`), so
  re-indexing an edited message overwrites in place (no dupes) and deleting by id
  is a point op.
- `conversationId` and `type` are `Keyword` (exact-match filter terms);
  `body` is `Text` (analysed for BM25 + fuzzy).

### 6.2 Indexing (async, best-effort)

```java
@Async
public void indexAsync(MessageByIdEntity m) {
    if (m == null || MessageType.SYSTEM.name().equals(m.getType()) || !StringUtils.hasText(m.getBody())) {
        return;
    }
    ChatMessageDocument doc = ChatMessageDocument.builder()
            .id(ChatMessageDocument.idOf(m.getMessageId()))
            .messageId(m.getMessageId())
            .conversationId(m.getConversationId() == null ? null : m.getConversationId().toString())
            .senderId(m.getSenderId() == null ? null : m.getSenderId().toString())
            .body(m.getBody())
            .type(m.getType())
            .createdAt(m.getCreatedAt())
            .build();
    try {
        EsRetry.run(() -> searchRepo.save(doc), "[CHAT-SEARCH] index " + m.getMessageId());
    } catch (Exception e) {
        log.warn("[CHAT-SEARCH] index {} failed: {}", m.getMessageId(), e.getMessage());
    }
}
```

- **`@Async`** — indexing runs on a separate executor and **never blocks the
  send path**. Cassandra is the source of truth; a lost index write only means a
  message is temporarily unsearchable.
- **SYSTEM + empty-body messages are skipped** — lifecycle noise and media-only
  messages don't belong in text search.
- **Wired from three sites in `MessageService`:**
  - `persist(...)` calls `chatSearch.indexAsync(byId)` on every send/forward;
  - `edit(...)` calls `chatSearch.indexAsync(m)` after updating the body
    (re-index overwrites the doc since the ES id is stable);
  - `delete(...)` calls `chatSearch.deleteAsync(messageId)` to remove it.
- **`EsRetry.run`** wraps transient ES failures with a bounded retry; a hard
  failure is swallowed with a WARN (best-effort).

### 6.3 The membership-scoped query

```java
public List<Long> searchMessageIds(Collection<UUID> conversationIds, String query, int size) {
    if (!StringUtils.hasText(query) || conversationIds == null || conversationIds.isEmpty()) {
        return List.of();
    }
    List<FieldValue> scope = conversationIds.stream()
            .map(id -> FieldValue.of(id.toString())).toList();

    Query esQuery = Query.of(q -> q.bool(b -> {
        // Relevance: fuzzy best-fields on the body, plus a phrase-prefix for typeahead.
        b.must(m -> m.match(mt -> mt.field("body").query(query).fuzziness("AUTO")));
        b.should(m -> m.matchPhrasePrefix(mp -> mp.field("body").query(query).boost(1.5f)));
        // Membership scope — the caller can only ever hit their own conversations.
        b.filter(f -> f.terms(t -> t.field("conversationId").terms(tt -> tt.value(scope))));
        // Never surface system messages.
        b.mustNot(mn -> mn.term(t -> t.field("type").value(MessageType.SYSTEM.name())));
        return b;
    }));

    NativeQuery nq = NativeQuery.builder().withQuery(esQuery).withPageable(PageRequest.of(0, size)).build();
    try {
        SearchHits<ChatMessageDocument> hits = EsRetry.call(
                () -> esOps.search(nq, ChatMessageDocument.class, IndexCoordinates.of(INDEX)), "[CHAT-SEARCH] query");
        return hits.stream().map(h -> h.getContent().getMessageId()).filter(java.util.Objects::nonNull).toList();
    } catch (Exception e) {
        if (EsRetry.isIndexNotFound(e)) {
            log.debug("[CHAT-SEARCH] empty (index not created yet)");
            return List.of();
        }
        throw e; // hard failure → caller falls back to a bounded Cassandra scan
    }
}
```

The `bool` query has exactly four clauses, and each earns its place:

| Clause | Role | Effect |
|---|---|---|
| `must` → `match(body, fuzziness=AUTO)` | **relevance + typo tolerance** | matches on the analysed body; `AUTO` fuzziness tolerates 1–2 char typos |
| `should` → `matchPhrasePrefix(body).boost(1.5)` | **typeahead** | boosts messages where the query is a leading phrase, so as-you-type search feels live |
| `filter` → `terms(conversationId ∈ scope)` | **security** | restricts hits to the caller's conversation ids; a filter (not a query) so it doesn't affect score and can be cached |
| `mustNot` → `term(type = SYSTEM)` | **noise removal** | belt-and-braces with the index-time skip; excludes lifecycle messages |

**Why the scope is a `filter` inside the query, not a post-filter.** The
membership restriction is applied by Elasticsearch itself. The caller passes only
conversation ids the user belongs to (resolved from Postgres), and ES only ever
returns hits within that `terms` set. A user **cannot** search a conversation
they aren't in — there is no way to widen the scope from the client, and no
post-hoc filtering step that could be skipped by a bug. This is the single most
important security property of the search layer.

**Return shape:** ranked `messageId`s only. Bodies are **not** returned from ES —
they're re-hydrated from Cassandra (the source of truth) by the caller, so search
can never show a stale body that Cassandra has since edited/deleted.

**Error contract:** an *index-not-found* (cold ES that has never been written)
returns `List.of()` — treated as "no results". Any *other* failure is
**re-thrown**, which is the trigger for the Cassandra fallback in the caller.

### 6.4 ES-first, Cassandra-scan fallback (in-conversation)

`MessageQueryService.search`:

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

The fallback `scanSearch` is a **bounded** single-partition Cassandra walk — the
same bucket walk the read path uses, capped hard so it can never runaway:

```java
private static final int SEARCH_MAX_BUCKETS = 24;   // ~240 days at BUCKET_DAYS=10
private static final int SEARCH_MAX_SCANNED = 3000;
...
for (int bucket = startBucket; bucket >= minBucket
        && bucketsWalked < SEARCH_MAX_BUCKETS
        && scanned < SEARCH_MAX_SCANNED
        && matches.size() < limit; bucket--, bucketsWalked++) {
    List<MessageByConversationEntity> rows = messageRepo.firstPage(convo.getId(), bucket, 500);
    for (MessageByConversationEntity r : rows) {
        scanned++;
        if (floorId != null && r.getMessageId() < floorId) continue;   // hidden pre-join history
        if (Boolean.TRUE.equals(r.getDeleted())) continue;
        if (MessageType.SYSTEM.name().equals(r.getType())) continue;
        if (r.getBody() != null && r.getBody().toLowerCase(Locale.ROOT).contains(needle)) {
            matches.add(r);
            if (matches.size() >= limit) break;
        }
    }
}
```

- It walks **at most 24 buckets or 3000 rows** back from the newest, doing a
  case-insensitive `contains` — a naive substring match, no ranking, but it
  guarantees search works before the index is warm or when ES is down.
- It respects the same visibility rules as ES: **history floor** (hidden
  pre-join messages), **deleted** tombstones, **SYSTEM** exclusion.
- It is **in-conversation only** — the whole scan is one conversation's
  partitions. There is deliberately **no cross-conversation scan fallback**
  (that would be unbounded across every room the user is in).

**Two-tier decision table:**

| Situation | In-conversation `search` | Cross-conversation `searchAll` |
|---|---|---|
| ES has hits | ES ranking, hydrated from Cassandra | ES ranking, hydrated |
| ES up but 0 hits | Cassandra scan (may still find substrings ES's analyzer missed) | empty |
| ES down / index cold | Cassandra scan | **empty** (no unbounded scan) |

### 6.5 Cross-conversation search & per-conversation floors

`searchAll` is ES-only and enforces a subtlety a single scalar floor can't:

```java
// Each conversation has its OWN join floor, so a single scalar floor is wrong here.
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
```

Because the caller is searching *many* conversations at once, each with its own
"joined-at" history floor, the visibility predicate is **per-message**: it looks
up the message's conversation, drops soft-deleted ones, and honours *that*
conversation's floor. A single scalar floor would leak pre-join history from one
room while over-hiding another.

### 6.6 Boot-time index provisioning

`ElasticsearchIndexInitializer` registers the chat index alongside the others:

```java
@EventListener(ApplicationReadyEvent.class)
public void ensureIndicesExist() {
    ensureIndex(PostSearchDocument.class,       "irc-posts");
    ensureIndex(QnaSearchDocument.class,        "irc-qna");
    ensureIndex(ResearchSearchDocument.class,   "irc-research");
    ensureIndex(ChatMessageDocument.class,      "irc-chat-messages");
}
```

It runs **after** `ApplicationReadyEvent` (not during bean init), is **idempotent**
(`exists()`-first, `createWithMapping()` only if missing), and **swallows any ES
failure with a WARN** — a missing ES box doesn't crash startup; the index will
also auto-materialise on the first `indexAsync` write once ES returns. Combined
with `createIndex = false` on the document and the degrade-safe query path, the
entire search layer is optional: the app boots, sends, and reads with ES
completely offline.

---

## 7. The inbox — pure Postgres, pinned-first, request-excluding

**WHAT.** `GET /api/v1/conversations` returns the caller's conversation list,
pinned conversations first then by most-recent activity, with pending
message-request threads excluded, DIRECT peers resolved, and an `hasUnread` flag.

**HOW.** `ConversationService.inbox` delegates to `memberRepo.findInbox`, whose
JPQL *is* the design:

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
    """, countQuery = """ … """)
Page<ConversationMember> findInbox(@Param("uid") UUID userId, Pageable pageable);
```

Every clause is load-bearing:

- **`JOIN FETCH m.conversation c`** — the conversation is hydrated in the **same
  round-trip** as the membership row. Because `ConversationMember` maps the
  conversation via `@MapsId` as a *single-valued* association (not a
  collection), the `JOIN FETCH` paginates correctly in SQL — no N+1, no
  in-memory pagination. One query returns the page of memberships **with** their
  conversations.
- **`status = ACTIVE`** — `LEFT` / `REMOVED` memberships never show; a
  `RESTRICTED` member's threads are excluded from the *main* inbox too (they see
  them elsewhere).
- **`archived = false`** — archived threads live behind `findArchived` (the same
  query with `archived = true` and no pin ordering).
- **`c.deletedAt IS NULL`** — a soft-deleted (retired) group drops out.
- **`NOT EXISTS (… MessageRequest PENDING …)`** — the pending-request exclusion.
  A conversation where *I* am the **recipient** of a still-`PENDING` request is
  hidden from my normal inbox (it lives in the separate Requests tray until I
  accept). Crucially, this keys on `recipientId = :uid` — the **requester**
  keeps seeing the thread in *their* normal inbox. This is the correct Instagram
  /Messenger "message requests" split. See
  [03-permissions-and-requests.md](03-permissions-and-requests.md).
- **`ORDER BY m.pinned DESC, c.lastMessageAt DESC NULLS LAST`** — pinned
  conversations float to the top (personal pin, per-member `pinned` flag), then
  most-recently-active first; a brand-new conversation with no messages
  (`lastMessageAt IS NULL`) sorts last, not first.

**DIRECT peer resolution in one round-trip.** After the page loads,
`mapInbox` resolves the "other participant" of every DIRECT conversation in a
single query rather than one-per-row:

```java
List<UUID> directConvIds = members.stream()
        .map(ConversationMember::getConversation)
        .filter(Conversation::isDirect).map(Conversation::getId).toList();
Map<UUID, UUID> peerByConv = new HashMap<>();
if (!directConvIds.isEmpty()) {
    for (ConversationMember peer : memberRepo.findPeers(directConvIds, userId)) {
        peerByConv.putIfAbsent(peer.getId().getConversationId(), peer.getId().getUserId());
    }
}
Set<UUID> peerUserIds = new HashSet<>(peerByConv.values());
Map<UUID, User> users = peerUserIds.isEmpty() ? Map.of()
        : userRepository.findActiveByIdIn(peerUserIds).stream().collect(Collectors.toMap(User::getId, u -> u));
```

`findPeers(cids, me)` is `WHERE conversationId IN :cids AND userId <> :me` — one
query returns every DIRECT peer for the whole page. Then one
`findActiveByIdIn(peerUserIds)` bulk-loads the peer `User`s. So the entire inbox
page is: **1 query for memberships+conversations, 1 for peers, 1 for peer users**
— three round-trips regardless of page size, no N+1.

**`hasUnread`.** Computed at map time by `ChatMapper.toConversation`, not stored:

```java
me != null && c.getLastMessageId() != null
        && c.getLastMessageId() > me.getLastReadMessageId(),
```

`hasUnread` is simply "the conversation's last message id is beyond my read
marker" — a pure comparison of the denormalised `lastMessageId` (on the
conversation) against `lastReadMessageId` (on my member row). No count, no
Cassandra hit. The exact `unreadCount` is also carried (the maintained counter),
but `hasUnread` gives a cheap boolean dot even if the count is momentarily stale.

**COMPLEXITY.** Inbox = 3 Postgres round-trips total (memberships+convos, peers,
peer users), each indexed. The `idx_member_inbox (user_id, archived)` index
serves the `WHERE`. `findArchived` is the same shape without the pin ordering or
request exclusion.

---

## 8. Notifications

**WHAT.** Chat surfaces three notification kinds — `NEW_MESSAGE`,
`MESSAGE_REQUEST`, `ADDED_TO_GROUP` — through the platform's **existing**
`CassandraNotificationService`, rather than a chat-specific pipeline. All three
are **in-app only** (never email).

### 8.1 The bridge

`ChatNotificationService` is a thin adapter — it builds a `DeliverRequest` and
hands it to `CassandraNotificationService.deliverAsync`:

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
            "NEW_MESSAGE:" + conversationId));
}

public void notifyMessageRequest(UUID recipientId, UUID requesterId, UUID conversationId, String requesterLabel) {
    notifications.deliverAsync(new DeliverRequest(
            recipientId, NotificationKind.MESSAGE_REQUEST,
            "Message request", requesterLabel + " wants to send you a message",
            requesterId, "Conversation", conversationId,
            "MESSAGE_REQUEST:" + conversationId));
}

public void notifyAddedToGroup(UUID recipientId, UUID actorId, UUID conversationId, String groupTitle, String actorLabel) {
    notifications.deliverAsync(new DeliverRequest(
            recipientId, NotificationKind.ADDED_TO_GROUP,
            "Added to a group", actorLabel + " added you to " + (groupTitle == null ? "a group" : "\"" + groupTitle + "\""),
            actorId, "Conversation", conversationId,
            "ADDED_TO_GROUP:" + conversationId + ":" + recipientId));
}
```

**Why reuse `CassandraNotificationService`.** That service already implements
self-suppression, block filtering, per-group aggregation, the unread-bell
counter, and the offline/email decision — writing a parallel chat pipeline would
duplicate all of it. `deliverAsync` runs on the notification executor, so
building/persisting the bell row never blocks the send path. Its core:

```java
public Optional<UUID> deliverSync(DeliverRequest req) {
    if (req.userId() == null || req.kind() == null) return Optional.empty();
    if (suppressed(req))                            return Optional.empty();
    return req.kind().aggregable() && req.groupKey() != null
            ? aggregateInto(req)
            : insertFresh(req);
}
```

### 8.2 The three kinds and their metadata

Each `NotificationKind` carries three flags: `prefCategory`, `aggregable`,
`emailEligible`. The chat kinds:

```java
/** A new direct/group message for an offline recipient. Aggregated per
 *  conversation via {@code NEW_MESSAGE:{conversationId}}. */
NEW_MESSAGE          (PrefCategory.SOCIAL,   true,  false),
/** A stranger's first message landed in your Message Requests inbox. */
MESSAGE_REQUEST      (PrefCategory.SOCIAL,   false, false),
/** Someone added you to a group conversation. */
ADDED_TO_GROUP       (PrefCategory.SOCIAL,   false, false),
```

| Kind | Fires when | Aggregable | Group key | Email? |
|---|---|---|---|---|
| `NEW_MESSAGE` | a delivered message and the recipient is **offline** | **yes** — coalesces per conversation | `NEW_MESSAGE:{conversationId}` | **no** |
| `MESSAGE_REQUEST` | a stranger's **first** message creates a request row | no | `MESSAGE_REQUEST:{conversationId}` | **no** |
| `ADDED_TO_GROUP` | you're added to / join a group | no | `ADDED_TO_GROUP:{conversationId}:{recipientId}` | **no** |

- **`NEW_MESSAGE` is aggregable + offline-only.** In `MessageService.dispatch`
  it's fired only for a normally-`ALLOW`ed message in a small-enough
  conversation, and only for recipients where `!presence.isOnline(r)` — an
  online recipient is already getting the live SSE `message.new`, so a bell row
  would be redundant. Because it's aggregable with a per-conversation group key,
  a burst of messages collapses into one "@alice and 3 others messaged you" row
  (`aggregate_count` bumps, `last_actor_id` replaces) instead of N bells.
- **`MESSAGE_REQUEST`** fires once, only when the request row is *just created*
  (`requestJustCreated`), so subsequent pre-accept messages don't re-notify.
- **`ADDED_TO_GROUP`** includes the `recipientId` in the group key so each added
  user gets their own row (adding 5 people is 5 distinct notifications, not one
  aggregated blob).

### 8.3 Why `emailEligible = false` for all chat kinds

```
//  CHAT / MESSAGING — in-app only (emailEligible=false) so an active
//  conversation never floods a mailbox; the SSE stream is the live path,
//  these bell rows are for offline / backgrounded recipients.
```

An active chat produces messages far faster than any social event; emailing per
message (or even per aggregated burst) would be spam. The live path is the SSE
stream, the offline path is a phone push (out of scope here) and the in-app bell.
Email is reserved for lower-volume, higher-signal events (mentions, comments,
system warnings). Because the flag lives on the enum, the decision is enforced in
exactly one place — `deliverSync`'s email branch simply never fires for a kind
with `emailEligible = false`.

### 8.4 Category wiring

For the inbox tabs, the notification `type` maps to a `NotificationCategory`.
The three chat types land in `CHAT`:

```java
private static final Set<NotificationType> CHAT_TYPES = EnumSet.of(
        NotificationType.NEW_MESSAGE,
        NotificationType.MESSAGE_REQUEST,
        NotificationType.ADDED_TO_GROUP);
...
if (CHAT_TYPES.contains(type)) return CHAT;
```

`NotificationType` (the persisted enum) mirrors `NotificationKind` (the delivery
catalog) for these three values, so a chat bell row is filterable under the
inbox's **Chat** tab and mark-by-category endpoint. The category is **derived at
response time** from the stored type — storage keeps only the type.

### 8.5 `UnreadBadgeCache` — after-commit invalidation

Separate from the notification bell, the **conversation** unread badge (the
app-icon count of unread *messages*) is cached in Redis:

```java
@Transactional(readOnly = true)
public long total(UUID userId) {
    String key = PREFIX + userId;
    try {
        String cached = redis.opsForValue().get(key);
        if (cached != null) return Long.parseLong(cached);
    } catch (Exception ignored) { /* fall through to DB */ }
    long sum = memberRepo.sumUnread(userId);
    try { redis.opsForValue().set(key, Long.toString(sum), TTL); } catch (Exception ignored) {}
    return sum;
}
```

The per-conversation `unread_count`s are authoritative in Postgres; this caches
only their **sum** (5-minute TTL) so the badge doesn't re-`SUM` on every poll.
The important part is invalidation:

```java
public void invalidate(UUID userId) {
    if (userId == null) return;
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { evict(userId); }
        });
    } else {
        evict(userId);
    }
}
```

**Why after-commit.** The invalidation is **deferred until the surrounding
transaction commits**. If it evicted *before* commit, a concurrent `total(...)`
read could run the `sumUnread` query against the not-yet-committed state, cache
the **old** sum, and leave the badge permanently stale until the TTL expires.
Evicting in `afterCommit` guarantees the next read re-sums the *new*, committed
state. This is the same "clear-after-commit, recompute-on-next-read" discipline
`ChatRealtimeBroadcaster` uses for events — chosen over delta-tracking because
deltas drift, whereas invalidate-and-recompute is always correct and nearly as
cheap. It **fails open**: any Redis error falls through to a live DB `sumUnread`.

`invalidate` is called on every unread-affecting change: `markRead`
(→ counter to 0), and in `dispatch` for the sender (their own marker advanced)
and each recipient whose `unread_count` was bumped.

---

## 9. Failure-mode summary

| Failure | Behaviour | Where |
|---|---|---|
| Elasticsearch down / cold index | In-conversation search falls back to a bounded Cassandra scan; cross-conversation search returns empty; indexing WARNs and drops | `ChatSearchService`, `MessageQueryService.search` |
| Redis down | Unread badge falls open to a live `sumUnread`; presence/typing degrade (out of scope) | `UnreadBadgeCache.total` |
| Notification delivery throws | Swallowed with WARN on the async executor; the message/lifecycle change already committed | `CassandraNotificationService.deliverAsync` |
| Realtime publish throws | Swallowed with WARN in `afterCommit`; message is durably stored, client recovers via gap-sync | `ChatRealtimeBroadcaster.runAfterCommit` |
| Concurrent invite joins past `maxUses` | Second `consumeUse` UPDATE affects 0 rows → `INVITE_INVALID` | `ConversationInviteRepository.consumeUse` |
| Deleted message still pinned | Pin row removed in the delete transaction; reader also drops deleted ids | `MessageService.delete`, `hydrateByIds` |
| Sole owner leaves | Group soft-deleted; count decrement preserved via targeted `softDelete` (not entity save) | `GroupMemberService.leave` |
| Admin re-adds a restricted user to lift the mute | `isRejoinable` returns false for `RESTRICTED` → skipped | `GroupMemberService.addMembers`, `join` |

---

## 10. Endpoint index (as-built)

| Method | Path | Service call |
|---|---|---|
| `POST` | `/api/v1/conversations` (GROUP) | `ConversationService.createGroup` |
| `GET` | `/api/v1/conversations` | `ConversationService.inbox` |
| `GET` | `/api/v1/conversations/archived` | `ConversationService.archived` |
| `GET` | `/api/v1/conversations/{id}/members` | `GroupMemberService.listMembers` |
| `POST` | `/api/v1/conversations/{id}/members` | `GroupMemberService.addMembers` |
| `DELETE` | `/api/v1/conversations/{id}/members/{userId}` | `GroupMemberService.removeMember` |
| `POST` | `/api/v1/conversations/{id}/members/{userId}/role` | `GroupMemberService.changeRole` |
| `POST` | `/api/v1/conversations/{id}/members/{userId}/restrict` | `GroupMemberService.restrictMember` |
| `POST` | `/api/v1/conversations/{id}/leave` | `GroupMemberService.leave` |
| `POST` | `/api/v1/conversations/{id}/transfer-owner` | `GroupMemberService.transferOwnership` |
| `POST` | `/api/v1/conversations/{id}/invite-link` | `GroupMemberService.createInvite` |
| `DELETE` | `/api/v1/conversations/{id}/invite-link` | `GroupMemberService.revokeInvite` |
| `POST` | `/api/v1/conversations/join` | `GroupMemberService.join` |
| `POST` | `/api/v1/conversations/{id}/messages/{messageId}/pin` | `MessageService.pinMessage` |
| `DELETE` | `/api/v1/conversations/{id}/messages/{messageId}/pin` | `MessageService.unpinMessage` |
| `GET` | `/api/v1/conversations/{id}/pinned` | `MessageQueryService.pinnedMessages` |
| `GET` | `/api/v1/conversations/{id}/messages/search` | `MessageQueryService.search` |
| `GET` | `/api/v1/messaging/search` | `MessageQueryService.searchAll` |

The full request/response schemas are in the as-built API contract
([09-api-reference.md](09-api-reference.md)); the conversation-level update,
mute, pin (personal), archive, and read-marker endpoints are covered alongside
the message hot path.
