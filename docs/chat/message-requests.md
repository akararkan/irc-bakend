# Message Requests API — First Contact from Strangers

The Requests inbox is where a DM from someone you're **not** connected to lands
until you decide what to do with it. A stranger's first message never rings your
main inbox: the send-path permission engine routes it to a `message_requests`
row, caps how many messages they can send before you answer, and only
**accepting** graduates the thread into a normal chat.

- **Base path:** `/api/v1/message-requests`
- **Auth:** `Authorization: Bearer <jwt>` on every endpoint (`@PreAuthorize("isAuthenticated()")` on the controller).
- **Errors:** unified envelope — see [Error handling](../errors/error-handling.md); switch on `errorCode`.
- **Actor:** every mutation is **recipient-only**. Acting on a request that isn't
  addressed to you returns `403 ACCESS_FORBIDDEN`.

## The stranger-first-contact flow

A request row is born on the **send path**, not here — this API only manages the
resulting inbox. When a STRANGER (one-way / no follow, no prior accepted thread)
sends their first DM, `authorizeDirectSend` returns `ROUTE_TO_REQUEST`:

1. The DIRECT conversation is created but **flagged** — it is excluded from the
   recipient's main inbox (`GET /conversations` filters out still-pending
   requests, see [conversations.md](conversations.md)).
2. A `message_requests` row is inserted: `status = PENDING`, `firstMessageId`
   set, `messageCount = 1`. A `UNIQUE (recipient_id, requester_id)` constraint
   guarantees **at most one pending row** per stranger pair — the anti-spam
   backbone.
3. The recipient gets a **`request.new`** realtime event (into their Requests
   tray) but **no push** by default; the message itself fans out as `message.new`
   only to the peer and the sender's own devices.
4. While `PENDING`, the sender sees **no** read receipts, typing, or presence
   for this thread — those signals stay suppressed until accept (see
   [realtime.md](realtime.md)).

### The 3-message pre-acceptance cap — `REQUEST_LIMIT_REACHED`

A stranger may send at most **3** messages into an un-accepted thread
(`STRANGER_MESSAGE_CAP = 3`). Each `ROUTE_TO_REQUEST` send increments
`messageCount`; the 4th send — or **any** send once the request is `DECLINED` /
`BLOCKED` — is refused with `403 REQUEST_LIMIT_REACHED`. The cap lifts entirely
once the recipient accepts (the thread is then `ALLOW`, not `ROUTE_TO_REQUEST`).
This code surfaces on the **send** endpoint, not on the request endpoints below.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/message-requests?status=&page=&size=` | List my requests, filtered by status |
| `GET` | `/message-requests/count` | Pending-request badge count |
| `POST` | `/message-requests/{id}/accept` | Accept — graduate the thread to a normal chat |
| `POST` | `/message-requests/{id}/decline` | Decline — hide the request |
| `POST` | `/message-requests/{id}/block` | Decline **and** block the requester |

---

### `GET /message-requests` — list requests

```
GET /api/v1/message-requests?status=PENDING&page=0&size=20
```

My Requests inbox, newest first (`created_at DESC`), Postgres-paginated. The
`status` filter accepts any `MessageRequestStatus` — default `PENDING`.

| Param | Type | Description |
|-------|------|-------------|
| `status` | enum | `PENDING` \| `ACCEPTED` \| `DECLINED` \| `BLOCKED` — default `PENDING` |
| `page` / `size` | int | Spring paging — default `size` 20 |

**Response `200`** — `Page<MessageRequestResponse>`:

```jsonc
{
  "content": [
    {
      "id": "3f8a1c22-…",              // messageRequestId (UUID)
      "conversationId": "1f3c…",       // the flagged DIRECT conversation
      "requesterId": "9c1f…",          // who sent first contact
      "requesterUsername": "ahmad",
      "requesterFullName": "Ahmad Rahman",
      "status": "PENDING",
      "firstMessageId": 172630000000000000,  // Snowflake of the opening message
      "messageCount": 2,                      // messages sent pre-acceptance (caps at 3)
      "createdAt": "2026-07-20T14:30:00.000Z"
    }
  ],
  "totalElements": 4, "totalPages": 1, "number": 0, "size": 20
}
```

**Errors:** `401 AUTH_UNAUTHORIZED` (no/invalid JWT).

---

### `GET /message-requests/count` — pending badge

```
GET /api/v1/message-requests/count
```

Cheap count of **pending** requests (ignores the `status` param — always
`PENDING`) for the Requests tab badge.

**Response `200`:**

```json
{ "count": 4 }
```

---

### `POST /message-requests/{id}/accept` — accept & graduate

```
POST /api/v1/message-requests/{id}/accept
```

Flips the row to `ACCEPTED`. This is the single act that **graduates the thread**:
the conversation now passes the permission engine's `hasAcceptedThread` check, so
every subsequent DM is `ALLOW`ed, the thread surfaces in the recipient's main
`GET /conversations` inbox, and receipts / typing / presence resume for both
sides.

| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Path — the `messageRequestId` |

**Request body:** none. **Response:** `200` (empty body).

**Side effects**

- `status → ACCEPTED` (persisted).
- Broadcasts **`conversation.updated`** with `memberChange: "REQUEST_ACCEPTED"`
  to the **requester** so their client refetches the now-normal thread (see
  [realtime.md](realtime.md)).

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 403 | `ACCESS_FORBIDDEN` | Caller is not the request's recipient |
| 404 | `MESSAGEREQUEST_NOT_FOUND` | No request with that `id` |
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |

---

### `POST /message-requests/{id}/decline` — decline

```
POST /api/v1/message-requests/{id}/decline
```

Flips the row to `DECLINED`. The request is **hidden** and the thread never
graduates. The requester is **not** told they were declined, but their next send
attempt into the thread is refused with `REQUEST_LIMIT_REACHED` (see the cap
above) — a quiet dead-end rather than an explicit rejection signal.

**Request body:** none. **Response:** `200` (empty body).

**Side effects:** `status → DECLINED`. No realtime broadcast, no notification.

**Errors:** same table as accept (`403 ACCESS_FORBIDDEN`,
`404 MESSAGEREQUEST_NOT_FOUND`).

---

### `POST /message-requests/{id}/block` — decline & block

```
POST /api/v1/message-requests/{id}/block
```

Decline plus a hard block: flips the row to `BLOCKED` **and** calls the shared
social block (`UserSocialService.block(requesterId)`), running in the recipient's
security context. From then on the block relationship makes every future DM in
either direction resolve to `DENY → BLOCKED` in the permission engine — the block
lives in the social graph, not just this row, so it also covers a brand-new
conversation the stranger might try to start.

**Request body:** none. **Response:** `200` (empty body).

**Side effects:** `status → BLOCKED`; a social block edge is created via the
shared user social API. No leak of who blocked whom.

**Errors:** same table as accept (`403 ACCESS_FORBIDDEN`,
`404 MESSAGEREQUEST_NOT_FOUND`).

---

## `MessageRequestResponse`

```jsonc
{
  "id": "3f8a1c22-…",                       // messageRequestId (UUID)
  "conversationId": "1f3c…",                // UUID of the flagged DIRECT conversation
  "requesterId": "9c1f…",                   // UUID — sender of first contact
  "requesterUsername": "ahmad",
  "requesterFullName": "Ahmad Rahman",
  "status": "PENDING",                      // PENDING | ACCEPTED | DECLINED | BLOCKED
  "firstMessageId": 172630000000000000,     // Snowflake (bigint) of the opening message
  "messageCount": 2,                        // pre-acceptance sends, capped at 3
  "createdAt": "2026-07-20T14:30:00.000Z"
}
```

`requesterUsername` / `requesterFullName` are hydrated from the requester's
profile at read time (null if the user record is gone).

---

**Related:** [Conversations](conversations.md) · [Messages](messages.md) ·
[Groups](groups.md) · [Realtime SSE](realtime.md) · [Search](search.md)
