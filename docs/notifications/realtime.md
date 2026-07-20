# Notifications — Realtime SSE Stream

Live delivery channel for the notification inbox: one Server-Sent Events stream
per tab pushes new/coalesced notifications, badge updates, and cross-tab
read/delete sync. The REST surface it mirrors is documented in
[notifications.md](./notifications.md); email delivery in
[email-preferences.md](./email-preferences.md).

Source of truth: `NotificationController#stream`, `NotificationSseService`,
`NotificationRedisPublisher`, `NotificationRedisSubscriber`,
`CassandraNotificationService` (publish side).

---

## Endpoint

```
GET /api/v1/notifications/stream?token=<jwt>
```

**Auth:** Bearer JWT **or** `?token=<accessToken>` query param. Browsers'
`EventSource` cannot send custom headers, so the query-param form is the normal
browser path. The token must be a valid **ACCESS** token (refresh tokens are
rejected).

| Query param | Type | Notes |
|---|---|---|
| `token` | string | JWT access token; only needed when the `Authorization` header can't be sent |

**Response `200`** — `Content-Type: text/event-stream`. The server immediately
sends:

1. a `retry: 3000` directive (tells `EventSource` to back off 3 s between
   reconnects instead of hammering a restarting server),
2. a ~2 KB SSE comment frame (forces proxies past their write-buffer threshold
   so the stream flushes immediately),
3. the `connected` handshake event.

Response headers are hardened for streaming through proxies:
`X-Accel-Buffering: no`, `Cache-Control: no-cache, no-store, must-revalidate`,
`Pragma: no-cache`, `Connection: keep-alive`.

**Auth failure** — the endpoint does **not** return the JSON error envelope
(that would break the negotiated `text/event-stream` content type). Instead it
writes a plain-text `401` and closes:

```
HTTP/1.1 401
Content-Type: text/plain

Authentication required. Pass access token as ?token=<jwt>.
```

Detect this client-side via `EventSource.onerror` with
`readyState === EventSource.CLOSED`, refresh the access token, and open a
**new** `EventSource`.

### Connection lifecycle

| Property | Value |
|---|---|
| Stream timeout | **24 hours** — after that the emitter times out and the client transparently reconnects |
| Heartbeat | `heartbeat` event every **15 s** (beats typical 30 s proxy idle-timeouts) |
| Connections per user | **5 max**, LRU eviction — opening a 6th stream **completes (closes) the oldest** one first. One `EventSource` per tab; ancient zombie tabs get evicted, active ones keep streaming |
| Multi-tab | Every event fans out to **all** of the user's open emitters, on every app instance |
| Reconnect hint | `retry: 3000` (3 s) sent on subscribe |

---

## Events

Use `addEventListener('<name>', …)` — these are **named** SSE events, plain
`onmessage` will not fire for them. All payloads are JSON.

| Event | Payload | Client action |
|---|---|---|
| `connected` | `{ userId, timestamp, tabs, message }` | Handshake — mark the stream live; `tabs` = this user's current connection count |
| `notification` | Full notification payload — [see below](#notification-payload) | **Upsert by `notificationId`**: prepend if new; if the id already exists (coalesced update, `coalesced: true`) replace the row and float it to the top |
| `unread-count` | `{ "count": 7 }` | **Set** the badge to `count` (absolute, never increment). Seed the badge from `GET /unread/count` on connect — don't assume an `unread-count` event follows every action |
| `read` | `{ "ids": ["…"], "allRead": false, "deleted": false }` | Mark those ids read locally (cross-tab sync). `allRead: true` with mark-all / mark-category sweeps → flip everything the sweep covered |
| `deleted` | `{ "ids": ["…"], "allRead": false, "deleted": true }` | Remove those ids locally. Special case from purge-read: `{ "ids": [], "allRead": true, "deleted": true }` = "drop every read row" |
| `heartbeat` | `{ "timestamp": "…" }` | Ignore (keepalive) |

Which REST actions fire `read` / `deleted` — and with what exact payloads — is
listed per endpoint in [notifications.md](./notifications.md#endpoints).

### `notification` payload

A **fresh** notification carries the full field set:

```json
{
  "notificationId": "3f1c2f6e-4d0e-4f4e-9d20-6b9f0a2f31aa",
  "type":           "POST_COMMENTED",
  "title":          "New comment on your post",
  "body":           "@ahmad commented on your post.",
  "actorId":        "b9a7…",
  "lastActorId":    "b9a7…",
  "aggregateCount": 1,
  "resourceType":   "Post",
  "resourceId":     "7c0d…",
  "groupKey":       "POST_COMMENTED:7c0d…",
  "createdAt":      "2026-07-20T09:45:00Z"
}
```

A **coalesced** update (same `notificationId`, the 60-minute aggregation window
— see [notifications.md](./notifications.md#aggregation-coalescing)) carries the
changed fields plus `coalesced: true`:

```json
{
  "notificationId": "3f1c2f6e-4d0e-4f4e-9d20-6b9f0a2f31aa",
  "type":           "POST_COMMENTED",
  "body":           "@fatima and 3 others commented on your post.",
  "lastActorId":    "e2c1…",
  "aggregateCount": 4,
  "resourceType":   "Post",
  "resourceId":     "7c0d…",
  "groupKey":       "POST_COMMENTED:7c0d…",
  "coalesced":      true
}
```

| Field | Type | Fresh | Coalesced | Notes |
|---|---|---|---|---|
| `notificationId` | UUID | yes | yes | Upsert key — matches `id` in the REST `NotificationResponse` |
| `type` | string | yes | yes | Notification kind ([catalog](./notifications.md#notification-kinds)) |
| `title` | string | yes | — | Coalesced updates keep the existing title |
| `body` | string | yes | yes | Rewritten on every coalesce ("and N others") |
| `actorId` | UUID \| null | yes | — | First/primary actor |
| `lastActorId` | UUID \| null | yes | yes | Most recent actor (drives the avatar on aggregated rows) |
| `aggregateCount` | long | yes (`1`) | yes | Total events represented by the row |
| `resourceType` / `resourceId` | — | yes | yes | What it's about |
| `groupKey` | string \| null | yes | yes | Aggregation key |
| `createdAt` | ISO-8601 UTC | yes | — | Row creation time (unchanged by coalescing) |
| `coalesced` | boolean | — | `true` | Present only on coalesced updates |

> The SSE payload is the **raw persisted row**, not the enriched REST
> `NotificationResponse` — it has no `actorUsername` / `actorFullName` /
> `actorProfileImage` / `category` / `deepLink`. Resolve actor display data from
> your user cache, or refetch the inbox page if you need the enriched shape.
> A coalesced update also implies the row is **unread again** — resurface it.

---

## Architecture (backend contributors)

### Cross-instance delivery via Redis pub/sub

SSE emitters are in-process objects, but the app runs multiple instances — the
instance that *creates* a notification is usually not the one *holding* the
recipient's SSE connection. Every push therefore goes through Redis:

```
CassandraNotificationService (any instance)
    │  PUBLISH irc:notifications:{userId}   '{"event":"notification","data":{…}}'
    ▼
Redis pub/sub  (pattern subscription: irc:notifications:*)
    │
    ▼
NotificationRedisSubscriber (every instance)
    │  parses envelope → routes on "event"
    ▼
NotificationSseService.push(userId, eventName, data)
    │  fan-out to every open SseEmitter for that user on THIS instance
    ▼
browser tabs
```

- **Channel:** `irc:notifications:{userId}` (one channel per recipient).
- Every instance pattern-subscribes to `irc:notifications:*`, so a notification
  produced anywhere reaches an SSE connection held anywhere.
- `read` / `deleted` events from REST actions travel the same channel, published
  after the transaction commits (`@TransactionalEventListener(AFTER_COMMIT)`) —
  no phantom events for rolled-back writes.
- Redis failures on the publish side are logged and swallowed — a pub/sub blip
  never breaks the originating request; the row is already persisted and shows
  up on the next inbox fetch.

### The `{event, data}` envelope — REQUIRED

Every message on `irc:notifications:{userId}` **must** be a JSON envelope:

```json
{
  "event": "notification",          // SSE event name to emit
  "data":  { "notificationId": "…", "type": "…", "…": "…" }   // SSE payload
}
```

`NotificationRedisSubscriber` routes on `event` (defaulting to `notification`
when absent) and forwards `data` verbatim as the SSE payload. Unrecognized
`event` names are forwarded as-is — new event types are forward-compatible
without subscriber changes.

> **Bug fixed (do not regress):** the primary delivery path in
> `CassandraNotificationService` used to publish the **flat payload map**
> (no envelope). The subscriber then read the missing `"data"` field and
> browsers received `notification` events with `data: null`. All publish sites
> now wrap in the `{event, data}` envelope — if you add a new publish site, use
> `NotificationRedisPublisher.publish(...)` (which always wraps) or wrap
> manually. As a safety net the subscriber now forwards the **whole message**
> when the `"data"` field is absent, but that fallback loses the event-name
> routing — always send the envelope.

Publishing helpers:

- `NotificationRedisPublisher.publish(recipientId, SseEventName, payload)` —
  the canonical way; `SseEventName` covers `notification`, `unread-count`,
  `read`, `deleted`.
- `CassandraNotificationService.publishRealtime / publishRealtimeCoalesce` —
  the notification-engine publish sites (already envelope-wrapped).

---

## Client integration checklist

```ts
const es = new EventSource(`/api/v1/notifications/stream?token=${accessToken}`);

es.addEventListener('connected',    () => setLive(true));
es.addEventListener('notification', e => upsertById(JSON.parse(e.data)));    // replace on same notificationId
es.addEventListener('unread-count', e => setBadge(JSON.parse(e.data).count)); // SET, not +1
es.addEventListener('read',         e => applyRead(JSON.parse(e.data)));
es.addEventListener('deleted',      e => applyDeleted(JSON.parse(e.data)));

es.onerror = () => {
  if (es.readyState === EventSource.CLOSED) {
    // 401 (expired token) — refresh, then open a NEW EventSource
    refreshTokenAndReconnect();
  } // otherwise EventSource auto-reconnects (retry: 3000)
};
```

- Seed the badge from `GET /api/v1/notifications/unread/count` on connect;
  treat `unread-count` events as authoritative absolute values thereafter.
- Make local `read`/`deleted` application idempotent — your own REST actions
  echo back to the acting tab too.
- `es.close()` on logout. One stream per tab; remember the 5-connection LRU cap.

---

**See also:** [Notifications REST API](./notifications.md) ·
[Email preferences & delivery](./email-preferences.md) ·
[Error envelope](../errors/error-handling.md)
