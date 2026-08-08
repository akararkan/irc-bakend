# Admin API Reference — Chat, Channels, Live & Legal Holds

Endpoint-level reference for the four chat-plane admin controllers under
`src/main/java/ak/dev/irc/app/admin/chat/`:

| Controller | Base path | Surface |
|---|---|---|
| `AdminChatController` | `/api/v1/admin/chat` | Conversation metadata browse + aggregates, call-session metadata, message-request quarantine stats |
| `AdminChannelController` | `/api/v1/admin/channels` | Channel directory, stats override, verify, takedown/restore, unlist, freeze, invite links |
| `AdminStreamController` | `/api/v1/admin/streams` | Stream fleet browse/detail, force-stop, key rotation, guest removal, recordings, gift rollup |
| `LegalHoldController` | `/api/v1/admin/chat/legal-holds` | Dual-controlled message-content release (the only sanctioned path to chat content) |

Design context and privacy rationale: [chat-channels-live.md](../communication/chat-channels-live.md).
UI build guide: [frontend-dashboard-guide.md](../frontend/README.md).

## Conventions

- **Auth** — `Authorization: Bearer <staff JWT>`. Class-level access: `ADMIN` or `MODERATOR` on the first three controllers; `ADMIN` only on legal holds. A method-level `@PreAuthorize` (rotate-key) replaces the class rule.
- **Step-up** — endpoints marked *Step-up: required* (`@RequiresStepUp`) demand a fresh step-up marker, armed via `POST /api/v1/security/step-up`. Missing marker → **403 `STEP_UP_REQUIRED`**.
- **Errors** — every error arrives in the canonical `ApiErrorResponse` envelope (`timestamp`, `status`, `error`, `message`, `path`, `errorCode`, `details?`, `traceId`); see [frontend-error-handling.md](../../errors/frontend-error-handling.md). Malformed UUID/date query params fail request binding with a 400 through the same envelope. Common per-surface codes are listed under each endpoint; 401 (no/expired token) and 403 (role missing) apply everywhere and are not repeated.
- **Paging** — Spring `Pageable`: `page` (0-based, default 0) and `size` (default 25, clamped server-side to 1–100). `sort` is ignored — ordering is fixed per query. `Page<T>` examples below are abbreviated to `content`/`totalElements`/`totalPages`/`number`/`size`; the wire format is Spring's `PageImpl`, which additionally carries `pageable`, `sort`, `first`, `last`, `numberOfElements`, `empty`.
- **Timestamps** — `Instant` fields serialize UTC `Z`-suffixed (`2026-08-06T19:02:11Z`); `LocalDateTime` fields serialize without a zone suffix (`2026-08-06T19:02:11`).
- **`@JsonInclude(NON_NULL)`** — the row records (`AdminConversationRow`, `AdminCallRow`, `AdminChannelRow`, `AdminStreamRow`) omit null fields entirely. The `LegalHold` entity and Map-built bodies serialize nulls.
- **`ReasonBody`** — several mutations accept the same optional request body: `{"reason": "<≤500 chars>", "reportId": "<uuid>"}`. Both fields optional; `reportId` links the resulting moderation decision to a safety report. The body itself may be omitted.

---

## Conversations & overview

### GET /api/v1/admin/chat/conversations
Metadata browse over GROUP/CHANNEL conversations, newest first (`createdAt DESC`). DIRECT conversations are deliberately not enumerable — aggregate-only (see overview below).

**Access**: ADMIN or MODERATOR. Step-up: no.

**Params**

| Name | In | Type | Default | Notes |
|---|---|---|---|---|
| `type` | query | string | `CHANNEL` | `GROUP` or `CHANNEL` (case-insensitive). `DIRECT` → 400. |
| `q` | query | string | — | Case-insensitive substring match on title **or** handle. |
| `ownerId` | query | UUID | — | Filter by owner. |
| `includeDeleted` | query | boolean | `false` | Include soft-deleted (taken-down) conversations. |
| `page`, `size` | query | int | 0, 25 | Size clamped to 1–100. |

**Request body**: None.

**Response**: 200, `Page<AdminConversationRow>`. Rows omit null fields (`NON_NULL`): `disappearingSeconds` appears only when a disappearing-message timer is set (> 0, groups); `deletedAt` only on soft-deleted rows; `handle`/`title` when present.

```json
{
  "content": [
    {
      "id": "b3e77c1a-2f4d-4e0b-9a6c-5d1e8f2a7b3c",
      "type": "CHANNEL",
      "title": "Kurdish History Research",
      "handle": "kurdish-history",
      "publicChannel": true,
      "verified": true,
      "memberCount": 1843,
      "postCount": 412,
      "ownerId": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
      "createdAt": "2026-03-14T09:22:41"
    }
  ],
  "totalElements": 342,
  "totalPages": 14,
  "number": 0,
  "size": 25
}
```

Never included: `last_message_preview` or any message body.

**Errors**
- `DM_BROWSE_FORBIDDEN` — 400 — `type=DIRECT` ("DM conversations are not browsable — aggregate stats only.").
- `INVALID_TYPE` — 400 — unrecognized `type` ("Unknown type. Allowed: GROUP, CHANNEL.").

### GET /api/v1/admin/chat/overview
Conversation totals by type — the aggregate (and only) DM view — plus verified-channel and pending-request counts.

**Access**: ADMIN or MODERATOR. Step-up: no.

**Request body**: None.

**Response**: 200. Keys in this order; `conversationsByType` covers every `ConversationType`, counting non-deleted rows.

```json
{
  "conversationsByType": { "DIRECT": 15234, "GROUP": 1287, "CHANNEL": 342 },
  "verifiedChannels": 18,
  "pendingMessageRequests": 96
}
```

**Errors**: none beyond the common ones.

---

## Calls

Call sessions are metadata only — calls are P2P and no content exists.

### GET /api/v1/admin/chat/calls
Browse call sessions, newest first (`startedAt DESC`).

**Access**: ADMIN or MODERATOR. Step-up: no.

**Params**

| Name | In | Type | Default | Notes |
|---|---|---|---|---|
| `type` | query | string | — | `VOICE` or `VIDEO` (case-insensitive). |
| `status` | query | string | — | `RINGING`, `ONGOING`, `ENDED`, `DECLINED`, `MISSED`, `CANCELLED`. |
| `from`, `to` | query | ISO date-time | — | e.g. `2026-08-01T00:00:00`; interpreted as UTC. |
| `page`, `size` | query | int | 0, 25 | Size clamped to 1–100. |

**Request body**: None.

**Response**: 200, `Page<AdminCallRow>`. `NON_NULL` — `answeredAt` absent on never-answered calls, `endedAt` absent while `ONGOING`/`RINGING`, `endReason` absent unless recorded.

```json
{
  "content": [
    {
      "id": "e4a2b6c8-1d3f-4a5b-9c7d-2e8f0a1b3c5d",
      "conversationId": "b3e77c1a-2f4d-4e0b-9a6c-5d1e8f2a7b3c",
      "initiatorId": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
      "type": "VIDEO",
      "status": "ENDED",
      "endReason": "hangup",
      "startedAt": "2026-08-06T14:03:12Z",
      "answeredAt": "2026-08-06T14:03:19Z",
      "endedAt": "2026-08-06T14:41:55Z"
    }
  ],
  "totalElements": 4210,
  "totalPages": 169,
  "number": 0,
  "size": 25
}
```

**Errors**
- `INVALID_TYPE` — 400 — unrecognized `type` ("Unknown call type. Allowed: VOICE, VIDEO.").
- `INVALID_STATUS` — 400 — unrecognized `status` (message lists the allowed `CallStatus` values).

### GET /api/v1/admin/chat/calls/stats
Call volume/outcome aggregates over a window (default: last 30 days).

**Access**: ADMIN or MODERATOR. Step-up: no.

**Params**

| Name | In | Type | Default | Notes |
|---|---|---|---|---|
| `from`, `to` | query | ISO date-time | now−30d, now | Interpreted as UTC. |

**Request body**: None.

**Response**: 200. Keys in this order. `byStatus`/`byType` contain only values that occurred in the window (GROUP BY). `answerRate` = (ENDED+ONGOING)/total ×100, `missedRate` = MISSED/total ×100, both rounded to one decimal; `0.0` when `total` is 0.

```json
{
  "from": "2026-07-07T10:00:00Z",
  "to": "2026-08-06T10:00:00Z",
  "total": 4210,
  "byStatus": { "ENDED": 3480, "DECLINED": 220, "MISSED": 410, "CANCELLED": 85, "ONGOING": 12, "RINGING": 3 },
  "byType": { "VOICE": 3100, "VIDEO": 1110 },
  "answerRate": 82.9,
  "missedRate": 9.7
}
```

**Errors**: none beyond the common ones.

---

## Message requests

### GET /api/v1/admin/chat/message-requests/stats
Message-request quarantine stats — the platform's best organic spam signal.

**Access**: ADMIN or MODERATOR. Step-up: no.

**Params**

| Name | In | Type | Default | Notes |
|---|---|---|---|---|
| `from`, `to` | query | ISO date-time | now−30d, now | Window for `windowed` only. |
| `topRequesters` | query | int | 20 | Rows in `topBlockedRequesters`; clamped to 1–100. |

**Request body**: None.

**Response**: 200. Keys in this order. `funnelAllTime` is all-time counts for every `MessageRequestStatus` (`PENDING`, `ACCEPTED`, `DECLINED`, `BLOCKED`); `windowed` covers only statuses that occurred in the window. `topBlockedRequesters` ranks requesters by how many **distinct** recipients blocked them.

```json
{
  "funnelAllTime": { "PENDING": 96, "ACCEPTED": 5210, "DECLINED": 830, "BLOCKED": 412 },
  "windowed": { "ACCEPTED": 340, "DECLINED": 61, "BLOCKED": 38 },
  "topBlockedRequesters": [
    { "requesterId": "9c2d4e6f-8a0b-4c1d-a2e3-f4a5b6c7d8e9", "blockedByDistinctRecipients": 17 }
  ]
}
```

**Errors**: none beyond the common ones.

---

## Channels admin

### GET /api/v1/admin/channels
Channel directory browse, newest first (`createdAt DESC`).

**Access**: ADMIN or MODERATOR. Step-up: no.

**Params**

| Name | In | Type | Default | Notes |
|---|---|---|---|---|
| `q` | query | string | — | Case-insensitive substring match on title **or** handle. |
| `verified` | query | boolean | — | Filter by verified badge. |
| `public` | query | boolean | — | Filter by public/discoverable flag. |
| `category` | query | string | — | Exact match, lowercased server-side. |
| `ownerId` | query | UUID | — | Filter by owner. |
| `includeDeleted` | query | boolean | `false` | Include taken-down channels. |
| `page`, `size` | query | int | 0, 25 | Size clamped to 1–100. |

**Request body**: None.

**Response**: 200, `Page<AdminChannelRow>`. `NON_NULL` — `category` and `deletedAt` absent when null. `frozenByAdmin` reflects the platform posting freeze (channel settings).

```json
{
  "content": [
    {
      "id": "b3e77c1a-2f4d-4e0b-9a6c-5d1e8f2a7b3c",
      "handle": "kurdish-history",
      "title": "Kurdish History Research",
      "category": "education",
      "publicChannel": true,
      "verified": true,
      "frozenByAdmin": false,
      "memberCount": 1843,
      "postCount": 412,
      "ownerId": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
      "createdAt": "2026-03-14T09:22:41"
    }
  ],
  "totalElements": 342,
  "totalPages": 14,
  "number": 0,
  "size": 25
}
```

**Errors**: none beyond the common ones.

### GET /api/v1/admin/channels/{id}
Single-channel detail (same row shape as the browse).

**Access**: ADMIN or MODERATOR. Step-up: no.

**Request body**: None.

**Response**: 200, one `AdminChannelRow` (see above).

**Errors**
- `CHANNEL_NOT_FOUND` — 404 — id unknown, not a channel, or already taken down.

### GET /api/v1/admin/channels/{id}/stats
Full channel analytics without the member gate (non-member admins previously got 403 on the owner route).

**Access**: ADMIN or MODERATOR. Step-up: no.

**Request body**: None.

**Response**: 200, `ChannelStatsResponse`:

```json
{
  "subscriberCount": 1843,
  "onlineSubscribers": 121,
  "joinedLast7Days": 58,
  "joinedLast30Days": 202,
  "leftLast30Days": 17,
  "mutedCount": 240,
  "notificationsEnabledCount": 1603,
  "postCount": 412,
  "postsByType": { "TEXT": 300, "IMAGE": 84, "VIDEO": 28 },
  "totalViews": 92130,
  "totalForwards": 3110,
  "joinsByDay": { "2026-07-08": 4, "2026-07-09": 11, "2026-07-10": 6 },
  "joinsBySource": { "DISCOVERY": 820, "INVITE_LINK": 512, "JOIN_REQUEST": 96, "ADDED_BY_ADMIN": 40, "OWNER": 1, "UNKNOWN": 374 },
  "topPosts": [
    {
      "messageId": 731928462115385344,
      "conversationId": "b3e77c1a-2f4d-4e0b-9a6c-5d1e8f2a7b3c",
      "senderId": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
      "senderUsername": "hawre",
      "senderFullName": "Hawre Karim",
      "type": "TEXT",
      "body": "New excavation report from the Erbil citadel dig",
      "media": null,
      "replyToId": null,
      "replyTo": null,
      "forwardedFrom": null,
      "mentions": null,
      "reactions": [],
      "editedAt": null,
      "deleted": false,
      "systemEvent": null,
      "createdAt": "2026-07-30T08:12:44Z",
      "starred": false,
      "tags": null,
      "authorSignature": "Editors",
      "views": 8120,
      "forwards": 302,
      "comments": 45,
      "poll": null,
      "location": null,
      "contact": null
    }
  ]
}
```

Notes: `joinsByDay` maps ISO date → joins for the last 30 days; `joinsBySource` maps join source (`OWNER`/`DISCOVERY`/`INVITE_LINK`/`JOIN_REQUEST`/`ADDED_BY_ADMIN`/`COMMENT`/`UNKNOWN`) → active subscribers; `topPosts` is the channel's most-viewed posts (`MessageResponse[]`, most viewed first, nulls included). Message `type` values: `TEXT`, `IMAGE`, `VIDEO`, `VOICE`, `AUDIO`, `GIF`, `STICKER`, `FILE`, `POLL`, `LOCATION`, `CONTACT`, `VIDEO_NOTE`, `SYSTEM`.

**Errors**
- `CHANNEL_NOT_FOUND` — 404 — id unknown or not a channel.

### PATCH /api/v1/admin/channels/{id}/verified
Grant/revoke the verified badge (re-homed from the stray `PUT /api/v1/channels/{id}/verified`). Re-indexes the channel and broadcasts the update; audited as `ADMIN_CHANNEL_VERIFY`.

**Access**: ADMIN or MODERATOR. Step-up: no.

**Params**

| Name | In | Type | Default | Notes |
|---|---|---|---|---|
| `verified` | query | boolean | — | Required. |

**Request body**: None.

**Response**: 204 No Content.

**Errors**
- `CHANNEL_NOT_FOUND` — 404 — id unknown, not a channel, or deleted.

### POST /api/v1/admin/channels/{id}/takedown
Soft-delete a channel (the platform's only channel takedown path): sets `deletedAt`, removes it from the search index, records a moderation decision + `ADMIN_CHANNEL_TAKEDOWN` audit row, and notifies the owner.

**Access**: ADMIN or MODERATOR. Step-up: **required**.

**Request body** (optional `ReasonBody`):

```json
{ "reason": "Coordinated spam network", "reportId": "5d6e7f80-9a0b-4c1d-8e2f-3a4b5c6d7e8f" }
```

**Response**: 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `CHANNEL_NOT_FOUND` — 404 — id unknown, not a channel, or already taken down.

### POST /api/v1/admin/channels/{id}/restore
Undo a takedown: clears `deletedAt`, re-indexes, audits `ADMIN_CHANNEL_RESTORE`, notifies the owner.

**Access**: ADMIN or MODERATOR. Step-up: **required**.

**Request body**: None.

**Response**: 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `CHANNEL_NOT_FOUND` — 404 — id unknown or not a channel (deleted channels are eligible here).

### POST /api/v1/admin/channels/{id}/unlist
Remove a channel from public discovery without deleting it (`publicChannel=false`; private channels de-index themselves). Audited `ADMIN_CHANNEL_UNLIST`.

**Access**: ADMIN or MODERATOR. Step-up: no.

**Request body** (optional `ReasonBody`):

```json
{ "reason": "Misleading discovery title", "reportId": null }
```

**Response**: 204 No Content.

**Errors**
- `CHANNEL_NOT_FOUND` — 404 — id unknown, not a channel, or deleted.

### POST /api/v1/admin/channels/{id}/freeze
Platform posting freeze (`channelSettings.frozenByAdmin=true`) — enforced in the channel-send authorization funnel. Audited `ADMIN_CHANNEL_FREEZE`.

**Access**: ADMIN or MODERATOR. Step-up: **required**.

**Request body** (optional `ReasonBody`):

```json
{ "reason": "Repeated ToS violations pending review", "reportId": "5d6e7f80-9a0b-4c1d-8e2f-3a4b5c6d7e8f" }
```

**Response**: 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `CHANNEL_NOT_FOUND` — 404 — id unknown, not a channel, or deleted.

### POST /api/v1/admin/channels/{id}/unfreeze
Lift the posting freeze. Audited `ADMIN_CHANNEL_UNFREEZE`.

**Access**: ADMIN or MODERATOR. Step-up: no.

**Request body**: None.

**Response**: 204 No Content.

**Errors**
- `CHANNEL_NOT_FOUND` — 404 — id unknown, not a channel, or deleted.

### GET /api/v1/admin/channels/{id}/invite-links
Non-revoked invite links of a channel. Safe by construction: only the SHA-256 `tokenHash` is stored — the plaintext token is shown once at creation and can never be reconstructed from this listing.

**Access**: ADMIN or MODERATOR. Step-up: no.

**Request body**: None.

**Response**: 200, array of raw `ConversationInvite` entities. Each carries the invite fields plus the standard audit-entity columns; nulls are included.

```json
[
  {
    "createdAt": "2026-06-02T10:15:00",
    "updatedAt": "2026-07-29T18:40:12",
    "createdBy": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
    "updatedBy": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
    "createdByIp": "203.0.113.7",
    "updatedByIp": "203.0.113.7",
    "createdByDevice": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) ...",
    "updatedByDevice": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) ...",
    "lastAction": "UPDATE",
    "actionNote": null,
    "id": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    "conversationId": "b3e77c1a-2f4d-4e0b-9a6c-5d1e8f2a7b3c",
    "tokenHash": "9f2c8a7b6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a",
    "createdByUser": "7f1c9e2b-8a3d-4c5e-b6f7-0a1f2a3b4c5f",
    "expiresAt": null,
    "maxUses": 100,
    "useCount": 37,
    "revoked": false,
    "requiresApproval": true,
    "usable": true
  }
]
```

Field notes: `expiresAt` null = never expires; `maxUses` null = unlimited; `usable` is computed (not revoked, not expired, uses remaining); `requiresApproval` = Telegram-style "request admin approval" links.

**Errors**
- `CHANNEL_NOT_FOUND` — 404 — id unknown, not a channel, or deleted.

### POST /api/v1/admin/channels/{id}/invite-links/{inviteId}/revoke
Force-revoke an invite link (invite-abuse response). Audited `ADMIN_CHANNEL_INVITE_REVOKE`.

**Access**: ADMIN or MODERATOR. Step-up: no.

**Request body** (optional `ReasonBody`):

```json
{ "reason": "Link circulating on spam boards", "reportId": null }
```

**Response**: 204 No Content.

**Errors**
- `CONVERSATIONINVITE_NOT_FOUND` — 404 — invite id unknown **or** belongs to a different conversation than `{id}`.

---

## Streams fleet & moderation

`stream_key`/`publish_key` never appear in any projection on this surface — force-stop and rotation return nothing.

### GET /api/v1/admin/streams
Platform-wide stream browse, newest first (`startedAt DESC`).

**Access**: ADMIN or MODERATOR. Step-up: no.

**Params**

| Name | In | Type | Default | Notes |
|---|---|---|---|---|
| `status` | query | string | — | `LIVE` or `ENDED` (case-insensitive). |
| `hostId` | query | UUID | — | Filter by host. |
| `page`, `size` | query | int | 0, 25 | Size clamped to 1–100. |

**Request body**: None.

**Response**: 200, `Page<AdminStreamRow>`. `NON_NULL` — `endedAt` absent while live; `recordingStatus` absent when never set. `recordingStatus` values: `DISABLED`, `RECORDING`, `PAUSED`, `PROCESSING`, `AVAILABLE`, `EMPTY`, `DELETED`.

```json
{
  "content": [
    {
      "id": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
      "hostId": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
      "title": "Live Q&A: archives of the Ottoman era",
      "status": "LIVE",
      "viewerCount": 412,
      "peakViewerCount": 977,
      "recordingEnabled": true,
      "recordingStatus": "RECORDING",
      "startedAt": "2026-08-06T19:02:11Z"
    }
  ],
  "totalElements": 1288,
  "totalPages": 52,
  "number": 0,
  "size": 25
}
```

**Errors**
- `INVALID_STATUS` — 400 — unrecognized `status` ("Unknown status. Allowed: LIVE, ENDED.").

### GET /api/v1/admin/streams/{id}
Stream detail: the row, active stage guests, and a recording summary.

**Access**: ADMIN or MODERATOR. Step-up: no.

**Request body**: None.

**Response**: 200. Top-level keys in this order: `stream`, `activeGuests`, `recording`. Guest rows expose the MediaMTX **path**, never the publish key; `publishPath` is a string and serializes as the literal `"null"` when unset. Guests are ordered by join time.

```json
{
  "stream": {
    "id": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
    "hostId": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
    "title": "Live Q&A: archives of the Ottoman era",
    "status": "LIVE",
    "viewerCount": 412,
    "peakViewerCount": 977,
    "recordingEnabled": true,
    "recordingStatus": "RECORDING",
    "startedAt": "2026-08-06T19:02:11Z"
  },
  "activeGuests": [
    {
      "userId": "9c2d4e6f-8a0b-4c1d-a2e3-f4a5b6c7d8e9",
      "muted": false,
      "publishPath": "c9a4b1e2f3d84c6a9b0e1d2c3f4a5b6c"
    }
  ],
  "recording": { "exists": true, "totalBytes": 734003200 }
}
```

**Errors**
- `LIVESTREAM_NOT_FOUND` — 404 — unknown stream id.

### POST /api/v1/admin/streams/{id}/force-stop
Force-stop a live stream: kicks the host and every active guest publisher off MediaMTX, then runs the full owner end-path (viewers, guests, recording finalize, fan-out). Records a moderation decision + `ADMIN_STREAM_FORCE_STOP` audit row; notifies the host.

**Access**: ADMIN or MODERATOR. Step-up: **required**.

**Request body** (optional `ReasonBody`):

```json
{ "reason": "Graphic content on stream", "reportId": "5d6e7f80-9a0b-4c1d-8e2f-3a4b5c6d7e8f" }
```

**Response**: 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `LIVESTREAM_NOT_FOUND` — 404 — unknown stream id.
- `STREAM_NOT_LIVE` — 400 — stream status is not `LIVE` ("Stream is not live.").

### POST /api/v1/admin/streams/{id}/rotate-key
Rotate the stream key: the old key dies at the MediaMTX auth hook immediately and the current publisher is kicked. Returns nothing — the new key is delivered to the host alone via their notification channel. Audited `ADMIN_STREAM_KEY_ROTATE`.

**Access**: **ADMIN only** (method-level `@PreAuthorize("hasRole('ADMIN')")` — key-level control never delegates to MODERATOR). Step-up: **required**.

**Request body** (optional `ReasonBody`):

```json
{ "reason": "Key leaked in a public paste", "reportId": null }
```

**Response**: 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- 403 — caller is MODERATOR, not ADMIN.
- `LIVESTREAM_NOT_FOUND` — 404 — unknown stream id.

### DELETE /api/v1/admin/streams/{id}/stage/{userId}
Remove a co-host from the stage (previously host-only) and kick their MediaMTX publisher. Idempotent: if the user is not an active guest the call is a no-op and still returns 204. Audited `ADMIN_STREAM_GUEST_REMOVE`.

**Access**: ADMIN or MODERATOR. Step-up: **required**.

**Request body** (optional `ReasonBody`):

```json
{ "reason": "Guest streaming prohibited content", "reportId": null }
```

**Response**: 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `LIVESTREAM_NOT_FOUND` — 404 — unknown stream id.

---

## Recordings

Recording endpoints are content-adjacent, therefore step-up gated and audited (`ADMIN_RECORDING_VIEW` / `ADMIN_RECORDINGS_FLEET_VIEW` / `ADMIN_RECORDING_DELETE`).

### GET /api/v1/admin/streams/{id}/recording
Recording metadata for one stream (existence, size, part count — no media bytes).

**Access**: ADMIN or MODERATOR. Step-up: **required**.

**Request body**: None.

**Response**: 200 (key order not guaranteed — built with `Map.of`). `recordingStatus` falls back to `"DISABLED"` when the stream has none recorded.

```json
{
  "streamId": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
  "recordingStatus": "AVAILABLE",
  "exists": true,
  "totalBytes": 734003200,
  "parts": 3
}
```

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `LIVESTREAM_NOT_FOUND` — 404 — unknown stream id.

### GET /api/v1/admin/streams/recordings
Recordings fleet — every on-disk recording with its stream row (when one still exists), sizes, and orphan flags. The disk-usage board.

**Access**: ADMIN or MODERATOR. Step-up: **required**.

**Params**

| Name | In | Type | Default | Notes |
|---|---|---|---|---|
| `limit` | query | int | 100 | Max detailed rows; clamped to 1–100. `fleetBytes`/`recordings` always cover **everything**. |

**Request body**: None.

**Response**: 200. Top-level keys in this order: `recordings` (total on-disk recording count), `fleetBytes` (sum over all recordings, not just detailed rows), `rows` (sorted by `bytes` descending), and `note` — present only when the detail list was capped. Each row starts with `streamId`, `bytes`, `parts`; when the stream row still exists it adds `hostId`, `status`, `startedAt`, otherwise it adds `"orphan": true` (directory on disk with no stream row — delete candidate).

```json
{
  "recordings": 212,
  "fleetBytes": 48317665280,
  "rows": [
    {
      "streamId": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
      "bytes": 2147483648,
      "parts": 4,
      "hostId": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
      "status": "ENDED",
      "startedAt": "2026-08-01T19:02:11Z"
    },
    {
      "streamId": "0d1e2f3a-4b5c-4d6e-8f9a-0b1c2d3e4f5a",
      "bytes": 1073741824,
      "parts": 1,
      "orphan": true
    }
  ],
  "note": "Detailed rows capped at 100 of 212 recordings; fleetBytes still covers everything."
}
```

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.

### DELETE /api/v1/admin/streams/{id}/recording
Delete a stream's recording files and set `recordingStatus=DELETED`. **Irreversible — the files are the only copy.** Records a moderation decision + audit row.

**Access**: ADMIN or MODERATOR. Step-up: **required**.

**Request body** (optional `ReasonBody`):

```json
{ "reason": "DMCA takedown 2026-0788", "reportId": "5d6e7f80-9a0b-4c1d-8e2f-3a4b5c6d7e8f" }
```

**Response**: 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `LIVESTREAM_NOT_FOUND` — 404 — unknown stream id.

---

## Gifts

### GET /api/v1/admin/streams/gifts/top
Platform-wide gift rollup: total coins ever gifted plus the top gifters across all streams. Gifts are symbolic (coins = score, no wallet).

**Access**: ADMIN or MODERATOR. Step-up: no.

**Params**

| Name | In | Type | Default | Notes |
|---|---|---|---|---|
| `limit` | query | int | 20 | Top-gifter rows; clamped to 1–100. |

**Request body**: None.

**Response**: 200 (top-level key order not guaranteed — `Map.of`). `topGifters` is ordered by `coins` descending; `totalCoins` is `0` when nothing was ever gifted.

```json
{
  "totalCoins": 158200,
  "topGifters": [
    { "userId": "9c2d4e6f-8a0b-4c1d-a2e3-f4a5b6c7d8e9", "coins": 12400, "gifts": 96 }
  ]
}
```

**Errors**: none beyond the common ones.

---

## Legal holds

The dual-control message-content release path (`/api/v1/admin/chat/legal-holds`) — the **only** sanctioned way to read private message content. ADMIN-only end to end; step-up on every mutation. Lifecycle: `OPEN` → (`APPROVED` | `REJECTED`) → `EXECUTED`. The approver must be a *different* admin than the opener; execute releases the newest ≤ 500 messages of the held conversation exactly once.

The `LegalHold` entity serializes with nulls included (no `NON_NULL`):

```json
{
  "id": "6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d",
  "conversationId": "b3e77c1a-2f4d-4e0b-9a6c-5d1e8f2a7b3c",
  "reason": "Court order ER-2026-0412",
  "status": "OPEN",
  "openedBy": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
  "openedAt": "2026-08-06T09:14:02",
  "approvedBy": null,
  "approvedAt": null,
  "executedBy": null,
  "executedAt": null,
  "messageCount": null,
  "decisionNote": null
}
```

### GET /api/v1/admin/chat/legal-holds
Case list, newest first (`openedAt DESC`).

**Access**: ADMIN. Step-up: no.

**Params**

| Name | In | Type | Default | Notes |
|---|---|---|---|---|
| `status` | query | string | — | `OPEN`, `APPROVED`, `EXECUTED`, `REJECTED` (case-insensitive). |
| `page`, `size` | query | int | 0, 25 | Size clamped to 1–100. |

**Request body**: None.

**Response**: 200, `Page<LegalHold>`:

```json
{
  "content": [
    {
      "id": "6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d",
      "conversationId": "b3e77c1a-2f4d-4e0b-9a6c-5d1e8f2a7b3c",
      "reason": "Court order ER-2026-0412",
      "status": "OPEN",
      "openedBy": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
      "openedAt": "2026-08-06T09:14:02",
      "approvedBy": null,
      "approvedAt": null,
      "executedBy": null,
      "executedAt": null,
      "messageCount": null,
      "decisionNote": null
    }
  ],
  "totalElements": 4,
  "totalPages": 1,
  "number": 0,
  "size": 25
}
```

**Errors**
- `INVALID_STATUS` — 400 — unrecognized `status` ("Unknown status. Allowed: OPEN, APPROVED, EXECUTED, REJECTED.").

### POST /api/v1/admin/chat/legal-holds
Open a hold on one conversation with a case reference (ticket / court order / DSA request id). Audited `LEGAL_HOLD_OPEN`.

**Access**: ADMIN. Step-up: **required**.

**Request body**:

```json
{
  "conversationId": "b3e77c1a-2f4d-4e0b-9a6c-5d1e8f2a7b3c",
  "reason": "Court order ER-2026-0412"
}
```

**Response**: 201, the created `LegalHold` with `status="OPEN"` (shape above).

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `INVALID_INPUT` — 400 — missing `conversationId` ("conversationId is required.") or blank `reason` ("A case reference (ticket / court order id) is required.").
- `CONVERSATION_NOT_FOUND` — 404 — `conversationId` unknown.

### POST /api/v1/admin/chat/legal-holds/{id}/approve
Second-admin approval — the opener can never approve their own hold. Sets `status=APPROVED`, `approvedBy`, `approvedAt`, optional `decisionNote`. Audited `LEGAL_HOLD_APPROVE`.

**Access**: ADMIN. Step-up: **required**.

**Request body** (optional):

```json
{ "note": "Order verified against the court registry" }
```

`note` is trimmed to 500 chars.

**Response**: 200, the updated `LegalHold`:

```json
{
  "id": "6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d",
  "conversationId": "b3e77c1a-2f4d-4e0b-9a6c-5d1e8f2a7b3c",
  "reason": "Court order ER-2026-0412",
  "status": "APPROVED",
  "openedBy": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
  "openedAt": "2026-08-06T09:14:02",
  "approvedBy": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
  "approvedAt": "2026-08-06T11:30:45",
  "executedBy": null,
  "executedAt": null,
  "messageCount": null,
  "decisionNote": "Order verified against the court registry"
}
```

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `LEGALHOLD_NOT_FOUND` — 404 — unknown hold id.
- `LEGAL_HOLD_WRONG_STATE` — 400 — hold is not `OPEN` (e.g. "Cannot approve a REJECTED hold — only OPEN holds can be approved.").
- `RESOURCE_CONFLICT` — **409** — dual control: the approving admin is the one who opened the hold ("Dual control: the admin who opened a legal hold cannot approve it — a second admin must review.").

### POST /api/v1/admin/chat/legal-holds/{id}/reject
Reject an open hold. Sets `status=REJECTED`, records the deciding admin in `approvedBy`/`approvedAt`, optional `decisionNote`. No dual-control check — the opener may reject their own hold. Audited `LEGAL_HOLD_REJECT`.

**Access**: ADMIN. Step-up: **required**.

**Request body** (optional):

```json
{ "note": "Order withdrawn by the requesting authority" }
```

**Response**: 200, the updated `LegalHold` with `status="REJECTED"` (shape as under approve).

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `LEGALHOLD_NOT_FOUND` — 404 — unknown hold id.
- `LEGAL_HOLD_WRONG_STATE` — 400 — hold is not `OPEN`.

### POST /api/v1/admin/chat/legal-holds/{id}/execute
The content release. `APPROVED` holds only, **once**: returns the newest ≤ 500 messages of the held conversation (bucket-walked newest-first, never a scan) and flips the hold to `EXECUTED` so it cannot be replayed. Audited `LEGAL_HOLD_EXECUTE` as a READ.

**Access**: ADMIN. Step-up: **required**.

**Request body**: None.

**Response**: 200. Top-level keys in this order: `holdId`, `conversationId`, `reason`, `openedBy`, `approvedBy`, `executedBy`, `messageCount`, `capped`, `messages`, `warning`. Message rows keep their key order too: `messageId`, `senderId`, `type`, `body`, `createdAt`, `editedAt`, `deleted`, `replyToId`, `forwardedFrom`, and `mediaCount` — the last one present only when the message carries media. Nulls are included. `messageId`/`replyToId` are Snowflake longs; `capped` is `true` when the 500-message cap was hit.

```json
{
  "holdId": "6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d",
  "conversationId": "b3e77c1a-2f4d-4e0b-9a6c-5d1e8f2a7b3c",
  "reason": "Court order ER-2026-0412",
  "openedBy": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
  "approvedBy": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
  "executedBy": "8e9f0a1b-2c3d-4e5f-a6b7-c8d9e0f1a2b3",
  "messageCount": 214,
  "capped": false,
  "messages": [
    {
      "messageId": 731928462115385344,
      "senderId": "9c2d4e6f-8a0b-4c1d-a2e3-f4a5b6c7d8e9",
      "type": "IMAGE",
      "body": "Here is the document",
      "createdAt": "2026-08-05T18:22:41.512Z",
      "editedAt": null,
      "deleted": false,
      "replyToId": null,
      "forwardedFrom": null,
      "mediaCount": 1
    },
    {
      "messageId": 731925101830471680,
      "senderId": "7f1c9e2b-8a3d-4c5e-b6f7-0a1b2c3d4e5f",
      "type": "TEXT",
      "body": "Can you send it over?",
      "createdAt": "2026-08-05T18:09:20.077Z",
      "editedAt": null,
      "deleted": false,
      "replyToId": null,
      "forwardedFrom": null
    }
  ],
  "warning": "This export contains private message content released under legal hold Court order ER-2026-0412. Handle per your evidence-retention policy; the hold is now EXECUTED and cannot be re-run."
}
```

Message `type` values: `TEXT`, `IMAGE`, `VIDEO`, `VOICE`, `AUDIO`, `GIF`, `STICKER`, `FILE`, `POLL`, `LOCATION`, `CONTACT`, `VIDEO_NOTE`, `SYSTEM`. Deleted messages appear as tombstones (`"deleted": true`).

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `LEGALHOLD_NOT_FOUND` — 404 — unknown hold id.
- `LEGAL_HOLD_WRONG_STATE` — 400 — hold is not `APPROVED`; this includes replaying an already-`EXECUTED` hold ("Cannot execute a EXECUTED hold — only APPROVED holds can be executed.").
- `CONVERSATION_NOT_FOUND` — 404 — the held conversation no longer exists.
