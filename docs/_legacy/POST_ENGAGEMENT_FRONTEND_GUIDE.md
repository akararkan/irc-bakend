# Post Engagement — Frontend Integration Guide

**Audience:** frontend engineers wiring up the post-engagement UI.
**Scope:** the four post-level engagement actions — **Views**, **Saves (bookmarks)**, **Reposts**, **Shares** — plus the realtime SSE updates that keep their counters live.

> This is the *how-to-integrate* companion. For the exhaustive endpoint reference (every field, every Cassandra table, every side effect) see [`POST_ACTIONS_API.md`](./POST_ACTIONS_API.md). This guide tells you **what to call, when, and how to update the UI**.

---

## Table of Contents

1. [Conventions you must follow](#1-conventions-you-must-follow)
2. [Views — count on screen](#2-views--count-on-screen)
3. [Saves (Bookmarks)](#3-saves-bookmarks)
4. [Reposts](#4-reposts)
5. [Shares](#5-shares)
6. [Realtime (SSE) — keeping counters live](#6-realtime-sse--keeping-counters-live)
7. [Counter reconciliation rules](#7-counter-reconciliation-rules)
8. [Quick reference](#8-quick-reference)
9. [Frontend checklist](#9-frontend-checklist)

---

## 1. Conventions you must follow

**Base path:** every endpoint below is under `https://<host>/api/v1`.

**Auth:** send `Authorization: Bearer <JWT>` on all calls. Endpoints are marked **🔒 required** (401 without a token) or **🔓 optional** (works anonymously, with reduced behaviour).

**Optimistic UI:** all toggle actions (save, like) return the state **after** the toggle. Flip the icon immediately on tap, fire the request, and roll back only if it rejects. Do **not** wait for the response to update the icon.

**Counters are eventually consistent.** Never re-fetch a post just to refresh a count after the user acts. Apply a local `+1` / `-1` and let the next full page load reconcile. The realtime stream (§6) also pushes deltas — see §7 for the one exception (shares).

**IDs are UUID strings. Timestamps are ISO-8601** (`2026-05-26T15:50:00Z`). Cursors are timestamps.

**Standard error handling:**

| Status | What it means | What the UI should do |
|---|---|---|
| `401` | Missing/expired token on a 🔒 endpoint | Bounce to login; roll back the optimistic change |
| `404` | `postId` doesn't exist | Show "post unavailable"; remove from list |
| `500` | Backend/counter write failed | Roll back optimistic change; toast "try again" |

---

## 2. Views — count on screen

**What it's for:** counting unique viewers. A repeat view from the same user inside a **7-day** window does **not** re-count.

### Endpoint

```
POST /api/v1/posts/{postId}/views          🔓 optional auth
```

**Response:**
```json
{ "postId": "<uuid>", "userId": "<uuid|absent>", "counted": true, "viewCount": 142 }
```
- `counted` — `true` if this call actually bumped the counter; `false` if it was a duplicate or anonymous.
- `viewCount` — the current count (use it to seed your display).

### 👉 What the frontend does

- Fire this **once** when a post becomes meaningfully visible — e.g. on detail-page open, or when a feed card crosses ~50% viewport for >1s (use an `IntersectionObserver`; debounce so a fast scroll doesn't fire it).
- Seed your view-count label from `viewCount` in the response.
- You don't need a token for this to work, but pass it if you have one so the user's view is properly de-duplicated.
- Do **not** call it repeatedly on scroll-back — the 7-day dedup protects the count, but you'd be wasting requests.

---

## 3. Saves (Bookmarks)

A save is a **private bookmark** — only the saving user ever sees it. The author is **not** notified. Saves can optionally be filed into a named **collection** (folder).

### Endpoints

```
POST   /api/v1/posts/{postId}/saves?collection=<name>   🔒 required   (toggle)
GET    /api/v1/posts/{postId}/saves/me                  🔓 optional   (am I saved?)
DELETE /api/v1/posts/{postId}/saves                     🔒 required   (explicit unsave, idempotent)
GET    /api/v1/posts/users/{userId}/saves               🔓 optional   (my saved list)
```

### 3.1 Toggle the bookmark

`POST /{postId}/saves` flips the state. `?collection=` is optional — omit it for the default "All" bucket.

**Response:** `{ "postId": "<uuid>", "userId": "<uuid>", "saved": true|false }`

### 👉 What the frontend does — bookmark icon

```ts
async function onBookmarkTap(postId: string, collection?: string) {
  setSaved(s => !s);                          // 1. optimistic flip
  try {
    const qs = collection ? `?collection=${encodeURIComponent(collection)}` : '';
    const { saved } = await api.post(`/posts/${postId}/saves${qs}`);
    setSaved(saved);                           // 2. trust server's final state
    setSaveCount(n => n + (saved ? 1 : -1));   // 3. local counter delta
  } catch (e) {
    setSaved(s => !s);                         // 4. roll back on failure
  }
}
```

- The toggle is **race-safe on the backend** (LWT) — double-taps can't double-count. You still want a debounce/disable to avoid UI flicker.
- **Changing the collection** of an existing save = unsave, then save again with the new `collection`. There is no "move" endpoint.

### 3.2 Initial icon state

On post load, hydrate the bookmark icon from one of:
- `savedByMe` on the `PostResponse` (preferred — comes free with the post), **or**
- `GET /{postId}/saves/me` → `{ saved: true|false }` (anonymous users always get `false`, no error).

### 3.3 Explicit unsave

`DELETE /{postId}/saves` always lands on `saved: false` (idempotent — safe to call even if not currently saved). Use this for a "Remove" button on the saved-posts screen rather than the toggle.

### 3.4 The "Saved posts" screen

```
GET /api/v1/posts/users/{userId}/saves?pageSize=20&cursor=<savedAt-of-last-item>
```

Returns **fully-hydrated posts** (`PostResponse[]`), newest-saved first — so you render them exactly like feed cards. Save-specific context rides along on two extra fields:

| Field | Use |
|---|---|
| `savedAt` | When the user saved it. **Use this as the `cursor`** for the next page. |
| `savedCollectionName` | Which folder it's in (`null` = default). Group/filter the screen by this. |

- **Pagination:** omit `cursor` for page 1; pass the last item's `savedAt` for each subsequent page. If you get fewer than `pageSize` items, you *might* still have more (see next note) — only stop when you get `0`.
- **Deleted posts are silently dropped** — if a saved post was hard-deleted, it won't appear, so a 20-page can legitimately return 17 items. Don't treat a short page as "end of list" by itself.

---

## 4. Reposts

A **repost** is **not** a toggle or a counter — it's a brand-new post that points back at the original (Twitter/Facebook style). There is **no `/repost` endpoint**.

### How to create a repost

Use the normal create-post call with two fields set:

```
POST /api/v1/posts            (application/json  — or multipart for media)   🔒 required
```
```json
{
  "postType": "REPOST",
  "sharedPostId": "<uuid-of-original-post>",
  "textContent": "optional quote / caption",
  "shareLink": "optional external link to original"
}
```
Returns a normal `PostResponse` for the newly-created repost. (Full create-post contract: see [`FEED_API.md`](./FEED_API.md) / [`POST_API.md`](./POST_API.md).)

### 👉 What the frontend does

- **Rendering a repost card:** when `post.postType === "REPOST"`, render the repost's own `textContent` as the quote, then fetch/embed the original post by `post.sharedPostId` underneath it.
- **Self-repost is allowed** — don't grey out the repost button on the user's own posts.
- **Do not** expect a repost counter, a realtime repost event, or a notification to the original author. Reposts are intentionally silent on the backend right now:
  - ❌ no `repost_count` on the original post
  - ❌ no `REPOST_COUNT_UPDATED` SSE event
  - ❌ no notification to the original author

> **Repost vs. Share:** a *repost* publishes a new post to your profile and followers' feeds. A *share* (§5) just logs that you sent a post elsewhere. Use repost for "quote to my feed", share for "send via DM / copy link / external".

---

## 5. Shares

A **share** records that the user tapped *Share* and sent the post somewhere (DM, external app, copy-link), with an optional caption. The share log is **append-only** — there is no "unshare", and the same user can share the same post repeatedly (each tap counts).

### Endpoints

```
POST /api/v1/posts/{postId}/shares      🔒 required   (record a share)
GET  /api/v1/posts/{postId}/shares      🔓 optional   (recent shares list)
```

### 5.1 Record a share

`POST /{postId}/shares`. Body is **optional**:
```json
{ "caption": "you all need to read this 👇" }
```
The sharer is taken from the JWT (any `sharerId` you send is ignored). Returns the created share row:
```json
{
  "postId": "<uuid>", "createdAt": "2026-05-26T15:52:00Z",
  "shareId": "<uuid>", "sharerId": "<uuid>", "caption": "..."
}
```

### 👉 What the frontend does

```ts
async function onShare(postId: string, caption?: string) {
  await api.post(`/posts/${postId}/shares`, caption ? { caption } : {});
  setShareCount(n => n + 1);   // local +1; no toggle, no "undo"
}
```

- Call this **every time** the user completes a share action — there's no dedup, that's intended ("shared N times").
- The post **author gets a `POST_SHARED` notification** (unless they shared their own post). You don't do anything for this — it's handled server-side.
- There is no "unshare" UI. Don't build one.

### 5.2 Recent shares list

`GET /{postId}/shares?pageSize=20` → `ShareByPostEntity[]`, newest first. Use it for a "shared by" / share-stats panel. Public — works without auth.

---

## 6. Realtime (SSE) — keeping counters live

Subscribe to a post's event stream to update counters live while the user is on the post (other people's saves/shares/views show up without a refresh).

```
GET /api/v1/posts/{postId}/stream?token=<jwt>     Content-Type: text/event-stream
```

**Why `?token=` and not a header:** browser `EventSource` cannot send an `Authorization` header, so the JWT goes in the query string. Anonymous (`?token` omitted) is allowed — you'll receive all events but won't trigger view counting.

### Events relevant to engagement

| `eventType` | Fires when | Fields you get |
|---|---|---|
| `VIEW_COUNT_UPDATED` | A new unique view was recorded | `actorId` (null if anon) |
| `SAVE_COUNT_UPDATED` | Someone saved **or** unsaved | `actorId`, **`saved`** (`true`=saved, `false`=unsaved) |
| `SHARE_COUNT_UPDATED` | Someone shared | `actorId`, **`postShareCount`** ⚠️ |

```ts
const es = new EventSource(`/api/v1/posts/${postId}/stream?token=${jwt}`);
es.onmessage = ({ data }) => {
  const ev = JSON.parse(data);
  switch (ev.eventType) {
    case 'VIEW_COUNT_UPDATED':  setViewCount(n => n + 1); break;
    case 'SAVE_COUNT_UPDATED':  setSaveCount(n => n + (ev.saved ? 1 : -1)); break; // signed delta
    case 'SHARE_COUNT_UPDATED': setShareCount(ev.postShareCount); break; // absolute!
  }
};
```

- **Close the stream** (`es.close()`) when the user leaves the post to free the connection.
- The stream sends `Cache-Control: no-cache` / `X-Accel-Buffering: no`, so it works behind nginx/CDN without buffering.

---

## 7. Counter reconciliation rules

This is the part that trips people up — read it.

- **General rule:** SSE events carry **deltas, not counts**. On `*_ADDED` / `*_REMOVED` (and `VIEW_COUNT_UPDATED`) apply `+1` / `-1` locally. Reconcile to the true value only on the next full `GET /posts/{id}`.

- **`SAVE_COUNT_UPDATED` now carries a direction** — the `saved` boolean (`true`=saved, `false`=unsaved). Apply a **signed delta**: `setSaveCount(n => n + (ev.saved ? 1 : -1))`. No more guessing or re-reading. (For your own toggle you already know the direction from the toggle response; you can dedupe your own `actorId` to avoid double-counting if you also applied it optimistically.)

- **`SHARE_COUNT_UPDATED` is the one exception that carries an absolute value** — `postShareCount`. **Set** your share count to it rather than incrementing. (It's re-read from the counter table right after the bump, so it can be momentarily stale under heavy concurrency; that self-corrects on the next event or page load.)

---

## 8. Quick reference

| Action | Method & path | Auth | Returns | Counter | SSE event | Notifies author |
|---|---|---|---|---|---|---|
| Record view | `POST /posts/{id}/views` | 🔓 | `{counted, viewCount}` | `view_count` +1 (if `counted`) | `VIEW_COUNT_UPDATED` | — |
| Toggle save | `POST /posts/{id}/saves?collection=` | 🔒 | `{saved}` | `save_count` ±1 | `SAVE_COUNT_UPDATED` | no |
| Am I saved? | `GET /posts/{id}/saves/me` | 🔓 | `{saved}` | — | — | — |
| Unsave | `DELETE /posts/{id}/saves` | 🔒 | `{saved:false}` | `save_count` −1 | `SAVE_COUNT_UPDATED` | no |
| My saves | `GET /posts/users/{userId}/saves` | 🔓 | `PostResponse[]` | — | — | — |
| Repost | `POST /posts` (`postType:"REPOST"`,`sharedPostId`) | 🔒 | `PostResponse` | none | none | no |
| Record share | `POST /posts/{id}/shares` | 🔒 | `ShareByPostEntity` | `share_count` +1 | `SHARE_COUNT_UPDATED` | **yes** (`POST_SHARED`) |
| Recent shares | `GET /posts/{id}/shares` | 🔓 | `ShareByPostEntity[]` | — | — | — |
| Live stream | `GET /posts/{id}/stream?token=` | 🔓 | SSE | — | all of the above | — |

`PostResponse` engagement fields you'll bind to: `viewCount`, `saveCount`, `shareCount`, `savedByMe`, `likedByMe`, and (saved-list only) `savedAt`, `savedCollectionName`. Repost detection: `postType === "REPOST"` + `sharedPostId`.

---

## 9. Frontend checklist

- [ ] Fire `POST /views` once per visible post (IntersectionObserver, debounced); seed label from `viewCount`.
- [ ] Bookmark icon: optimistic flip → `POST /saves` → trust returned `saved` → local count delta → roll back on error.
- [ ] Seed bookmark state from `savedByMe` (or `GET /saves/me` for anon-safe check).
- [ ] "Saved posts" screen paginates by `savedAt` cursor; group by `savedCollectionName`; stop only on a 0-length page.
- [ ] Repost button works on own posts; render reposts by embedding the original via `sharedPostId`; expect no counter/notification.
- [ ] Share action calls `POST /shares` every time (no toggle, no undo); local `+1`.
- [ ] SSE: subscribe on post open with `?token=`, close on leave.
- [ ] SSE counter handling: `VIEW_COUNT_UPDATED` → `+1`; `SHARE_COUNT_UPDATED` → **set** to `postShareCount`; `SAVE_COUNT_UPDATED` → signed delta from `saved` (`+1`/`-1`).
- [ ] All optimistic updates roll back on `401/404/500`.
