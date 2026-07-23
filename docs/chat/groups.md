# Group Membership API — Roles, Members, Invite Links

Everything about the membership side of a group conversation: listing members,
adding and removing (kicking) them, promoting/demoting admins, restricting
(read-only) a member, leaving, transferring ownership, and Telegram-style invite
links. Group *metadata* (title, avatar, settings) and message sending live in
[conversations.md](./conversations.md) and [messages.md](./messages.md); this
file is the `GroupMemberController` surface.

- **Base path:** `/api/v1/conversations` (`GroupMemberController`).
- **Auth:** `Authorization: Bearer <jwt>` on every endpoint.
- **Errors:** unified envelope — switch on `errorCode`; see
  [Error handling](../errors/error-handling.md).
- **Scope:** every endpoint here is **group-only**. Calling one on a DIRECT
  conversation returns `400 BAD_REQUEST` ("applies only to group
  conversations"); an unknown/deleted id returns `404 CONVERSATION_NOT_FOUND`.
- **IDs:** `conversationId` (the `{id}` path segment) and `userId` are UUIDs.

Every membership change writes a `SYSTEM` timeline message and emits a
`member.changed` realtime event to the group (and directly to the affected user,
even if they just lost membership). See [realtime.md](./realtime.md) for the
event shape.

---

## Permission-matrix reminder

Every authority decision runs through one pure function
(`GroupPermissions.can(role, action, targetRole, settings)`) — the matrix is
enforced in exactly one place. Compact version:

| Action | Who can |
|--------|---------|
| View members | Any readable member (`ACTIVE` or `RESTRICTED`) |
| Add members | Owner / admin — or **all members** if `whoCanAddMembers = ALL_MEMBERS` |
| Remove (kick) | Owner / admin — an **admin can't act on the owner or another admin** |
| Promote to admin | Owner always; an admin only if `adminsCanPromote` is on |
| Demote admin | **Owner only** |
| Restrict / unrestrict | Owner / admin — same admin-can't-act-on-admin rule |
| Leave | Any member (the owner must transfer or delete first) |
| Transfer ownership | **Owner only** |
| Create / revoke invite link | Owner / admin |
| Join via token | Anyone holding a valid, unexpired, non-exhausted token |

An admin can never act on the owner or on another admin — only on plain members;
the owner can act on anyone. Owner-only actions: transfer ownership, delete the
group, demote an admin.

---

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/{id}/members` | List members (paged) |
| `POST` | `/{id}/members` | Add one or more members |
| `DELETE` | `/{id}/members/{userId}` | Remove (kick) a member |
| `POST` | `/{id}/members/{userId}/role` | Promote / demote |
| `POST` | `/{id}/members/{userId}/restrict` | Restrict / unrestrict (read-only) |
| `POST` | `/{id}/leave` | Leave the group |
| `POST` | `/{id}/transfer-owner` | Hand ownership to another member |
| `POST` | `/{id}/invite-link` | Create / rotate an invite link |
| `DELETE` | `/{id}/invite-link` | Revoke all invite links |
| `POST` | `/join` | Join a group via an invite token |

---

### `GET /conversations/{id}/members`

List the group's members, paged. **Any readable member** (active or restricted)
may call it.

```
GET /api/v1/conversations/{id}/members?page=0&size=30
```

`?page=&size=` → Spring `Page<MemberResponse>` (default size **30**).

**`MemberResponse`:**

```jsonc
{ "userId": "1f3c…", "username": "sara.k", "fullName": "Sara K",
  "role":   "OWNER | ADMIN | MEMBER",
  "status": "ACTIVE | RESTRICTED | LEFT | REMOVED",
  "joinedAt": "2026-07-20T14:30:00.000Z" }
```

**Errors:** `NOT_A_MEMBER` (403) if you can't read the group;
`CONVERSATION_NOT_FOUND` (404).

---

### `POST /conversations/{id}/members` — add members

Add one or more users. Requires the `ADD_MEMBERS` permission.

**Request body (`AddMembersRequest`):**

```jsonc
{ "userIds": ["1f3c…", "2a4d…"] }   // 1..100 ids, non-empty
```

**Response:** `200 OK` (empty body).

The add is **best-effort per id** — each id is validated independently and
silently skipped when it can't be added, so the call succeeds even if some ids
don't take. An id is skipped when: no such active user; a block exists either way
(abuse guard); or the user is already `ACTIVE`/`RESTRICTED` (an add must **not**
lift an existing restriction). A `LEFT`/`REMOVED` member is re-activated as a
plain `MEMBER`.

**Side effects (per successfully added member):**

- `conversation_members` row inserted/re-activated; `memberCount` bumped.
- `MEMBER_ADDED` SYSTEM message ("@actor added @user").
- `ADDED_TO_GROUP` notification to the new member.
- `member.changed` (`ADDED`) realtime event.

**Errors:** `ADMINS_ONLY` (403) if you lack `ADD_MEMBERS`; `BAD_REQUEST` (400)
if `userIds` is empty or > 100; `CONVERSATION_NOT_FOUND` (404).

---

### `DELETE /conversations/{id}/members/{userId}` — remove (kick)

Kick a member. Requires `REMOVE_MEMBER`.

**Response:** `204 No Content`.

Sets the target's status to `REMOVED` (their read access stops immediately),
decrements `memberCount`, writes a `MEMBER_REMOVED` SYSTEM message, and emits
`member.changed` (`REMOVED`) to the group and the removed user.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 403 | `NOT_OWNER` | Target is the owner (the owner can't be removed) |
| 403 | `CANNOT_ACT_ON_ADMIN` | An admin tried to remove the owner or another admin |
| 403 | `ADMINS_ONLY` | Caller lacks `REMOVE_MEMBER` |
| 404 | `Member`-not-found | No such member in this group |
| 404 | `CONVERSATION_NOT_FOUND` | — |

---

### `POST /conversations/{id}/members/{userId}/role` — promote / demote

Change a member's role between `ADMIN` and `MEMBER`.

**Request body (`ChangeRoleRequest`):**

```jsonc
{ "role": "ADMIN" }   // ADMIN (promote) | MEMBER (demote); required
```

**Response:** `200 OK`. No-op (still `200`) if the target already holds that role.

- **Promote** (`→ ADMIN`) needs the `PROMOTE_ADMIN` permission — the owner
  always; an admin only when `adminsCanPromote` is enabled.
- **Demote** (`→ MEMBER`) needs `DEMOTE_ADMIN` — **owner only**.

Writes a `ROLE_CHANGED` SYSTEM message and emits `member.changed`
(`PROMOTED` / `DEMOTED`).

**Errors:** `NOT_OWNER` (403) if the target is the owner (the owner's role can't
be changed); `ADMINS_ONLY` (403) if the caller lacks the required permission;
`BAD_REQUEST` (400) if `role` is missing or not `ADMIN`/`MEMBER`;
`Member`-not-found (404).

---

### `POST /conversations/{id}/members/{userId}/restrict` — restrict / unrestrict

Mute a member to read-only (`RESTRICTED`) or lift it (`ACTIVE`). A restricted
member can still read but `authorizeGroupSend` denies their posts (`READ_ONLY`).
Requires `RESTRICT_MEMBER`.

**Request body (`RestrictMemberRequest`):**

```jsonc
{ "restricted": true }   // false to un-restrict; defaults to false if omitted
```

**Response:** `200 OK`.

Flips the target's status and emits `member.changed`
(`RESTRICTED` / `UNRESTRICTED`). Unlike the other lifecycle actions, restrict does
**not** write a SYSTEM timeline message — it's a quiet moderation action.

**Errors:** `NOT_OWNER` (403) if the target is the owner;
`CANNOT_ACT_ON_ADMIN` (403) if an admin targets the owner/another admin;
`ADMINS_ONLY` (403) if the caller lacks `RESTRICT_MEMBER`; `Member`-not-found (404).

---

### `POST /conversations/{id}/leave` — leave

Voluntarily leave. Any active member may leave.

**Response:** `204 No Content`.

Sets your status to `LEFT`, decrements `memberCount`, writes a `MEMBER_LEFT`
SYSTEM message, and emits `member.changed` (`LEFT`) — delivered directly to you
(not broadcast to the group as a member-add-style event).

- The **owner cannot leave** while other members remain → `400 BAD_REQUEST`
  ("Transfer ownership before leaving, or delete the group"). Use
  `transfer-owner` first, or `DELETE /conversations/{id}` to retire the group.
- A **sole owner** (last member) leaving soft-deletes the whole group in the same
  transaction.

**Errors:** `BAD_REQUEST` (400) for the owner-with-members case;
`NOT_A_MEMBER` (403); `CONVERSATION_NOT_FOUND` (404).

---

### `POST /conversations/{id}/transfer-owner` — transfer ownership

Hand ownership to another current member. **Owner only.**

**Request body (`TransferOwnerRequest`):**

```jsonc
{ "newOwnerId": "2a4d…" }   // must be an ACTIVE member; required
```

**Response:** `200 OK`.

Demotes the old owner to `ADMIN`, promotes the target to `OWNER`, updates the
conversation's `ownerId`, writes an `OWNERSHIP_TRANSFERRED` SYSTEM message, and
emits two `member.changed` events (`PROMOTED` for the new owner, `DEMOTED` for
the old).

**Errors:** `NOT_OWNER` (403) if the caller isn't the owner; `NOT_A_MEMBER` (403)
if the caller isn't in the group; `Member`-not-found (404) if `newOwnerId` isn't
an active member.

---

### `POST /conversations/{id}/invite-link` — create / rotate

Mint a Telegram-style invite link. Requires `CREATE_INVITE`. Creating a link
**revokes any prior links** for the group first (rotation). Works for **groups
and channels** (a private channel is shared exactly this way — it has no public
@handle URL).

**Request body (`CreateInviteLinkRequest`, optional — may be omitted entirely):**

```jsonc
{ "expiresInHours": 24,   // optional, positive; null = never expires
  "maxUses": 50 }         // optional, positive; null = unlimited uses
```

**Response:** `201 Created` — `InviteLinkResponse`:

```jsonc
{ "conversationId": "1f3c…",
  "token": "9a1f…c4",          // plaintext, shown ONCE — only a SHA-256 hash is stored
  "expiresAt": "2026-07-23T14:30:00.000Z" | null,
  "maxUses": 50 | null,
  "useCount": 0,
  "shareUrl": "https://irc.example.com/join/9a1f…c4" }  // ready-to-share ({irc.base-url}/join/{token})
```

The **`token` is returned exactly once** and can never be recovered from the
database — surface it in the share link immediately. `shareUrl` is the
ready-to-share form; the frontend route behind it hands the token to
`POST /conversations/join`.

**Errors:** `ADMINS_ONLY` (403) if the caller lacks `CREATE_INVITE`;
`BAD_REQUEST` (400) if `expiresInHours`/`maxUses` are non-positive;
`CONVERSATION_NOT_FOUND` (404).

---

### `DELETE /conversations/{id}/invite-link` — revoke

Revoke all outstanding invite links for the group. Requires `CREATE_INVITE`.

**Response:** `204 No Content`. Idempotent. Any subsequent join with a revoked
token fails with `INVITE_INVALID`.

**Errors:** `ADMINS_ONLY` (403); `CONVERSATION_NOT_FOUND` (404).

---

### `POST /conversations/join` — join via token

Join a group using an invite token. Any authenticated user holding a valid token
may call it (this exact-match path wins over `/{id}`).

**Request body (`JoinByTokenRequest`):**

```jsonc
{ "token": "9a1f…c4" }   // the plaintext token from InviteLinkResponse; required
```

**Response:** `200 OK` — `ConversationResponse` (your fresh member view of the
group; see [conversations.md](./conversations.md)).

- **Idempotent** for an already-`ACTIVE`/`RESTRICTED` member — returns the
  conversation without consuming a use (a restricted member can't use a link to
  lift their restriction).
- A `LEFT`/`REMOVED` user re-joins as a plain `MEMBER`.
- A use is **atomically consumed up-front** (guarded UPDATE), so `maxUses` can
  never be exceeded under concurrent joins.
- On a real join: `memberCount++`, a `MEMBER_ADDED` SYSTEM message ("@user joined
  via invite link"), and a `member.changed` (`ADDED`) event.

**Errors:** `INVITE_INVALID` (403) if the token is unknown / expired / revoked /
exhausted, or the group was deleted; `BAD_REQUEST` (400) if `token` is blank.

---

## Relevant error codes

Full list and envelope shape: [Error handling](../errors/error-handling.md).
The ones specific to this surface:

| `errorCode` | HTTP | Meaning |
|-------------|------|---------|
| `NOT_OWNER` | 403 | Action requires the owner (transfer, demote, or acting on the owner). |
| `CANNOT_ACT_ON_ADMIN` | 403 | An admin tried to remove/restrict the owner or another admin. |
| `ADMINS_ONLY` | 403 | Caller lacks the required admin permission for this action. |
| `NOT_A_MEMBER` | 403 | Not an (active/readable) member of the group. |
| `INVITE_INVALID` | 403 | Invite token expired / revoked / exhausted / for a deleted group. |
| `BAD_REQUEST` | 400 | Validation or semantic error (bad role, non-group, owner-can't-leave, empty `userIds`). |
| `CONVERSATION_NOT_FOUND` | 404 | Unknown or deleted conversation; a missing target member also 404s. |

---

Related: [conversations.md](./conversations.md) ·
[messages.md](./messages.md) · [message-requests.md](./message-requests.md) ·
[realtime.md](./realtime.md) · [search.md](./search.md)
