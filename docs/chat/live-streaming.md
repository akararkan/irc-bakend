# Live streaming

Go live to an audience over the existing SSE stream. The server owns the **stream
lifecycle**, a **live viewer registry** (drives the viewer count + presence),
**discovery**, and **live chat** — the audio/video itself is ingested to and
served from an **external media server** (MediaMTX) addressed by a per-stream
secret `streamKey`. The media never flows through this app.

## The media plane — four surfaces, one stream-id path

> **Why the browser must use WHIP, not RTMP.** Browsers **cannot** publish RTMP —
> only native encoders (OBS, ffmpeg) can. To go live straight from the app camera
> (the TikTok / YouTube / Facebook experience) the browser publishes over
> **WebRTC / WHIP**. A stream whose only "go live" is RTMP will never appear —
> nothing publishes, so the HLS playlist 404s forever.

| Surface | URL (per stream) | Who | Notes |
|---|---|---|---|
| **WHIP** (WebRTC in) | `whipUrl` = `http://localhost:8889/{id}/whip?pass={key}` | **host** | Browser camera goes live here. Host-only. |
| **RTMP** (in) | `ingestUrl` = `rtmp://localhost:1935/{id}?user=publisher&pass={key}` | **host** | OBS / ffmpeg / desktop encoders. Host-only. |
| **WHEP** (WebRTC out) | `whepUrl` = `http://localhost:8889/{id}/whep` | viewers | Sub-second latency — the "live" feel. Public. |
| **HLS** (out) | `playbackUrl` = `http://localhost:8888/{id}/index.m3u8` | viewers | Universal reach, higher latency. Public. fMP4/CMAF. |

The MediaMTX path is the **stream id** (a UUID). Publish URLs carry the secret
`streamKey` as `?pass=`; MediaMTX forwards every publish/read to the backend
auth hook (`MediaAuthController` → `LiveStreamService.authorizeMediaAccess`),
which allows a **publish** only with the right key and **reads** for any LIVE
stream. Config: `mediamtx.yml` + the `mediamtx` service in `docker-compose.yml`.

Configure the media origins (defaults target the local `mediamtx` service):
```
app.streaming.webrtc-base=http://localhost:8889     # WHIP publish + WHEP playback
app.streaming.ingest-base=rtmp://localhost:1935     # RTMP publish (OBS/ffmpeg)
app.streaming.playback-base=http://localhost:8888   # HLS playback
app.streaming.auth-secret=dev-media-auth-secret     # MUST match mediamtx.yml authHTTPAddress
app.streaming.recordings-dir=./recordings           # where MediaMTX writes recordings (bind-mounted)
app.streaming.control-api-base=http://localhost:9997 # MediaMTX Control API — per-stream record on/off
app.streaming.recording.default-on=false            # default when POST /streams omits `record`
```

## Endpoints

| Method & path | Body | Does |
|---|---|---|
| `POST /streams` | `{ "title": "…", "description": "…", "record": true }` | Go live. `record` (optional, default off) records the broadcast to disk for later download. Response carries the **host-only** `whipUrl` + `ingestUrl`. → `201 LiveStreamResponse` |
| `GET /streams/live` | — | Discover currently-live streams, most-watched first. → `[LiveStreamResponse]` |
| `GET /streams/live/following` | — | The **"following is live" row** — streams from people you follow that are live now, most-recently-started first. → `[LiveStreamResponse]` (frontend guide: [`live-row-frontend.md`](./live-row-frontend.md)) |
| `GET /streams/{id}` | — | Stream detail (the host also sees `whipUrl`/`ingestUrl`). → `LiveStreamResponse` |
| `POST /streams/{id}/end` | — | Host ends the stream. → `204` |
| `POST /streams/{id}/join` | — | Join as a viewer (registers presence, returns `playbackUrl`/`whepUrl`). → `LiveStreamResponse` |
| `POST /streams/{id}/leave` | — | Leave. → `204` |
| `POST /streams/{id}/chat` | `{ "text": "…" }` | Send a live-chat line to viewers. → `200` |
| **Management (owner-only)** | | |
| `GET /streams/mine?page=&size=` | — | My streams (LIVE + ENDED), newest-first. → `Page<LiveStreamResponse>` |
| `PATCH /streams/{id}` | `{ "title"?, "description"? }` | Edit metadata. While LIVE, viewers get `stream.updated`. → `LiveStreamResponse` |
| `DELETE /streams/{id}` | — | Delete the stream + its recording (ends it first if live). → `204` |
| **Recording (owner-only)** | | |
| `GET /streams/{id}/recording` | — | Recording manifest — status + downloadable parts. → `RecordingInfo` |
| `DELETE /streams/{id}/recording` | — | Delete only the recording, keep the stream. → `204` |
| `GET /streams/{id}/recording/download?part=` | — | Download the recording (`video/mp4`, attachment). `part` optional for a single file. → `200` bytes |

### LiveStreamResponse
```json
{
  "id": "<uuid>", "hostId": "<uuid>",
  "hostUsername": "alice", "hostDisplayName": "Alice Ng", "hostAvatarUrl": "https://…",
  "title": "…", "description": "…",
  "status": "LIVE",
  "playbackUrl": "http://localhost:8888/<id>/index.m3u8",
  "whepUrl":     "http://localhost:8889/<id>/whep",
  "whipUrl":     "http://localhost:8889/<id>/whip?pass=<streamKey>",
  "ingestUrl":   "rtmp://localhost:1935/<id>?user=publisher&pass=<streamKey>",
  "viewerCount": 342, "startedAt": "…", "endedAt": null,
  "shareUrl": "https://irc.example.com/live/<id>",
  "recordingStatus": "AVAILABLE",
  "recordingAvailable": true,
  "recordingDownloadUrl": "/api/v1/streams/<id>/recording/download"
}
```
`recordingStatus` is the recording lifecycle (`DISABLED` when off, `RECORDING`
while live, `AVAILABLE`/`EMPTY` after end, `DELETED` once removed).
`recordingAvailable` is `true` only when a finished recording exists;
`recordingDownloadUrl` is the owner-only API path to fetch it — **omitted from the
JSON** when there's nothing to download (the API drops null fields, so read it as
"absent ⇒ none"). These come straight off the record (no disk I/O) so they ride on
every `LiveStreamResponse`.
`hostUsername` / `hostDisplayName` / `hostAvatarUrl` carry the streamer's identity
so a **live row** (the TikTok / Instagram rail of who's live) renders the avatar ring
and `@name` straight from this payload — no extra per-card user fetch. They are
present on the read/discovery endpoints (`GET /streams/live`,
`GET /streams/live/following`, `GET /streams/{id}`), the go-live/join responses, and
the `stream.started` fan-out event; they are omitted (null) on the high-frequency
`stream.viewer` / `stream.chat` / `stream.ended` events, where the client already has
the host from when it joined. `hostAvatarUrl` is the host's uploaded avatar — the
**same source every avatar in the app uses** (`User.getProfileImage()`); it is
`null`/absent only when the host has set no avatar. It may be a relative path
(prefix your API/asset base) or absolute.

`whipUrl` and `ingestUrl` are **host-only** (they carry the secret key); both are
`null` for viewers. `playbackUrl`/`whepUrl` are safe for anyone.

`shareUrl` (`{irc.base-url}/live/{id}`) is the safe-for-anyone watch link: the
frontend route behind it resolves the stream via `GET /streams/{id}` and calls
`join` — it never exposes the stream key. `irc.base-url` must be the real **web/
frontend origin** (dev default `http://localhost:5173`; set `IRC_BASE_URL` in
prod), otherwise every shared live link is dead.

## Going live from the browser (WHIP)

> **Cold-camera track race.** MediaMTX freezes a WebRTC session's track list
> `webrtcTrackGatherTimeout` after the peer connection is up. Chrome's first
> *encoded* H264 frame from a cold camera can arrive after the default **2s**,
> which freezes the session as **audio-only** — viewers and the recorder then get
> sound with no picture (a black download). `mediamtx.yml` sets
> `webrtcTrackGatherTimeout: 10s` to cover cold-camera encoder start-up. On the
> client, pull a real decoded frame through a detached `<video>` before sending
> the SDP offer to narrow the race further.

After `POST /streams`, publish the camera to `whipUrl`. Preferring **H264** keeps
HLS working too (WHEP is codec-agnostic):
```js
async function goLive(whipUrl) {
  const media = await navigator.mediaDevices.getUserMedia({ video: {width:1280,height:720}, audio: true });
  const pc = new RTCPeerConnection();
  media.getTracks().forEach(t => pc.addTrack(t, media));

  // prefer H264 so the HLS remux works (optional; WHEP plays anything)
  const vtx = pc.getTransceivers().find(t => t.sender.track?.kind === 'video');
  const caps = RTCRtpSender.getCapabilities('video');
  if (vtx?.setCodecPreferences && caps) {
    const h264 = caps.codecs.filter(c => /H264/i.test(c.mimeType));
    if (h264.length) vtx.setCodecPreferences([...h264, ...caps.codecs.filter(c => !/H264/i.test(c.mimeType))]);
  }

  await pc.setLocalDescription(await pc.createOffer());
  await new Promise(r => pc.iceGatheringState === 'complete' ? r()
    : pc.addEventListener('icegatheringstatechange', () => pc.iceGatheringState === 'complete' && r()));

  const res = await fetch(whipUrl, { method:'POST', headers:{'Content-Type':'application/sdp'}, body: pc.localDescription.sdp });
  await pc.setRemoteDescription({ type:'answer', sdp: await res.text() });
  return { pc, media };            // pc.close() + media.getTracks().forEach(t=>t.stop()) to end
}
```

## Watching (WHEP first, HLS fallback)

```js
// WHEP — sub-second latency, pure WebRTC
async function watchWhep(whepUrl, videoEl) {
  const pc = new RTCPeerConnection();
  pc.addTransceiver('video', {direction:'recvonly'});
  pc.addTransceiver('audio', {direction:'recvonly'});
  pc.ontrack = e => videoEl.srcObject = e.streams[0];
  await pc.setLocalDescription(await pc.createOffer());
  await new Promise(r => pc.iceGatheringState === 'complete' ? r()
    : pc.addEventListener('icegatheringstatechange', () => pc.iceGatheringState === 'complete' && r()));
  const res = await fetch(whepUrl, { method:'POST', headers:{'Content-Type':'application/sdp'}, body: pc.localDescription.sdp });
  await pc.setRemoteDescription({ type:'answer', sdp: await res.text() });
  return pc;
}

// HLS — universal reach (import hls.js)
function watchHls(playbackUrl, videoEl) {
  if (videoEl.canPlayType('application/vnd.apple.mpegurl')) { videoEl.src = playbackUrl; return; } // Safari
  const hls = new Hls({ lowLatencyMode: true });
  hls.loadSource(playbackUrl); hls.attachMedia(videoEl); return hls;
}
```

> **Try it now without touching the frontend:** open `docs/chat/live-tester.html`
> from a `localhost` origin (e.g. `python3 -m http.server` in that folder, then
> `http://localhost:8000/live-tester.html`). It logs in, goes live from your
> camera via WHIP, and plays it back via WHEP/HLS. `file://` won't work — the
> camera needs a secure context (`localhost` or HTTPS).

### Production note
`webrtcEncryption: no` and plain-HTTP WHIP/WHEP are for **local dev**. In
production put TLS in front (WHIP/WHEP over HTTPS), set `mediamtx.yml`
`webrtcAdditionalHosts` to the server's public IP/hostname, publish `8189/udp`
on the firewall, and add a TURN server (`webrtcICEServers2`) for viewers behind
strict NATs.

## Realtime (multiplexed on `/messaging/stream`)

| event | payload | delivered to |
|---|---|---|
| `stream.started` | `stream` | **host's followers** (fan-out) |
| `stream.viewer` | `stream`, `userId`, `memberChange` (`JOINED`/`LEFT`) | current viewers + host |
| `stream.chat` | `streamChat` (`LiveChatMessage`) | current viewers + host |
| `stream.updated` | `stream` | current viewers + host |
| `stream.ended` | `stream` | current viewers + host **+ host's followers** (fan-out) |

The `stream` object inside `stream.viewer` carries the **already-updated**
`viewerCount`, so the viewer counter updates in realtime on every join/leave —
render it directly, no re-fetch needed. `stream.updated` fires when the host
edits the title/description mid-broadcast — patch the card in place.

`stream.started` and `stream.ended` are fanned out to the host's **followers**
(mirror images), so a follower's "following is live" rail adds the card when a
followed host goes live and drops it when they end — no polling. `stream.ended`
carries the already-`ENDED` `stream`; match and remove by `stream.id`.
`stream.viewer` is **not** fanned to followers (it fires on every join/leave — a
per-stream fan-out storm), so a rail's viewer counts are approximate between
refreshes by design; the exact count is live on the watch page (a participant).

`LiveChatMessage`: `{ "streamId", "userId", "username", "text", "sentAt" }`.

## Recording & management (owner-only)

The host can record a broadcast and download it afterwards, and manage their
stream catalogue.

**Recording is per-path opt-in.** Pass `"record": true` to `POST /streams`
(default off, configurable via `app.streaming.recording.default-on`). MediaMTX
records **nothing** by default (`mediamtx.yml` → `record: no`); on an opted-in
go-live the backend (`MediaControlClient`) calls the MediaMTX **Control API**
(`:9997`, `POST /v3/config/paths/add/{streamId}` → `record: true`) to record
**only that one path** — so streams the host didn't opt into **never touch disk**
(no wasted writes, no delete-race orphans, no privacy window). The API call is
best-effort: if it fails the stream still goes live, it just won't be recorded.

When a path is recording, MediaMTX writes fMP4 under `<recordings-dir>/<stream-id>/`
(bind-mounted from `./recordings`), and — because the app runs on the host — it
serves the owner a download **straight from that directory**, no media round trip.
On end/delete the backend removes the per-path config (stops recording, frees the
entry); recorded files stay until the owner deletes them.

Lifecycle in `recordingStatus`: `RECORDING` while live → `AVAILABLE` (parts on
disk) or `EMPTY` (nothing published) on end; `DISABLED` when the host didn't opt
in (nothing was recorded); `DELETED` after the owner removes it.

`RecordingInfo` (from `GET /streams/{id}/recording`):
```json
{
  "streamId": "<uuid>", "status": "AVAILABLE", "available": true,
  "partCount": 1, "totalBytes": 48210432,
  "primaryFile": "2026-07-27_09-15-03-000000.mp4",
  "parts": [
    { "file": "2026-07-27_09-15-03-000000.mp4", "sizeBytes": 48210432,
      "modifiedAt": "…", "downloadUrl": "/api/v1/streams/<id>/recording/download?part=<file>" }
  ]
}
```
A long segment window keeps a typical broadcast in a **single** part, so
`GET …/recording/download` (no `part`) just returns it. If a broadcast split into
several parts (MediaMTX restarts its recorder when the track set changes — e.g. a
browser camera whose audio registers a beat before video), the no-`part` download
returns the **`primaryFile`** — the largest part, i.e. the one carrying video — so
"download my recording" always yields the watchable file, never the tiny
audio-only prelude. Name a `part` to fetch a specific one. The download is authed
(Bearer) and returns `video/mp4` as an attachment — fetch it as a Blob, don't use
a plain `<a href>`.

**Management.** `GET /streams/mine` is the owner's catalogue (LIVE + ENDED,
newest-first, paged off `idx_stream_host`). `PATCH /streams/{id}` edits
title/description (a live edit emits `stream.updated`). `DELETE /streams/{id}`
removes the stream, its viewers, and its recording (ending it first if live).

**Complexity.** `mine` = O(log N + pageSize); `PATCH` = O(1); `DELETE` = O(1) row
+ O(viewers) presence purge (indexed) + O(parts) file cleanup; `recording`
manifest = O(parts); download = O(bytes).

## Notes
- Only the host can `end` a stream; ending marks every viewer inactive.
- You must `join` (or be the host) before you can `chat`; live chat is
  rate-limited (20 / 10s) and **ephemeral** (broadcast only, not persisted, so
  late joiners don't replay it).
- `viewerCount` is the current active-viewer count; a peak is tracked internally.
