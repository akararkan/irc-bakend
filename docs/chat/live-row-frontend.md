# Frontend guide — the "following is live" row

A horizontal rail of the people you follow who are **live right now** — the
TikTok / Instagram live row. Newest-live-first, each cell an avatar ring +
`@handle`. Tap one to open the stream.

Everything the row needs (avatar, name, title, viewer count) arrives in **one**
response — no per-cell user fetch.

---

## 1. The data

`api.streams.followingLive()` returns an array of cards already mapped by
`liveStreamFrom`. The fields the row uses:

| field | what |
|---|---|
| `id` | stream id → navigate to `/live/{id}` |
| `hostUsername` | raw handle, e.g. `alice` |
| `hostHandle` | normalized `@alice` (ready to render) |
| `hostDisplayName` | `First Last` |
| `hostAvatarUrl` | avatar for the ring — **may be `null`** (fall back to an initial) |
| `title` | stream title |
| `viewerCount` | current viewers |
| `time` | "2m" style relative label (from `startedAt`) |

> Host-only secrets (`whipUrl`, `ingestUrl`) are **null/absent for viewers** —
> the row never receives the stream key. Don't reference them here.

---

## 2. Fetch it

```js
import { chat } from '@/api/chat'   // wherever your api module lives

const cards = await chat.streams.followingLive()
// [] when you follow nobody who is live — render an empty state, not an error.
```

---

## 3. Render the row

```jsx
function LiveRow() {
  const [cards, setCards] = React.useState([])
  const navigate = useNavigate()

  // initial load
  React.useEffect(() => {
    chat.streams.followingLive().then(setCards).catch(() => setCards([]))
  }, [])

  // keep it live over the existing SSE stream (see §4)
  React.useEffect(() => subscribe((evt) => {
    if (evt.type === 'stream.started' && evt.stream) {
      setCards(prev => [evt.stream, ...prev.filter(s => s.id !== evt.stream.id)])
    }
    if (evt.type === 'stream.ended') {
      setCards(prev => prev.filter(s => s.id !== evt.streamId))
    }
    if (evt.type === 'stream.viewer' && evt.stream) {
      setCards(prev => prev.map(s => (s.id === evt.stream.id ? { ...s, ...evt.stream } : s)))
    }
  }), [subscribe])

  if (!cards.length) return null   // nobody you follow is live

  return (
    <div className="live-row">
      {cards.map(s => (
        <button key={s.id} className="live-cell" onClick={() => navigate(`/live/${s.id}`)}>
          <span className="live-ring">
            {s.hostAvatarUrl
              ? <img src={s.hostAvatarUrl} alt={s.hostHandle} />
              : <span className="avatar-fallback">{(s.hostDisplayName || s.hostUsername || '?')[0]}</span>}
            <span className="live-badge">LIVE</span>
          </span>
          <span className="live-name">{s.hostHandle}</span>
          <span className="live-meta">{s.viewerCount} watching</span>
        </button>
      ))}
    </div>
  )
}
```

The avatar ring + `LIVE` badge is pure CSS — the pulsing ring is what signals
"live". `hostAvatarUrl` can be `null`, so always keep the initial fallback.

---

## 4. Keeping it live (no polling)

The row stays honest off the **one per-user SSE stream** (`/messaging/stream`),
the same socket chat uses. Three events matter — all already mapped through
`liveStreamFrom`, so `evt.stream` carries the same host fields as the fetch:

| event | do |
|---|---|
| `stream.started` | **prepend** the card (someone you follow just went live) |
| `stream.ended` | **remove** the card by `evt.streamId` |
| `stream.viewer` | **patch** `viewerCount` on the matching card (if present) |

Both `stream.started` **and** `stream.ended` are fanned out to the host's
followers server-side, so a follower's row adds the card the instant a followed
user goes live and drops it the instant they end — no refresh, no polling, no
reconcile timer needed. `stream.viewer` is **not** fanned to followers (it would
be a fan-out storm on every join/leave), so the rail's viewer counts are
approximate between refreshes — the exact count is live on the watch page. Don't
build the row's correctness on `stream.viewer`.

---

## 5. Two rows, one mapper

`followingLive()` and `live()` return the exact same card shape, so the same
cell component renders both:

| call | row |
|---|---|
| `chat.streams.followingLive()` | **Following** — people you follow, newest-live-first |
| `chat.streams.live()` | **Discover** — everyone live, most-watched-first |

---

## Checklist

- [ ] `hostAvatarUrl` may be `null` → initial fallback.
- [ ] Empty array = empty state, **not** an error.
- [ ] Never read `whipUrl`/`ingestUrl` in the row — they're host-only and absent.
- [ ] Subscribe to `stream.started` / `stream.ended` / `stream.viewer` to avoid polling.
- [ ] Tap → `/live/{id}` (the watch page calls `join` and plays WHEP/HLS).
