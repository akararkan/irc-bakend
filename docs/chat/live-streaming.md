# Live streaming

Go live to an audience over the existing SSE stream. The server owns the **stream
lifecycle**, a **live viewer registry** (drives the viewer count + presence),
**discovery**, and **live chat** — the audio/video itself is ingested to and
served from an **external media server** (RTMP/WebRTC in, HLS/WebRTC out)
addressed by a per-stream secret `streamKey`. The media never flows through this
app.

Configure the media origins (the defaults are placeholders):
```
app.streaming.ingest-base=rtmp://your-ingest/live     # host publishes here (+ /{streamKey})
app.streaming.playback-base=https://your-cdn/live      # viewers play here   (+ /{streamId}.m3u8)
```

## Endpoints

| Method & path | Body | Does |
|---|---|---|
| `POST /streams` | `{ "title": "…", "description": "…" }` | Go live. Response carries the **host-only** `ingestUrl`. → `201 LiveStreamResponse` |
| `GET /streams/live` | — | Discover currently-live streams, most-watched first. → `[LiveStreamResponse]` |
| `GET /streams/{id}` | — | Stream detail (the host also sees `ingestUrl`). → `LiveStreamResponse` |
| `POST /streams/{id}/end` | — | Host ends the stream. → `204` |
| `POST /streams/{id}/join` | — | Join as a viewer (registers presence, returns `playbackUrl`). → `LiveStreamResponse` |
| `POST /streams/{id}/leave` | — | Leave. → `204` |
| `POST /streams/{id}/chat` | `{ "text": "…" }` | Send a live-chat line to viewers. → `200` |

### LiveStreamResponse
```json
{
  "id": "<uuid>", "hostId": "<uuid>", "title": "…", "description": "…",
  "status": "LIVE", "playbackUrl": "https://your-cdn/live/<id>.m3u8",
  "ingestUrl": "rtmp://your-ingest/live/<streamKey>",
  "viewerCount": 342, "startedAt": "…", "endedAt": null,
  "shareUrl": "https://irc.example.com/live/<id>"
}
```
`ingestUrl` is **host-only** (carries the secret key); it is `null` for viewers.

`shareUrl` (`{irc.base-url}/live/{id}`) is the safe-for-anyone watch link: the
frontend route behind it resolves the stream via `GET /streams/{id}` and calls
`join` — it never exposes the stream key.

## Realtime (multiplexed on `/messaging/stream`)

| event | payload | delivered to |
|---|---|---|
| `stream.viewer` | `stream`, `userId`, `memberChange` (`JOINED`/`LEFT`) | current viewers + host |
| `stream.chat` | `streamChat` (`LiveChatMessage`) | current viewers + host |
| `stream.ended` | `stream` | current viewers + host |

The `stream` object inside `stream.viewer` carries the **already-updated**
`viewerCount`, so the viewer counter updates in realtime on every join/leave —
render it directly, no re-fetch needed.

`LiveChatMessage`: `{ "streamId", "userId", "username", "text", "sentAt" }`.
(`stream.started` is reserved for a future followers fan-out.)

## Notes
- Only the host can `end` a stream; ending marks every viewer inactive.
- You must `join` (or be the host) before you can `chat`; live chat is
  rate-limited (20 / 10s) and **ephemeral** (broadcast only, not persisted, so
  late joiners don't replay it).
- `viewerCount` is the current active-viewer count; a peak is tracked internally.
