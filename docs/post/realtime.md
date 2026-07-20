# Post Realtime Stream (SSE)

A Server-Sent Events channel per post that pushes every interaction — reactions,
comments, replies, edits, deletes and counter-affecting events — to all open viewers
in real time. One topic per post; many subscribers per topic; cross-instance fan-out
rides Redis pub/sub, so an action written on any server pod reaches subscribers on
every pod.

- **Endpoint:** `GET /api/v1/posts/{id}/stream`
- **Auth:** optional — anonymous viewers are allowed (the post stream is public).
  Authenticated viewers pass `Authorization: Bearer <JWT>` *or* `?token=<jwt>`
  (browser `EventSource` cannot send custom headers).
- **Errors:** non-stream failures use the unified envelope — see
  [Error handling](../errors/error-handling.md).

Related: [Posts CRUD](./posts.md) · [Engagement](./engagement.md) ·
[Feed](./feed.md) · [Media](./media.md)

---

## 1. `GET /api/v1/posts/{id}/stream` — subscribe

```
GET /api/v1/posts/{id}/stream?token=<jwt>
Accept: text/event-stream
```

**Path parameters**

| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | The post to subscribe to |

**Query parameters**

| Param | Type | Description |
|-------|------|-------------|
| `token` | string | Optional JWT for `EventSource` clients. Must be a valid **ACCESS**-type token; an invalid/expired token silently downgrades the subscription to anonymous (no 401 on this endpoint) |

**Why identify yourself at all?** The viewer id is used for the **no-echo rule**:
the actor's own subscription is skipped when broadcasting, so the tab that posted the
comment / tapped the heart never receives its own event back (it already has the
result from the originating HTTP response and would otherwise render it twice).
Anonymous subscribers receive everything.

**Response:** `Content-Type: text/event-stream` with anti-buffering headers so
proxies (Nginx / Railway / Cloudflare) don't hold frames:

```
X-Accel-Buffering:  no
Cache-Control:      no-cache, no-store, must-revalidate
Connection:         keep-alive
```

**Connection lifecycle**

- **Timeout: 24 hours.** The emitter has an explicit 24 h server-side timeout
  (`0`/infinite is container-dependent — some servlet containers treat it as the 30 s
  default, which caused reconnect storms). On expiry `EventSource` auto-reconnects;
  a finite timeout also guarantees half-open sockets are eventually reaped.
- **`reconnectTime: 3000` ms** is sent as the first frame, so a JVM restart doesn't
  trigger a connection-refused storm — browsers back off 3 s between attempts.
- **`heartbeat` every 25 s** keeps proxy/LB connections alive and lets clients detect
  dead subscriptions quickly.
- Stale emitters are pruned silently on send failure.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `TYPE_MISMATCH` | `{id}` is not a UUID |

(Subscribing to a nonexistent post id succeeds but only ever delivers `connected` +
heartbeats.)

---

## 2. Event model — deltas, not counters

> **The single most important client rule:** events carry **+1 / −1 semantics via
> the event type only — never counter values.** Cassandra counter columns are
> eventually consistent; re-reading right after an increment can return a stale
> number, so the server does not bundle fresh counts into events. Clients apply the
> delta locally (`REACTION_ADDED` → `reactionCount + 1`, `REACTION_REMOVED` →
> `reactionCount − 1`, `COMMENT_CREATED` / `REPLY_CREATED` → `commentCount + 1`,
> `VIEW_COUNT_UPDATED` → `viewCount + 1`, `SAVE_COUNT_UPDATED` → apply the `saved`
> direction flag, …) and reconcile with the canonical counters on the next REST
> fetch of the post.

The one pragmatic exception: `SHARE_COUNT_UPDATED` may include a fresh
`postShareCount` (the share path already reads the counter to build its
`ShareLinkInfo` response). Treat it as advisory — the delta model still works if you
ignore it.

Every event frame is one JSON `PostRealtimeEvent` object. Null fields are omitted
from the wire (`@JsonInclude(NON_NULL)`) to keep frames small; read whichever fields
are present and dispatch on `eventType`. The SSE **event name equals the
`eventType` enum name** (e.g. `event: REACTION_ADDED`), so `addEventListener`
per-type works directly.

**`PostRealtimeEvent` fields**

| Field | Type | Present on | Description |
|-------|------|------------|-------------|
| `eventType` | string | all | One of the types below |
| `postId` | UUID | all | The subscribed post |
| `actorId` | UUID | most | User who triggered the event (may be absent for anonymous view bumps) |
| `actorUsername` / `actorAvatarUrl` | string | sometimes | Actor profile snapshot when the publisher enriched it |
| `commentId` | UUID | `COMMENT_*`, `REPLY_*`, `COMMENT_REACTION_*` | The comment / reply concerned |
| `parentCommentId` | UUID | `REPLY_CREATED` | The top-level parent (depth-1 rule) |
| `reactionType` | string | `REACTION_*`, `COMMENT_REACTION_*` | Always `"LIKE"` (single reaction type) |
| `saved` | boolean | `SAVE_COUNT_UPDATED` | Direction flag: `true` = saved (+1), `false` = unsaved (−1) |
| `textContent` | string | `COMMENT_CREATED` / `COMMENT_EDITED` / `REPLY_CREATED` / `POST_UPDATED` | Text snapshot |
| `mediaUrl` / `mediaType` / `mediaThumbnailUrl` | string | comment events | Inline media, when present |
| `postShareCount` | long | `SHARE_COUNT_UPDATED` only | Advisory fresh share counter (see above) |
| `timestamp` | LocalDateTime | all | Server time of the event |

---

## 3. Event catalog

Named events, in the order a client typically encounters them:

| SSE event name | Fired when | Client delta |
|----------------|-----------|--------------|
| `connected` | Handshake — first named frame after subscribing | — |
| `REACTION_ADDED` | Someone liked the post ([toggle](./engagement.md#11-post-apiv1postspostidreactions--toggle-like)) | `reactionCount + 1` |
| `REACTION_REMOVED` | Someone unliked the post | `reactionCount − 1` |
| `COMMENT_CREATED` | New top-level comment | `commentCount + 1`; prepend/append the comment |
| `REPLY_CREATED` | New reply (carries `parentCommentId`) | `commentCount + 1`, parent's `replyCount + 1` |
| `COMMENT_EDITED` | Comment / reply text edited | Replace `textContent`, set `edited` |
| `COMMENT_DELETED` | Comment / reply hard-deleted | Remove the row; `commentCount − (1 + its replyCount)` for top-level |
| `COMMENT_REACTION_ADDED` | Someone liked a comment | Comment's `reactionCount + 1` |
| `COMMENT_REACTION_REMOVED` | Someone unliked a comment | Comment's `reactionCount − 1` |
| `VIEW_COUNT_UPDATED` | A fresh unique view was counted | `viewCount + 1` |
| `SAVE_COUNT_UPDATED` | Save toggled (direction in `saved`) | `saveCount ± 1` per `saved` flag |
| `SHARE_COUNT_UPDATED` | A share was recorded | `shareCount + 1` (or use advisory `postShareCount`) |
| `POST_UPDATED` | The author edited the post ([PATCH](./posts.md#4-patch-apiv1postsid--partial-edit-author-only)) | Reconcile `textContent` / refetch the post |
| `heartbeat` | Every ~25 s | Keep-alive; reset your staleness timer |

Enum values that exist but are **never emitted today** (reserved — don't rely on
them, but don't crash on unknown names either): `REACTION_CHANGED` and
`COMMENT_REACTION_CHANGED` (meaningless under the single-`LIKE` rule) and
`POST_DELETED` (post deletion currently reaches clients via the post vanishing from
REST reads, not via an SSE frame).

---

## 4. Sample stream

```
retry: 3000

event: connected
data: {"postId":"f66aebce-d659-45b8-8479-75195f5d6d4b","viewerId":"41ee2a6b-2cd9-417b-861c-d1293c623690","timestamp":"2026-07-20T14:30:00","subscribers":3}

event: REACTION_ADDED
data: {"eventType":"REACTION_ADDED","postId":"f66aebce-d659-45b8-8479-75195f5d6d4b","actorId":"9c1f1a2b-3344-5566-7788-99aabbccddee","reactionType":"LIKE","timestamp":"2026-07-20T14:31:00"}

event: COMMENT_CREATED
data: {"eventType":"COMMENT_CREATED","postId":"f66aebce-d659-45b8-8479-75195f5d6d4b","commentId":"c0a1b2c3-d4e5-f678-9012-3456789abcde","actorId":"9c1f1a2b-3344-5566-7788-99aabbccddee","textContent":"Great post!","timestamp":"2026-07-20T14:32:00"}

event: REPLY_CREATED
data: {"eventType":"REPLY_CREATED","postId":"f66aebce-d659-45b8-8479-75195f5d6d4b","commentId":"r0a1b2c3-d4e5-f678-9012-3456789abcde","parentCommentId":"c0a1b2c3-d4e5-f678-9012-3456789abcde","actorId":"7d2e3f4a-1122-3344-5566-778899aabbcc","textContent":"Agreed!","timestamp":"2026-07-20T14:32:30"}

event: VIEW_COUNT_UPDATED
data: {"eventType":"VIEW_COUNT_UPDATED","postId":"f66aebce-d659-45b8-8479-75195f5d6d4b","actorId":"7d2e3f4a-1122-3344-5566-778899aabbcc","timestamp":"2026-07-20T14:33:15"}

event: SAVE_COUNT_UPDATED
data: {"eventType":"SAVE_COUNT_UPDATED","postId":"f66aebce-d659-45b8-8479-75195f5d6d4b","actorId":"7d2e3f4a-1122-3344-5566-778899aabbcc","saved":true,"timestamp":"2026-07-20T14:33:20"}

event: POST_UPDATED
data: {"eventType":"POST_UPDATED","postId":"f66aebce-d659-45b8-8479-75195f5d6d4b","actorId":"41ee2a6b-2cd9-417b-861c-d1293c623690","textContent":"Edited body — typo fix","timestamp":"2026-07-20T14:45:00"}

event: heartbeat
data: {"timestamp":"2026-07-20T14:45:25"}
```

---

## 5. Client recipe

```js
const es = new EventSource(
  `/api/v1/posts/${postId}/stream?token=${encodeURIComponent(jwt)}`
);

es.addEventListener("connected", (e) => console.log("live", JSON.parse(e.data)));

es.addEventListener("REACTION_ADDED",   () => bump("reactionCount", +1));
es.addEventListener("REACTION_REMOVED", () => bump("reactionCount", -1));

es.addEventListener("COMMENT_CREATED", (e) => {
  const ev = JSON.parse(e.data);
  bump("commentCount", +1);
  appendComment(ev);                     // ev.commentId, ev.textContent, ev.actorId
});

es.addEventListener("SAVE_COUNT_UPDATED", (e) => {
  const ev = JSON.parse(e.data);
  bump("saveCount", ev.saved ? +1 : -1); // direction flag, not a counter value
});

es.addEventListener("POST_UPDATED", () => refetchPost(postId));

// No es.onerror teardown needed: EventSource reconnects automatically
// (retry hint = 3 s; server timeout = 24 h). Reconcile counters with a
// REST refetch after each reconnect.
```

Notes:

- **No echo:** don't special-case your own actions — your tab simply never receives
  them. Apply your optimistic update from the HTTP response instead.
- Because events are deltas, a missed window (sleep, network blip) drifts your local
  counters — always reconcile from `GET /api/v1/posts/{id}` on reconnect/focus.
- One `EventSource` per visible post is fine; close it (`es.close()`) when the post
  leaves the screen to free the server slot.
