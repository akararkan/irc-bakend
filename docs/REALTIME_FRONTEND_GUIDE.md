# Realtime & Concurrency — Frontend Integration Guide (React)

How the IRC backend pushes events to the React app, what the recent
concurrency / multi-thread changes mean for you, and the exact patterns
(hooks + reducers) to consume each stream safely.

> Audience: React frontend engineers. Code samples use plain React 18 +
> TypeScript — no framework lock-in. Adapt to your state library
> (Zustand / Redux / TanStack Query) as needed.

---

## Table of contents

1. [Why this exists](#1-why-this-exists)
2. [Endpoint inventory](#2-endpoint-inventory)
3. [Backend changes you MUST handle](#3-backend-changes-you-must-handle)
4. [The counter-delta model (critical)](#4-the-counter-delta-model-critical)
5. [Event-name casing footgun](#5-event-name-casing-footgun)
6. [Auth — passing the JWT to EventSource](#6-auth--passing-the-jwt-to-eventsource)
7. [The shared `useEventStream` hook](#7-the-shared-useeventstream-hook)
8. [Per-stream React patterns](#8-per-stream-react-patterns)
   - [Notifications](#81-notifications)
   - [Story tray](#82-story-tray)
   - [Per-post / per-question / per-research stream](#83-per-post--per-question--per-research-stream)
   - [Per-story stream](#84-per-story-stream)
9. [Story create — new `lifetimeHours` field](#9-story-create--new-lifetimehours-field)
10. [Handling 429 Too Many Requests](#10-handling-429-too-many-requests)
11. [Per-user SSE connection cap (5)](#11-per-user-sse-connection-cap-5)
12. [Reconnection + tab-visibility strategy](#12-reconnection--tab-visibility-strategy)
13. [Optimistic UI patterns](#13-optimistic-ui-patterns)
14. [Cleanup checklist](#14-cleanup-checklist)

---

## 1. Why this exists

The backend runs multi-threaded, fanout-on-write, with several real
concurrency boundaries that affect what the frontend sees:

- **Async fanout.** When a post is created, the HTTP response returns
  immediately. The `feed_by_user` rows for the author's followers are
  written on a bounded background executor — so a follower's home feed
  may not include the post for ~tens of milliseconds.
- **Live counters are eventually consistent.** Reaction / view / save
  toggles use Cassandra LWT + counter columns; re-reading the counter
  right after a toggle can return a stale value. **The realtime event
  does not carry counter values** — see [§4](#4-the-counter-delta-model-critical).
- **Realtime is push-only, one-way.** All streams are Server-Sent
  Events (SSE) over `text/event-stream`. The frontend reads, the
  backend writes. There is **no WebSocket layer.**
- **Per-user SSE cap.** A single user account can hold at most **5**
  concurrent connections per stream — opening a 6th closes the oldest
  one server-side ([§11](#11-per-user-sse-connection-cap-5)).
- **Rate-limited writes.** Hot write paths (post, comment, save, share,
  story) return **429** with a `retry-after-seconds` payload — see [§10](#10-handling-429-too-many-requests).

These aren't optional concerns. Ignoring them produces UI bugs that
look intermittent but aren't: dropped counters, double-incremented
likes, stale feeds, refused EventSource reconnects, lost notifications
across tabs.

---

## 2. Endpoint inventory

Every endpoint below produces `text/event-stream` and emits a
`heartbeat` event every 15–25s to keep proxies (Cloudflare, Nginx) from
closing the socket.

| Stream | URL | Keying | Auth | Anon allowed |
|---|---|---|---|---|
| Notifications | `GET /api/v1/notifications/stream` | per user | JWT (header or `?token=`) | no |
| Story tray | `GET /api/v1/stories/tray/stream` | per viewer | JWT (header or `?token=`) | no |
| Activity | `GET /api/v1/users/me/activity/stream` | per user | JWT | no |
| Post stream | `GET /api/v1/posts/{postId}/stream` | per post | optional | **yes** (anon viewers don't get `likedByMe`-style fields) |
| Question stream | `GET /api/v1/questions/{questionId}/stream` | per question | optional | yes |
| Research stream | `GET /api/v1/research/{researchId}/stream` | per research | optional | yes |
| Per-story stream | `GET /api/v1/stories/{storyId}/stream` | per story | optional | yes |

The "stream" naming is deliberate — they all behave the same way and
share the same React hook ([§7](#7-the-shared-useeventstream-hook)).

---

## 3. Backend changes you MUST handle

These shipped recently. If you haven't updated for them, here's the
short list:

| Change | What it means for the frontend |
|---|---|
| **Per-row TTL on stories (8h / 16h / 24h)** | Pass `lifetimeHours` on the create payload. Default is 24. The story's `expiresAt` on the response now reflects the chosen window — drive the tray ring's "expires in N hours" badge off this. See [§9](#9-story-create--new-lifetimehours-field). |
| **Per-user SSE cap = 5** | A 6th `EventSource` for the same user closes the oldest. Keep one `EventSource` per stream, shared across components — don't open a new one per panel/tab. See [§11](#11-per-user-sse-connection-cap-5). |
| **Counters removed from realtime events** | `PostRealtimeEvent` / `QnaRealtimeEvent` / `ResearchRealtimeEvent` no longer carry `*Count` fields. Apply +1/−1 locally on the event type. See [§4](#4-the-counter-delta-model-critical). |
| **RateLimiter on write paths** | Post / comment / story / save / share return **429** with `retry-after-seconds`. Surface a "slow down" toast and back off. See [§10](#10-handling-429-too-many-requests). |
| **Story-tray event names are lowercase** | The wire format uses lowercase enum values (`new_story`, `story_removed`, `poll_vote_cast`) — not the Java `NEW_STORY` casing. See [§5](#5-event-name-casing-footgun). |
| **Shared SSE heartbeat tick** | Backend now sends one consolidated heartbeat per stream. Treat absence of heartbeat for >60s as a disconnect signal and force a reconnect. See [§12](#12-reconnection--tab-visibility-strategy). |

---

## 4. The counter-delta model (critical)

**Realtime events do not carry counter values.** Look at any
`PostRealtimeEvent` on the wire — there is no `postReactionCount`,
`postCommentCount`, etc. The client is expected to apply a **delta**
based on the event type:

| Event | Apply locally |
|---|---|
| `REACTION_ADDED` | `reactionCount += 1`, `likedByMe = true` (if `actorId === me`) |
| `REACTION_REMOVED` | `reactionCount -= 1`, `likedByMe = false` (if `actorId === me`) |
| `COMMENT_CREATED` | `commentCount += 1` |
| `COMMENT_DELETED` | `commentCount -= 1` |
| `SAVE_COUNT_UPDATED` | `saveCount += event.saved ? 1 : -1` |
| `VIEW_COUNT_UPDATED` | `viewCount += 1` |
| `SHARE_COUNT_UPDATED` | `shareCount += 1` |

**Why**: Cassandra `COUNTER` columns are eventually consistent.
Re-reading right after an UPDATE can return a stale value. Sending the
fresh count over the wire would race the next event and create flicker
("17 → 16 → 17"). Deltas are stable and ordered.

### React reducer pattern

```ts
type Counters = { reactions: number; comments: number; saves: number;
                  views: number; shares: number; likedByMe: boolean;
                  savedByMe: boolean };

type Action =
  | { type: 'REACTION_ADDED'; actorId: string }
  | { type: 'REACTION_REMOVED'; actorId: string }
  | { type: 'COMMENT_CREATED' }
  | { type: 'COMMENT_DELETED' }
  | { type: 'SAVE_COUNT_UPDATED'; saved: boolean; actorId: string }
  | { type: 'VIEW_COUNT_UPDATED' }
  | { type: 'SHARE_COUNT_UPDATED' };

function counterReducer(state: Counters, a: Action, me: string): Counters {
  switch (a.type) {
    case 'REACTION_ADDED':
      return { ...state,
        reactions: state.reactions + 1,
        likedByMe: a.actorId === me ? true : state.likedByMe };
    case 'REACTION_REMOVED':
      return { ...state,
        reactions: Math.max(0, state.reactions - 1),
        likedByMe: a.actorId === me ? false : state.likedByMe };
    case 'COMMENT_CREATED':
      return { ...state, comments: state.comments + 1 };
    case 'COMMENT_DELETED':
      return { ...state, comments: Math.max(0, state.comments - 1) };
    case 'SAVE_COUNT_UPDATED':
      return { ...state,
        saves: state.saves + (a.saved ? 1 : -1),
        savedByMe: a.actorId === me ? a.saved : state.savedByMe };
    case 'VIEW_COUNT_UPDATED':
      return { ...state, views: state.views + 1 };
    case 'SHARE_COUNT_UPDATED':
      return { ...state, shares: state.shares + 1 };
    default: return state;
  }
}
```

### When to re-fetch

If the user navigates away and comes back, you've missed events. **Always
re-fetch the canonical counts** from `GET /api/v1/posts/{id}` (or the
hydrated feed item) when re-mounting, then subscribe to realtime
deltas. Don't trust accumulated local deltas across navigations.

---

## 5. Event-name casing footgun

**Per-topic streams** (post / qna / research / per-story) emit event
names in **UPPERCASE** matching the Java enum (e.g. `REACTION_ADDED`).

**Story tray** stream (`/stories/tray/stream`) emits event names in
**lowercase** matching the lowercased enum value (e.g. `new_story`,
`story_removed`, `poll_vote_cast`).

If you don't know which one a stream uses, register handlers for both
and have one of them log a warning if hit — never silently mis-handle:

```ts
const KNOWN_TRAY_EVENTS = ['new_story', 'story_removed', 'poll_vote_cast', 'heartbeat', 'connected'];
es.addEventListener('NEW_STORY', () => console.warn('[TRAY] received UPPERCASE event; backend expected lowercase'));
```

---

## 6. Auth — passing the JWT to EventSource

`EventSource` can't send custom request headers (no `Authorization:
Bearer …`). Every stream accepts a fallback:

```
GET /api/v1/notifications/stream?token=<accessToken>
```

```ts
const url = `${API_BASE}/api/v1/notifications/stream?token=${encodeURIComponent(accessToken)}`;
const es = new EventSource(url, { withCredentials: false });
```

> **Security note.** Don't put the JWT in any URL the user sees
> (`<a href>`, server-side log lines). Build the EventSource URL in
> memory only. The backend validates the token and treats `?token=`
> exactly like the `Authorization` header.

For the **public** streams (post / qna / research / per-story), omit
the token entirely if the user is anonymous — those endpoints
explicitly allow unauthenticated viewers.

---

## 7. The shared `useEventStream` hook

One hook covers every SSE stream. Returns a connection state +
`addEventListener` indirection so callers can register typed event
handlers without leaking the raw `EventSource`.

```ts
import { useEffect, useRef, useState } from 'react';

export type SseState = 'connecting' | 'open' | 'closed' | 'reconnecting';

export interface UseEventStreamOptions {
  url: string;             // already includes ?token= if needed
  enabled?: boolean;       // skip the connection (e.g. user is logged out)
  onMessage: (eventName: string, data: unknown) => void;
  onError?: (e: Event) => void;
}

export function useEventStream({ url, enabled = true, onMessage, onError }: UseEventStreamOptions) {
  const [state, setState] = useState<SseState>('closed');
  const esRef = useRef<EventSource | null>(null);
  const lastBeatRef = useRef<number>(Date.now());

  useEffect(() => {
    if (!enabled) return;

    setState('connecting');
    const es = new EventSource(url);
    esRef.current = es;

    es.onopen = () => { setState('open'); lastBeatRef.current = Date.now(); };

    // Generic dispatch — read every named event the backend emits.
    // EventSource fires onmessage ONLY for unnamed events; named ones
    // need addEventListener.
    const wrap = (name: string) => (ev: MessageEvent) => {
      lastBeatRef.current = Date.now();
      let data: unknown = ev.data;
      try { data = JSON.parse(ev.data); } catch { /* keep as string */ }
      onMessage(name, data);
    };

    // Register every event name you might receive on any stream.
    // Adding handlers for events the server never emits is free.
    const NAMES = [
      'connected', 'heartbeat',
      // story tray (lowercase)
      'new_story', 'story_removed', 'poll_vote_cast',
      // post (uppercase)
      'REACTION_ADDED','REACTION_REMOVED','REACTION_CHANGED',
      'COMMENT_CREATED','COMMENT_EDITED','COMMENT_DELETED','REPLY_CREATED',
      'COMMENT_REACTION_ADDED','COMMENT_REACTION_REMOVED','COMMENT_REACTION_CHANGED',
      'VIEW_COUNT_UPDATED','SHARE_COUNT_UPDATED','SAVE_COUNT_UPDATED',
      'POST_UPDATED','POST_DELETED',
      // qna
      'ANSWER_CREATED','REANSWER_CREATED','ANSWER_EDITED','ANSWER_DELETED',
      'ANSWER_REACTION_ADDED','ANSWER_REACTION_REMOVED','ANSWER_REACTION_CHANGED',
      'ANSWER_ACCEPTED','ANSWER_UNACCEPTED',
      'QUESTION_UPDATED','QUESTION_DELETED','QUESTION_LOCKED','QUESTION_UNLOCKED',
      // research
      'RESEARCH_UPDATED','RESEARCH_DELETED','RESEARCH_PUBLISHED',
      'DOWNLOAD_COUNT_UPDATED','CITATION_COUNT_UPDATED',
      'REACTION_COUNT_UPDATED','COMMENT_COUNT_UPDATED',
      // per-story
      'STORY_VIEWED','STORY_REACTED','STORY_UNREACTED','STORY_REPLIED',
      'STORY_POLL_VOTED','STORY_EXPIRED','STORY_DELETED','REPLY_COUNT_UPDATED',
      // notifications
      'notification',
    ];
    const handlers = NAMES.map(n => {
      const h = wrap(n);
      es.addEventListener(n, h as EventListener);
      return [n, h] as const;
    });

    es.onerror = (e) => {
      // The browser will try to reconnect automatically using the
      // `retry: 3000` hint sent by the backend on first connect. We
      // surface state for the UI, but DO NOT close + reopen here —
      // that fights the browser's reconnect and risks tripping the
      // per-user cap.
      setState('reconnecting');
      onError?.(e);
    };

    // Watchdog: if no event arrives for 60s, force a fresh connect.
    // Heartbeat is every 15–25s, so 60s of silence means something's
    // wrong with the socket the browser hasn't noticed yet.
    const watchdog = window.setInterval(() => {
      if (Date.now() - lastBeatRef.current > 60_000) {
        try { es.close(); } catch {}
        setState('reconnecting');
      }
    }, 10_000);

    return () => {
      window.clearInterval(watchdog);
      handlers.forEach(([n, h]) => es.removeEventListener(n, h as EventListener));
      try { es.close(); } catch {}
      esRef.current = null;
      setState('closed');
    };
  }, [url, enabled]);

  return state;
}
```

> **Why one EventSource per stream.** The per-user SSE cap is **5**.
> Open one connection per stream type (notifications, tray, plus
> whatever per-topic stream the current page needs) and **share it
> across components**. Two tabs are still two connections — that's
> fine; the limit is per user, not per tab.

---

## 8. Per-stream React patterns

### 8.1 Notifications

Event names on this stream are lowercase `notification` /
`heartbeat` / `connected`. Payload for `notification` is the same
`NotificationResponse` shape `GET /api/v1/notifications` returns.

```ts
export function useNotifications(token: string) {
  const dispatch = useNotificationStore(s => s.dispatch);

  useEventStream({
    url: `${API_BASE}/api/v1/notifications/stream?token=${encodeURIComponent(token)}`,
    enabled: !!token,
    onMessage: (name, data) => {
      switch (name) {
        case 'notification':
          dispatch({ type: 'PUSHED', payload: data });
          break;
        case 'connected':   /* show "live" badge */ break;
        case 'heartbeat':   /* no-op — watchdog tracks it */ break;
      }
    },
  });
}
```

### 8.2 Story tray

Event names are **lowercase**. `expiresAt` on `new_story` events
already reflects the author's chosen 8h / 16h / 24h window — drive
ring-greys-out countdowns directly from it.

```ts
export function useStoryTray(token: string) {
  const dispatch = useStoryTrayStore(s => s.dispatch);

  useEventStream({
    url: `${API_BASE}/api/v1/stories/tray/stream?token=${encodeURIComponent(token)}`,
    enabled: !!token,
    onMessage: (name, data) => {
      switch (name) {
        case 'new_story':       dispatch({ type: 'ADD', story: data }); break;
        case 'story_removed':   dispatch({ type: 'REMOVE', storyId: (data as any).storyId }); break;
        case 'poll_vote_cast':  dispatch({ type: 'POLL_UPDATE', payload: data }); break;
      }
    },
  });
}
```

### 8.3 Per-post / per-question / per-research stream

Single component pattern — one effect per opened detail view. Mount it
on the detail page only; unmount on navigation so the connection
doesn't outlive the screen.

```ts
export function usePostRealtime(postId: string, me: string | null) {
  const dispatch = usePostStore(s => s.dispatch);

  useEventStream({
    url: `${API_BASE}/api/v1/posts/${postId}/stream${me ? `?token=${encodeURIComponent(currentToken())}` : ''}`,
    enabled: !!postId,
    onMessage: (name, data: any) => {
      // Counters are delta-only — see §4.
      switch (name) {
        case 'REACTION_ADDED':
          dispatch({ type: 'REACTION_ADDED', actorId: data.actorId });
          break;
        case 'REACTION_REMOVED':
          dispatch({ type: 'REACTION_REMOVED', actorId: data.actorId });
          break;
        case 'COMMENT_CREATED':
          dispatch({ type: 'COMMENT_CREATED', comment: data });
          break;
        case 'COMMENT_DELETED':
          dispatch({ type: 'COMMENT_DELETED', commentId: data.commentId });
          break;
        case 'SAVE_COUNT_UPDATED':
          dispatch({ type: 'SAVE_COUNT_UPDATED', saved: data.saved, actorId: data.actorId });
          break;
        case 'VIEW_COUNT_UPDATED':
          dispatch({ type: 'VIEW_COUNT_UPDATED' });
          break;
        case 'POST_UPDATED':
          dispatch({ type: 'POST_UPDATED', textContent: data.textContent });
          break;
        case 'POST_DELETED':
          dispatch({ type: 'POST_DELETED' });
          break;
      }
    },
  });
}
```

Same pattern for `useQuestionRealtime(questionId)` and
`useResearchRealtime(researchId)` — only the URL prefix and event names
change.

### 8.4 Per-story stream

Used inside the story viewer overlay. Drives the "X viewed", live poll
tally, and "story has been deleted by author" eject:

```ts
export function useStoryViewerRealtime(storyId: string) {
  const dispatch = useStoryViewerStore(s => s.dispatch);

  useEventStream({
    url: `${API_BASE}/api/v1/stories/${storyId}/stream`,
    enabled: !!storyId,
    onMessage: (name, data) => {
      switch (name) {
        case 'STORY_VIEWED':         dispatch({ type: 'VIEW_BUMP' }); break;
        case 'STORY_REACTED':        dispatch({ type: 'REACT_BUMP' }); break;
        case 'STORY_POLL_VOTED':     dispatch({ type: 'POLL_TALLY', payload: data }); break;
        case 'STORY_DELETED':
        case 'STORY_EXPIRED':        dispatch({ type: 'CLOSE_VIEWER' }); break;
      }
    },
  });
}
```

---

## 9. Story create — new `lifetimeHours` field

The story create endpoint now accepts an author-selectable lifetime.

```ts
// JSON
await api.post('/api/v1/stories', {
  storyType:    'IMAGE',
  visibility:   'PUBLIC',
  mediaUrl,
  thumbnailUrl,
  textContent,
  lifetimeHours: 8,   // 8, 16, or 24 — anything else → 24
});

// Multipart
const fd = new FormData();
fd.append('storyType', 'PHOTO');
fd.append('visibility', 'PUBLIC');
fd.append('textContent', caption);
fd.append('lifetimeHours', String(8));        // ← new
fd.append('media', file);
await api.post('/api/v1/stories', fd, {
  headers: { 'Content-Type': 'multipart/form-data' },
});
```

The response contains `expiresAt`. Use it directly for the ring's
countdown badge:

```tsx
function StoryRingBadge({ expiresAt }: { expiresAt: string }) {
  const remainingMs = new Date(expiresAt).getTime() - Date.now();
  const hours = Math.max(0, Math.floor(remainingMs / 3_600_000));
  return <span className="text-xs opacity-70">{hours}h left</span>;
}
```

UI surface: a three-option selector in the story composer.

```tsx
const OPTIONS = [
  { value: 8,  label: '8 hours' },
  { value: 16, label: '16 hours' },
  { value: 24, label: '24 hours (default)' },
];
```

> Sending a value the backend doesn't recognise (e.g. `12`) silently
> falls back to 24h — no 400 error. Treat the dropdown as the source
> of truth client-side.

---

## 10. Handling 429 Too Many Requests

The following write endpoints can return **429**:

- `POST /api/v1/posts` (JSON + multipart)
- `POST /api/v1/posts/{id}/comments` and `…/comments/{id}/replies`
- `POST /api/v1/posts/{id}/saves`
- `POST /api/v1/posts/{id}/shares` and `…/share`
- `POST /api/v1/stories` (JSON + multipart)
- (and existing reaction endpoints in research / qna)

**Response shape**:

```json
{
  "status": 429,
  "error":  "Too Many Requests",
  "message": "You're doing that a bit too fast — try again in 12s.",
  "retryAfterSeconds": 12,
  "action": "social"
}
```

### React fetcher pattern

```ts
export async function postWithRateLimit<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${currentToken()}` },
    body: JSON.stringify(body),
  });

  if (res.status === 429) {
    const err = await res.json().catch(() => ({}));
    const retry = (err.retryAfterSeconds ?? 5) as number;
    toast.warning(`Slow down — try again in ${retry}s`);
    // Temporarily disable the submit button so the user can't spam through it.
    disableActionFor(err.action ?? 'global', retry * 1000);
    throw new RateLimitError(retry);
  }

  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}
```

Pair this with form disabling so the user can't keep clicking submit
during the cooldown:

```ts
const [cooldownUntil, setCooldownUntil] = useState(0);
const inCooldown = cooldownUntil > Date.now();
<Button disabled={inCooldown || pending} onClick={onSubmit}>
  {inCooldown ? `Wait ${Math.ceil((cooldownUntil - Date.now()) / 1000)}s` : 'Post'}
</Button>
```

---

## 11. Per-user SSE connection cap (5)

The backend caps a single user at **5 concurrent SSE connections per
stream** (notifications and tray today; same pattern coming to other
streams). Opening a 6th closes the **oldest** server-side — the client
will see an `onerror` followed by a normal reconnect of the survivor.

### Implications

- **Open ONE EventSource per stream type and SHARE it.** Don't create
  a new connection in every panel/widget that needs notifications —
  put the EventSource at the top of the React tree (e.g. inside a
  `<RealtimeProvider>`), expose the data via context, and let
  consumers subscribe through React state instead of opening fresh
  streams.
- **Avoid HMR-storm leaks in dev.** React Fast-Refresh can leak old
  EventSources during heavy edits. The `useEventStream` hook above
  always calls `es.close()` in its cleanup; if you bypass it,
  call `close()` manually.
- **Background tabs still count.** A long-running second tab keeps its
  connection open. On the 6th tab, the backend closes the oldest. The
  user sees a brief reconnect; no data loss because the server-side
  inbox is the source of truth.

---

## 12. Reconnection + tab-visibility strategy

EventSource auto-reconnects on its own, using the `retry: 3000` hint
the backend sends on connect. **Don't fight it** by closing + reopening
on every error — you'll trip the per-user cap.

What you **should** do:

1. **Watchdog on heartbeat absence.** If no event of any kind (heartbeat,
   payload) arrives for **60 seconds**, force-close and reconnect.
   Production proxies sometimes wedge sockets that the browser hasn't
   declared dead yet. The hook in [§7](#7-the-shared-useeventstream-hook) does this.
2. **Pause on long-hidden tabs.** When `document.visibilityState`
   stays `'hidden'` for more than ~5 minutes, close the EventSource.
   Re-open on `visibilitychange` back to `'visible'`. This frees up a
   slot for the user's other tabs and avoids hitting the cap.
3. **Re-fetch on reconnect.** Realtime is best-effort delivery; events
   that arrived during the disconnect are lost. On reconnect, re-fetch
   the canonical state (notifications unread count, current counters)
   so the UI is correct.

```tsx
function BackgroundedReconnect({ children }: { children: React.ReactNode }) {
  const [active, setActive] = useState(document.visibilityState === 'visible');
  useEffect(() => {
    let hiddenTimer: number | undefined;
    const onChange = () => {
      if (document.visibilityState === 'hidden') {
        hiddenTimer = window.setTimeout(() => setActive(false), 5 * 60_000);
      } else {
        if (hiddenTimer) window.clearTimeout(hiddenTimer);
        setActive(true);
      }
    };
    document.addEventListener('visibilitychange', onChange);
    return () => document.removeEventListener('visibilitychange', onChange);
  }, []);
  // Inside, pass `enabled={active}` to every useEventStream(...) call.
  return <RealtimeContext.Provider value={active}>{children}</RealtimeContext.Provider>;
}
```

---

## 13. Optimistic UI patterns

Because counters are delta-only and writes are rate-limited, optimistic
UI works well — but you must reconcile carefully.

### Reactions

```ts
async function toggleLike(postId: string) {
  const prevLiked = store.likedByMe;
  // Optimistic flip
  store.set({ likedByMe: !prevLiked, reactions: store.reactions + (prevLiked ? -1 : 1) });
  try {
    const res = await api.post(`/api/v1/posts/${postId}/reactions`);
    // Authoritative bool — usually matches the optimistic flip
    store.set({ likedByMe: res.liked });
  } catch (e) {
    if (e instanceof RateLimitError) {
      // Roll back the optimistic flip
      store.set({ likedByMe: prevLiked,
                  reactions: store.reactions + (prevLiked ? 1 : -1) });
    } else {
      // Network error — roll back too, surface a retry
      store.set({ likedByMe: prevLiked,
                  reactions: store.reactions + (prevLiked ? 1 : -1) });
    }
  }
}
```

### Don't double-count realtime echo

When you toggle a reaction, the backend broadcasts the event back on
the stream. If you've already applied the optimistic delta, applying
the realtime event again double-counts.

**Pattern**: include the actor id on the event (already in the payload).
If `event.actorId === me`, skip the counter delta — you already
applied it optimistically. This is also why the realtime broadcaster
sends `actorId`.

```ts
case 'REACTION_ADDED':
  if (event.actorId === me) break;          // already optimistically applied
  dispatch({ type: 'REACTION_ADDED', actorId: event.actorId });
  break;
```

For comments, the canonical approach is: insert the optimistic comment
with a client-generated tag (e.g. `tempId`); when the realtime
`COMMENT_CREATED` arrives with the real `commentId`, swap the placeholder
in by `tempId` rather than appending a duplicate row.

---

## 14. Cleanup checklist

Before shipping a screen that uses realtime, walk through this:

- [ ] Exactly **one** `useEventStream` call per stream type — no panel
      opening its own EventSource.
- [ ] `enabled={false}` while the JWT is missing / refreshing — never
      open with `token=undefined`.
- [ ] Counter updates use **delta** logic (`+1` / `-1`), not server-supplied
      absolute values.
- [ ] If the user is the actor, **skip the realtime delta** (already
      applied optimistically) — see [§13](#13-optimistic-ui-patterns).
- [ ] Event name lookup matches the stream's casing convention —
      lowercase for tray, UPPERCASE for everything else ([§5](#5-event-name-casing-footgun)).
- [ ] **Watchdog** on heartbeat absence (>60s = force reconnect).
- [ ] **Re-fetch** authoritative counts on reconnect / tab visibility return.
- [ ] **429 handling**: read `retryAfterSeconds`, disable the action,
      show a friendly toast — never silently swallow it.
- [ ] Story composer passes `lifetimeHours` (8 / 16 / 24); the ring badge
      reads `expiresAt` from the response.
- [ ] Cleanup in `useEffect` return removes every `addEventListener`
      AND calls `es.close()`.

If a regression shows up around "counters jump around" or
"notifications stop randomly", the cause is almost always one of:
double-counting from optimistic + realtime echo, no watchdog, or a
leaked EventSource from a forgotten cleanup.

---

## Appendix — backend mental model (for context)

Useful to know but not strictly required:

- **One thread pool for everything async.** `taskExecutor`
  (core=8 / max=32 / queue=10 000 / CallerRunsPolicy). Backed by the
  bounded `ThreadPoolTaskExecutor` in `AsyncConfig`. Fanout writes,
  notification dispatch, search indexing, etc. all share it. Under
  burst load it gracefully degrades to "caller runs" — slowing the
  fanout loop instead of dropping events.
- **Circuit-breaker on feed fanout.** After 20 consecutive Cassandra
  write failures the fanout writes pause for 30s and the read path
  serves from `posts_by_author`. Frontend symptom: a freshly-created
  post appears in the author's profile but not in followers' home
  feeds until the breaker half-opens. Recovery is automatic.
- **Realtime is Redis pub/sub across instances.** Events published by
  any backend instance are received by every instance and forwarded
  to the SSE emitters on that instance. Behind a load balancer, your
  EventSource connects to whichever instance has the cheapest
  connection slot — no sticky sessions required.
