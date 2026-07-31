# Feed API Reference — Complete Analysis

Every endpoint that reads the feed or produces its ranking signals.

- **Base path:** `/api/v1/posts` unless stated otherwise.
- **Auth:** `Authorization: Bearer <JWT>` (or `?token=` for SSE). Endpoints
  marked *viewer-bound* derive the user from the JWT and return empty for
  anonymous callers.
- **Page clamp:** every `pageSize` / `limit` is clamped to **1..100**.
- **Errors:** unified envelope — see [../errors/error-handling.md](../errors/error-handling.md).

---

## 1. Home feed

### 1.1 `GET /feed` · `GET /feed/cursor` — home timeline (array shape, v1)

Ranked by default since the recommendation upgrade.

| Param | Default | Notes |
|---|---|---|
| `pageSize` / `limit` | 20 | `limit` wins when both sent (legacy) |
| `cursor` | — | ISO-8601 instant; omit for the first page |
| `ranked` | `true` | `false` = exact pre-ranking chronological read |
| `userId` | — | legacy fallback when no JWT; JWT always wins |

**Response:** bare JSON array of [FeedItem](#feeditem) — shape unchanged;
ranking added four nullable fields. First page additionally contains channel
digest + exploration items. The chronologically-oldest timeline item is
**pinned last** so clients deriving `cursor = items.at(-1).createdAt` keep
paginating correctly (see [algorithm.md](./algorithm.md#pagination)).

**End of feed:** empty array.

### 1.2 `GET /feed/home` — composite home screen (v2, canonical)

*Viewer-bound.* One request renders the whole home screen.

| Param | Default |
|---|---|
| `pageSize` | 20 |
| `cursor` | — |
| `ranked` | `true` |

**Response:**

```json
{
  "items":      [ FeedItem, … ],          // ranked best-first, no tail-pin
  "liveNow":    [ LiveStream, … ],        // first page only; [] on cursor pages
  "nextCursor": "2026-08-01T09:12:44Z",   // pass back as ?cursor=; null = end
  "ranked":     true
}
```

`nextCursor` is authoritative — computed from the raw timeline window, never
from ranked item order. `liveNow` entries are the public
`LiveStreamResponse` (id, host identity + avatar, title, `playbackUrl` HLS,
`whepUrl` WebRTC, `viewerCount`, `shareUrl`; never a stream key).

### 1.3 `GET /feed/live-now` — the live rail alone

*Viewer-bound.* Followed hosts first (most recently started), filled to 10
with the most-watched public streams, blocked hosts removed. For clients
polling the rail more often than the feed body.

**Response:** array of `LiveStreamResponse`.

---

## 2. FeedItem schema {#feeditem}

```json
{
  "id": "uuid",
  "authorId": "uuid | null",
  "author": { "id", "username", "fullName", "profileImage" },
  "entityType": "POST | RESEARCH | QUESTION | CHANNEL_POST",
  "postType": "POST | REEL | VOICE_POST | REPOST | PUBLICATION | QUESTION | CHANNEL_POST",
  "textPreview": "string | null",
  "mediaUrl": "string | null",
  "videoUrl": "string | null",
  "reactionCount": 0, "commentCount": 0, "viewCount": 0,
  "saveCount": 0, "shareCount": 0,
  "likedByMe": false, "savedByMe": false,
  "createdAt": "instant",

  "source": "FOLLOWING | SELF | CHANNEL | EXPLORE | null",
  "rankScore": 12.73,
  "channel": { "id", "handle", "title", "avatarUrl", "verified", "subscriberCount" },
  "channelPostId": "1234567890123456789"
}
```

Dispatch rules for clients:

| `entityType` | Detail fetch | Counters meaning |
|---|---|---|
| `POST` | `GET /api/v1/posts/{id}` | live `post_counters` |
| `RESEARCH` | `GET /api/v1/researches/{id}` | zeros at feed time — fetch on click-through |
| `QUESTION` | `GET /api/v1/questions/{id}` | zeros at feed time |
| `CHANNEL_POST` | `GET /api/v1/channels/{channel.id}` + message `channelPostId` | `viewCount`=views, `shareCount`=forwards, `commentCount`=discussion comments |

For `CHANNEL_POST`: `author` is null (the channel signs the post — render
`channel.title` + `channel.avatarUrl`), `id` is a synthetic UUID for list
keying only, and the real message id is `channelPostId` (snowflake string).
Channel-post comments live at
`GET/POST /api/v1/channels/{channelId}/posts/{channelPostId}/comments`;
views are batch-marked via `POST /api/v1/channels/{channelId}/posts/views`.

`source` drives UI labels: `EXPLORE` → "Suggested for you", `CHANNEL` →
channel card chrome. `rankScore` is debug/telemetry — ordering is already
applied server-side.

---

## 3. Other feed surfaces (unchanged behavior)

| Endpoint | What | Notes |
|---|---|---|
| `GET /by-author/{authorId}` | profile feed | public, cursor-paginated, newest first |
| `GET /reels` · `/feed/reels` | global reels for a UTC day bucket | `?day=YYYY-MM-DD` |
| `GET /reels/following` | reels from followed accounts | *viewer-bound*, parallel fan-in, merge-sorted |
| `GET /reels/for-you` | engagement-ranked reels | anonymous OK (no follow boost); this feed also seeds the home-feed exploration slice |
| `GET /reels/by-author/{authorId}` | one author's reels | cursor-paginated |
| `GET /suggestions` | multi-signal "People You May Know" | *viewer-bound*, `?limit=` — see [docs/suggestions/](../suggestions/README.md) |
| `POST /suggestions/recompute` | recompute caller's suggestions | 202 Accepted |

---

## 4. Signal-producing endpoints (ranking inputs)

These existing engagement endpoints now **also** feed the interest graph
(`user_author_affinity`) asynchronously — no contract change, listed here
because they are the ranker's inputs:

| Endpoint | Counter effect | Affinity effect |
|---|---|---|
| `POST /{postId}/reactions` (toggle ON) | `reaction_count`+1 | viewer→author **+3** |
| `POST /{postId}/comments` | `comment_count`+1 | **+5** |
| `POST /comments/{commentId}/replies` | `comment_count`+1 | **+5** |
| `POST /{postId}/saves` (toggle ON) | `save_count`+1 | **+4** |
| `POST /{postId}/shares` · `POST /{postId}/share` | `share_count`+1 | **+5** |
| `POST /{postId}/views` (first view, deduped) | `view_count`+1 | **+1** |

Toggle-OFF (unlike/unsave) decrements the public counter but **not**
affinity. Self-engagement never writes affinity.

Channel-side signals consumed by the ranker (written elsewhere in the chat
API): `POST /api/v1/channels/{id}/posts/views` (unique views via Redis HLL),
message forwards, and discussion-comment counts — all in `message_counters`.

---

## 5. Realtime companions

| Stream | Endpoint | Feed-relevant events |
|---|---|---|
| user notification SSE | `GET /api/v1/notifications/stream` (see docs/notifications) | `FEED_NEW_POST {postId, authorId}` — prepend hint; client refetches page 1 for a ranked insert |
| per-post SSE | `GET /api/v1/posts/{id}/stream` | reaction/comment/view/save/share deltas (clients apply ±1 locally) |
| chat/live SSE | `GET /api/v1/messaging/stream` | `STREAM_STARTED` / `STREAM_ENDED` → refresh the `liveNow` rail; `message.new` on subscribed channels → channel digest is stale |

Realtime events carry **deltas, not counter values** — apply locally, don't
re-read.

---

## 6. Related discovery APIs (context)

- Channels: `GET /api/v1/channels/discover`, `GET /api/v1/channels/{id}`,
  subscribe/unsubscribe — see [../chat/channels/](../chat/channels/overview.md).
- Live: `GET /api/v1/streams/live`, `GET /api/v1/streams/live/following`,
  `POST /api/v1/streams` — see [../chat/live-streaming.md](../chat/live-streaming.md).
- Hashtags: `GET /api/v1/hashtags/{tag}/posts`, `/usage`.
- Trending tags: [../platform/tags.md](../platform/tags.md).
