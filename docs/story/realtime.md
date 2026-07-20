# Story Realtime (SSE)

Live updates for the story subsystem over **Server-Sent Events**
(`text/event-stream`). Push-only, one-way — there is no WebSocket layer.

Two streams:

| Stream | URL | Keyed per | Auth |
|---|---|---|---|
| [Story tray](#story-tray-stream) | `GET /api/v1/stories/tray/stream` | viewer | required (header or `?token=`) |
| [Per-story](#per-story-stream) | `GET /api/v1/stories/{storyId}/stream` | story | required (header or `?token=`) |

**Auth for `EventSource`:** browsers cannot set the `Authorization` header on
an `EventSource`, so both streams also accept the JWT **access token** as
`?token=<jwt>` (validated exactly like the bearer header; refresh tokens are
rejected). Build the URL in memory only — never render it into the DOM or
logs.

**Errors:** REST endpoints use the shared envelope
([Error handling](../errors/error-handling.md)); stream endpoints reject
unauthenticated connects with a `401` (plain text / body-less — an SSE
`Accept` header cannot negotiate a JSON body), which surfaces as
`EventSource.onerror` on the client.

Sibling docs: [Stories](stories.md) · [Polls](polls.md) ·
[Close friends](close-friends.md) · [Highlights](highlights.md)

---

## The realtime model: deltas and facts, not counter values

Story counters live in Cassandra `COUNTER` columns, which are eventually
consistent — re-reading a counter right after an update can return a stale
value, and pushing that stale number over the wire would race the next event
and make the UI flicker ("17 → 16 → 17").

So realtime events carry **what happened** (the fact: who viewed, who
reacted, what was deleted), and the client applies **local deltas**
(`+1` / `-1`) to the counts it already has:

- On event → mutate local state by the event's meaning, not by a count field.
- On remount / re-navigation → **re-fetch canonical state** first (you missed
  events while unmounted), then resubscribe for deltas. Never trust
  accumulated local deltas across navigations.

**One deliberate exception:** the tray stream's `poll_vote_cast` event *does*
carry the authoritative tallies (`voteA`, `voteB`, `voteTotal`) fresh from
the counter read that the vote itself performed — replace your local tally
with these values rather than incrementing.

Broadcasts fan out cross-instance via Redis pub/sub
(`irc:story-tray:{viewerId}` and `irc:stories:{storyId}`) and, when a
publish happens inside a transaction, are deferred until after commit so
subscribers never see state the database is about to roll back.

---

## Event-name casing (footgun)

**SSE event names on the wire are the *lowercased* enum values** —
`new_story`, not `NEW_STORY`; `story_viewed`, not `STORY_VIEWED`. This
applies to **both** story streams.

`EventSource.onmessage` only fires for *unnamed* events, and every event
here is named — so you must register `addEventListener` **per event name**:

```js
const es = new EventSource(
  `${API_BASE}/api/v1/stories/tray/stream?token=${encodeURIComponent(accessToken)}`
);
for (const name of ['connected', 'new_story', 'story_removed', 'poll_vote_cast', 'heartbeat']) {
  es.addEventListener(name, (ev) => handle(name, ev.data && JSON.parse(ev.data)));
}
```

(Registering a listener for an event the server never sends is free.)

---

## Story tray stream

```
GET /api/v1/stories/tray/stream
```

**Auth:** required — bearer header or `?token=<accessToken>`.

One background connection per logged-in user. Lights up / greys out the
story-tray rings without polling, and gives story authors live poll tallies.

### Query parameters

| Param | Type | Required | Description |
|---|---|---|---|
| `token` | string | no* | JWT access token — *required if the `Authorization` header is absent (the `EventSource` case) |

### Connection behavior

| Property | Value |
|---|---|
| Content type | `text/event-stream` |
| Heartbeat | `heartbeat` event every **25 s** (shared server tick). Treat > 60 s of silence as a dead socket and force a reconnect. |
| Server-side timeout | 10 min per connection — the browser's `EventSource` reconnects automatically |
| Concurrent connections | **Capped at 5 per user** — opening a 6th closes the **oldest** (LRU eviction). Share one `EventSource` across components/tabs instead of opening one per panel. |
| Proxy buffering | Disabled server-side (`X-Accel-Buffering: no`, `Cache-Control: no-store`) |

### Events

| Event name | Delivered to | Payload highlights | Client action |
|---|---|---|---|
| `connected` | the subscriber | `{"viewerId": "…"}` | Handshake — mark the stream open |
| `new_story` | followers / close friends of the author (per story visibility) | author info, `storyId`, `storyType`, `visibility`, `thumbnailUrl`, `expiresAt` | Light the author's tray ring |
| `story_removed` | everyone whose ring the story lit (visibility-aware; author always included) | `authorId`, `storyId` | Remove/grey the ring if it was the author's last story |
| `poll_vote_cast` | **story author only** | `storyId`, `pollId`, `voteA`, `voteB`, `voteTotal` (other tray fields null) | Replace the StoryEditor's live tally — no need to poll [`/polls/{pollId}/results`](polls.md#live-results) |
| `heartbeat` | the subscriber | the string `ping` | Refresh the liveness timer; otherwise ignore |

### Payload — `StoryTrayEvent`

Fields are omitted when null (`NON_NULL` serialization):

```json
{
  "eventType": "NEW_STORY",
  "authorId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
  "authorUsername": "sh.yusuf",
  "authorFullName": "Yusuf Rahman",
  "authorAvatarUrl": "https://cdn.irc.example/avatars/yusuf.jpg",
  "storyId": "0c9d8e7f-6a5b-4c3d-9e2f-1a0b9c8d7e6f",
  "storyType": "IMAGE",
  "visibility": "PUBLIC",
  "thumbnailUrl": "https://cdn.irc.example/stories/thumb/9b2f1c.jpg",
  "expiresAt": "2026-07-20T17:15:00",
  "timestamp": "2026-07-20T09:15:01"
}
```

| Field | Type | Notes |
|---|---|---|
| `eventType` | string | The enum value (uppercase **inside the JSON**; the lowercase form is the SSE *event name*) |
| `authorId`, `authorUsername`, `authorFullName`, `authorAvatarUrl` | — | Who posted; render the ring from these |
| `storyId`, `storyType`, `visibility`, `thumbnailUrl`, `backgroundValue`, `expiresAt` | — | Ring cover + "expires in N hours" badge |
| `pollId`, `voteA`, `voteB`, `voteTotal` | — | Present on `poll_vote_cast` only |
| `timestamp` | ISO local datetime | Server emit time |

### Errors

| Status | Body | When |
|---|---|---|
| 401 | plain text `Authentication required. Pass access token as ?token=<jwt>.` | Missing/invalid/expired token, or a non-`ACCESS` token type |

---

## Per-story stream

```
GET /api/v1/stories/{storyId}/stream
```

**Auth:** required — bearer header or `?token=<accessToken>`.

Open this while a viewer has a specific story on screen. It carries live
views, reactions, replies, poll votes, and lifecycle events ("the author
just deleted this story — eject the viewer").

### Path parameters

| Param | Type | Description |
|---|---|---|
| `storyId` | UUID | The story to watch |

### Query parameters

| Param | Type | Required | Description |
|---|---|---|---|
| `token` | string | no* | JWT access token — *required if the `Authorization` header is absent |

### Connection behavior

| Property | Value |
|---|---|
| Content type | `text/event-stream` |
| Heartbeat | `heartbeat` every **25 s** (shared tick) |
| Server-side timeout | **5 min** per connection — expected; `EventSource` auto-reconnects. Close the stream client-side when the viewer overlay unmounts. |
| Proxy buffering | Disabled server-side (`X-Accel-Buffering: no`) |

### Events

`connected` and `heartbeat`, plus the lowercased `StoryRealtimeEventType`
values:

| Event name | Meaning | Suggested local delta |
|---|---|---|
| `connected` | Handshake; payload `{"storyId": "…"}` | Mark stream open |
| `story_viewed` | Someone viewed the story (`actorId`, `actorUsername`) | `viewCount += 1`; prepend to viewer list |
| `story_reacted` | Someone reacted (`actorId`, `reactionEmoji`) | `reactionCount += 1` |
| `story_unreacted` | Reaction withdrawn | `reactionCount -= 1` |
| `story_replied` | Someone replied (`actorId`, `replyText`) | `replyCount += 1` |
| `story_poll_voted` | A poll vote landed (`actorId`, `pollChoice`) | Bump the chosen side, or refresh from [`/polls/{pollId}/results`](polls.md#live-results) |
| `story_expired` | The story's TTL fired | Eject the viewer / advance to next story |
| `story_deleted` | The author hard-deleted the story | Same — eject immediately |
| `view_count_updated` | Counter fact without actor context | `viewCount += 1` |
| `reaction_count_updated` | Counter fact without actor context | Apply delta per payload |
| `reply_count_updated` | Counter fact without actor context | Apply delta per payload |
| `heartbeat` | Keepalive (`ping`) | Refresh liveness timer |

### Payload — `StoryRealtimeEvent`

Fields are omitted when null (`NON_NULL` serialization). A `story_reacted`
event, for example:

```json
{
  "eventType": "STORY_REACTED",
  "storyId": "0c9d8e7f-6a5b-4c3d-9e2f-1a0b9c8d7e6f",
  "actorId": "3e2d1c0b-9a87-4654-b321-0fedcba98765",
  "actorUsername": "amina.k",
  "actorAvatarUrl": "https://cdn.irc.example/avatars/amina.jpg",
  "reactionEmoji": "❤️",
  "timestamp": "2026-07-20T10:44:09"
}
```

| Field | Type | Notes |
|---|---|---|
| `eventType` | string | Uppercase enum value inside the JSON; lowercase form is the SSE event name |
| `storyId` | UUID | The story this event belongs to |
| `actorId`, `actorUsername`, `actorAvatarUrl` | — | Who acted (view / reaction / reply / vote events) |
| `reactionEmoji` | string | `story_reacted` only |
| `replyText` | string | `story_replied` only |
| `pollChoice` | string | `story_poll_voted` only (`A` / `B`) |
| `pollVoteACount`, `pollVoteBCount` | int | Optional tally hints on poll events |
| `viewCount`, `reactionCount`, `replyCount` | long | Optional; usually absent — prefer local deltas (see model above) |
| `timestamp` | ISO local datetime | Server emit time |

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No usable access token (`"Authentication required. Pass access token as ?token=<jwt>."`). Strict-SSE clients may observe a body-less error status; either way `EventSource.onerror` fires. |
| 400 | `TYPE_MISMATCH` | `storyId` is not a valid UUID |

---

## Reconnection checklist

1. Let the browser's built-in `EventSource` retry handle transient drops —
   do not close + reopen yourself on `onerror` (fighting the browser's
   backoff can trip the tray stream's 5-connection cap).
2. Track the last `heartbeat`; if > 60 s pass without one, close and reopen
   deliberately.
3. On tab re-focus / component remount, re-fetch canonical state
   ([`GET /stories/by-author/{authorId}`](stories.md#list-an-authors-active-stories),
   [`GET /polls/{pollId}/results`](polls.md#live-results)) before trusting
   the stream again.

## Related

- REST endpoints these streams complement: [stories.md](stories.md),
  [polls.md](polls.md), [highlights.md](highlights.md)
- Who receives `CLOSE_FRIENDS` fan-outs: [close-friends.md](close-friends.md)
