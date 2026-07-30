# Channels — admins, invites & join requests

The access-control surface: granular admin rights, letting people in (invite
links, join requests), and owner-level member actions (transfer, kick, restrict).

Base path `/api/v1`; auth required on all. `{id}` = channel UUID, `{userId}` =
member UUID.

---

## `GET /channels/{id}/admins` — list admins

The owner plus every admin, with **effective** rights (legacy admins render as
full rights). Any member may call it.

**Response** — `200`, `ChannelAdminResponse[]`:

| field | type | notes |
|---|---|---|
| `userId` | UUID | |
| `username` | string | |
| `fullName` | string | |
| `role` | string | `OWNER` / `ADMIN`. |
| `rights` | [AdminRights](#adminrights) | effective rights. |
| `since` | timestamp | when they gained the role. |

```json
[ { "userId": "9c2a…", "username": "aram", "fullName": "Aram K",
    "role": "OWNER",
    "rights": { "canPostMessages": true, "canEditMessages": true,
                "canDeleteMessages": true, "canPinMessages": true,
                "canInviteUsers": true, "canApproveJoinRequests": true,
                "canChangeInfo": true, "canAddAdmins": true,
                "canManageLive": true,
                "customTitle": null },
    "since": "2026-07-24T09:12:00" } ]
```

---

## `PUT /channels/{id}/admins/{userId}` — promote / edit rights

Promotes an **active subscriber** to admin, or edits an existing admin. Requires
`canAddAdmins`. The body is **optional** — a bare `PUT` grants **full** rights.
Every flag is nullable; **null flags default to `true`**, so rights only ever
*remove* capability.

**Request** — `ChannelAdminRequest` (all optional):

| field | type | notes |
|---|---|---|
| `canPostMessages` | boolean | post new messages. |
| `canEditMessages` | boolean | edit **any** post; close polls. |
| `canDeleteMessages` | boolean | delete any post. |
| `canPinMessages` | boolean | pin / unpin. |
| `canInviteUsers` | boolean | create invite links, add members. |
| `canApproveJoinRequests` | boolean | approve / reject join requests. |
| `canChangeInfo` | boolean | title/photo/cover/settings, link a discussion group. |
| `canAddAdmins` | boolean | promote / demote admins. |
| `canManageLive` | boolean | live streams & video chats. |
| `customTitle` | string | ≤ 32-char rank label (e.g. `"Editor"`). |

> **Removed:** `canManageStories` no longer exists — [channel stories &
> highlights](stories.md) were deleted. Sending it is silently ignored.

```json
{ "canPostMessages": true, "canEditMessages": true, "canDeleteMessages": false,
  "canPinMessages": true, "canChangeInfo": false, "canAddAdmins": false,
  "canManageLive": true, "customTitle": "Editor" }
```

**Response** — `200`, [`ChannelAdminResponse`](#get-channelsidadmins--list-admins).
**Errors** — `403 ADMINS_ONLY` (lacks `canAddAdmins`), `403 CANNOT_ACT_ON_ADMIN`
(acting on the owner), `400` (target isn't an active subscriber).

## `DELETE /channels/{id}/admins/{userId}` — demote

Back to a plain subscriber. Requires `canAddAdmins`. **Response** — `204`.

---

## AdminRights

Persisted as a JSONB column on the membership row; a `null` column (legacy
admins **and the owner**) means **full rights**. Every enforcement point funnels
through `ChannelRights.can(member, AdminRights::isX)` — the owner is always
allowed; a `403 ADMINS_ONLY` is raised otherwise.

| Right | Gates |
|---|---|
| `canPostMessages` | Posting to the channel. |
| `canEditMessages` | Editing **any** post (channel posts belong to the channel); closing polls. |
| `canDeleteMessages` | Deleting any post. |
| `canPinMessages` | Pinning / unpinning. |
| `canInviteUsers` | Creating invite links and adding members. |
| `canApproveJoinRequests` | Approving / rejecting join requests. |
| `canChangeInfo` | Title, photo, cover, settings; linking a [discussion group](discussion.md). |
| `canAddAdmins` | Promoting / demoting admins. |
| `canManageLive` | Starting / managing [live streams](../live-streaming.md) and video chats. |
| `customTitle` | Display-only rank label. |

> `canManageStories` was **removed** with [channel stories](stories.md); reads of
> older `admin_rights` JSON that still contain it ignore the stale flag.

---

## Invite links

Multiple links can be live at once, each independently revocable. The member
endpoints under `/conversations/{id}/…` are shared with [groups](../groups.md);
creating one requires `canInviteUsers`.

### `POST /conversations/{id}/invite-links` — create

**Request** — `CreateInviteLinkRequest` (all optional):

| field | type | notes |
|---|---|---|
| `expiresInHours` | int > 0 | null = no expiry. |
| `maxUses` | int > 0 | null = unlimited. |
| `requiresApproval` | boolean | default `false`; when true, redeeming files a join request. |

**Response** — `200`, `InviteLinkResponse` — the plaintext `token`/`shareUrl` is
returned **exactly once** (only a hash is stored):

| field | type | notes |
|---|---|---|
| `conversationId` | UUID | |
| `token` | string | plaintext; unrecoverable afterward. |
| `expiresAt` | timestamp | `null` if permanent. |
| `maxUses` | int | `null` if unlimited. |
| `useCount` | int | |
| `shareUrl` | string | `{base}/join/{token}`. |

### `GET /conversations/{id}/invite-links` — list (metadata only)

**Response** — `200`, `InviteLinkInfoResponse[]` (**no** token):
`id`, `conversationId`, `createdBy`, `createdAt`, `expiresAt`, `maxUses`,
`useCount`, `revoked`, `requiresApproval`, `permanent` (no expiry **and** no use
limit).

### `DELETE /conversations/{id}/invite-links/{inviteId}` — revoke
**Response** — `204`.

### `POST /conversations/join` — redeem

**Request** — `{ "token": "<plaintext>" }`. **Response** — `200`,
`JoinByTokenResponse`:

```json
{ "status": "JOINED", "conversation": { …ConversationResponse… } }
```
or, when the link requires approval (the use is consumed, a request is filed):
```json
{ "status": "PENDING_APPROVAL", "conversation": null }
```
**Errors** — `403 INVITE_INVALID` (expired / revoked / exhausted).

> Legacy single-link endpoints (`POST`/`DELETE /conversations/{id}/invite-link`)
> still work and keep their rotate-all semantics.

---

## Join requests

Requests come from approval-links or from a `joinByRequest` subscribe. Each new
request notifies every admin (in-app `CHANNEL_JOIN_REQUEST`, aggregated per
channel) and fires a realtime **`channel.join_request`** event.

### `GET /channels/{id}/join-requests?status=&page=&size=`

Approvers only (`canApproveJoinRequests`). `status` = `PENDING` (default when
omitted) / `APPROVED` / `REJECTED`. `page` default 0, `size` default 50 (clamped
1–100).

**Response** — `200`, a Spring `Page<JoinRequestResponse>`; each row:

| field | type | notes |
|---|---|---|
| `id` | UUID | request id. |
| `conversationId` | UUID | |
| `userId`, `username`, `fullName` | | the requester. |
| `status` | string | `PENDING` / `APPROVED` / `REJECTED`. |
| `requestedAt` | timestamp | |
| `decidedBy` | UUID | admin who decided; `null` while pending. |
| `decidedAt` | timestamp | `null` while pending. |

### `POST /channels/{id}/join-requests/{userId}/approve`
Adds the member (system message + `member.changed ADDED`, join-source
`JOIN_REQUEST`) and notifies the requester (`CHANNEL_JOIN_APPROVED`).
**Response** — `200`, the updated `JoinRequestResponse`.

### `POST /channels/{id}/join-requests/{userId}/reject`
**Response** — `200`, the updated `JoinRequestResponse`. A rejected user may
re-request (the row flips back to `PENDING`).

---

## Ownership, kick, restrict

Shared `/conversations/{id}/…` member endpoints (see [groups](../groups.md)),
now working on channels too:

| Endpoint | Body | Response | Notes |
|---|---|---|---|
| `POST /conversations/{id}/transfer-owner` | `{ "newOwnerId": "<uuid>" }` | `200` | Owner only; previous owner becomes admin. |
| `DELETE /conversations/{id}/members/{userId}` | — | `204` | Kick a subscriber. |
| `POST /conversations/{id}/members/{userId}/role` | `ChangeRoleRequest` | `200` | Change role. |
| `POST /conversations/{id}/members/{userId}/restrict` | `RestrictMemberRequest` | `200` | Restrict a member. |

---

## Join-source tracking

Every subscriber records **how** they arrived, surfaced in
[stats](stats.md) as `joinsBySource`:

| Source | Set when |
|---|---|
| `OWNER` | Channel creator. |
| `DISCOVERY` | Public self-subscribe. |
| `INVITE_LINK` | Redeemed an invite link. |
| `JOIN_REQUEST` | Approved after a join request. |
| `ADDED_BY_ADMIN` | Added directly by an admin. |
| `COMMENT` | Auto-joined by commenting on a post (see [discussion.md](discussion.md)). |
| `UNKNOWN` | Legacy rows with no recorded source. |
