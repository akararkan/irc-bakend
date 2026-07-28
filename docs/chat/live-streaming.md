# Live streaming

Go live to an audience over the existing SSE stream. The server owns the **stream
lifecycle**, a **live viewer registry** (drives the viewer count + presence),
**discovery**, **live chat**, a **multi-guest stage** (co-hosts who come up and
talk beside the host), and live **reactions + gifts** — the audio/video itself is
ingested to and served from an **external media server** (MediaMTX) addressed by a
per-stream secret `streamKey`. The media never flows through this app.

> **Multi-guest / co-hosts, reactions and gifts** live in their own section below:
> [Multi-guest stage, reactions & gifts](#multi-guest-stage-reactions--gifts).
> Frontend build guide: [`live-multiguest-frontend.md`](./live-multiguest-frontend.md).

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

## Multi-guest stage, reactions & gifts

The host can bring viewers **up on stage** to talk beside them (the TikTok
"multi-guest" experience), mute them, and take them down; anyone watching can
**tap reactions** and send **symbolic gifts**. It all rides the same per-user SSE
stream and the same MediaMTX media plane. Frontend build guide:
[`live-multiguest-frontend.md`](./live-multiguest-frontend.md).

### The media model — many publishers, one audience

Single-host streaming has one publisher (the host, on the stream-id path). The
stage adds **more publishers**: each active guest publishes their own camera to
**their own media path** — a fresh id minted when they come up, gated by their
**own** secret key (never the host's stream key). Everyone (host, other guests,
viewers) then **subscribes to every active publisher**:

| Who | Publishes to (WHIP, secret) | Everyone watches (WHEP, public) |
|---|---|---|
| **Host** | `…/{streamId}/whip?pass={streamKey}` | `…/{streamId}/whep` |
| **Guest** | `…/{publishPath}/whip?pass={publishKey}` | `…/{publishPath}/whep` |

So a room with the host + 3 guests is **4 publishers and 4 WHEP subscriptions**
per viewer. The guest paths are ordinary MediaMTX paths (the `all_others`
catch-all), authorized by the same auth hook: `LiveStreamService.authorizeMediaAccess`
now also resolves a **guest** path (`authorizeGuestMediaAccess`) — a **publish** is
allowed only for an `ACTIVE` guest of a LIVE stream presenting their matching
`publishKey`; **reads** are public while the parent stream is LIVE. **Guests are
never recorded** — only the host path opts into recording, so the downloadable file
is still the host's broadcast (guest paths inherit `record: no`).

Stage size is capped by `app.streaming.stage.max-guests` (default **6**; the host
is always on and does not count).

### The stage lifecycle

```
viewer ──raise hand──▶ REQUESTED ──host approve──▶ ACTIVE ──(step down / removed / stream ends)──▶ REMOVED
host ──invite──▶ INVITED ──viewer accept──▶ ACTIVE
              (host deny / viewer decline) ──▶ DECLINED
```

Only an `ACTIVE` guest holds live publish credentials and counts against the cap.

### Endpoints (all under `/api/v1`, `isAuthenticated()`)

| Method & path | Body | Who | Does |
|---|---|---|---|
| `GET /streams/{id}/stage` | — | anyone | Current roster (host + active guests). → `StageState` |
| `POST /streams/{id}/stage/requests` | — | viewer | **Raise your hand** to come up. Host gets `stream.stage.request`. → `StageMember` (you) |
| `GET /streams/{id}/stage/requests` | — | **host** | Pending hand-raises (the request queue). → `[StageMember]` |
| `POST /streams/{id}/stage/requests/{userId}/approve` | — | **host** | Approve a hand-raise → guest goes up. → `StageMember` (public) |
| `POST /streams/{id}/stage/requests/{userId}/deny` | — | **host** | Deny a hand-raise. → `204` |
| `POST /streams/{id}/stage/invites/{userId}` | — | **host** | **Invite** a viewer up. They get `stream.stage.invite`. → `204` |
| `POST /streams/{id}/stage/accept` | — | invitee | Accept an invite → you go up. **Response carries YOUR publish creds.** → `StageMember` (with `whipUrl`/`publishKey`) |
| `POST /streams/{id}/stage/decline` | — | invitee | Decline an invite. → `204` |
| `POST /streams/{id}/stage/leave` | — | guest | **Step down** off the stage yourself. → `204` |
| `DELETE /streams/{id}/stage/{userId}` | — | **host** | **Take a guest down** (revokes creds + kicks their media session). → `204` |
| `POST /streams/{id}/stage/{userId}/mute` | — | **host** | **Mute** a guest for everyone. → `204` |
| `POST /streams/{id}/stage/{userId}/unmute` | — | **host** | **Unmute** a guest. → `204` |
| `POST /streams/{id}/reactions` | `{ "type": "LIKE" }` | watcher | Tap a floating reaction (`type` optional → `LIKE`). Broadcast, never stored. → `204` |
| `GET /streams/gifts/catalog` | — | anyone | The gift catalogue for the picker. → `[GiftCatalogEntry]` |
| `POST /streams/{id}/gifts` | `{ "giftId": "ROSE" }` | watcher | Send a **symbolic** gift. → `StreamGiftEvent` (incl. your new total) |
| `GET /streams/{id}/gifts/top?limit=10` | — | anyone | **Top supporters** leaderboard, biggest first. → `[GiftSupporter]` |

To send a reaction or gift you must be the host or a joined viewer; the stage
mutations enforce host-only (`403 ACCESS_FORBIDDEN`) or membership as noted.
Reactions are rate-limited 30/10s, gifts 10/10s, hand-raises 5/30s.

### Mute is authoritative, not just the host's speaker

There is **no per-track server-side mute** at this layer. A host mute is a
`muted` flag on the guest that rides **every `stream.stage` roster broadcast** — so
**every client** (viewers, the guest themselves, other guests) mutes that guest's
audio locally. A muted guest is therefore silent for *everyone*, and even a client
that ignores the flag is muted by everyone else. `DELETE …/stage/{userId}` is the
hard stop: it flips the guest to `REMOVED` (so the auth hook denies any re-publish),
best-effort **kicks** their live WebRTC session via the MediaMTX Control API, and
sends them a `stream.stage.grant` with `status: REMOVED` so their own client tears
its publisher down.

### Credential visibility (important)

A guest's `whipUrl` + `publishKey` are the secret **publish** credential. They are
returned **only to that guest** — in the `accept` response and the private
`stream.stage.grant` frame addressed to them — and are `null` (dropped by the
global `non_null` inclusion) everywhere else: the roster (`stream.stage`), the
request frame to the host, and the host's `approve` response all carry the
**public** member view (`whepUrl` only). The `whepUrl` (subscribe) is safe for
anyone.

### Realtime events (multiplexed on `/messaging/stream`)

| event | payload | delivered to |
|---|---|---|
| `stream.stage` | `stage` (`StageState`) | current viewers + host + active guests |
| `stream.stage.request` | `stageMember` (the requester), `userId` | **host only** |
| `stream.stage.invite` | `stageMember` (the host who invited), `userId` | **the invited viewer only** |
| `stream.stage.grant` | `stageMember` (**with creds** when going up; `status: REMOVED` on revoke) | **that one guest only** |
| `stream.reaction` | `streamReaction`, `userId` | current viewers + host + active guests |
| `stream.gift` | `streamGift`, `userId` | current viewers + host + active guests |

`stream.stage` is the whole panel — render the roster straight from `stage.members`
(host first, then guests) and the "stage is full" state from
`stage.guestCount >= stage.maxGuests`. A guest learns their own publish URL from
`stream.stage.grant` (host-approved) or the `accept` response (they accepted an
invite). `stream.ended` already tears the whole stage down — there is no separate
"stage cleared" frame.

### DTO shapes

```json
// StageState (GET /streams/{id}/stage, and stream.stage)
{
  "streamId": "<uuid>", "hostId": "<uuid>",
  "guestCount": 2, "maxGuests": 6,
  "members": [
    { "streamId":"<uuid>", "userId":"<host>", "username":"alice", "displayName":"Alice Ng",
      "avatarUrl":"https://…", "role":"HOST", "status":"ACTIVE", "muted":false,
      "whepUrl":"http://localhost:8889/<streamId>/whep", "joinedAt":"…" },
    { "streamId":"<uuid>", "userId":"<guest>", "username":"omar", "displayName":"Omar K",
      "avatarUrl":"https://…", "role":"GUEST", "status":"ACTIVE", "muted":true,
      "whepUrl":"http://localhost:8889/<publishPath>/whep", "joinedAt":"…" }
    // public members omit whipUrl / publishKey
  ]
}

// StageMember delivered privately to the guest (accept response / stream.stage.grant)
// — same shape PLUS the secret publish credential:
//   "whipUrl": "http://localhost:8889/<publishPath>/whip?pass=<publishKey>",
//   "publishKey": "<publishKey>"

// StreamReaction (stream.reaction)
{ "streamId":"<uuid>", "userId":"<uuid>", "type":"LIKE", "sentAt":"…" }

// StreamGiftEvent (POST /streams/{id}/gifts, and stream.gift)
{ "streamId":"<uuid>", "senderId":"<uuid>", "senderUsername":"omar",
  "senderAvatarUrl":"https://…", "giftId":"ROSE", "giftName":"Rose", "iconKey":"rose",
  "coins":1, "senderTotalCoins":12, "sentAt":"…" }

// GiftCatalogEntry (GET /streams/gifts/catalog)  ·  GiftSupporter (GET …/gifts/top)
{ "id":"ROSE", "name":"Rose", "iconKey":"rose", "coins":1 }
{ "userId":"<uuid>", "username":"omar", "displayName":"Omar K", "avatarUrl":"https://…",
  "coins":12, "giftCount":5 }
```

Gifts are **symbolic** — `coins` is a ranking score for the "top supporters"
board, never money; there is no wallet, purchase, or payout. The gift *animation*
is ephemeral (broadcast only), but the per-sender coin **tally persists** so the
leaderboard survives a reload and outlives the stream.

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
