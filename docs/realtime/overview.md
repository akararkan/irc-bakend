# Realtime — the master reference

Everything live in this application, in one place: every SSE stream, every
event, the wire conventions they share, and — most importantly — **what a
frontend should subscribe to on each screen** and how to handle what arrives.
Per-domain docs carry full payload field tables; this document is the map.

**TL;DR for the frontend.** An authenticated shell keeps **three always-on
streams** open for the whole session:

1. `GET /api/v1/messaging/stream` — everything chat: messages, receipts,
   typing/activity, presence, groups, channels, calls, live streams + stage.
2. `GET /api/v1/notifications/stream` — the bell: inbox rows + unread badge.
3. `GET /api/v1/stories/tray/stream` — the story tray rail.

Everything else is **page-scoped**: open when the screen mounts, close when it
unmounts (post detail, story viewer, question, research, activity, admin
audit). Media for calls/live-streaming flows over **WebRTC, not SSE** — see
[§9](#9-the-media-plane-calls--live-streaming).

---

## 1. Architecture

The realtime layer is **100% Server-Sent Events (SSE) + Redis pub/sub**. There
are no WebSockets anywhere: every live surface is an `EventSource` subscription
to an HTTP `text/event-stream` endpoint, and Redis pub/sub fans events out
across app instances. RabbitMQ handles *asynchronous work* (notification
creation, analytics) — never browser delivery ([messaging.md](messaging.md)).
The one non-SSE exception is **media**: voice/video call audio and live-stream
video ride WebRTC; their *signaling and control events* still ride the SSE
streams below.

```
service (tx commits) ──► *RealtimePublisher ──► Redis channel ──► *RealtimeSubscriber (every instance)
                                                                        │
                                                                        ▼
                                                            *RealtimeService (local SseEmitter registry)
                                                                        │
                                                                        ▼
                                                              browser EventSource
```

Each domain has a `realtime` package with the same four roles:

- `*RealtimeService` / `*SseService` — per-instance `SseEmitter` registry
  (subscribe / broadcast / heartbeat)
- `*RealtimePublisher` — serialises the event and `PUBLISH`es it to Redis
- `*RealtimeSubscriber` — receives it on every instance, forwards to the local registry
- `*RealtimeBroadcaster` — defers the publish until **after the transaction
  commits**, so subscribers never see data that rolls back

All wired together in `app/config/RedisMessagingConfig.java`.

---

## 2. SSE endpoint catalog — every stream

| # | Endpoint | Auth | Scope | Heartbeat | Timeout | Detail doc |
|---|---|---|---|---|---|---|
| 1 | `GET /api/v1/messaging/stream` | Bearer or `?token=` | per user — ALL chat | 15 s | 24 h | [chat/realtime.md](../chat/realtime.md) |
| 2 | `GET /api/v1/notifications/stream` | Bearer or `?token=` | per user | 15 s | 24 h | [notifications/realtime.md](../notifications/realtime.md) |
| 3 | `GET /api/v1/stories/tray/stream` | Bearer or `?token=` | per viewer | 25 s | 10 min | [story/realtime.md](../story/realtime.md) |
| 4 | `GET /api/v1/stories/{storyId}/stream` | Bearer or `?token=` | per story | 25 s | 5 min | [story/realtime.md](../story/realtime.md) |
| 5 | `GET /api/v1/posts/{id}/stream` | optional (anonymous OK) | per post | 25 s | 24 h | [post/realtime.md](../post/realtime.md) |
| 6 | `GET /api/v1/researches/{researchId}/stream` | optional Bearer (anonymous OK) | per research | 25 s | none (`0L`) | [research/realtime.md](../research/realtime.md) |
| 7 | `GET /api/v1/questions/{questionId}/stream` | optional Bearer (anonymous OK) | per question | 25 s | none (`0L`) | [qna/realtime.md](../qna/realtime.md) |
| 8 | `GET /api/v1/users/me/activity/stream` | Bearer or `?token=` | per user | 25 s | none (`0L`) | [platform/activity.md](../platform/activity.md) |
| 9 | `GET /api/v1/admin/audit/stream` | admin only | global firehose | 25 s | none (`0L`) | [platform/audit.md](../platform/audit.md) |

Both per-user 24 h streams (1, 2) cap at **5 concurrent connections per user**
with LRU eviction (a 6th tab silently kills the oldest), send `retry: 3000`,
and push a 2 KB comment frame at handshake to defeat proxy buffering.

### Stream 1 — `/messaging/stream` (chat, calls, live) — event names

Dotted lowercase, multiplexed by `event:` name; payload is a sparse
`ChatRealtimeEvent` disambiguated by `conversationId`. Full payload tables in
[chat/realtime.md](../chat/realtime.md).

| Family | Events | Notes |
|---|---|---|
| Messages | `message.new` · `message.edited` · `message.deleted` · `message.reaction` | `message.new` carries the full `MessageResponse` — render it directly, dedupe by `messageId` (your own sends echo to your other devices) |
| Receipts | `receipt.delivered` · `receipt.read` | Reciprocal privacy — suppressed if either side disabled read receipts |
| Composer | `typing` (`isTyping` + `activity`: `TYPING`, `RECORDING_VOICE`, `SENDING_PHOTO`, `SENDING_VIDEO`, `SENDING_VOICE`, `SENDING_FILE`, …) | Ephemeral; auto-expires ~6 s — keep a per-user TTL client-side |
| Presence | `presence` (`presenceStatus`, `lastSeenEpochMs`) | Also poll `GET /presence?userIds=` for initial paint |
| Conversation | `conversation.updated` · `member.changed` · `request.new` | `request.new` lands in the Requests tray, not the inbox |
| Channels | `poll.updated` · `channel.join_request` · `message.comment` | `message.comment` is a ±1 delta on a channel post's comment count |
| Calls | `call.incoming` · `call.accepted` · `call.declined` · `call.ended` · `call.participant` · `call.signal` | The complete WebRTC signaling plane — see [chat/calls.md](../chat/calls.md) |
| Live | `stream.started` · `stream.ended` · `stream.updated` · `stream.viewer` · `stream.chat` | `started`/`ended` also fan out to the host's **followers** (live rail add/remove) |
| Stage | `stream.stage` · `stream.stage.request` · `stream.stage.invite` · `stream.stage.grant` · `stream.reaction` · `stream.gift` | Multi-guest co-hosting; `stage.grant` is private (carries the guest's secret publish key) — see [chat/live-streaming.md](../chat/live-streaming.md) |

### Stream 2 — `/notifications/stream` — event names

`connected` · `notification` (new **or coalesced** row — **upsert by `id`**,
never append blindly) · `unread-count` (`{count}` — authoritative badge) ·
`read` / `deleted` (cross-tab sync of mark-read/delete) · `heartbeat`.

### Streams 3–4 — story tray & story viewer — event names

**Lowercased** enum values. Tray: `new_story` · `story_removed` ·
`poll_vote_cast` (author-only, carries full tallies) · `heartbeat`. Viewer:
`story_viewed` · `story_reacted` / `story_unreacted` · `story_replied` ·
`story_poll_voted` · `story_expired` / `story_deleted` ·
`view/reaction/reply_count_updated` · `heartbeat`.

### Streams 5–7 — post / research / question detail — event names

**UPPERCASE** enum names. Post: `REACTION_ADDED/CHANGED/REMOVED`,
`COMMENT_CREATED/EDITED/DELETED`, `REPLY_CREATED`,
`COMMENT_REACTION_ADDED/CHANGED/REMOVED`, `VIEW/SHARE/SAVE_COUNT_UPDATED`,
`POST_UPDATED/DELETED`. Research: same shape plus
`DOWNLOAD/CITATION_COUNT_UPDATED`, `RESEARCH_UPDATED/DELETED/PUBLISHED`.
Question: `ANSWER_CREATED/REANSWER_CREATED/EDITED/DELETED`,
`ANSWER_REACTION_*`, `ANSWER_ACCEPTED/UNACCEPTED`,
`QUESTION_UPDATED/DELETED/LOCKED/UNLOCKED`, `VIEW/SAVE/SHARE_COUNT_UPDATED`.

### Streams 8–9 — activity & audit

Activity: each event is named by the **UPPERCASE** `UserActivityType` of the
row (`POST_CREATED`, `GLOBAL_SEARCH`, `PROFILE_VIEW`, …) — your own actions,
every tab/device. Audit: a single `audit` event per row, admin console only.

---

## 3. Frontend guide — what to use, screen by screen

**Lifecycle rules first:**

- Keep streams 1–3 open for the whole authenticated session, in ONE place
  (a context/provider at the app shell). Never open them per page — the
  per-user caps (5) will LRU-evict your own older tabs.
- Page-scoped streams (4–8): subscribe on mount, **`close()` on unmount**.
  One post/story/question/research page = one subscription.
- On every `connected` (first connect *and* every auto-reconnect), re-fetch
  the screen's data once via REST to reconcile anything missed while offline.
- Missed heartbeats > 2× cadence ⇒ treat as dead: `close()` and resubscribe.

| Screen / UI surface | Subscribe to | Render from |
|---|---|---|
| App shell (any page, logged in) | streams **1 + 2 + 3** | Bell badge from `unread-count`; chat badge from `GET /messaging/unread-count` + `message.new` bumps; story rail from tray events |
| Chat inbox + open conversation | stream 1 (already open) | Fan out client-side by `conversationId`; typing bubbles from `typing` (+`activity` verb); ticks from `receipt.*`; online dots from `presence` |
| Channel page | stream 1 | Same as chat + `poll.updated`, `message.comment` deltas; admins also watch `channel.join_request` |
| Incoming call UI | stream 1 | `call.incoming` opens the ringing modal; exchange SDP/ICE via `POST /calls/{id}/signal` ↔ `call.signal`; tear down on `call.ended` |
| Live directory + "following is live" rail | stream 1 | `stream.started` prepends the card (payload is the full public stream — no re-fetch), `stream.ended` removes it |
| Live watch page | stream 1 + WHEP/HLS media ([§9](#9-the-media-plane-calls--live-streaming)) | `stream.viewer` (live count), `stream.chat`, `stream.updated`, stage/reaction/gift events |
| Notifications page / bell dropdown | stream 2 | `notification` upserts by `id`; `read`/`deleted` keep sibling tabs in sync |
| Story tray rail | stream 3 | `new_story` / `story_removed`; authors also get `poll_vote_cast` tallies |
| Story viewer (fullscreen) | stream 4 | Viewer/reaction/reply events + `story_expired`/`story_deleted` (close the viewer) |
| Post detail / reel page | stream 5 | Comments/reactions live; counters by delta ([§6](#6-the-delta-model)) |
| Research detail page | stream 6 | Same pattern |
| Question page | stream 7 | Answers/acceptance live; `QUESTION_LOCKED` disables the composer |
| "Your activity" page | stream 8 | Prepend rows as they arrive |
| Admin audit console | stream 9 | Live firehose table |

### Minimal robust subscriber (vanilla JS)

```js
function subscribe(url, token, handlers, { heartbeatMs = 25_000 } = {}) {
  let es, watchdog;
  const arm = () => {
    clearTimeout(watchdog);
    watchdog = setTimeout(() => { es.close(); open(); }, heartbeatMs * 2); // missed 2 beats
  };
  const open = () => {
    es = new EventSource(`${url}?token=${token}`);   // EventSource can't set headers
    for (const [name, fn] of Object.entries(handlers)) {
      es.addEventListener(name, e => { arm(); fn(e.data ? JSON.parse(e.data) : null); });
    }
    es.addEventListener('heartbeat', arm);
    es.addEventListener('connected', () => { arm(); handlers.__reconciled?.(); });
    es.onerror = () => {};                            // EventSource auto-reconnects (retry: 3000)
  };
  open();
  return () => { clearTimeout(watchdog); es.close(); };
}

// the always-on chat stream, fanned out by conversationId:
const off = subscribe('/api/v1/messaging/stream', accessToken, {
  'message.new':  evt => store.appendMessage(evt.conversationId, evt.message),  // dedupe by messageId
  'typing':       evt => store.setTyping(evt.conversationId, evt.userId,
                                         evt.isTyping, evt.activity /* TYPING | RECORDING_VOICE | … */),
  'receipt.read': evt => store.advanceRead(evt.conversationId, evt.userId, evt.lastReadMessageId),
  'presence':     evt => store.setPresence(evt.userId, evt.presenceStatus, evt.lastSeenEpochMs),
  '__reconciled': () => store.refetchInbox(),          // on every (re)connect
});
```

Send-side composer states (what to POST while the user is doing things):

```js
api.typing(convId, true, 'TYPING')            // keystrokes (throttle ≤ 1/3s, resend on activity change)
api.typing(convId, true, 'RECORDING_VOICE')   // mic held
api.typing(convId, true, 'SENDING_PHOTO')     // upload in flight (attachment progress bars stay client-side)
api.typing(convId, false)                     // optional stop — the 6 s TTL self-heals
```

---

## 4. Authentication

Browser `EventSource` **cannot set request headers**, so every identity-bearing
stream accepts the JWT access token as `?token=<accessToken>` (must be a valid
`ACCESS`-type token; `REFRESH` is rejected). Bearer headers work for
fetch-based/server-side consumers. Unauthenticated subscribes are refused
**401 with a plain-text hint, not the JSON error envelope** — see
[error-handling.md](../errors/error-handling.md#sse-error-semantics). The
post, research and question streams are anonymous-capable (public content is
viewable logged out).

---

## 5. Wire conventions

- **Event-name casing differs by stream** — register listeners exactly:
  dotted lowercase on chat (`message.new`), lowercase words on
  notifications (`unread-count`), lowercased enums on story streams
  (`new_story`), UPPERCASE enums on post/research/question/activity.
- **Sparse payloads.** Chat events serialize only the fields relevant to that
  event type (`@JsonInclude(NON_NULL)` style) — read what the event table for
  that stream documents, tolerate extra/missing fields.
- **Upsert, don't append.** Aggregating surfaces (notification rows) reuse the
  same `id` when they coalesce — always upsert by id.
- **Self-echo differs by stream.** Chat delivers your own `message.new` to your
  *other* devices (dedupe by `messageId`). Topic streams (post / research /
  question) **suppress the actor's own subscription** — you already have the
  result in the originating HTTP response. Notification/activity streams are
  per-user by design and never suppress.

## 6. The delta model

**Realtime events carry the event and its context — never authoritative
counter values.** Clients apply `+1`/`-1` locally from the event type (and
direction flags), then reconcile with the true numbers on the next REST read.
Exceptions that *do* carry values, because they aren't delta-able client-side:
post `SHARE_COUNT_UPDATED` (fresh `postShareCount`), story `poll_vote_cast`
and `story_poll_voted` (full tallies), chat `stream.viewer` (the updated
`viewerCount` rides in the `stream` object), notification `unread-count`
(authoritative badge).

## 7. Heartbeats, timeouts & reconnect

- Cadence: **15 s** on the two per-user 24 h streams (chat, notifications),
  **25 s** everywhere else — always faster than typical proxy idle timeouts.
- Handshake `retry: 3000` on chat/notifications/post so a restarting JVM isn't
  hammered; other streams use the browser default.
- Finite timeouts (24 h / 10 min / 5 min) mean the server completes the
  emitter and `EventSource` transparently reconnects — treat `connected` as
  your "reconcile via REST" signal every time, not only on first open.
- Streams set `Cache-Control: no-cache` and `X-Accel-Buffering: no`; the 24 h
  streams also flush a 2 KB comment frame at handshake.
- Per-user caps (chat + notifications): **5 emitters per user**, LRU-evicted.

## 8. Privacy suppression (chat)

Ephemeral signals (`typing`, `presence`, `receipt.*`) go dark whenever the
relationship isn't fully open: pending message requests, RESTRICTED threads,
any block (presence reads `offline`). Per-user toggles (read receipts,
last-seen, typing) are **reciprocal** — turn yours off, lose both directions.
An `activity` state never leaks more presence than plain typing would.
Details: [chat/realtime.md](../chat/realtime.md) · [chat/settings.md](../chat/settings.md).

## 9. The media plane (calls & live streaming)

The only realtime that is *not* SSE. Control/signaling always rides stream 1.

- **1:1 / group calls** — pure WebRTC peer-to-peer: negotiate via
  `POST /calls/{id}/signal` (OFFER/ANSWER/ICE) delivered as `call.signal`
  events; media never touches the backend. [chat/calls.md](../chat/calls.md)
- **Live streaming** — MediaMTX media server: hosts publish via
  **WebRTC/WHIP `:8889`** (browsers cannot publish RTMP; RTMP `:1935` is for
  OBS), viewers watch **WHEP `:8889`** with **HLS `:8888`** fallback; the
  publish URL carries a secret stream key enforced by the backend auth hook.
  Multi-guest stages give each guest their own publish path + key (delivered
  privately via `stream.stage.grant`) and every participant WHEP-subscribes to
  each publisher. [chat/live-streaming.md](../chat/live-streaming.md)

## 10. Redis channels (multi-instance fan-out)

`SseEmitter` registries are per-instance; every event goes through Redis so the
instance holding the socket delivers it:

| Channel | Scope | Publisher class |
|---|---|---|
| `irc:chat:{userId}` | per user (chat stream — pipelined multi-recipient publish) | `ChatRedisPublisher` |
| `irc:notifications:{userId}` | per user | `NotificationRedisPublisher` |
| `irc:posts:{postId}` | per post | `PostRealtimePublisher` |
| `irc:stories:{storyId}` | per story | `StoryRealtimePublisher` |
| `irc:story-tray:{viewerId}` | per viewer | `StoryTrayRealtimePublisher` |
| `irc:questions:{questionId}` | per question | `QnaRealtimePublisher` |
| `irc:research:{researchId}` | per research | `ResearchRealtimePublisher` |
| `irc:activity:{userId}` | per user | `UserActivityRealtimePublisher` |
| `irc:audit:stream` | global (single channel) | `AuditRealtimePublisher` |
| `irc:feed:{userId}` | per user (published on fanout; no SSE bridge wired yet) | `FeedRealtimePublisher` |

`RedisMessagingConfig` subscribes with `PatternTopic(prefix + "*")` (audit is a
plain `ChannelTopic`), dispatches on a bounded pool (`redis-sub-*`, 4–8
threads, queue 1000, caller-runs backpressure), retries the connection every
5 s, and tolerates Redis being down at boot.

## See also

- [messaging.md](messaging.md) — RabbitMQ topology feeding the notification pipeline
- [../chat/realtime.md](../chat/realtime.md) — chat stream payload tables + typing/receipts/presence endpoints
- [../notifications/realtime.md](../notifications/realtime.md) — notification stream payloads
- [../notifications/notifications.md](../notifications/notifications.md) — the persisted inbox the bell events mirror
- [../story/realtime.md](../story/realtime.md) · [../post/realtime.md](../post/realtime.md) — story/post payloads + recipes
- [../chat/calls.md](../chat/calls.md) · [../chat/live-streaming.md](../chat/live-streaming.md) — media-plane flows
- [../errors/error-handling.md](../errors/error-handling.md) — SSE status-only error semantics
