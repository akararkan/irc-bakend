# Reels — Complete API Documentation

Full reference for every endpoint that creates, reads, reacts to, saves,
shares, searches, or tracks views on a **Reel** (`postType = REEL`).

Reels are short-form videos stored in the same Cassandra `posts_by_id` table
as every other post type, but they also get an additional write into
`reels_by_day` (day-bucketed global discover feed) and their own watch-history
table `reel_views_by_user`.

---

## Table of contents

1. [Overview & data model](#1-overview--data-model)
2. [Auth & common headers](#2-auth--common-headers)
3. [Unified error response](#3-unified-error-response)
4. [DTOs](#4-dtos)
5. [Create a reel](#5-create-a-reel)
6. [Global reels feed (discover)](#6-global-reels-feed-discover)
7. [Get a single reel](#7-get-a-single-reel)
8. [Edit a reel](#8-edit-a-reel)
9. [Delete a reel](#9-delete-a-reel)
10. [SSE realtime stream](#10-sse-realtime-stream)
11. [Record a view](#11-record-a-view)
12. [Watch history (reel-specific)](#12-watch-history-reel-specific)
13. [Reactions (like / unlike)](#13-reactions-like--unlike)
14. [Comments & replies](#14-comments--replies)
15. [Saves (bookmarks)](#15-saves-bookmarks)
16. [Shares](#16-shares)
17. [Profile feed (author's reels)](#17-profile-feed-authors-reels)
18. [Cassandra tables index](#18-cassandra-tables-index)
19. [Side-effect map](#19-side-effect-map)

---

## 1. Overview & data model

A Reel is a `Post` with `postType = REEL`. On creation the service:

1. Writes the canonical row to **`posts_by_id`** (looked up by UUID).
2. Writes a denormalised row to **`posts_by_author`** (profile feed).
3. Writes a row to **`reels_by_day`** (global discover feed, bucketed by UTC day).
4. Fans out to followers' **`feed_by_user`** timelines (async).
5. Indexes in **Elasticsearch** (async, eventual).
6. Records the event in **`user_activity_by_user`** (Cassandra, async).

Views on a reel write two places:

- **`views_by_post`** — deduped per user, drives `view_count`.
- **`reel_views_by_user`** — per-watch history (not deduped), drives the "watched reels" screen.

All counters (`reactionCount`, `commentCount`, `viewCount`, `saveCount`,
`shareCount`) live in **`post_counters`** (Cassandra counter table) and are
read in bulk per feed page.

---

## 2. Auth & common headers

| Header | Value | Required for |
|---|---|---|
| `Authorization` | `Bearer <accessToken>` | All write endpoints and `me` queries |
| `Content-Type` | `application/json` | JSON body endpoints |
| `Content-Type` | `multipart/form-data` | Multipart upload endpoints |

Anonymous (no JWT) is accepted on all read endpoints. Write endpoints return
`401` when the principal is absent.

---

## 3. Unified error response

```json
{
  "status":    403,
  "errorCode": "FORBIDDEN",
  "message":   "Not the author",
  "timestamp": "2026-05-26T10:30:00"
}
```

| errorCode | HTTP | Condition |
|---|---|---|
| `UNAUTHORIZED` | 401 | Missing or invalid JWT |
| `FORBIDDEN` | 403 | JWT valid but not the author |
| `NOT_FOUND` | 404 | Post id does not exist |
| `UPLOAD_FAILED` | 502 | R2/S3 upload failed (multipart create) |
| `POST_CREATE_FAILED` | 500 | DB write failed after R2 upload (files rolled back) |

---

## 4. DTOs

### 4.1 PostResponse — full single-reel shape

Returned by create, get, and edit.

```json
{
  "id":           "5e5c69f7-eb97-4582-8b14-1ec328171fbd",
  "authorId":     "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id":           "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "username":     "akar",
    "fullName":     "Akar Arkan",
    "avatarUrl":    "https://cdn.example.com/avatars/akar.jpg"
  },
  "postType":     "REEL",
  "status":       "PUBLISHED",
  "visibility":   "PUBLIC",
  "textContent":  "Short caption for this reel #knowledge",
  "audioTrackUrl":  null,
  "audioTrackName": null,
  "locationName": "Erbil, Iraq",
  "locationLat":  36.191,
  "locationLng":  44.009,
  "sharedPostId": null,
  "shareLink":    null,
  "mediaUrls":    ["https://cdn.example.com/posts/media/reel-001.mp4"],
  "mediaTypes":   ["VIDEO"],
  "reactionCount": 42,
  "commentCount":  7,
  "viewCount":     310,
  "saveCount":     18,
  "shareCount":    5,
  "likedByMe":    false,
  "savedByMe":    false,
  "createdAt":    "2026-05-26T09:00:00Z",
  "updatedAt":    "2026-05-26T09:00:00Z",
  "savedAt":      null,
  "savedCollectionName": null
}
```

`savedAt` and `savedCollectionName` are **only populated** in the saved-posts
list (`GET /posts/users/{userId}/saves`); null everywhere else.

### 4.2 FeedItemResponse — lightweight feed shape

Returned by the global reels feed and home timeline.

```json
{
  "id":           "5e5c69f7-eb97-4582-8b14-1ec328171fbd",
  "authorId":     "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id":        "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "username":  "akar",
    "fullName":  "Akar Arkan",
    "avatarUrl": "https://cdn.example.com/avatars/akar.jpg"
  },
  "postType":    "REEL",
  "textPreview": "Short caption for this reel #knowledge",
  "mediaUrl":    "https://cdn.example.com/posts/media/reel-001.mp4",
  "reactionCount": 42,
  "commentCount":  7,
  "viewCount":     310,
  "saveCount":     18,
  "shareCount":    5,
  "likedByMe":   false,
  "savedByMe":   false,
  "createdAt":   "2026-05-26T09:00:00Z"
}
```

`mediaUrl` is the cover/first media — only a single URL (use the full
`PostResponse` to get the full `mediaUrls` list).

### 4.3 ReelViewResponse — watch-history entry

```json
{
  "id":            "uuid",
  "watchedSeconds": 28,
  "reel": {
    "id":              "5e5c69f7-eb97-4582-8b14-1ec328171fbd",
    "textPreview":     "Short caption for this reel #knowledge",
    "thumbnailUrl":    null,
    "mediaUrl":        "https://cdn.example.com/posts/media/reel-001.mp4",
    "durationSeconds": null,
    "author": {
      "id":       "41ee2a6b-2cd9-417b-861c-d1293c623690",
      "username": "akar",
      "fullName": "Akar Arkan",
      "avatarUrl":"https://cdn.example.com/avatars/akar.jpg"
    }
  },
  "watchedAt":    "2026-05-26T09:45:00",
  "timeAgo":      "41 minutes ago",
  "formattedDate":"May 26, 2026"
}
```

---

## 5. Create a reel

Two accepted content types — JSON (pre-uploaded URL) or multipart (upload + create in one call).

---

### 5.1 JSON create

```
POST /api/v1/posts
Content-Type: application/json
Authorization: Bearer <token>
```

**Request body:**

```json
{
  "postType":    "REEL",
  "visibility":  "PUBLIC",
  "textContent": "Optional caption #knowledge",
  "mediaUrls":   ["https://cdn.example.com/posts/media/reel-001.mp4"],
  "mediaTypes":  ["VIDEO"],
  "audioTrackUrl":   null,
  "audioTrackName":  null,
  "locationName":    "Erbil, Iraq",
  "locationLat":     36.191,
  "locationLng":     44.009,
  "soundId":         null,
  "sharedPostId":    null,
  "shareLink":       null
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `postType` | string | **Yes** | Must be `"REEL"` |
| `visibility` | string | No | `PUBLIC` (default), `FOLLOWERS`, `PRIVATE`, `CLOSE_FRIENDS` |
| `textContent` | string | No | Caption; max 280 chars in preview |
| `mediaUrls` | `string[]` | No | Pre-uploaded R2/CDN URLs |
| `mediaTypes` | `string[]` | No | `IMAGE`, `VIDEO`, `AUDIO`, `OTHER` — parallel array to `mediaUrls` |
| `audioTrackUrl` | string | No | Background audio URL |
| `audioTrackName` | string | No | Display name for the track |
| `locationName` | string | No | Free-text location label |
| `locationLat` | double | No | Latitude |
| `locationLng` | double | No | Longitude |
| `soundId` | UUID | No | Sound library ID — bumps `use_count` on the sound |
| `sharedPostId` | UUID | No | Source post if this is a reshare |
| `shareLink` | string | No | External share URL |

**Response:** `200 OK` — `PostResponse`

> **Note:** `authorId` is always derived from the JWT — a body-supplied
> `authorId` is silently ignored.

---

### 5.2 Multipart create (upload + post in one call)

```
POST /api/v1/posts
Content-Type: multipart/form-data
Authorization: Bearer <token>
```

**Form fields** (all optional except `postType`):

| Field | Type | Notes |
|---|---|---|
| `postType` | string | `REEL` |
| `visibility` | string | `PUBLIC` (default) |
| `textContent` | string | Caption |
| `audioTrackUrl` | string | |
| `audioTrackName` | string | |
| `locationName` | string | |
| `locationLat` | string (double) | |
| `locationLng` | string (double) | |
| `sharedPostId` | string (UUID) | |
| `shareLink` | string | |
| `soundId` | string (UUID) | |
| `files` / `files[]` / `media` / `media[]` / `file` / `video` / `videos` / `image` / `images` | binary | Any of these part names are accepted |

**Response:** `200 OK` — `PostResponse`

**Side effects on failure:**
- If R2 upload succeeds but DB write fails → uploaded R2 objects are deleted
  (rollback). Response is `500` with `{"error":"post_create_failed","rolledBackFiles":N}`.
- If R2 upload fails → `502` with `{"error":"upload_failed","message":"..."}`.

---

## 6. Global reels feed (discover)

Returns all reels for a given UTC day bucket, newest first.
Two path aliases — both accept the same parameters.

```
GET /api/v1/posts/reels
GET /api/v1/posts/feed/reels     ← legacy alias
```

**Auth:** none (public)

**Query parameters:**

| Param | Type | Default | Description |
|---|---|---|---|
| `day` | string | today UTC | Day bucket in `YYYY-MM-DD` format. Omit for today. |
| `pageSize` | int | 20 | Number of reels to return (canonical param name) |
| `size` | int | 20 | Legacy alias for `pageSize` |
| `page` | int | 0 | Legacy page index (ignored in cursor-based backend — kept for client compatibility) |

**Effective size** = `size > 0 ? size : pageSize`.

**Response:** `200 OK` — `List<FeedItemResponse>`

```json
[
  {
    "id":          "5e5c69f7-eb97-4582-8b14-1ec328171fbd",
    "authorId":    "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "author":      { "username": "akar", ... },
    "postType":    "REEL",
    "textPreview": "Short caption #knowledge",
    "mediaUrl":    "https://cdn.example.com/posts/media/reel-001.mp4",
    "reactionCount": 42,
    "commentCount":   7,
    "viewCount":     310,
    "saveCount":      18,
    "shareCount":      5,
    "likedByMe":   false,
    "savedByMe":   false,
    "createdAt":   "2026-05-26T09:00:00Z"
  }
]
```

**Cassandra query:** `SELECT * FROM reels_by_day WHERE day_bucket = ? LIMIT ?`

**To page backwards through days**, call with `day=2026-05-25`, then `day=2026-05-24`, etc.
There is no server-side cursor for cross-day pagination — the client iterates day strings.

---

## 7. Get a single reel

```
GET /api/v1/posts/{id}
```

**Auth:** none (public)

**Path param:** `id` — UUID of the reel/post

**Response:** `200 OK` — `PostResponse` (full shape)
**Response:** `404 Not Found` if the id does not exist

---

## 8. Edit a reel

Author-only partial update. Any field left null is untouched.

```
PATCH /api/v1/posts/{id}
Authorization: Bearer <token>
```

**Request body (all fields optional):**

```json
{
  "textContent":   "Updated caption",
  "visibility":    "FOLLOWERS",
  "mediaUrls":     ["https://cdn.example.com/posts/media/reel-002.mp4"],
  "mediaTypes":    ["VIDEO"],
  "audioTrackUrl": null,
  "audioTrackName": null,
  "locationName":  "Baghdad, Iraq",
  "locationLat":   33.341,
  "locationLng":   44.401
}
```

**Response:** `200 OK` — `PostResponse`

**Side effects:**
- Updates `posts_by_id` (canonical row)
- Best-effort update of `posts_by_author` (profile feed mirror)
- Re-indexes in Elasticsearch (async)
- Broadcasts `POST_UPDATED` SSE event on the post's realtime channel

**Errors:**

| Status | Condition |
|---|---|
| 401 | No JWT |
| 403 | JWT valid but caller is not the author |
| 404 | Post not found |

---

## 9. Delete a reel

Author-only hard delete.

```
DELETE /api/v1/posts/{id}
Authorization: Bearer <token>
```

**Response:** `204 No Content`

**Side effects:**
- Hard-deletes `posts_by_id`, `posts_by_author`
- Removes Elasticsearch document (async)
- Does **not** clean up `reels_by_day` rows (they expire naturally via TTL)

**Errors:**

| Status | Condition |
|---|---|
| 401 | No JWT |
| 403 | Caller is not the author |
| 404 | Post not found |

---

## 10. SSE realtime stream

Live event stream for a single reel — receives reactions, comments, view/save/share
counter deltas. Anonymous viewers are allowed (stream is public).

```
GET /api/v1/posts/{id}/stream
```

**Auth:** optional JWT via `Authorization` header **or** `?token=<accessToken>` query param
(browser `EventSource` cannot send custom headers — use `?token=`).

**Response:** `text/event-stream`

Event types:

| Event name | Payload | Description |
|---|---|---|
| `connected` | `{postId}` | Handshake on subscribe |
| `POST_UPDATED` | partial `PostResponse` | Author edited the reel |
| `REACTION_TOGGLED` | `{postId, delta: +1\|-1}` | Like toggled — client applies delta locally |
| `COMMENT_ADDED` | `{postId, delta: +1}` | New comment |
| `COMMENT_DELETED` | `{postId, delta: -N}` | Comment (+ its replies) deleted |
| `VIEW_RECORDED` | `{postId, delta: +1}` | New unique view |
| `SAVE_TOGGLED` | `{postId, delta: +1\|-1}` | Save toggled |
| `SHARE_RECORDED` | `{postId, delta: +1}` | Post shared |
| `heartbeat` | `{}` | Keepalive every ~25 s |

Counters are **delta-based** — the client applies `+1` / `-1` to its local
counter state rather than re-reading from the server.

---

## 11. Record a view

Deduped per user per post. Repeated calls by the same user do not inflate
`view_count`. Anonymous viewers get the current count back but do not bump it.

```
POST /api/v1/posts/{postId}/views
Authorization: Bearer <token>   (optional)
```

**No request body.**

**Response:** `200 OK`

```json
{
  "postId":    "5e5c69f7-eb97-4582-8b14-1ec328171fbd",
  "userId":    "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "counted":   true,
  "viewCount": 311
}
```

| Field | Description |
|---|---|
| `counted` | `true` if this call actually incremented `view_count`; `false` if duplicate or anonymous |
| `viewCount` | Current total after this call |

**Side effects (when `counted = true`):**
- Increments `post_counters.view_count`
- Broadcasts `VIEW_RECORDED` delta event on the SSE stream

> This endpoint tracks **generic post views** (deduped). For **reel-specific
> watch history** (per-session, with `watchedSeconds`) use
> `POST /api/v1/posts/{postId}/reels/view` (§12.1).

---

## 12. Watch history (reel-specific)

These endpoints track full watch sessions with duration, powering the
"Watched reels" history screen. Unlike §11, these are **not deduped** —
each call creates a new `reel_views_by_user` row.

---

### 12.1 Record a watch session

```
POST /api/v1/posts/{postId}/reels/view
Authorization: Bearer <token>
```

**Request body (optional):**

```json
{ "watchedSeconds": 28 }
```

`watchedSeconds` must be ≥ 0. Omit the body entirely to record a watch
without duration data.

**Response:** `201 Created` — `ReelViewResponse`

```json
{
  "id":             "uuid",
  "watchedSeconds": 28,
  "reel": {
    "id":              "5e5c69f7-eb97-4582-8b14-1ec328171fbd",
    "textPreview":     "Short caption #knowledge",
    "thumbnailUrl":    null,
    "mediaUrl":        "https://cdn.example.com/posts/media/reel-001.mp4",
    "durationSeconds": null,
    "author": {
      "id":       "41ee2a6b-2cd9-417b-861c-d1293c623690",
      "username": "akar",
      "fullName": "Akar Arkan",
      "avatarUrl":"https://cdn.example.com/avatars/akar.jpg"
    }
  },
  "watchedAt":    "2026-05-26T09:45:00",
  "timeAgo":      "just now",
  "formattedDate":"May 26, 2026"
}
```

**Side effects:**
- Writes to `reel_views_by_user` (Cassandra)
- Does NOT auto-call `POST /views` — call that endpoint separately if you
  also want to increment `view_count`

**Errors:** `401` if no JWT; `404` if postId does not exist

---

### 12.2 List my watch history

Paginated list of this user's watched reels, newest first.

```
GET /api/v1/users/me/reels/watched?page={page}&size={size}
Authorization: Bearer <token>
```

**Query params:**

| Param | Type | Default |
|---|---|---|
| `page` | int | 0 |
| `size` | int | 20 |

**Response:** `200 OK` — `Page<ReelViewResponse>`

```json
{
  "content": [ { /* ReelViewResponse */ }, ... ],
  "totalElements": 47,
  "totalPages": 3,
  "size": 20,
  "number": 0
}
```

---

### 12.3 Delete one watch entry

```
DELETE /api/v1/users/me/reels/watched/{reelViewId}
Authorization: Bearer <token>
```

**Path param:** `reelViewId` — UUID of the watch entry

**Response:** `204 No Content`

**Errors:** `403` if the entry belongs to a different user; `404` if not found

---

### 12.4 Clear entire watch history

```
DELETE /api/v1/users/me/reels/watched
Authorization: Bearer <token>
```

**Response:** `200 OK`

```json
{ "deleted": 47 }
```

---

## 13. Reactions (like / unlike)

Reels share the same reaction endpoints as all other post types.

---

### 13.1 Toggle like (recommended)

Atomically flips the like state using a Cassandra LWT — a second call
within the same state undoes the first.

```
POST /api/v1/posts/{postId}/reactions
Authorization: Bearer <token>
```

**No request body.**

**Response:** `200 OK`

```json
{
  "postId": "5e5c69f7-eb97-4582-8b14-1ec328171fbd",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "liked":  true
}
```

**Side effects (on like):**
- Inserts into `reactions_by_post` (LWT — `INSERT IF NOT EXISTS`)
- Inserts into `reactions_by_user`
- Increments `post_counters.reaction_count`
- Fires `POST_REACTED` notification to the reel author (aggregated)
- Broadcasts `REACTION_TOGGLED` delta `+1` on the SSE stream

**Side effects (on unlike — second toggle):**
- Deletes from `reactions_by_post` and `reactions_by_user`
- Decrements `post_counters.reaction_count`
- Broadcasts `REACTION_TOGGLED` delta `-1`

---

### 13.2 Explicit unlike

```
DELETE /api/v1/posts/{postId}/reactions
Authorization: Bearer <token>
```

Idempotent — no-op if not currently liked.

**Response:** `200 OK`

```json
{ "postId": "uuid", "liked": false }
```

---

### 13.3 Check if I liked this reel

```
GET /api/v1/posts/{postId}/reactions/me
Authorization: Bearer <token>   (optional)
```

**Response:** `200 OK`

```json
{
  "postId": "uuid",
  "userId": "uuid",
  "liked":  true
}
```

Anonymous callers get `liked: false`.

---

## 14. Comments & replies

Reels share the full comment system with all other post types. Comments are
flat at **depth 1** — replies to a reply land as a sibling under the same
top-level comment.

---

### 14.1 Create a comment

```
POST /api/v1/posts/{postId}/comments
Authorization: Bearer <token>
```

**Request body:**

```json
{
  "text":      "Masha'Allah, great reel!",
  "mediaUrl":  null,
  "mediaType": null
}
```

**Response:** `200 OK` — `CommentResponse`

**Side effects:**
- Writes to `comments_by_post`, `comment_counters`
- Increments `post_counters.comment_count`
- Fires `POST_COMMENTED` notification to the reel author
- Resolves `@mentions` in `text` and fires `USER_MENTIONED` notifications
- Broadcasts `COMMENT_ADDED` delta on the SSE stream

---

### 14.2 List comments

```
GET /api/v1/posts/{postId}/comments?pageSize={n}&cursor={iso-instant}
```

**Auth:** none (public)

**Query params:**

| Param | Type | Default | Description |
|---|---|---|---|
| `pageSize` | int | 20 | |
| `cursor` | Instant | none | Exclusive lower bound; omit for first page |

**Response:** `200 OK` — `List<CommentResponse>` (chronological)

---

### 14.3 Reply to a comment

```
POST /api/v1/posts/comments/{commentId}/replies
Authorization: Bearer <token>
```

**Request body:**

```json
{
  "text":     "JazakAllah khair!",
  "mediaUrl": null
}
```

**Response:** `200 OK` — `ReplyResponse`

**Side effects:**
- Fires `POST_COMMENT_REPLIED` notification to the comment author

---

### 14.4 List replies

```
GET /api/v1/posts/comments/{commentId}/replies?pageSize={n}
```

**Auth:** none (public)

**Response:** `200 OK` — `List<ReplyResponse>`

---

### 14.5 Edit a comment

```
PATCH /api/v1/posts/comments/{commentId}
Authorization: Bearer <token>
```

**Request body:** `{ "text": "edited text" }`

**Response:** `204 No Content`

---

### 14.6 Delete a comment

Hard-deletes the comment and its entire reply thread in a single range
tombstone.

```
DELETE /api/v1/posts/comments/{commentId}
Authorization: Bearer <token>
```

**Response:** `204 No Content`

**Side effects:**
- Decrements `post_counters.comment_count` by `1 + replyCount`
- Broadcasts `COMMENT_DELETED` delta on the SSE stream

---

### 14.7 Toggle like on a comment

```
POST /api/v1/posts/{postId}/comments/{commentId}/reactions
Authorization: Bearer <token>
```

**Response:** `200 OK` — `{commentId, userId, liked: true|false}`

**Side effects:** Fires `POST_COMMENT_REACTED` notification to the comment author

---

### 14.8 Explicit unlike a comment

```
DELETE /api/v1/posts/{postId}/comments/{commentId}/reactions
Authorization: Bearer <token>
```

**Response:** `200 OK` — `{commentId, liked: false}`

---

## 15. Saves (bookmarks)

---

### 15.1 Toggle save

```
POST /api/v1/posts/{postId}/saves?collection={name}
Authorization: Bearer <token>
```

`collection` is optional — a folder/collection name (e.g. `"Favourites"`).
Omit for the default collection.

**Response:** `200 OK`

```json
{
  "postId": "uuid",
  "userId": "uuid",
  "saved":  true
}
```

**Side effects (on save):**
- Inserts into `saves_by_post_user`, `saves_by_user` (LWT)
- Increments `post_counters.save_count`
- Broadcasts `SAVE_TOGGLED` delta `+1`

---

### 15.2 Check if I saved this reel

```
GET /api/v1/posts/{postId}/saves/me
Authorization: Bearer <token>   (optional)
```

**Response:** `200 OK` — `{postId, userId, saved: true|false}`

---

### 15.3 Explicit unsave

```
DELETE /api/v1/posts/{postId}/saves
Authorization: Bearer <token>
```

Idempotent — no-op if not currently saved.

**Response:** `200 OK` — `{postId, saved: false}`

---

### 15.4 My saved reels

Reels saved by the viewer appear in the general saved-posts list. Filter
on the client by `postType === "REEL"` since the endpoint returns all
saved post types.

```
GET /api/v1/posts/users/{userId}/saves?pageSize={n}&cursor={iso-instant}
```

**Auth:** none (public for any userId)

**Response:** `200 OK` — `List<PostResponse>` (newest save first)

In each row: `savedAt` and `savedCollectionName` are populated.

---

## 16. Shares

---

### 16.1 Record a share

```
POST /api/v1/posts/{postId}/shares
Authorization: Bearer <token>
```

**Request body (optional):**

```json
{ "caption": "Check out this reel!" }
```

**Response:** `200 OK` — raw `ShareByPostEntity`

**Side effects:**
- Appends to `shares_by_post` (append-only ledger)
- Increments `post_counters.share_count`
- Fires `POST_SHARED` notification to the reel author
- Broadcasts `SHARE_RECORDED` delta on the SSE stream

---

### 16.2 List shares on a reel

```
GET /api/v1/posts/{postId}/shares?pageSize={n}
```

**Auth:** none (public)

**Response:** `200 OK` — `List<ShareByPostEntity>` (newest first)

---

## 17. Profile feed (author's posts & reels)

### 17.1 Mixed profile feed (all post types)

Returns all of an author's post types mixed (posts, reels, reposts), newest
first, cursor-paginated.

```
GET /api/v1/posts/by-author/{authorId}?pageSize={n}&cursor={iso-instant}
```

**Auth:** none (public)

| Param | Type | Default | Description |
|---|---|---|---|
| `pageSize` | int | 20 | |
| `cursor` | Instant | none | Exclusive; omit for first page |

**Response:** `200 OK` — `List<FeedItemResponse>` (mixed types, newest first)

> **Don't** client-filter this to `postType === "REEL"` for a Reels tab — a
> 20-row page can contain zero reels even when the author has many, so filtering
> breaks pagination. Use §17.2 for a correctly-paginated reel-only list.

### 17.2 Author's reels (reel-only)

Reel-only slice of an author's posts, newest first, cursor-paginated — the
profile **Reels tab**. Pairs with `reelCount` from
`GET /api/v1/users/{id}/stats` (see `USER_API.md` §9.14), so the tab count and
list always agree.

```
GET /api/v1/posts/reels/by-author/{authorId}?pageSize={n}&cursor={iso-instant}
```

**Auth:** none (public)

| Param | Type | Default | Description |
|---|---|---|---|
| `pageSize` | int | 20 | |
| `cursor` | Instant | none | `createdAt` of the last row from the previous page; omit for first page |

**Response:** `200 OK` — `List<FeedItemResponse>` (all `postType = REEL`, newest first)

---

## 18. Cassandra tables index

| Table | Purpose | Key structure |
|---|---|---|
| `posts_by_id` | Canonical reel row — all fields | PK: `post_id` |
| `reels_by_day` | Global discover feed — one row per reel per day | PK: `day_bucket` · CK: `created_at DESC`, `post_id` |
| `posts_by_author` | Profile feed denorm — all post types | PK: `author_id` · CK: `created_at DESC`, `post_id` |
| `feed_by_user` | Follower home timeline fanout | PK: `user_id` · CK: `created_at DESC`, `post_id` |
| `post_counters` | Counter columns: reaction/comment/view/save/share | PK: `post_id` |
| `reactions_by_post` | Per-post per-user like lookup | PK: `post_id` · CK: `user_id` |
| `reactions_by_user` | Per-user reaction history | PK: `user_id` · CK: `created_at DESC` |
| `comments_by_post` | Top-level comments | PK: `post_id` · CK: `created_at`, `comment_id` |
| `replies_by_comment` | Replies under a comment | PK: `comment_id` · CK: `created_at`, `reply_id` |
| `comment_counters` | Per-comment reaction count | PK: `comment_id` |
| `comment_reactions_by_comment` | Per-comment per-user like | PK: `comment_id` · CK: `user_id` |
| `saves_by_post_user` | Save lookup (did I save?) | PK: `post_id` · CK: `user_id` |
| `saves_by_user` | My saves list — cursor-paginated | PK: `user_id` · CK: `created_at DESC`, `post_id` |
| `shares_by_post` | Share ledger per post | PK: `post_id` · CK: `created_at DESC`, `share_id` |
| `views_by_post` | Deduped view tracker | PK: `post_id` · CK: `user_id` |
| `reel_views_by_user` | Per-user watch history (not deduped) | PK: `user_id` · CK: `created_at DESC`, `reel_view_id` |

---

## 19. Side-effect map

What each action touches across the system:

| Action | Cassandra tables written | Counter | Notification | SSE event | Activity row |
|---|---|---|---|---|---|
| **Create reel** | `posts_by_id`, `posts_by_author`, `reels_by_day`, `feed_by_user` (async fanout) | — | — | — | `POST_CREATED` |
| **Delete reel** | `posts_by_id`, `posts_by_author` | — | — | — | — |
| **Record view** | `views_by_post` | `view_count +1` | — | `VIEW_RECORDED +1` | — |
| **Record watch** | `reel_views_by_user` | — | — | — | — |
| **Toggle like (on)** | `reactions_by_post`, `reactions_by_user` | `reaction_count +1` | `POST_REACTED` | `REACTION_TOGGLED +1` | — |
| **Toggle like (off)** | `reactions_by_post`, `reactions_by_user` | `reaction_count -1` | — | `REACTION_TOGGLED -1` | — |
| **Create comment** | `comments_by_post`, `comment_counters` | `comment_count +1` | `POST_COMMENTED` | `COMMENT_ADDED +1` | — |
| **Delete comment** | `comments_by_post`, `replies_by_comment`, `comment_counters` | `comment_count -(1+replies)` | — | `COMMENT_DELETED -N` | — |
| **Toggle save (on)** | `saves_by_post_user`, `saves_by_user` | `save_count +1` | — | `SAVE_TOGGLED +1` | — |
| **Toggle save (off)** | `saves_by_post_user`, `saves_by_user` | `save_count -1` | — | `SAVE_TOGGLED -1` | — |
| **Record share** | `shares_by_post` | `share_count +1` | `POST_SHARED` | `SHARE_RECORDED +1` | — |
| **Like comment (on)** | `comment_reactions_by_comment` | comment `reaction_count +1` | `POST_COMMENT_REACTED` | — | — |
| **Edit reel** | `posts_by_id`, `posts_by_author` (best-effort) | — | — | `POST_UPDATED` | — |
