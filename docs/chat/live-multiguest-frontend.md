# Live multi-guest, reactions & gifts — frontend guide

A practical guide to building the **TikTok-style "go live together"** UI on top of
the backend that already exists. The backend contract is in
[`live-streaming.md`](./live-streaming.md#multi-guest-stage-reactions--gifts) — this
file is the *how do I build the screen* companion for the React app (`ika`,
`/Users/khi/Documents/ika`).

You already have single-host live working (`src/pages/LivePage.jsx`,
`src/lib/liveWebrtc.js`, the `chat.streams.*` API client, the SSE `subscribe`). This
guide **adds to those files** — nothing here throws them away.

---

## 1. The one idea that makes everything click

Single-host live = **one publisher** (the host) that everyone watches.

Multi-guest live = **several publishers**. The host publishes their camera as before;
each guest who comes up publishes **their own camera to their own URL**; and
**everyone (host, guests, viewers) subscribes to every publisher**. The stage is
just "the set of people currently publishing".

```
        publishes                     subscribes (WHEP)
host   ──▶ …/{streamId}/whip     host  ◀── every guest's …/{path}/whep
guest1 ──▶ …/{path1}/whip        guest ◀── host + every other guest
guest2 ──▶ …/{path2}/whip        viewer◀── host + every guest
```

So the watch screen becomes a **grid of video tiles**, one per stage member. Your
job on the frontend is:

1. keep that grid in sync with the `stream.stage` roster the server pushes, and
2. when **you** are a guest, publish your own camera (exactly the way the host
   already does).

Both reuse code you already have: `publishCamera()` and `playWhep()` from
`src/lib/liveWebrtc.js`.

The stage is capped at **6 guests** (server config `max-guests`); the host is always
on and doesn't count. `stage.guestCount >= stage.maxGuests` ⇒ the "Go up" buttons
should read "Stage full".

---

## 2. Register the new realtime events (do this first)

The SSE client (`src/api/chat.js`) only delivers events whose names are in
`EVENT_HANDLER` — **an unlisted event is silently dropped**. Add the six new names,
all routed to the existing `onStream` handler (LivePage already listens through the
`onAny` firehose):

```js
// src/api/chat.js — EVENT_HANDLER map, next to the other stream.* lines
'stream.stage':          'onStream',
'stream.stage.request':  'onStream',
'stream.stage.invite':   'onStream',
'stream.stage.grant':    'onStream',
'stream.reaction':       'onStream',
'stream.gift':           'onStream',
```

Then adapt their payloads in `adaptEvent()` (same file, in the live-streaming
block). The server sends each event's data under a single key:

```js
case 'stream.stage':
  return { type, stage: d.stage, streamId: d.stage?.streamId || d.streamId || null }
case 'stream.stage.request':   // host only — a viewer raised their hand
case 'stream.stage.invite':    // invitee only — the host invited you (carries the host member)
case 'stream.stage.grant':     // you only — your seat changed; carries YOUR publish creds, or status REMOVED
  return { type, stageMember: d.stageMember, streamId: d.stageMember?.streamId || null, userId: d.userId || null }
case 'stream.reaction':
  return { type, streamReaction: d.streamReaction, streamId: d.streamReaction?.streamId || null, userId: d.userId || null }
case 'stream.gift':
  return { type, streamGift: d.streamGift, streamId: d.streamGift?.streamId || null, userId: d.userId || null }
```

That's the entire wiring change. Everything else is UI.

---

## 3. API client additions

Add these to the `streams` block in `src/api/chat.js`. They're thin passthroughs —
the shapes are documented in `live-streaming.md`.

```js
stage: {
  get:       (id)          => http.get(`/api/v1/streams/${id}/stage`),
  requests:  (id)          => http.get(`/api/v1/streams/${id}/stage/requests`), // host
  requestUp: (id)          => http.post(`/api/v1/streams/${id}/stage/requests`, {}),
  approve:   (id, userId)  => http.post(`/api/v1/streams/${id}/stage/requests/${userId}/approve`, {}),
  deny:      (id, userId)  => http.post(`/api/v1/streams/${id}/stage/requests/${userId}/deny`, {}),
  invite:    (id, userId)  => http.post(`/api/v1/streams/${id}/stage/invites/${userId}`, {}),
  accept:    (id)          => http.post(`/api/v1/streams/${id}/stage/accept`, {}),   // returns YOUR creds
  decline:   (id)          => http.post(`/api/v1/streams/${id}/stage/decline`, {}),
  leave:     (id)          => http.post(`/api/v1/streams/${id}/stage/leave`, {}),
  remove:    (id, userId)  => http.del(`/api/v1/streams/${id}/stage/${userId}`),      // host
  mute:      (id, userId)  => http.post(`/api/v1/streams/${id}/stage/${userId}/mute`, {}),   // host
  unmute:    (id, userId)  => http.post(`/api/v1/streams/${id}/stage/${userId}/unmute`, {}), // host
},
react: (id, type)   => http.post(`/api/v1/streams/${id}/reactions`, type ? { type } : {}),
gifts: {
  catalog: ()             => http.get('/api/v1/streams/gifts/catalog'),
  send:    (id, giftId)   => http.post(`/api/v1/streams/${id}/gifts`, { giftId }),
  top:     (id, limit=10) => http.get(`/api/v1/streams/${id}/gifts/top`, { limit }),
},
```

---

## 4. The stage grid — keep tiles in sync with the roster

Hold the roster in state and re-render the grid from it. On mount, fetch it once
(`stage.get`), then let `stream.stage` frames replace it.

```jsx
const [stage, setStage] = React.useState(null)   // StageState | null

// initial load
React.useEffect(() => {
  if (!streamId) return
  api.chat.streams.stage.get(streamId).then(setStage).catch(() => {})
}, [streamId])

// live updates (inside the existing subscribe((evt) => …) in StreamRoom)
if (evt.type === 'stream.stage' && evt.stage) setStage(evt.stage)
```

`stage.members` is `[host, ...guests]` — render a tile per member. **Your own tile is
your local camera preview** (see §5); **every other member's tile is a WHEP
subscription** to `member.whepUrl`. A member's `avatarUrl` is a **relative** backend
path (like `hostAvatarUrl` today) — run it through your `assetUrl()` before using it,
or the avatar 404s; keep an initials fallback since it can be null.

A small hook that manages the WHEP connections and the `<video>` refs for the
*remote* members. It diffs the roster: open a connection for a member you don't have
yet, close one for a member who left, and — crucially — **apply the mute flag**:

```jsx
function useStageVideos(members, myId) {
  const conns = React.useRef(new Map())   // userId -> { handle, el, dead }
  const [, force] = React.useReducer(x => x + 1, 0)

  React.useEffect(() => {
    const want = new Map((members || [])
      .filter(m => String(m.userId) !== String(myId) && m.whepUrl)  // skip my own tile
      .map(m => [String(m.userId), m]))

    // close connections for members who left
    for (const [uid, c] of conns.current) {
      if (!want.has(uid)) { c.dead = true; c.handle?.stop?.(); conns.current.delete(uid) }
    }
    // open connections for new members; enforce mute on all
    want.forEach((m, uid) => {
      let c = conns.current.get(uid)
      if (!c) {
        const el = document.createElement('video')
        el.autoplay = true; el.playsInline = true
        c = { el, handle: null, dead: false }
        conns.current.set(uid, c)
        // RETRY: the roster frame can beat the guest's first publish, so WHEP may
        // 404 for a beat. Keep trying until it connects (or the member leaves).
        const dial = async () => {
          while (!c.dead && !c.handle) {
            try { c.handle = await playWhep(m.whepUrl, el) }
            catch { await new Promise(r => setTimeout(r, 1500)) }
          }
        }
        dial()
        force()
      }
      // MUTE ENFORCEMENT: a host-muted guest is silenced on EVERY client.
      c.el.muted = !!m.muted
    })
  }, [members, myId])

  React.useEffect(() => () => { conns.current.forEach(c => { c.dead = true; c.handle?.stop?.() }); conns.current.clear() }, [])
  return conns.current   // userId -> { el, muted }
}
```

Then mount each remote member's `<video>` element into its tile (attach `c.el` via a
ref callback), and render your own `<video ref={myTileRef} muted>` for your local
camera. Layout is up to you — a CSS grid of `lv-tile`s that flexes from 1 up to 7
cells is plenty.

> **Why `el.muted = m.muted` is the whole mute story.** There is no server-side
> per-track mute. The backend's `muted` flag is the single source of truth and it
> rides every roster frame; because *every* client mutes that member's audio
> locally, the guest is silent for everyone. Do not skip this line — it *is* the
> mute feature on the receive side.

---

## 5. Being a guest — publish your own camera

This is the part that feels new, but it's the **same `publishCamera()` the host
already uses** — just pointed at *your* WHIP URL, which the server hands you
privately. You never see another guest's key, and viewers never see yours.

You become a guest one of two ways; both give you a `StageMember` **with**
`whipUrl`:

- **You accepted an invite** → the `stage.accept()` response body is your member.
- **The host approved your hand-raise** → a `stream.stage.grant` event arrives with
  your member.

Handle both the same way:

```jsx
const guestCastRef = React.useRef(null)  // { pc, media, stop } while I'm up

async function goUp(member) {                 // member.whipUrl is present
  if (!member?.whipUrl || guestCastRef.current) return
  const handle = await publishCamera(member.whipUrl, {
    onLocalStream: (media) => { if (myTileRef.current) { myTileRef.current.srcObject = media; myTileRef.current.muted = true } },
    onState: (s) => { /* 'connected' → you're live on stage */ },
  })
  guestCastRef.current = handle
}

function stepDown() {                          // leaving the stage
  guestCastRef.current?.stop?.()
  guestCastRef.current = null
}
```

Wire it to the events (inside the same `subscribe` handler):

```jsx
if (evt.type === 'stream.stage.grant' && evt.stageMember) {
  const m = evt.stageMember
  if (String(m.userId) !== String(myId)) return
  if (m.status === 'REMOVED') { stepDown(); showToast('You were taken off the stage') }
  else if (m.whipUrl)         { goUp(m) }      // host approved you
}
```

For the invite-accept path, call `goUp(await api.chat.streams.stage.accept(streamId))`.

Two more must-dos while you're a guest:

- **Honor your own mute.** When a `stream.stage` roster shows *your* member with
  `muted: true`, disable your mic track so you actually go quiet at the source:
  ```jsx
  const me = stage?.members?.find(m => String(m.userId) === String(myId))
  const micTrack = guestCastRef.current?.media?.getAudioTracks?.()[0]
  if (micTrack) micTrack.enabled = !(me?.muted)
  ```
- **Step down on unmount / leaving** — call `stepDown()` and (best-effort)
  `api.chat.streams.stage.leave(streamId)` so your seat frees up and your tile
  disappears for everyone.

---

## 6. Host controls

The host's extra UI, all one-liners against the API. Refresh comes over
`stream.stage` (roster) and `stream.stage.request` (queue) — you don't need to
re-fetch after a mutation, the broadcast will arrive.

```jsx
// The request queue (host): seed from stage.requests(), then append on stream.stage.request
const [requests, setRequests] = React.useState([])
React.useEffect(() => {
  if (!isHost) return
  api.chat.streams.stage.requests(streamId).then(setRequests).catch(() => {})
}, [isHost, streamId])
// in subscribe:
if (evt.type === 'stream.stage.request' && evt.stageMember)
  setRequests(prev => [...prev.filter(r => r.userId !== evt.stageMember.userId), evt.stageMember])

// Actions (host):
api.chat.streams.stage.approve(streamId, userId)   // bring them up  → they get creds via stream.stage.grant
api.chat.streams.stage.deny(streamId, userId)      // reject the hand-raise
api.chat.streams.stage.invite(streamId, userId)    // invite a specific viewer
api.chat.streams.stage.mute(streamId, userId)      // silence a guest for everyone
api.chat.streams.stage.unmute(streamId, userId)
api.chat.streams.stage.remove(streamId, userId)    // hard take-down
```

Clear a request from your local queue when the roster shows that user is now
`ACTIVE` (approved) or when you deny them.

For "invite a viewer", the userIds come from the presence lines you already collect
in the chat log (`stream.viewer` events) — put an "Invite up" affordance on each
watcher row.

## 6b. Being invited (viewer side)

An invited viewer gets `stream.stage.invite` carrying the **host** member (so you can
say who invited them):

```jsx
if (evt.type === 'stream.stage.invite' && evt.stageMember) {
  const host = evt.stageMember
  // show an accept/decline prompt, e.g. a toast with two buttons:
  //   accept  → const me = await api.chat.streams.stage.accept(streamId); goUp(me)
  //   decline → api.chat.streams.stage.decline(streamId)
}
```

If the invite can arrive while the user is *not* on the live page, carry `streamId`
(it's on `stageMember.streamId`) and route them to `/live/{streamId}` first.

---

## 7. Reactions — floating hearts

Two halves: **send** on tap, **animate** on every `stream.reaction`.

```jsx
// send (throttle client-side; the server also caps 30 / 10s)
const lastTap = React.useRef(0)
function tapHeart(type = 'LIKE') {
  const now = Date.now()
  if (now - lastTap.current < 180) { spawnFloater(type); return } // animate locally, skip the call
  lastTap.current = now
  spawnFloater(type)                       // optimistic — your own heart shows instantly
  api.chat.streams.react(streamId, type).catch(() => {})
}

// receive (in subscribe): everyone else's taps
if (evt.type === 'stream.reaction' && evt.streamReaction) {
  if (String(evt.streamReaction.userId) !== String(myId)) spawnFloater(evt.streamReaction.type)
}
```

`spawnFloater(type)` is pure CSS/animation — push an item into a short-lived array,
render a `<span>` that drifts up and fades, and drop it after ~2.5s. Reaction types:
`LIKE LOVE CLAP FIRE WOW` (map each to an emoji/sprite; unknown ⇒ heart).

---

## 8. Gifts — symbolic, with a "top supporters" board

Gifts are **symbolic only** (no wallet, no money — `coins` is a leaderboard score).
Three pieces:

```jsx
// 1) the picker — fetch once
const [catalog, setCatalog] = React.useState([])
React.useEffect(() => { api.chat.streams.gifts.catalog().then(setCatalog).catch(() => {}) }, [])
//    each entry: { id, name, iconKey, coins } — render iconKey → your emoji/sprite

// 2) send
async function sendGift(giftId) {
  try { await api.chat.streams.gifts.send(streamId, giftId) }  // animation arrives via stream.gift
  catch (e) { showToast(chatError(e, 'Could not send the gift')) }
}

// 3) receive → animate + update the board (in subscribe)
if (evt.type === 'stream.gift' && evt.streamGift) {
  const g = evt.streamGift
  spawnGift(g.iconKey, g.coins)                                   // big center-screen animation
  setBoard(prev => upsertSupporter(prev, g.senderId, g.senderTotalCoins, g.senderUsername))
}
```

The leaderboard survives reloads because the backend persists the per-sender tally —
seed it with `api.chat.streams.gifts.top(streamId, 10)` on mount, then keep it fresh
from the `senderTotalCoins` on each `stream.gift`. `upsertSupporter` just replaces
that sender's row and re-sorts by coins.

Bigger gifts (`coins`) should get a bigger/longer animation — that's the only thing
`coins` drives on screen besides the ranking.

---

## 9. Teardown & edge cases

- **`stream.ended`** already tears the whole room down (you handle it today). Also
  `stepDown()` your guest publisher and stop every WHEP tile in that handler.
- **Leaving the page**: stop your guest publisher and every tile connection on
  unmount; call `stage.leave` if you were up (mirror the existing viewer `leave`
  `pagehide` beacon pattern).
- **Stage full**: `stage.guestCount >= stage.maxGuests` → disable "Go up" / show a
  "Stage full (6)" hint. The server also rejects with `400` ("The stage is full").
- **A guest with no camera permission**: `publishCamera` rejects exactly as it does
  for the host — reuse the same `NotAllowedError` messaging you already have.
- **You're the host**: your own tile is your existing camera preview
  (`publishCamera(stream.whipUrl)`) — nothing changes there; you simply *also* now
  subscribe to guest tiles and render the host controls.

---

## 10. Manual test plan (two browsers)

The media plane needs the local `mediamtx` container up and the backend running
(`docs/chat/live-streaming.md` covers the stack). Then:

1. **Browser A** (host): go live from the camera as today.
2. **Browser B** (a different account): open the stream, **Raise hand**.
3. **A** sees the request in the queue → **Approve**. **B**'s camera should appear as
   a second tile on **both** screens within a second or two.
4. **A** taps **Mute** on B → B's audio goes silent on **A**, on B's own tile
   indicator, and on any **Browser C** viewer. **Unmute** restores it.
5. **A** **invites** B down and up again via **Remove** / **Invite** → tiles add and
   drop on all screens.
6. **B** (or a viewer) taps hearts and sends a **Rose** → floaters/animation on all
   screens; **A**'s "top supporters" shows B climbing.
7. **A** **Ends** the stream → all tiles tear down everywhere.

Watch the browser console and the `mediamtx` container logs if a guest tile stays
black — the same cold-camera/WHIP notes from `live-streaming.md` apply to guests.

---

### Files you'll touch

| File | Change |
|---|---|
| `src/api/chat.js` | add the 6 event names to `EVENT_HANDLER`, their `adaptEvent` cases, and the `stage`/`react`/`gifts` API methods |
| `src/pages/LivePage.jsx` | stage grid + `useStageVideos`, guest `goUp/stepDown`, host controls, reactions & gifts UI, teardown |
| `src/lib/liveWebrtc.js` | **no change** — reuse `publishCamera` / `playWhep` as-is |
| CSS | the tile grid, floating reactions, gift animation, leaderboard |
