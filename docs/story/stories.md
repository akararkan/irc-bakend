# Stories API

Ephemeral, author-published media (text, image, video, linked content, scholar
knowledge pills). Each story auto-deletes after an author-chosen window —
**8 h**, **16 h**, or **24 h** (default) — implemented with **per-row Cassandra
TTL** (`INSERT … USING TTL`), so expired rows simply disappear from every read
surface. There is no server-side sweep job and no read-time expiry filter.

**Base path:** `/api/v1/stories`

**Auth:** `Authorization: Bearer <JWT>` (access token). Reading `PUBLIC`
stories is allowed anonymously; every write requires auth and derives the
author from the JWT — the body never carries an `authorId`.

**Errors:** all error responses use the shared envelope
(`status` / `error` / `message` / `path` / `errorCode` / `details` / `traceId`)
— see [Error handling](../errors/error-handling.md).

Sibling docs: [Polls](polls.md) · [Close friends](close-friends.md) ·
[Highlights](highlights.md) · [Realtime (SSE)](realtime.md)

---

## Concepts

### Visibility model

| Value | Who can view |
|---|---|
| `PUBLIC` | Everyone, including anonymous viewers |
| `FOLLOWERS_ONLY` | Authenticated viewers who follow the author (Postgres follow check) |
| `CLOSE_FRIENDS` | Authenticated viewers on the author's [close-friends list](close-friends.md) (Cassandra list) |
| `ONLY_ME` | Only the author |

The author always sees their own stories regardless of visibility. A failed
follow lookup resolves to "not visible" — the read path never errors on a
transient datastore hiccup.

### Lifetime (`lifetimeHours`)

| Sent | Story lives | Notes |
|---|---|---|
| `8` | 8 h | Same TTL applied to the lookup row, views, poll and vote rows |
| `16` | 16 h | Same |
| `24` | 24 h | Default |
| omitted / `null` | 24 h | Legacy-client default |
| anything else (`12`, `0`, `-5`, …) | 24 h | **Silent fallback — the server never returns 400 for a bad lifetime** |

The chosen window is written to the row's `expiresAt` column *and* applied as
the Cassandra per-row TTL. Drive "expires in N hours" UI off `expiresAt`.

### Story object

Returned by both create endpoints and `GET /stories/by-author/{authorId}`:

| Field | Type | Description |
|---|---|---|
| `authorId` | UUID | Story owner (always the JWT principal at create time) |
| `createdAt` | ISO-8601 instant | Server-assigned creation time |
| `storyId` | UUID | Server-generated id |
| `storyType` | string | Canonically one of `TEXT`, `IMAGE`, `VIDEO`, `LINKED_POST`, `LINKED_REEL`, `LINKED_QNA`, `LINKED_RESEARCH`, `KNOWLEDGE_PILL` (stored as a free string; the multipart endpoint defaults to `PHOTO`) |
| `visibility` | string | See visibility model above |
| `mediaUrl` | string \| null | Public URL of the story media |
| `thumbnailUrl` | string \| null | Public URL of the preview thumbnail |
| `textContent` | string \| null | Caption / text-story body |
| `expiresAt` | ISO-8601 instant | Absolute death time (creation + lifetime) |

### Rate limiting

Both create endpoints run through the per-user **`social` bucket: 30 writes
per minute** (shared with save/share actions). Exceeding it returns
`429 RATE_LIMITED` with `details.retryAfterSeconds`. The limiter **fails open**
if Redis is unreachable.

---

## Create story (JSON)

```
POST /api/v1/stories
```

**Auth:** required (`Authorization: Bearer <JWT>`). The author is the JWT
principal — do not send an `authorId`.

Creates a story from already-hosted URLs (e.g. media uploaded earlier).
`Content-Type: application/json` selects this variant.

### Request body

```json
{
  "storyType": "IMAGE",
  "visibility": "CLOSE_FRIENDS",
  "mediaUrl": "https://cdn.irc.example/stories/media/9b2f1c.jpg",
  "thumbnailUrl": "https://cdn.irc.example/stories/thumb/9b2f1c.jpg",
  "textContent": "Notes from today's tafsir circle",
  "lifetimeHours": 8
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `storyType` | string | yes | See story object table |
| `visibility` | string | yes | `PUBLIC` / `FOLLOWERS_ONLY` / `CLOSE_FRIENDS` / `ONLY_ME` |
| `mediaUrl` | string | conditional | Needed for image/video/linked types |
| `thumbnailUrl` | string | no | Video / linked-content thumb |
| `textContent` | string | no | Caption or text-story body |
| `lifetimeHours` | int | no | `8`, `16`, or `24`; anything else falls back to `24` |

### Response — `200 OK`

```json
{
  "authorId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
  "createdAt": "2026-07-20T09:15:00Z",
  "storyId": "0c9d8e7f-6a5b-4c3d-9e2f-1a0b9c8d7e6f",
  "storyType": "IMAGE",
  "visibility": "CLOSE_FRIENDS",
  "mediaUrl": "https://cdn.irc.example/stories/media/9b2f1c.jpg",
  "thumbnailUrl": "https://cdn.irc.example/stories/thumb/9b2f1c.jpg",
  "textContent": "Notes from today's tafsir circle",
  "expiresAt": "2026-07-20T17:15:00Z"
}
```

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 429 | `RATE_LIMITED` | More than 30 `social` writes in the current minute (`details.retryAfterSeconds`) |
| 400 | `MALFORMED_JSON` | Body is not valid JSON |

### Side effects

- Writes `stories_by_author` + `story_lookup` with the chosen per-row TTL.
- No notification is sent; the tray SSE contract for this event is
  `new_story` — see [Realtime](realtime.md#story-tray-stream).

---

## Create story (multipart upload)

```
POST /api/v1/stories
```

**Auth:** required. `Content-Type: multipart/form-data` selects this variant.

Direct photo/video upload. Files are streamed to Cloudflare R2
(`stories/media` and `stories/thumb` prefixes) and the resulting public URLs
are stored on the story row. The **rate-limit check fires *before* the R2
upload**, so a throttled user cannot burn storage bandwidth.

### Form fields

| Name | Kind | Required | Notes |
|---|---|---|---|
| `storyType` | text | no | Defaults to `PHOTO` |
| `visibility` | text | no | Defaults to `PUBLIC` |
| `textContent` | text | no | Caption |
| `lifetimeHours` | text (int) | no | `8` / `16` / `24`; invalid → `24` |
| `media` | file | no | The story body (image / video) |
| `thumbnail` | file | no | Video thumbnail |

### Example

```bash
curl -X POST "https://api.irc.example/api/v1/stories" \
  -H "Authorization: Bearer $TOKEN" \
  -F storyType=VIDEO \
  -F visibility=FOLLOWERS_ONLY \
  -F lifetimeHours=16 \
  -F media=@lecture-clip.mp4 \
  -F thumbnail=@lecture-thumb.jpg
```

### Response — `200 OK`

Same story object as the JSON variant, with `mediaUrl` / `thumbnailUrl`
pointing at the freshly uploaded R2 objects.

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 429 | `RATE_LIMITED` | Social write budget exhausted — checked **before** any upload |
| 413 | `FILE_TOO_LARGE` | Upload exceeds the configured multipart limit |
| 503 | `STORAGE_UNAVAILABLE` | R2 unreachable (SDK client error) |
| 502 | `STORAGE_ERROR` | R2 returned a service error |

### Side effects

- Uploads `media` → `stories/media/…`, `thumbnail` → `stories/thumb/…` on R2.
- Writes the story rows with per-row TTL, as above.

---

## List an author's active stories

```
GET /api/v1/stories/by-author/{authorId}
```

**Auth:** optional. The viewer is derived from the JWT if present; anonymous
callers see `PUBLIC` stories only.

Returns the author's live (non-expired) stories, newest first, filtered by
what *this* viewer is allowed to see. Expired stories are already gone at the
storage layer (TTL), so no client-side expiry filtering is needed.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `authorId` | UUID | The story author to list |

### Response — `200 OK`

```json
[
  {
    "authorId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
    "createdAt": "2026-07-20T09:15:00Z",
    "storyId": "0c9d8e7f-6a5b-4c3d-9e2f-1a0b9c8d7e6f",
    "storyType": "IMAGE",
    "visibility": "PUBLIC",
    "mediaUrl": "https://cdn.irc.example/stories/media/9b2f1c.jpg",
    "thumbnailUrl": "https://cdn.irc.example/stories/thumb/9b2f1c.jpg",
    "textContent": "Notes from today's tafsir circle",
    "expiresAt": "2026-07-20T17:15:00Z"
  }
]
```

An empty array means "no stories visible to you" — indistinguishable, by
design, from "no active stories at all".

### Errors

| Status | errorCode | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | `authorId` is not a valid UUID (including the JS literals `undefined` / `null`) |

---

## Delete a story

```
DELETE /api/v1/stories/{storyId}
```

**Auth:** required. **Author only.**

Hard-deletes a story before its TTL fires ("I changed my mind"). The TTL
would clean it up anyway; this makes it disappear immediately.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `storyId` | UUID | Story to delete |

### Response — `204 No Content`

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 403 | `FORBIDDEN` | Caller is not the story's author (`"Only the author can delete this story"`) |
| 404 | `STORY_NOT_FOUND` | No such story — already expired, already deleted, or never existed |

### Side effects

1. Tombstones the canonical rows (`stories_by_author` + `story_lookup`) —
   this step always runs first.
2. Best-effort wipe of any attached [poll](polls.md) across all five poll
   tables.
3. Fans out a `story_removed` event on the [story-tray SSE
   stream](realtime.md#story-tray-stream) to every viewer whose tray ring the
   story lit — visibility-aware (`PUBLIC` / `FOLLOWERS_ONLY` → all followers,
   keyset-paged and capped at 50 000 recipients; `CLOSE_FRIENDS` → the
   close-friends list; `ONLY_ME` → the author only). The author is always
   notified so their own tray updates.
- Story **view rows are intentionally left to TTL** — no cleanup latency on
  the response.

---

## Record a story view

```
POST /api/v1/stories/{storyId}/views
```

**Auth:** required.

Records that the caller watched a story. Visibility is enforced server-side.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `storyId` | UUID | The story being viewed |

### Response — `202 Accepted` (empty body)

Note that `202` is returned even when nothing was recorded — the following
cases are **silent no-ops** (never an error):

- The story no longer exists (expired / deleted).
- The caller is the author (self-views are not logged).
- The caller is not allowed to see the story (visibility reject).

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |

### Side effects

- Inserts a row into `story_views_by_story`, TTL'd to the **parent story's
  remaining lifetime** — an 8 h story's view log dies with the story.
- No counter is bumped and no notification is sent. The per-story SSE
  contract defines a `story_viewed` / `view_count_updated` event pair — see
  [Realtime](realtime.md#per-story-stream).

---

## Viewer log ("who saw my story?")

```
GET /api/v1/stories/{storyId}/views
```

**Auth:** required — **author only**. Who watched your story is private to
you (Instagram semantics): anonymous callers get `401`, authenticated
non-authors get `403`.

Returns the story's viewers, **newest first** (the underlying table is
clustered `viewed_at DESC`, so this ordering is free).

### Path parameters

| Param | Type | Description |
|---|---|---|
| `storyId` | UUID | The story whose viewer log to read |

### Query parameters

| Param | Type | Default | Description |
|---|---|---|---|
| `pageSize` | int | `50` | Maximum rows returned (first page only; no cursor) |

### Response — `200 OK`

```json
[
  {
    "storyId": "0c9d8e7f-6a5b-4c3d-9e2f-1a0b9c8d7e6f",
    "viewedAt": "2026-07-20T10:42:11Z",
    "viewerId": "3e2d1c0b-9a87-4654-b321-0fedcba98765"
  },
  {
    "storyId": "0c9d8e7f-6a5b-4c3d-9e2f-1a0b9c8d7e6f",
    "viewedAt": "2026-07-20T10:38:54Z",
    "viewerId": "7a6b5c4d-3e2f-4a1b-8c9d-0e1f2a3b4c5d"
  }
]
```

| Field | Type | Description |
|---|---|---|
| `storyId` | UUID | Echo of the path parameter |
| `viewedAt` | ISO-8601 instant | When the view was recorded |
| `viewerId` | UUID | Who viewed |

Expired view rows vanish with the parent story's TTL, so this list is always
scoped to the story's lifetime.

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid token |
| 403 | `FORBIDDEN` | Caller is not the story's author |
| 400 | `TYPE_MISMATCH` | `storyId` is not a valid UUID |

An unknown `storyId` returns an empty list `[]`.

---

## Related

- Attach and vote on story polls: [polls.md](polls.md)
- Who counts as a close friend: [close-friends.md](close-friends.md)
- Archiving stories permanently: [highlights.md](highlights.md)
- Live updates (tray + per-story SSE): [realtime.md](realtime.md)
