# Home Feed — Frontend Integration Guide

How a client (the `ika` React app) renders the ranked home feed.

## 1. One request per home screen

```js
const { items, liveNow, nextCursor } = await api.get('/api/v1/posts/feed/home?pageSize=20');
```

- Render `liveNow` as the top rail (avatar rings, `viewerCount` badge,
  click → `/live/{id}` watch page via `whepUrl`/`playbackUrl`).
- Render `items` in order — the server already ranked and diversified.
- Infinite scroll: `GET /feed/home?cursor={nextCursor}`; stop when
  `nextCursor` is null or `items` is empty. Cursor pages contain no
  `liveNow`, no channel digest, no explore items — just ranked timeline.

Clients still on `GET /feed` (bare array) keep working unchanged, including
`cursor = items.at(-1).createdAt` pagination (the server tail-pins the
oldest timeline item for exactly this reason).

## 2. Rendering by `entityType`

```js
switch (item.entityType) {
  case 'POST':         // post/reel card as before
  case 'RESEARCH':     // research card → /researches/{id}
  case 'QUESTION':     // question card → /questions/{id}
  case 'CHANNEL_POST': // NEW — channel broadcast card
}
```

### Channel cards (`entityType === 'CHANNEL_POST'`)

- Header: `item.channel.avatarUrl` + `item.channel.title` (+ verified badge
  when `item.channel.verified`); **no user author** (`author` is null).
- Body: `textPreview`, `mediaUrl`.
- Counters: `viewCount` = views, `shareCount` = forwards, `commentCount` =
  discussion comments. No like/save affordances (channels use their own
  reaction system inside the channel screen).
- Click-through: channel screen for `item.channel.id`, scrolled to message
  `item.channelPostId` (snowflake string — do **not** parse as number in
  JS; keep it a string, it exceeds `Number.MAX_SAFE_INTEGER`).
- Mark views in batch when cards become visible:
  `POST /api/v1/channels/{channelId}/posts/views { messageIds: [channelPostId] }`.

### Labels by `source`

| `source` | UI |
|---|---|
| `FOLLOWING` | nothing (normal card) |
| `SELF` | nothing |
| `CHANNEL` | channel chrome (above) |
| `EXPLORE` | "Suggested for you" caption + optional Follow button |

## 3. Realtime

- `FEED_NEW_POST` on the notification SSE → show a "New posts" pill; on
  click refetch page 1 (a ranked feed can't blindly prepend).
- `STREAM_STARTED` / `STREAM_ENDED` on the messaging SSE → refresh the rail
  (`GET /api/v1/posts/feed/live-now` is the cheap dedicated call).
- Per-post counter events carry deltas — apply `±1` locally.

## 4. Engagement = personalization

Every like / comment / save / share / first-view the user performs trains
their interest graph server-side automatically. No client work needed —
just keep calling the existing engagement endpoints.

## 5. Do / don't

- **Do** key lists on `item.id` (unique across all entity types, including
  the synthetic channel-post UUID).
- **Do** send `?ranked=false` for a "Latest" toggle tab if the product
  wants a chronological view — same response shape.
- **Don't** derive pagination from ranked item order on `/feed/home` — use
  `nextCursor`.
- **Don't** assume every page contains channel/explore items — they are
  first-page injections and can be absent (no subscriptions, nothing
  trending, cold cache).
