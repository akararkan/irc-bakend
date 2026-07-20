# Q&A API — Realtime (SSE)

Per-question Server-Sent Events stream: every answer, reanswer, reaction, accept/unaccept,
lifecycle change and counter update on a question, in near real time.

**Base path:** `/api/v1/questions`

Sibling docs: [Questions](./questions.md) · [Answers](./answers.md) · [Engagement](./engagement.md)
Errors use the shared envelope — see [Error handling](../errors/error-handling.md).

---

## Subscribe

```
GET /api/v1/questions/{questionId}/stream
Accept: text/event-stream
```

**Auth:** Optional. The JWT is resolved from the `access_token` cookie or the
`Authorization: Bearer <jwt>` header; anonymous subscribers are accepted (the handshake
reports `"viewerId": "anonymous"`). Unlike the story-tray and notification streams, this
endpoint does **not** accept a `?token=` query parameter — browsers using `EventSource`
must rely on the cookie.

The question is **fetched first**, so a missing or deleted question fails fast with the
standard `404 QUESTION_NOT_FOUND` envelope instead of opening a zombie stream. A viewer
with a block edge to the question author also gets `404` (existence hidden) when
authenticated.

Connection properties:

- No server-side timeout — the connection lives until the client disconnects.
- A `connected` handshake event fires immediately on subscribe.
- A `heartbeat` event fires every **25 seconds** to keep proxies from idling the socket.
- Cross-instance fan-out via Redis pub/sub channel `irc:questions:{questionId}` — events
  raised on any app instance reach subscribers on every instance.
- Broadcasts are deferred until **after the database transaction commits**, so subscribers
  never see counters that are about to roll back.
- **The actor's own subscription is skipped** on broadcast — the actor already has the
  result from the originating HTTP response. Don't wait for your own event.

### Example (browser)

```js
const es = new EventSource(`/api/v1/questions/${questionId}/stream`); // cookie auth
es.addEventListener("ANSWER_CREATED", (e) => {
  const event = JSON.parse(e.data);
  // insert event.answer into the thread; trust event.questionAnswerCount
});
es.addEventListener("heartbeat", () => { /* connection alive */ });
```

---

## Events

The SSE `event:` name is the **uppercase `QnaRealtimeEventType` enum name** (e.g.
`ANSWER_CREATED`) — *not* lowercased. (This differs from the story-tray stream, which
lowercases its event names.) Two infrastructure events use fixed lowercase names:
`connected` and `heartbeat`.

### `connected` (handshake)

```json
{
  "questionId": "3f6f8a3e-9d1c-4a51-b8f1-0f0f4d1c2a10",
  "viewerId": "anonymous",
  "timestamp": "2026-07-20T10:00:00.000",
  "subscribers": 4
}
```

### `heartbeat` (every 25 s)

```json
{ "timestamp": "2026-07-20T10:00:25.000" }
```

### `QnaRealtimeEventType` catalogue

| Event name | Fired by | Key payload fields |
|---|---|---|
| `ANSWER_CREATED` | New top-level answer | `answerId`, `answer` (full DTO), `body`, `questionAnswerCount` |
| `REANSWER_CREATED` | New reply | `answerId`, `parentAnswerId`, `answer`, `body`, `answerReplyCount` |
| `ANSWER_EDITED` | Answer/reply edit | `answerId`, `parentAnswerId?`, `answer`, `body` |
| `ANSWER_DELETED` | Answer/reply soft-delete | `answerId`, `parentAnswerId?`, `answer` (tombstone: `deleted: true`), `questionAnswerCount` / `answerReplyCount` |
| `ANSWER_REACTION_ADDED` | LIKE added (or idempotent re-add) | `answerId`, `reactionType: "LIKE"`, `answer`, `answerReactionCount` |
| `ANSWER_REACTION_CHANGED` | Declared in the enum; not emitted today (single LIKE reaction — nothing to change to) | — |
| `ANSWER_REACTION_REMOVED` | LIKE removed (or idempotent no-op) | `answerId`, `answer`, `answerReactionCount` |
| `ANSWER_ACCEPTED` | Question author accepts | `answerId`, `answer` |
| `ANSWER_UNACCEPTED` | Question author unaccepts | `answerId`, `answer` |
| `QUESTION_UPDATED` | Question edit | `body` (new question body) |
| `QUESTION_DELETED` | Question hard-delete | actor fields only — close the view |
| `QUESTION_LOCKED` | Author locks answers | actor fields |
| `QUESTION_UNLOCKED` | Author unlocks answers | actor fields |
| `VIEW_COUNT_UPDATED` | Deduplicated view recorded | `questionViewCount` |
| `SAVE_COUNT_UPDATED` | Save/unsave (incl. idempotent re-broadcasts) | `questionSaveCount` |
| `SHARE_COUNT_UPDATED` | Share recorded | `shareCount` |

---

## Payload schema (`QnaRealtimeEvent`)

One shape covers every event; clients dispatch on `eventType` and read whichever fields
are populated. **Null fields are omitted from the wire** (`@JsonInclude(NON_NULL)`) to
keep frames small.

```json
{
  "eventType": "REANSWER_CREATED",
  "questionId": "3f6f8a3e-9d1c-4a51-b8f1-0f0f4d1c2a10",
  "actorId": "77aa1b2c-...",
  "actorUsername": "scholar_omar",
  "actorAvatarUrl": "https://cdn.example.com/avatars/omar.jpg",
  "answerId": "b52c3d4e-...",
  "parentAnswerId": "a41b2c3d-...",
  "answer": { "id": "b52c3d4e-...", "body": "...", "myReaction": null, "...": "..." },
  "body": "JazakAllahu khayran — one addition...",
  "answerReplyCount": 3,
  "timestamp": "2026-07-20T10:02:14.221"
}
```

| Field | Type | Notes |
|---|---|---|
| `eventType` | enum | One of the names above. |
| `questionId` | UUID | Always present. |
| `actorId` / `actorUsername` / `actorAvatarUrl` | — | Who triggered the event; may be absent for system events. |
| `answerId` | UUID | Present on every answer-scoped event. |
| `parentAnswerId` | UUID | Root answer id when the affected answer is a reanswer; absent for top-level answers. Route updates with `threadId = parentAnswerId ?? answerId`. |
| `answer` | `QuestionAnswerResponse` | Full, freshly recomputed answer DTO on every answer-scoped event — patch the row in place, **no refetch**. Viewer-specific fields (`myReaction`) are neutral (`null`); resolve them per-viewer. On `ANSWER_DELETED` this is the tombstone. |
| `reactionType` | string | `"LIKE"` on `ANSWER_REACTION_ADDED`. |
| `previousReactionType` | string | Reserved for `ANSWER_REACTION_CHANGED` (not emitted today). |
| `body` | string | Body snapshot on `ANSWER_CREATED` / `REANSWER_CREATED` / `ANSWER_EDITED` / `QUESTION_UPDATED`. |
| `questionAnswerCount` | long | Fresh top-level answer count. |
| `questionViewCount` | long | Fresh view count (`VIEW_COUNT_UPDATED`). |
| `questionSaveCount` | long | Fresh save count (`SAVE_COUNT_UPDATED`). |
| `shareCount` | long | Fresh share count (`SHARE_COUNT_UPDATED`). |
| `answerReactionCount` | long | Fresh LIKE count on the affected answer. |
| `answerReplyCount` | long | Fresh reply count on the affected thread root. |
| `timestamp` | datetime | Server time of the broadcast. |

### Counter model

> **Q&A events carry fresh absolute counter values, not deltas.** This intentionally
> differs from the Post realtime channel (which omits counter values and lets clients
> apply ±1 locally). Every Q&A counter field above is the authoritative post-commit
> number — **overwrite** your local counter with it; never add it to an optimistic bump.
> Idempotent operations (double LIKE, re-save, remove-nonexistent-reaction) deliberately
> re-broadcast the authoritative count so a desynced optimistic UI self-heals.

### Client handling notes

- Because your own actions are not echoed back on your own subscription, apply the HTTP
  response optimistically and treat SSE events as *other people's* activity plus counter
  reconciliation.
- On `QUESTION_DELETED`, tear down the page — the stream's question no longer exists and
  the emitter will die with the connection.
- Reconnect with plain `EventSource` retry semantics; there is no event-id replay. After
  a reconnect, refetch the question and its answers to resync, then resume patching from
  events.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 404 | `QUESTION_NOT_FOUND` | Unknown/deleted question — checked *before* the stream opens; also returned to blocked viewers. |
