# Post Package — Complete API Documentation

The full reference for every endpoint under `ak.dev.irc.app.post` — the
Cassandra-backed social-media layer that powers posts, stories, reels,
sounds, highlights, comments, reactions, saves, shares, polls,
hashtags, mentions, and close friends.

Every endpoint below shows:

- **HTTP method + path**
- **Auth requirement** (anonymous-OK or JWT-required)
- **Path / query parameters** (with types and defaults)
- **JSON request body** (where applicable)
- **JSON response body** (full realistic sample)
- **Side effects** (which Cassandra tables get touched, what
  notifications fire, what realtime events broadcast, what activity rows
  get logged)
- **Error responses** (specific HTTP statuses + `errorCode` strings)

---

## Table of contents

1. [Overview](#1-overview)
2. [Authentication & request headers](#2-authentication--request-headers)
3. [Unified error response](#3-unified-error-response)
4. [Enums (full catalog)](#4-enums)
5. [DTOs (response shapes)](#5-dtos)
6. [Post CRUD endpoints](#6-post-crud-endpoints)
7. [Feed endpoints](#7-feed-endpoints)
8. [Search endpoints](#8-search-endpoints)
9. [Friend suggestions](#9-friend-suggestions)
10. [SSE realtime stream](#10-sse-realtime-stream)
11. [Post reactions](#11-post-reactions)
12. [Comment reactions](#12-comment-reactions)
13. [Views](#13-views)
14. [Comments & Replies](#14-comments--replies)
15. [Saves (bookmarks)](#15-saves-bookmarks)
16. [Shares](#16-shares)
17. [Media (carousel)](#17-media-carousel)
18. [Hashtags & Mentions](#18-hashtags--mentions)
19. [Sounds (TikTok-style audio library)](#19-sounds)
20. [Stories](#20-stories)
21. [Close Friends](#21-close-friends)
22. [Story Polls](#22-story-polls)
23. [Highlights (permanent story archives)](#23-highlights)
24. [Realtime event types](#24-realtime-event-types)
25. [Notifications fired by the post layer](#25-notifications-fired-by-the-post-layer)
26. [Activity feed integration](#26-activity-feed-integration)
27. [Cassandra tables index](#27-cassandra-tables-index)
28. [Cross-cutting rules](#28-cross-cutting-rules)

---

## 1. Overview

The post package is the **social-media layer** of the platform. It is
backed by **Cassandra** (denormalised tables, one per query pattern) +
**Elasticsearch** (search) + **Redis** (caching / dedup / counters
mirror) + **R2** (binary storage).

The hierarchy is:

```
Post
 ├── Reactions (single LIKE)
 ├── Comments (chronological, depth-1 replies)
 │    └── Reactions (single LIKE per comment)
 ├── Media (image / video / audio carousel)
 ├── Hashtags (extracted from textContent)
 ├── Mentions (extracted from textContent)
 ├── Sound (TikTok-style adoption)
 ├── Saves (bookmarks per user, with collections)
 ├── Shares (append-only ledger)
 └── Views (deduped per user 7-day window)

Story (24h ephemeral content)
 ├── Visibility: PUBLIC / FOLLOWERS_ONLY / CLOSE_FRIENDS / ONLY_ME
 ├── Poll (two-option, 24h TTL)
 └── Views (viewer log)

Highlight (permanent archive of stories)
 └── StoryInHighlight (snapshot copy of the story content)

Close Friends (per-owner list — drives CLOSE_FRIENDS story visibility)

Sound (reusable audio in the library — TikTok-style)
```

### Key design rules

- **Single reaction type** — only `LIKE`. Mirrors QnA + Research
  ("academic, not entertainment").
- **Replies flat at depth 1** — a reply-to-reply lands as a sibling
  under the same top-level comment. Server-enforced.
- **Self-repost allowed** — users can repost their own posts.
- **JWT-derived authorship** — every create endpoint derives
  `authorId` / `sharerId` from the JWT principal. Body-supplied ids
  are ignored.
- **Async fanout** — feed delivery, search indexing, hashtag indexing,
  notification fan-out, activity feed writes — all `@Async`. Never
  blocks the create response.
- **R2 rollback on DB failure** — multipart create deletes uploaded
  keys if the post-insert fails so the bucket never grows orphans.
- **Counter columns are atomic** — `CounterService` issues raw
  `UPDATE … SET col = col + N` CQL. Spring Data `save()` does NOT
  work on counter tables.
- **JWT filter is SSE-aware** — stale cookies on `/stream` endpoints
  pass through (no 401) so the controller's `?token=` fallback can
  authenticate. Prevents the "CORS error / null status" antipattern
  in Firefox.

---

## 2. Authentication & request headers

Every authenticated endpoint accepts either:

- **`Authorization: Bearer <jwt>`** header (preferred for API clients), OR
- **`access_token`** HttpOnly cookie (preferred for browser clients).

SSE endpoints accept a **third option**: `?token=<jwt>` query parameter,
since browser `EventSource` cannot send custom headers.

### Role legend used throughout this document

Each endpoint section uses these markers:

- **🟢 Public** — no JWT required; the response works for anonymous viewers (state-dependent fields like `likedByMe` are `false`).
- **🔵 Authenticated** — any logged-in user (regular `USER` role and up).
- **🟡 Author-only** — caller must be the author/owner of the resource being mutated. Enforced server-side. Non-author callers get `403` (bare body) or — when the service throws a raw `SecurityException` — `500 INTERNAL_ERROR`.
- **🔴 Admin-only** — endpoint is intended to be called by a `MODERATOR`/`ADMIN`/`SUPER_ADMIN` only. **Not yet enforced at the controller via `@PreAuthorize` for the post package** — the production deployment should gate these at the gateway or via a future role check. Until then they are documented as admin-only so the frontend does not expose them in regular-user UI.

### Admin-only endpoints in the post package

The post package has just a few admin-flavoured endpoints. Frontend
should not surface these in normal user flows:

| Endpoint | Reason |
|----------|--------|
| `POST /api/v1/sounds` with `autoApprove: true` | Skips moderation. Only an admin should be able to publish a sound directly to the category browser. |
| `POST /api/v1/sounds/{id}/approve` | Moderator review of a `PENDING_REVIEW` sound. Promotes it to `APPROVED` and fans it into `sounds_by_category`. |
| `GET /api/v1/polls/{pollId}/voters/{choice}` | Returns the list of users that voted a side. The Javadoc explicitly states the caller is responsible for enforcing story-author or admin permission — the controller does **not** check. Treat as sensitive author/admin data on the frontend. |

Every other endpoint is either public, authenticated-user, or author-only.

### Common request headers

```
Authorization: Bearer eyJhbGciOi...        ← JWT (or cookie)
Content-Type:  application/json            ← for JSON endpoints
                                              (multipart endpoints use
                                              multipart/form-data with
                                              auto-set boundary)
Accept:        application/json
```

### Anonymous-safe endpoints

These endpoints **do NOT 401** when called without auth — they return
a sensible empty / default response:

- `GET /posts/{id}` — public post detail
- `GET /posts/by-author/{authorId}` — public profile feed
- `GET /posts/reels` — public reels
- `GET /posts/search` — public search
- `GET /posts/{id}/stream` — public SSE (anon viewer)
- `GET /posts/{postId}/reactions/me` — returns `{ liked: false }`
- `GET /posts/{postId}/saves/me` — returns `{ saved: false }`
- `GET /posts/{postId}/comments` — public comment list
- `GET /posts/comments/{commentId}/replies` — public reply list
- `GET /posts/{postId}/shares` — public share list
- `GET /posts/users/{userId}/reactions` — public reaction history
- `POST /posts/{postId}/views` — anon viewers see count, don't bump
- `GET /sounds/{id}`, `/sounds/by-category/{cat}`, `/sounds/{id}/posts`,
  `/sounds/{id}/usage` — public sound library
- `GET /stories/by-author/{authorId}` — only PUBLIC stories shown to anon
- `GET /hashtags/{tag}/posts`, `/hashtags/{tag}/usage`
- `GET /users/{userId}/mentions`
- `GET /polls/{pollId}/results`, `/polls/{pollId}/vote/me` (returns `choice: null`)
- `GET /close-friends/is-member` — returns `false` for anon
- `GET /highlights/by-author/{authorId}`, `/highlights/{id}/stories`

### Bare-body endpoints

Some authenticated endpoints return only an HTTP status with **no JSON
body** when auth is missing or the user lacks permission:

- `401` — `if (user == null)` controller short-circuit on every create / mutate endpoint
- `403` — `DELETE /posts/{id}` when caller is not the author
- `404` — `GET /posts/{id}`, `GET /sounds/{id}`, `GET /stories/{id}/poll` when missing

The frontend must check `response.status` for these. They do NOT
include an `ApiErrorResponse` body.

---

## 3. Unified error response

Every other 4xx / 5xx response (except the multipart-create custom
bodies in §6.2) follows this exact shape:

```json
{
  "timestamp": "2026-05-21T14:30:00",
  "status":    400,
  "error":     "Bad Request",
  "message":   "Parameter 'postId' must be of type 'UUID'. Received: 'undefined'",
  "path":      "/api/v1/posts/undefined",
  "errorCode": "TYPE_MISMATCH",
  "details": {
    "parameter":     "postId",
    "expectedType":  "UUID",
    "receivedValue": "undefined",
    "hint":          "frontend_path_param_unhydrated"
  },
  "fieldErrors": null,
  "traceId":     "a1b2c3d4-e5f6-7890-1234-567890abcdef"
}
```

| Field | Type | Always present? | Notes |
|-------|------|------------------|-------|
| `timestamp`   | ISO 8601 LocalDateTime | yes | Server-side time, no zone (UTC) |
| `status`      | int | yes | HTTP status code |
| `error`       | string | yes | HTTP reason phrase |
| `message`     | string | yes | Human-readable, safe to show users |
| `path`        | string | yes | Request URI |
| `errorCode`   | string | yes (except bare-body) | Machine-readable for `switch`/`case` |
| `details`     | map | when set | Per-error context |
| `fieldErrors` | array | only on `VALIDATION_FAILED` | `[{field, message, rejectedValue}, ...]` |
| `traceId`     | string | yes | Surface in UI for support |

### Common error codes per status

| Status | Codes |
|--------|-------|
| `400` | `VALIDATION_FAILED`, `MALFORMED_JSON`, `TYPE_MISMATCH`, `MISSING_PARAMETER`, `ILLEGAL_ARGUMENT`, `BAD_REQUEST` |
| `401` | `AUTH_REQUIRED`, `AUTH_TOKEN_INVALID`, `AUTH_WRONG_TOKEN_TYPE`, `AUTH_USER_NOT_FOUND`, `AUTH_ACCOUNT_DISABLED`, `AUTH_ACCOUNT_LOCKED` |
| `403` | `ACCESS_DENIED`, `ACCESS_FORBIDDEN` |
| `404` | `POST_NOT_FOUND`, `RESOURCE_NOT_FOUND`, `ENDPOINT_NOT_FOUND` |
| `405` | `METHOD_NOT_ALLOWED` |
| `409` | `RESOURCE_CONFLICT`, `*_DUPLICATE`, `DATA_INTEGRITY_VIOLATION` |
| `413` | `FILE_TOO_LARGE` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` |
| `429` | `RATE_LIMITED` |
| `500` | `INTERNAL_ERROR`, `ILLEGAL_STATE` |
| `502` | (multipart-create only) custom `{ error: "upload_failed", message }` |
| `503` | `STORAGE_UNAVAILABLE` (R2/S3 SDK errors) |

---

## 4. Enums

### `PostType` — drives feed-routing and post-shape on the UI

| Value | Meaning |
|-------|---------|
| `TEXT` | Pure text |
| `EMBEDDED` | Text + media (image / video / carousel) |
| `VOICE_POST` | Primary content is voice/audio |
| `REEL` | Short-form video — additionally fans into the global `reels_by_day` feed |
| `REPOST` | Re-share of another user's post (`sharedPostId` set) |
| `STORY` | Ephemeral 24h content (see §20) |

### `PostVisibility`

`PUBLIC` · `FOLLOWERS_ONLY` · `ONLY_ME`

`ONLY_ME` still fans out to the author's own home feed so they see their own posts on `/feed`.

### `PostStatus`

`DRAFT` · `PUBLISHED` · `ARCHIVED` · `REMOVED`

New posts → `PUBLISHED`. (`DRAFT` is in the enum but no endpoint exposes it today — see `BACKEND_ENHANCEMENTS.md` for the drafts roadmap.)

### `PostMediaType`

`IMAGE` · `VIDEO` · `AUDIO_TRACK` · `DOCUMENT`

Stored as parallel arrays with `mediaUrls`.

### `PostReactionType`

`LIKE` — **the only value**. Single-reaction-type project rule.

### `StoryType`

`TEXT` · `IMAGE` · `VIDEO` · `LINKED_POST` · `LINKED_REEL` · `LINKED_QNA` · `LINKED_RESEARCH` · `KNOWLEDGE_PILL` (IRC exclusive — scholar knowledge flashcards)

### `StoryVisibility`

`PUBLIC` · `FOLLOWERS_ONLY` · `CLOSE_FRIENDS` · `ONLY_ME`

Wider than `PostVisibility` — stories add a `CLOSE_FRIENDS` scope.

### `StoryOverlayType`

`TEXT` · `EMOJI` · `STICKER` · `LINK` · `MENTION` · `HASHTAG` · `POLL` · `QUESTION` · `COUNTDOWN` · `LOCATION`

### `ViewerRelationship`

`AUTHOR` · `CLOSE_FRIEND` · `FOLLOWER` · `PUBLIC` — used by the story visibility resolver.

### `SoundCategory`

`NASHEED` · `QURAN_RECITATION` · `LECTURE_CLIP` · `NATURE` · `ORIGINAL` · `PLATFORM_MUSIC`

### `SoundStatus`

`PENDING_REVIEW` · `APPROVED` · `REJECTED` · `ARCHIVED`

---

## 5. DTOs

### `AuthorSummary` — embedded in every post / comment / reply response

```java
record AuthorSummary(
    UUID   id,
    String username,
    String fullName,
    String profileImage
) {}
```

JSON:

```json
{
  "id": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "username": "akar.arkanf19",
  "fullName": "akar arkan",
  "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
}
```

### `PostResponse` — single post

```java
record PostResponse(
    UUID    id,
    UUID    authorId,
    AuthorSummary author,
    String  postType,                 // PostType enum as string
    String  status,                   // PostStatus enum as string
    String  visibility,               // PostVisibility enum as string
    String  textContent,
    String  audioTrackUrl,
    String  audioTrackName,
    String  locationName,
    Double  locationLat,
    Double  locationLng,
    UUID    sharedPostId,             // for REPOST posts
    String  shareLink,
    List<String> mediaUrls,
    List<String> mediaTypes,          // PostMediaType enum strings, parallel to mediaUrls
    // ── Live counters from post_counters ──
    long    reactionCount,
    long    commentCount,
    long    viewCount,
    long    saveCount,
    long    shareCount,
    // ── Viewer-relative flags ──
    boolean likedByMe,
    boolean savedByMe,
    Instant createdAt,
    Instant updatedAt,
    // ── Populated ONLY on saved-list endpoints; null elsewhere ──
    Instant savedAt,
    String  savedCollectionName
) {}
```

### `FeedItemResponse` — light projection for list endpoints

```java
record FeedItemResponse(
    UUID    id,
    UUID    authorId,
    AuthorSummary author,
    String  postType,
    String  textPreview,              // truncated to 280 chars
    String  mediaUrl,                 // first item of the media list, or null
    long    reactionCount,
    long    commentCount,
    long    viewCount,
    long    saveCount,
    long    shareCount,
    boolean likedByMe,
    boolean savedByMe,
    Instant createdAt
) {}
```

### `CommentResponse`

```java
record CommentResponse(
    UUID    id,
    UUID    postId,
    UUID    authorId,
    AuthorSummary author,
    String  textContent,
    String  mediaUrl,
    String  mediaType,
    long    reactionCount,
    long    replyCount,
    boolean likedByMe,
    Boolean deleted,                  // true → text nulled, row kept for thread shape
    Boolean edited,
    Instant createdAt
) {}
```

### `ReplyResponse`

```java
record ReplyResponse(
    UUID    id,
    UUID    parentId,                 // top-level comment id (depth-1 rule)
    UUID    postId,
    UUID    authorId,
    AuthorSummary author,
    String  textContent,
    String  mediaUrl,
    long    reactionCount,
    boolean likedByMe,
    Boolean deleted,
    Boolean edited,
    Instant createdAt
) {}
```

### `CursorPage<T>` — for cursor-paginated endpoints

```java
class CursorPage<T> {
    List<T> items;
    LocalDateTime nextCursor;         // null = end of feed
    boolean hasMore;
}
```

---

## 6. Post CRUD endpoints

Base path: **`/api/v1/posts`**.

### 6.1 `POST /api/v1/posts` — create (JSON)

**Auth:** 🔵 Authenticated.

**What it does.** Creates a new post owned by the authenticated user.
This is the canonical creation path when the media is already uploaded
elsewhere (e.g. by a previous direct-to-R2 upload) and the frontend just
needs to persist the post row referencing those URLs. For uploads
bundled with the post, use the multipart variant in §6.2 instead.

**When the frontend uses this.** A scholar writes a text post, attaches
links to media that was uploaded in a previous step, sets the
visibility, optionally tags a location / sound / reposted post, and
hits "Publish". The response is the fully-hydrated post — the
frontend can prepend it to the home/profile feed immediately without
any follow-up fetch.

**Request body (`CreatePostCommand`):**

```json
{
  "postType":      "EMBEDDED",
  "visibility":    "PUBLIC",
  "textContent":   "Reading at the library today 📚 #fiqh @ahmed",
  "audioTrackUrl": null,
  "audioTrackName": null,
  "locationName":  "Erbil Central Library",
  "locationLat":   36.1911,
  "locationLng":   44.0094,
  "sharedPostId":  null,
  "shareLink":     null,
  "mediaUrls":     ["https://cdn.example.com/posts/2f.jpg"],
  "mediaTypes":    ["IMAGE"],
  "soundId":       null
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `postType`   | string enum | yes | `TEXT` / `EMBEDDED` / `VOICE_POST` / `REEL` / `REPOST` / `STORY` |
| `visibility` | string enum | yes | `PUBLIC` / `FOLLOWERS_ONLY` / `ONLY_ME` |
| `textContent` | string | no (yes for `TEXT`) | Post body |
| `audioTrackUrl` | string | for `VOICE_POST` | R2 URL of the audio file |
| `audioTrackName` | string | no | Display label for audio |
| `locationName` | string | no | Free-text location |
| `locationLat` | double | no | Geo coord |
| `locationLng` | double | no | Geo coord |
| `sharedPostId` | UUID | for `REPOST` | The post being reposted |
| `shareLink` | string | no | External URL (for link cards) |
| `mediaUrls` | string[] | no | R2 public URLs |
| `mediaTypes` | string[] | parallel to `mediaUrls` | `IMAGE` / `VIDEO` / `AUDIO_TRACK` / `DOCUMENT` |
| `soundId` | UUID | no | Adopts a Sound library entry; bumps `sound_counters.use_count` |

> **`authorId` is server-derived from the JWT** — any body value is ignored.

**Response `200`:** full `PostResponse`:

```json
{
  "id": "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id": "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "username": "akar.arkanf19",
    "fullName": "akar arkan",
    "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
  },
  "postType":    "EMBEDDED",
  "status":      "PUBLISHED",
  "visibility":  "PUBLIC",
  "textContent": "Reading at the library today 📚 #fiqh @ahmed",
  "audioTrackUrl":  null,
  "audioTrackName": null,
  "locationName":   "Erbil Central Library",
  "locationLat":    36.1911,
  "locationLng":    44.0094,
  "sharedPostId":   null,
  "shareLink":      null,
  "mediaUrls":      ["https://cdn.example.com/posts/2f.jpg"],
  "mediaTypes":     ["IMAGE"],
  "reactionCount":  0,
  "commentCount":   0,
  "viewCount":      0,
  "saveCount":      0,
  "shareCount":     0,
  "likedByMe":      false,
  "savedByMe":      false,
  "createdAt":      "2026-05-21T14:30:00Z",
  "updatedAt":      "2026-05-21T14:30:00Z",
  "savedAt":        null,
  "savedCollectionName": null
}
```

### Other post-type create examples

**TEXT post** — request body:

```json
{
  "postType":   "TEXT",
  "visibility": "PUBLIC",
  "textContent": "On the importance of consistency in daily worship — a thread.",
  "mediaUrls":  [],
  "mediaTypes": []
}
```

**REPOST with caption** — request body:

```json
{
  "postType":   "REPOST",
  "visibility": "PUBLIC",
  "textContent": "Adding my own thoughts on this 👇",
  "sharedPostId": "a1b2c3d4-5e6f-7890-1234-567890abcdef",
  "mediaUrls":  [],
  "mediaTypes": []
}
```

**REEL using a sound** — request body:

```json
{
  "postType":   "REEL",
  "visibility": "PUBLIC",
  "textContent": "60-second recap from yesterday's lecture",
  "mediaUrls":   ["https://cdn.example.com/reels/clip-9c1f.mp4"],
  "mediaTypes":  ["VIDEO"],
  "soundId":     "snd-1234abcd"
}
```

**VOICE_POST** — request body:

```json
{
  "postType":       "VOICE_POST",
  "visibility":     "FOLLOWERS_ONLY",
  "audioTrackUrl":  "https://cdn.example.com/voice/note-a1b2.m4a",
  "audioTrackName": "Quick note about today",
  "mediaUrls":      [],
  "mediaTypes":     []
}
```

### Side effects on create

Synchronous writes:

1. `posts_by_id` (canonical row)
2. `posts_by_author` (profile feed)
3. `reels_by_day` (only if `postType == "REEL"`)
4. `sound_counters.use_count++` + `posts_by_sound` (only if `soundId` set)
5. Hashtag extraction → `posts_by_hashtag` + `hashtag_counters++`
6. Mention extraction → `mentions_by_user` (per recipient)

Asynchronous:

7. Followers fan-out to `feed_by_user` (capped at 50 000 followers)
8. Elasticsearch index
9. Per-recipient notification (`POST_NEW` for followers, `USER_MENTIONED` for mention recipients)
10. Activity row `POST_CREATED` for the author

### Error responses

| Condition | HTTP | `errorCode` |
|-----------|------|-------------|
| Not authenticated | `401` | bare body |
| Body unparseable | `400` | `MALFORMED_JSON` |
| Cassandra write fails | `500` | `INTERNAL_ERROR` |

---

### 6.2 `POST /api/v1/posts` — create (multipart)

**Auth:** 🔵 Authenticated.

**What it does.** Creates a new post **and** uploads the binary media
in a single multipart request. Each `files[]` part is streamed to R2,
and the resulting public URLs are written into `mediaUrls` automatically.
This is the path the in-app composer uses for image posts and reels —
the user picks files, types a caption, hits Publish, one round-trip.

**Why it exists.** Atomic create-with-files. If any R2 upload fails,
nothing is persisted. If R2 succeeds but the Cassandra write fails,
every uploaded R2 key is best-effort deleted so the bucket never grows
orphans. Frontend gets a structured error in both cases (see the
"R2 upload failure" and "DB-after-R2 failure" error tables below).

**Frontend hint.** Accepted file part names are very permissive —
`files`, `files[]`, `media`, `media[]`, `file`, `video`, `videos`,
`image`, `images` all work — so a single composer component can post
to this endpoint regardless of its internal field naming.

**Request:** `Content-Type: multipart/form-data`. Form fields (all
optional except `postType`):

| Form field | Type | Notes |
|------------|------|-------|
| `postType` | string | required (defaults to `"POST"` if not supplied, which the schema treats as `TEXT`) |
| `visibility` | string | defaults to `"PUBLIC"` |
| `textContent` | string | optional |
| `audioTrackUrl` | string | optional |
| `audioTrackName` | string | optional |
| `locationName` | string | optional |
| `locationLat` | double | optional |
| `locationLng` | double | optional |
| `sharedPostId` | UUID | optional |
| `shareLink` | string | optional |
| `soundId` | UUID | optional |
| `files[]` / `files` / `media[]` / `media` / `file` / `video` / `videos` / `image` / `images` | file | repeatable — any of these part names is accepted |

```bash
curl -X POST https://api.irc.example.com/api/v1/posts \
  -H "Authorization: Bearer <jwt>" \
  -F 'postType=REEL' \
  -F 'visibility=PUBLIC' \
  -F 'textContent=Quick recap from todays lecture' \
  -F 'locationName=Cairo' \
  -F 'files[]=@reel.mp4;type=video/mp4' \
  -F 'files[]=@thumb.jpg;type=image/jpeg'
```

**Response `200`:** same shape as the JSON create — full `PostResponse`.

**Error responses:**

| Condition | HTTP | Body |
|-----------|------|------|
| Not authenticated | `401` | bare body |
| File exceeds `max-file-size` | `413` | `FILE_TOO_LARGE` (`ApiErrorResponse`) |
| Wrong content type | `415` | `UNSUPPORTED_MEDIA_TYPE` |
| **R2 upload failure** | `502` | **Custom body, NOT `ApiErrorResponse`:** `{ "error": "upload_failed", "message": "<r2 error>" }`. Previously-uploaded R2 keys are best-effort deleted. |
| **DB insert fails after R2 success** | `500` | **Custom body:** `{ "error": "post_create_failed", "message": "...", "rolledBackFiles": <int> }`. All R2 keys deleted. |

---

### 6.3 `GET /api/v1/posts/{id}` — single post

**Auth:** 🟢 Public (anonymous-safe). `likedByMe` / `savedByMe` reflect
the authenticated viewer when present; both default to `false` for
anonymous viewers.

**What it does.** Returns the canonical post row joined with the
author's profile summary, the live denormalised counters
(`reactionCount`, `commentCount`, …), and the viewer-relative flags.

**When the frontend uses this.** The post-detail page on direct URL
load. Also used to hydrate one post id (e.g. when an SSE event arrives
referencing a post the client doesn't have cached).

**Performance.** Single Cassandra point-read on `posts_by_id` +
one counter row + one author lookup + 2 viewer-relative lookups
(reaction + save). Sub-millisecond when the counter row is hot in the
Redis mirror.

**Path parameter:** `id` — UUID.

**Response `200`:** full `PostResponse`:

```json
{
  "id": "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id": "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "username": "akar.arkanf19",
    "fullName": "akar arkan",
    "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
  },
  "postType":   "EMBEDDED",
  "status":     "PUBLISHED",
  "visibility": "PUBLIC",
  "textContent": "Reading at the library today 📚 #fiqh @ahmed",
  "audioTrackUrl": null,
  "audioTrackName": null,
  "locationName": "Erbil Central Library",
  "locationLat":  36.1911,
  "locationLng":  44.0094,
  "sharedPostId": null,
  "shareLink":    null,
  "mediaUrls":   ["https://cdn.example.com/posts/2f.jpg"],
  "mediaTypes":  ["IMAGE"],
  "reactionCount": 12,
  "commentCount":  3,
  "viewCount":     345,
  "saveCount":     7,
  "shareCount":    1,
  "likedByMe":  true,
  "savedByMe":  false,
  "createdAt": "2026-05-21T14:30:00Z",
  "updatedAt": "2026-05-21T14:30:00Z",
  "savedAt": null,
  "savedCollectionName": null
}
```

**Error responses:**

| Condition | HTTP | Body |
|-----------|------|------|
| `{id}` malformed UUID | `400` | `TYPE_MISMATCH` |
| Post not found / hard-deleted | `404` | bare body (no JSON) |

---

### 6.4 `PATCH /api/v1/posts/{id}` — partial edit (author-only)

**Auth:** 🟡 Author-only.

**What it does.** Edits the post body / visibility / location / media
in place. Every field on the body is optional; `null` means "leave
untouched". Only the author can edit their own post — non-authors get
`403` (bare body).

**When the frontend uses this.** The "Edit post" action on the post
menu. Common cases: fix a typo in `textContent`, change `visibility`
from `PUBLIC` to `FOLLOWERS_ONLY`, swap the media list, update the
location pin.

**Side effects.** The post's `updatedAt` is refreshed. The
profile-feed mirror (`posts_by_author`) is updated best-effort.
Elasticsearch is async-reindexed. Every open SSE subscriber gets a
`POST_UPDATED` event so their UI reconciles without a refetch.

**Path parameter:** `id` — UUID of the post to edit.

**Request body (`EditPostCommand`):** every field nullable; null = leave
untouched.

```json
{
  "textContent":    "Edited body — typo fix",
  "visibility":     "FOLLOWERS_ONLY",
  "audioTrackUrl":  null,
  "audioTrackName": null,
  "locationName":   "Erbil",
  "locationLat":    36.19,
  "locationLng":    44.00,
  "mediaUrls":      null,
  "mediaTypes":     null
}
```

**Response `200`:** updated `PostResponse` with fresh `updatedAt`:

```json
{
  "id": "f66aebce-...",
  "authorId": "41ee2a6b-...",
  "author": { "id": "41ee2a6b-...", "username": "akar.arkanf19", "fullName": "akar arkan", "profileImage": "..." },
  "postType":   "EMBEDDED",
  "status":     "PUBLISHED",
  "visibility": "FOLLOWERS_ONLY",
  "textContent": "Edited body — typo fix",
  "audioTrackUrl": null, "audioTrackName": null,
  "locationName": "Erbil",
  "locationLat":  36.19,
  "locationLng":  44.00,
  "sharedPostId": null, "shareLink": null,
  "mediaUrls":   ["https://cdn.example.com/posts/2f.jpg"],
  "mediaTypes":  ["IMAGE"],
  "reactionCount": 12, "commentCount": 3, "viewCount": 345,
  "saveCount": 7, "shareCount": 1,
  "likedByMe": true, "savedByMe": false,
  "createdAt": "2026-05-21T14:30:00Z",
  "updatedAt": "2026-05-21T14:45:00Z",
  "savedAt": null, "savedCollectionName": null
}
```

### Side effects

- `posts_by_id.updatedAt = now`.
- Best-effort mirror onto `posts_by_author` (profile feed).
- Async Elasticsearch re-index.
- Broadcasts `POST_UPDATED` on the post's SSE channel.

### Error responses

| Condition | HTTP | Body |
|-----------|------|------|
| Not authenticated | `401` | bare body |
| Not the author | `403` | bare body |
| Post not found | `404` | bare body |
| Body unparseable | `400` | `MALFORMED_JSON` |

---

### 6.5 `DELETE /api/v1/posts/{id}` — hard-delete (author-only)

**Auth:** 🟡 Author-only.

**What it does.** Permanently removes the post from `posts_by_id` and
its profile-feed mirror, and queues async deletion from the
Elasticsearch index. `feed_by_user` rows die naturally via the 30-day
TTL — no expensive wide-row sweep needed.

**When the frontend uses this.** The user taps the post menu → Delete
post → confirms. After the `204`, remove the post from the local
feed/profile cache.

**Caveats.** This is a hard delete (no soft-delete recovery). For an
"archive" UX (hide from feeds but keep at the URL) consider waiting on
`PostStatus.ARCHIVED` — the enum exists but no endpoint exposes it yet
(see `BACKEND_ENHANCEMENTS.md` §2.9).

**Path parameter:** `id` — UUID.

**Request body:** none.

**Response:** `204 No Content`.

### Side effects

- `posts_by_id` row removed.
- `posts_by_author` row removed (best-effort).
- Async Elasticsearch de-indexing.
- `feed_by_user` rows die naturally via 30-day TTL.

### Error responses

| Condition | HTTP | Body |
|-----------|------|------|
| Not authenticated | `401` | bare body |
| Post not found | `404` | bare body |
| Not the author | `403` | bare body |

---

## 7. Feed endpoints

### 7.1 `GET /api/v1/posts/feed` — home timeline

Also available as **`GET /api/v1/posts/feed/cursor`** (legacy alias —
same response).

**Auth:** 🔵 Authenticated (anon-without-`?userId=` returns `[]`).

**What it does.** Returns the viewer's home timeline — every post by
an author they follow, plus their own posts, newest first. This is the
single most important endpoint for the social UI.

**How the list is built.** Fanout-on-write: when an author publishes,
a row is inserted into every follower's `feed_by_user` partition.
Reads are a single partition scan (sub-millisecond) backed by a Redis
`ZSET` cache. `ONLY_ME` posts still fan out to the author's own
partition so they see their own private posts in their feed.

**Cursor pagination.** First page: omit `cursor`. Next page: pass the
`createdAt` of the last item from the previous page as `?cursor=`.
End-of-feed: the response array is empty.

**Frontend tips.**
- Use `pageSize=20` as the default; bump to `50` for infinite-scroll surfaces with virtualised lists.
- Skeleton-load using the cached previous page while the new page resolves.
- Cache the response in IndexedDB keyed by `userId` for instant cold-load on app start; the SSE stream from the post-detail page is the live source of truth for individual posts.

**Query parameters:**

| Name | Type | Default | Notes |
|------|------|---------|-------|
| `userId` | UUID | from JWT | Legacy fallback; the JWT principal is preferred |
| `pageSize` | int | 20 | Canonical name |
| `limit` | int | 20 | Legacy alias for `pageSize` |
| `cursor` | ISO Instant | none | Pass `nextCursor` from the previous page |

**Response `200`:** `List<FeedItemResponse>` — newest first.

This is the **most important endpoint** for the social feed. Sample
response showing 5 items of different post types:

```json
[
  {
    "id": "f66aebce-d659-45b8-8479-75195f5d6d4b",
    "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "author": {
      "id": "41ee2a6b-2cd9-417b-861c-d1293c623690",
      "username": "akar.arkanf19",
      "fullName": "akar arkan",
      "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
    },
    "postType": "EMBEDDED",
    "textPreview": "Reading at the library today 📚 #fiqh @ahmed",
    "mediaUrl": "https://cdn.example.com/posts/2f.jpg",
    "reactionCount": 12,
    "commentCount":  3,
    "viewCount":     345,
    "saveCount":     7,
    "shareCount":    1,
    "likedByMe": true,
    "savedByMe": false,
    "createdAt": "2026-05-21T14:30:00Z"
  },
  {
    "id": "aa11bb22-3344-5566-7788-99aabbccddee",
    "authorId": "9c1f1a2b-3344-5566-7788-99aabbccddee",
    "author": {
      "id": "9c1f1a2b-3344-5566-7788-99aabbccddee",
      "username": "ahmad",
      "fullName": "Ahmad Rahman",
      "profileImage": "https://cdn.example.com/avatars/9c1f.jpg"
    },
    "postType": "REEL",
    "textPreview": "60-second recap from yesterday's lecture",
    "mediaUrl": "https://cdn.example.com/reels/clip-9c1f.mp4",
    "reactionCount": 124,
    "commentCount":  18,
    "viewCount":     8492,
    "saveCount":     35,
    "shareCount":    7,
    "likedByMe": false,
    "savedByMe": true,
    "createdAt": "2026-05-21T13:15:00Z"
  },
  {
    "id": "bb22cc33-4455-6677-8899-aabbccddeeff",
    "authorId": "41ee2a6b-...",
    "author": {
      "id": "41ee2a6b-...",
      "username": "akar.arkanf19",
      "fullName": "akar arkan",
      "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
    },
    "postType": "TEXT",
    "textPreview": "On the importance of consistency in daily worship — a thread.",
    "mediaUrl": null,
    "reactionCount": 56,
    "commentCount":  12,
    "viewCount":     1240,
    "saveCount":     22,
    "shareCount":    4,
    "likedByMe": true,
    "savedByMe": false,
    "createdAt": "2026-05-21T11:00:00Z"
  },
  {
    "id": "cc33dd44-5566-7788-99aa-bbccddeeff00",
    "authorId": "7d2e3f4a-...",
    "author": {
      "id": "7d2e3f4a-...",
      "username": "fatima",
      "fullName": "Fatima Yusuf",
      "profileImage": null
    },
    "postType": "VOICE_POST",
    "textPreview": null,
    "mediaUrl": "https://cdn.example.com/voice/note-a1b2.m4a",
    "reactionCount": 8,
    "commentCount":  2,
    "viewCount":     94,
    "saveCount":     3,
    "shareCount":    0,
    "likedByMe": false,
    "savedByMe": false,
    "createdAt": "2026-05-21T08:30:00Z"
  },
  {
    "id": "dd44ee55-6677-8899-aabb-ccddeeff0011",
    "authorId": "9c1f1a2b-...",
    "author": {
      "id": "9c1f1a2b-...",
      "username": "ahmad",
      "fullName": "Ahmad Rahman",
      "profileImage": "https://cdn.example.com/avatars/9c1f.jpg"
    },
    "postType": "REPOST",
    "textPreview": "Adding my own thoughts on this 👇",
    "mediaUrl": null,
    "reactionCount": 4,
    "commentCount":  1,
    "viewCount":     220,
    "saveCount":     1,
    "shareCount":    0,
    "likedByMe": false,
    "savedByMe": false,
    "createdAt": "2026-05-20T22:45:00Z"
  }
]
```

Empty feed: `200` + `[]`.

### How the home feed is built

- Backed by `feed_by_user` (fanout-on-write — each post by an author you
  follow gets a row in your partition at write time).
- Read path: 1) Redis ZSET (`feed:timeline:{userId}`, last ~100 post
  ids by score = `createdAt`) → 2) fallback to a `feed_by_user`
  partition slice → 3) backfill Redis on cache miss.
- Self-fanout: the author's own posts are inserted into their own
  partition so the home feed always shows their own posts.
- `ONLY_ME` posts only fan out to the author's own partition.

### Fanout-on-write internals

- **Async** — fanout runs on a Spring `@Async` thread, off the HTTP
  request path. The create response returns as soon as the canonical
  post row is committed.
- **Keyset follower pagination.** The fanout walks followers in batches
  of 500 via `UserFollowRepository.findFollowerIdsAfter(authorId, cursor,
  limit)` — keyset on `follower.id ASC`, NOT offset pagination. Each
  page costs the same constant time regardless of how deep the scan is,
  so an author with 50k followers doesn't pay quadratic time on the
  tail pages.
- **Parallel per-batch writes.** Within each batch the per-follower
  work (`feed_by_user` insert + Redis ZSET update + realtime publish +
  `POST_NEW` notification queue) runs through `parallelStream` so the
  500 writes share ForkJoinPool's parallelism instead of serializing on
  one async thread.
- **Cap.** Hard-capped at `MAX_FANOUT_FOLLOWERS = 50,000`. Beyond that
  the design moves to a "celebrity push" pattern (small
  `celebrity_posts` table merged at read time) — not yet implemented.
- **Notification email-eligibility.** `POST_NEW` is configured
  `emailEligible=false`, so a creator with 50k followers does NOT
  trigger 50k emails — only in-app inbox rows and the realtime SSE push.

---

### 7.2 `GET /api/v1/posts/by-author/{authorId}` — profile feed

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns every post written by a specific author,
newest first, cursor-paginated. Backed by `posts_by_author` — a single
partition scan, so this is fast even on a researcher with thousands of
posts.

**When the frontend uses this.** Rendering a user's profile page. Pair
this with the user's profile DTO (from `/api/v1/users/{id}/profile`)
and the lightweight `FeedItemResponse` rows give you everything the
profile grid needs (cover thumbnail, post type, counters, viewer-
relative like/save flags) without per-post round-trips.

**Path parameter:** `authorId` — UUID.

**Query parameters:**

| Name | Type | Default |
|------|------|---------|
| `pageSize` | int | 20 |
| `cursor` | ISO Instant | none |

**Response `200`:** `List<FeedItemResponse>` — only posts by that author, newest first.

```json
[
  {
    "id": "f66aebce-...",
    "authorId": "41ee2a6b-...",
    "author": {
      "id": "41ee2a6b-...",
      "username": "akar.arkanf19",
      "fullName": "akar arkan",
      "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
    },
    "postType": "EMBEDDED",
    "textPreview": "Reading at the library today...",
    "mediaUrl": "https://cdn.example.com/posts/2f.jpg",
    "reactionCount": 12, "commentCount": 3, "viewCount": 345,
    "saveCount": 7, "shareCount": 1,
    "likedByMe": false, "savedByMe": false,
    "createdAt": "2026-05-21T14:30:00Z"
  },
  {
    "id": "bb22cc33-...",
    "authorId": "41ee2a6b-...",
    "author": { "id": "41ee2a6b-...", "username": "akar.arkanf19", "fullName": "akar arkan", "profileImage": "..." },
    "postType": "TEXT",
    "textPreview": "On the importance of consistency...",
    "mediaUrl": null,
    "reactionCount": 56, "commentCount": 12, "viewCount": 1240,
    "saveCount": 22, "shareCount": 4,
    "likedByMe": false, "savedByMe": false,
    "createdAt": "2026-05-21T11:00:00Z"
  }
]
```

Backed by `posts_by_author` — partition per author, clustered DESC by `created_at`.

---

### 7.3 `GET /api/v1/posts/reels` — global reels discover feed

Also available as **`GET /api/v1/posts/feed/reels`** (legacy alias).

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the global reels discover feed for a given
UTC day. Defaults to today. Reels are partitioned by UTC date in the
`reels_by_day` Cassandra table so each partition stays bounded — a
single date doesn't bloat over time.

**When the frontend uses this.** The Reels tab / discover surface.
Day-bucketing lets you implement "Yesterday's top reels" by passing
`?day=2026-05-20`. A native scroll-back-in-time UI can walk the bucket
chain `today → today-1 → today-2 → …` and concat.

**Note.** This is NOT a ranked / algorithmic feed — it's chronological
within each day. The trending / For-You ranked feed is on the roadmap
(see `BACKEND_ENHANCEMENTS.md` §1.3 and §1.4).

**Query parameters:**

| Name | Type | Default | Notes |
|------|------|---------|-------|
| `day` | string `YYYY-MM-DD` (UTC) | today | The day bucket to read |
| `pageSize` | int | 20 | Canonical |
| `size` | int | 20 | Legacy alias |
| `page` | int | 0 | Legacy — currently unused |

**Response `200`:** `List<FeedItemResponse>` — `postType` always `"REEL"`.

```json
[
  {
    "id": "aa11bb22-3344-5566-7788-99aabbccddee",
    "authorId": "9c1f1a2b-...",
    "author": {
      "id": "9c1f1a2b-...",
      "username": "ahmad",
      "fullName": "Ahmad Rahman",
      "profileImage": "https://cdn.example.com/avatars/9c1f.jpg"
    },
    "postType": "REEL",
    "textPreview": "60-second recap from yesterday's lecture",
    "mediaUrl": "https://cdn.example.com/reels/clip-9c1f.mp4",
    "reactionCount": 124,
    "commentCount":  18,
    "viewCount":     8492,
    "saveCount":     35,
    "shareCount":    7,
    "likedByMe": false,
    "savedByMe": true,
    "createdAt": "2026-05-21T13:15:00Z"
  },
  {
    "id": "ee55ff66-7788-99aa-bbcc-ddeeff001122",
    "authorId": "1234abcd-...",
    "author": {
      "id": "1234abcd-...",
      "username": "yusuf",
      "fullName": "Yusuf Khan",
      "profileImage": "https://cdn.example.com/avatars/1234.jpg"
    },
    "postType": "REEL",
    "textPreview": "Three minutes on tajweed basics",
    "mediaUrl": "https://cdn.example.com/reels/clip-1234.mp4",
    "reactionCount": 89,
    "commentCount":  11,
    "viewCount":     5230,
    "saveCount":     22,
    "shareCount":    3,
    "likedByMe": true,
    "savedByMe": false,
    "createdAt": "2026-05-21T09:42:00Z"
  }
]
```

Backed by `reels_by_day` — partitioned per UTC date so partition size stays bounded.

---

## 8. Search endpoints

### 8.1 `GET /api/v1/posts/search` — Elasticsearch full-text search

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Searches the post Elasticsearch index by free-text
query, returning ranked post UUIDs. The frontend then hydrates each
via `GET /api/v1/posts/{id}`. The index is updated asynchronously on
post create / edit / delete — eventually consistent (typically <1s).

**When the frontend uses this.** Global search bar, advanced search,
hashtag search disambiguation.

**Future.** Today the response is bare UUIDs; on the roadmap is
returning `PostSummary` preview rows inline (with `textPreview`,
`thumbnailUrl`, `author`, `createdAt`) to avoid the N+1 hydration. See
`BACKEND_ENHANCEMENTS.md` §1.4-ish search-preview note.

**Query parameters:**

| Name | Type | Required | Default |
|------|------|----------|---------|
| `q` | string | yes | — |
| `page` | int | no | 0 |
| `size` | int | no | 20 |

**Response `200`:** ranked post UUIDs — frontend hydrates each via `GET /posts/{id}`.

```json
{
  "query":   "zakat",
  "page":    0,
  "size":    20,
  "results": [
    "f66aebce-d659-45b8-8479-75195f5d6d4b",
    "a1b2c3d4-5e6f-7890-1234-567890abcdef",
    "bb22cc33-4455-6677-8899-aabbccddeeff"
  ]
}
```

Empty result: `{ "query": "...", "page": 0, "size": 20, "results": [] }`.

### Error responses

| Condition | HTTP | `errorCode` |
|-----------|------|-------------|
| Missing `q` | `400` | `MISSING_PARAMETER` |
| Elasticsearch unreachable | `500` | `INTERNAL_ERROR` |

---

## 9. Friend suggestions

### 9.1 `GET /api/v1/posts/suggestions` — top suggestions for a user

**Auth:** 🔵 Authenticated.

**What it does.** Returns the pre-computed list of users this account
might want to follow — pure friends-of-friends collaborative filtering.
Score = number of mutuals; min 2 mutuals; top 50 stored per user.

**When the frontend uses this.** "Who to follow" sidebar, onboarding
suggestions, empty-feed recommendations.

**Refresh cadence.** Pre-computed by an async job, stored in
`friend_suggestions_by_user` (partition per user, clustered by
`score DESC`). Trigger an explicit refresh via §9.2 after a significant
graph change.

**Query parameters:**

| Name | Type | Required | Default |
|------|------|----------|---------|
| `userId` | UUID | yes | — |
| `limit` | int | no | 20 |

**Response `200`:** pre-computed list, already sorted by mutual-count DESC.

```json
[
  {
    "userId":      "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "score":       6,
    "candidateId": "9c1f1a2b-3344-5566-7788-99aabbccddee",
    "reason":      "6 mutual follows",
    "computedAt":  "2026-05-20T03:15:00Z"
  },
  {
    "userId":      "41ee2a6b-...",
    "score":       4,
    "candidateId": "7d2e3f4a-...",
    "reason":      "4 mutual follows",
    "computedAt":  "2026-05-20T03:15:00Z"
  },
  {
    "userId":      "41ee2a6b-...",
    "score":       3,
    "candidateId": "1234abcd-...",
    "reason":      "3 mutual follows",
    "computedAt":  "2026-05-20T03:15:00Z"
  }
]
```

Algorithm: friends-of-friends mutual-follow scoring. Min 2 mutuals; top 50 stored per user.

---

### 9.2 `POST /api/v1/posts/suggestions/recompute` — trigger async recompute

**Auth:** 🔵 Authenticated.

**What it does.** Fires an asynchronous recompute of the user's
suggestion partition. The walk is bounded (top 50 mutuals stored).

**When the frontend uses this.** After a follow / unfollow burst (e.g.
the user just imported their contacts and followed 30 people), to
refresh the "who to follow" pane. Or as a background hook on the user
profile page after the friend graph changes.

**Returns immediately.** `202 Accepted` with an empty body. The
recompute itself runs on the Spring async pool.

**Query parameter:** `userId` (UUID, required).

**Request body:** none.

**Response:** `202 Accepted` (empty body). The recompute runs on the
async pool.

---

## 10. SSE realtime stream

### 10.1 `GET /api/v1/posts/{id}/stream` — per-post events

**Auth:** 🟢 Public (anonymous viewers allowed). Authenticated viewers:

- Via JWT principal (header / cookie), OR
- Via **`?token=<jwt>`** query parameter (for browser `EventSource`)

**What it does.** Opens a Server-Sent Events connection that pushes
every interaction with this post in real time: reactions, comments,
replies, view-count bumps, save-count bumps, share-count bumps, edits,
deletes. One subscription per post; multiple subscriptions per viewer
are fine (different tabs).

**When the frontend uses this.** The post-detail page mounts an
`EventSource`, listens for the named events, and patches the local
counters / comment list without polling. The actor's own subscription
is filtered out server-side so the originating tab doesn't render its
own action twice.

**Cross-instance.** Even if the action was written on a different
server instance, it reaches your tab — events flow through Redis
pub/sub so any pod can broadcast to any subscriber.

**Reconnection.** `EventSource` auto-reconnects on disconnect. Server
sends a `reconnectTime` hint of 3 seconds so a JVM restart doesn't
trigger a connection storm.

**Anti-buffering headers.** The response explicitly sets
`X-Accel-Buffering: no` and `Cache-Control: no-cache` so Railway /
Nginx / Cloudflare don't hold events until the response closes.

**Counter values are NOT bundled in events.** The fresh
`postReactionCount` / `postCommentCount` / `postSaveCount` /
`postViewCount` / `postShareCount` / `commentReactionCount` /
`commentReplyCount` fields are intentionally omitted from
`PostRealtimeEvent`. Cassandra counter columns are eventually
consistent, so re-reading right after the increment can return a
stale number. Clients apply the +1 / -1 delta locally from the event
type (`REACTION_ADDED` → +1, `REACTION_REMOVED` → −1, etc.) and
reconcile with the canonical counter on the next REST fetch.

**Path parameter:** `id` — post UUID.

**Response:** `Content-Type: text/event-stream`. The server sets:

```
X-Accel-Buffering:  no
Cache-Control:      no-cache, no-store, must-revalidate
Connection:         keep-alive
```

Sample event sequence (note: counter fields are NOT present — clients
apply local deltas):

```
event: connected
data: {"postId":"f66aebce-...","viewerId":"41ee2a6b-...","timestamp":"2026-05-21T14:30:00","subscribers":3}

event: REACTION_ADDED
data: {"eventType":"REACTION_ADDED","postId":"f66aebce-...","actorId":"9c1f-...","actorUsername":"ahmad","actorAvatarUrl":"https://...","reactionType":"LIKE","timestamp":"2026-05-21T14:31:00"}

event: COMMENT_CREATED
data: {"eventType":"COMMENT_CREATED","postId":"f66aebce-...","commentId":"c0a1...","actorId":"9c1f-...","actorUsername":"ahmad","textContent":"Great post!","timestamp":"2026-05-21T14:32:00"}

event: REPLY_CREATED
data: {"eventType":"REPLY_CREATED","postId":"f66aebce-...","commentId":"r2b3...","parentCommentId":"c0a1...","actorId":"7d2e-...","textContent":"Agreed!","timestamp":"2026-05-21T14:32:30"}

event: COMMENT_REACTION_ADDED
data: {"eventType":"COMMENT_REACTION_ADDED","postId":"f66aebce-...","commentId":"c0a1...","actorId":"7d2e-...","reactionType":"LIKE","timestamp":"2026-05-21T14:33:00"}

event: VIEW_COUNT_UPDATED
data: {"eventType":"VIEW_COUNT_UPDATED","postId":"f66aebce-...","actorId":"7d2e-...","timestamp":"2026-05-21T14:33:15"}

event: SAVE_COUNT_UPDATED
data: {"eventType":"SAVE_COUNT_UPDATED","postId":"f66aebce-...","actorId":"7d2e-...","timestamp":"2026-05-21T14:33:20"}

event: SHARE_COUNT_UPDATED
data: {"eventType":"SHARE_COUNT_UPDATED","postId":"f66aebce-...","actorId":"1234-...","timestamp":"2026-05-21T14:33:25"}

event: POST_UPDATED
data: {"eventType":"POST_UPDATED","postId":"f66aebce-...","actorId":"41ee2a6b-...","textContent":"Edited body","timestamp":"2026-05-21T14:45:00"}

event: COMMENT_EDITED
data: {"eventType":"COMMENT_EDITED","postId":"f66aebce-...","commentId":"c0a1...","actorId":"9c1f-...","textContent":"Edited text","timestamp":"2026-05-21T14:45:30"}

event: COMMENT_DELETED
data: {"eventType":"COMMENT_DELETED","postId":"f66aebce-...","commentId":"c0a1...","actorId":"9c1f-...","timestamp":"2026-05-21T14:46:00"}

event: REACTION_REMOVED
data: {"eventType":"REACTION_REMOVED","postId":"f66aebce-...","actorId":"9c1f-...","reactionType":"LIKE","timestamp":"2026-05-21T14:50:00"}

event: heartbeat
data: {"timestamp":"2026-05-21T14:51:00"}
```

- The actor's own subscription is filtered server-side — the originating
  tab does NOT receive an echo.
- Stale emitters are pruned silently on send failure.
- Cross-instance fan-out is via Redis pub/sub (`PostRealtimePublisher` /
  `PostRealtimeSubscriber`).
- Heartbeat every ~25s.

See §24 for the full event-type catalog.

---

## 11. Post reactions

Single reaction type = `LIKE`. Presence of a row = the like.

### 11.1 `POST /api/v1/posts/{postId}/reactions` — toggle like

**Auth:** 🔵 Authenticated.

**What it does.** Toggle: if the viewer hasn't liked the post, the
like is added; if they already liked it, the like is removed. The
response always returns the **post-toggle** state via `liked`.

**When the frontend uses this.** The heart icon on the post card.
Common pattern: optimistically flip the icon, call this endpoint,
reconcile with the response.

**LWT-guarded toggle.** The write uses Cassandra
`INSERT … IF NOT EXISTS` (on like) and `DELETE … IF EXISTS` (on
unlike). The `wasApplied()` result tells the server whether THIS
request actually flipped the row, so only the winning request bumps
the counter / fires the broadcast / sends the notification. Two
near-simultaneous double-taps from the same viewer can never
double-bump `reaction_count`. Cost: one Paxos round-trip per
write (~2× a regular insert latency) — acceptable for like
frequency, and the integrity tradeoff is worth it.

**Path parameter:** `postId` — UUID.

**Request body:** none.

**Response `200`** (now liked):

```json
{
  "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "liked":  true
}
```

A second call flips it:

```json
{
  "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "liked":  false
}
```

### Side effects on like

- `reactions_by_post` row inserted via LWT (`IF NOT EXISTS`).
- `reactions_by_user` row inserted (mirror, no LWT — lookup row is the authority).
- `post_counters.reaction_count++` (atomic CQL — only if LWT applied).
- Broadcasts `REACTION_ADDED` on the post's SSE channel.
- Fires `POST_REACTED` notification to the post author.
- Activity row `POST_REACTION` for the actor.

### Side effects on unlike

- `reactions_by_post` row deleted via LWT (`IF EXISTS`).
- Mirror row in `reactions_by_user` deleted.
- Counter `-1` (only if LWT applied).
- Broadcasts `REACTION_REMOVED`.

### Lost-race semantics

If two requests race and the second one's LWT does NOT apply (the
first request won), the loser is a silent no-op — no counter delta,
no broadcast — and returns the post-flip state so the caller's UI
still ends up consistent.

---

### 11.2 `DELETE /api/v1/posts/{postId}/reactions` — explicit unlike

**Auth:** 🔵 Authenticated. Idempotent — no-op if not currently liked.

**What it does.** Removes the viewer's like on the post. Unlike the
toggle in §11.1, this is **explicit unlike-only** semantic — safe to
call without first knowing the current liked state. Useful for
"undo" flows or when the frontend is sure the user wants to remove
the like (e.g. swipe-to-unlike gesture, "remove from likes" admin
context).

**Response `200`:**

```json
{ "postId": "f66aebce-...", "liked": false }
```

---

### 11.3 `GET /api/v1/posts/{postId}/reactions/me` — "did I like this?"

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** A cheap point-read returning whether the current
viewer has liked this post. Anonymous viewers get `liked: false`.

**When the frontend uses this.** When the post-detail page loads and
the cached `likedByMe` flag on the post is stale or unavailable —
e.g. opening a post via direct URL after a session resume.

**Authenticated response `200`:**

```json
{ "postId": "f66aebce-...", "userId": "41ee2a6b-...", "liked": true }
```

**Anonymous response `200`:**

```json
{ "postId": "f66aebce-...", "liked": false }
```

---

### 11.4 `GET /api/v1/posts/users/{userId}/reactions` — reaction history

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the recent posts a given user has liked,
newest first. The response is light — only `(userId, createdAt, postId)`
tuples — so the frontend can decide what to render (often a sub-grid
on the profile page).

**When the frontend uses this.** "Liked" tab on a user's profile.
Hydrate each `postId` via `GET /posts/{id}` as the user scrolls into it.

**Path parameter:** `userId` — UUID.

**Query parameter:** `pageSize` (int, default 20).

**Response `200`:** newest first across all posts liked by that user.

```json
[
  {
    "userId":    "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "createdAt": "2026-05-21T14:30:00Z",
    "postId":    "f66aebce-d659-45b8-8479-75195f5d6d4b"
  },
  {
    "userId":    "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "createdAt": "2026-05-21T11:05:00Z",
    "postId":    "bb22cc33-4455-6677-8899-aabbccddeeff"
  },
  {
    "userId":    "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "createdAt": "2026-05-20T22:45:00Z",
    "postId":    "dd44ee55-6677-8899-aabb-ccddeeff0011"
  }
]
```

---

## 12. Comment reactions

Toggle / unlike likes on a comment. Same single-`LIKE` rule as posts.

### 12.1 `POST /api/v1/posts/{postId}/comments/{commentId}/reactions` — toggle

**Auth:** 🔵 Authenticated.

**What it does.** Toggle a like on a comment (or reply). Same
single-LIKE rule as post reactions. Returns the post-toggle `liked`
state.

**When the frontend uses this.** The heart icon next to each comment.
Applies to top-level comments AND replies.

**LWT-guarded toggle.** Same `IF NOT EXISTS` / `IF EXISTS` pattern as
post reactions (§11.1). Only the request whose LWT was applied owns
the counter delta + broadcast + notification — concurrent toggles
can't double-bump `comment_counters.reaction_count`.

**Request body:** none.

**Response `200`:**

```json
{
  "commentId": "c0a1b2c3-d4e5-f678-9012-3456789abcde",
  "userId":    "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "liked":     true
}
```

### Side effects

- `comment_reactions_by_comment` row inserted via LWT (`IF NOT EXISTS`).
- `comment_counters.reaction_count++` (only if LWT applied).
- Broadcasts `COMMENT_REACTION_ADDED`.
- Fires `POST_COMMENT_REACTED` notification to the comment author.

---

### 12.2 `DELETE /api/v1/posts/{postId}/comments/{commentId}/reactions` — explicit unlike

**Auth:** 🔵 Authenticated. Idempotent.

**What it does.** Explicit "remove my like from this comment". Same
semantics as the post unlike — no-op if not currently liked. Useful
for undo flows where the toggle's stateful behavior would be wrong.

**Response `200`:**

```json
{ "commentId": "c0a1b2c3-...", "liked": false }
```

---

## 13. Views

### 13.1 `POST /api/v1/posts/{postId}/views` — record a view

**Auth:** 🟢 Public (anonymous-safe). Anonymous viewers see the count
but don't bump it.

**What it does.** Records a unique view for the authenticated user
within a 7-day Redis-dedupe window. The first view by a user counts;
repeat views within the window are silent no-ops (the response still
returns the live count).

**When the frontend uses this.** Fire-and-forget when a post enters
the viewport for ≥1 second (or however your impression threshold is
set). Don't await the response — `counted: true | false` is mostly
informational.

**Why dedupe in Redis.** Cassandra LWT (`INSERT IF NOT EXISTS`) does a
Paxos round-trip and bottlenecks on hot keys. Redis NX is
sub-millisecond. The source of truth (`views_by_post`) is still
strongly partition-keyed so we get reconciliation for free if Redis is
ever cleared.

**Path parameter:** `postId` — UUID.

**Request body:** none.

**Authenticated, first view in 7-day Redis-dedupe window — Response `200`:**

```json
{
  "postId":    "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "userId":    "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "counted":   true,
  "viewCount": 346
}
```

**Authenticated, repeat view within window:**

```json
{
  "postId":    "f66aebce-...",
  "userId":    "41ee2a6b-...",
  "counted":   false,
  "viewCount": 346
}
```

**Anonymous:**

```json
{
  "postId":    "f66aebce-...",
  "counted":   false,
  "viewCount": 346
}
```

### How dedupe works

1. Redis `SET NX` on `view:{postId}:{userId}` with 7-day TTL.
2. On NX success: `views_by_post` row + `post_counters.view_count++`.
3. On NX failure: no-op.
4. If Redis is unreachable: fall back to a `views_by_post` point read.

### Side effects

- Broadcasts `VIEW_COUNT_UPDATED` on the post's SSE channel.
- Reel-watch endpoint (`POST /posts/{postId}/reels/view`) additionally
  writes a `REEL_WATCH` activity row.

---

## 14. Comments & Replies

### 14.1 `POST /api/v1/posts/{postId}/comments` — create top-level comment

**Auth:** 🔵 Authenticated.

**What it does.** Posts a top-level comment under the post. Returns
the fully-hydrated `CommentResponse` so the frontend can prepend it to
the comment list immediately. Inline image / video URL is supported
via `mediaUrl` + `mediaType`.

**When the frontend uses this.** The "Add a comment" composer on the
post-detail page.

**Built-in dedup.** Same author + same `text` on the same post within
~3 seconds is silently treated as a no-op — the existing comment is
returned instead of a duplicate row. Protects against double-clicks
and retry-on-timeout races.

**Path parameter:** `postId` — UUID.

**Request body (`CreateCommentRequest`):**

```json
{
  "text":      "Great post — thank you for sharing!",
  "mediaUrl":  null,
  "mediaType": null
}
```

| Field | Type | Notes |
|-------|------|-------|
| `text` | string | The comment body |
| `mediaUrl` | string | Optional — inline image / video URL |
| `mediaType` | string | `IMAGE` / `VIDEO` if media is present |

**Response `200`** (`CommentResponse`):

```json
{
  "id": "c0a1b2c3-d4e5-f678-9012-3456789abcde",
  "postId":   "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id": "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "username": "akar.arkanf19",
    "fullName": "akar arkan",
    "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
  },
  "textContent": "Great post — thank you for sharing!",
  "mediaUrl":  null,
  "mediaType": null,
  "reactionCount": 0,
  "replyCount":    0,
  "likedByMe":  false,
  "deleted":    false,
  "edited":     false,
  "createdAt":  "2026-05-21T14:32:00Z"
}
```

### Side effects

- `comments_by_post` row inserted.
- `comment_lookup` row inserted (for fast point-read by `commentId`).
- `post_counters.comment_count++`.
- Broadcasts `COMMENT_CREATED`.
- Fires `POST_COMMENTED` notification to the post author.
- Activity row `POST_COMMENT` for the actor.

### Dedup

Same author + same text on the same post within 3 seconds = silent
no-op, returns the existing comment instead.

---

### 14.2 `GET /api/v1/posts/{postId}/comments` — list top-level comments

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Pages through the top-level comments on a post,
chronologically (oldest first — natural reading order). Each
`CommentResponse` carries author profile, counters, viewer-relative
`likedByMe`, and the `edited` flag.

**Deleted comments are physically gone.** Earlier versions soft-deleted
(rows stayed with `is_deleted=true`); long threads bloated because
deleted rows were still scanned. The current implementation hard-deletes
the row from `comments_by_post`, removes the `comment_lookup` row, and
range-deletes the comment's entire reply partition in one tombstone. The
post's `comment_count` decrements by **1 + replyCount** so the visible
total stays accurate. Trade-off: deleted top-levels no longer appear as
"[deleted]" placeholders. Frontend just renders the remaining items.

**Bulk-load hydration.** Per-row counter / liked / saved checks are
collapsed into 3 multi-partition `IN` queries (one for `post_counters`,
one for the viewer's likes, one for the viewer's saves) — see §28 for
the hydration contract.

**Cursor pagination.** Pass the `createdAt` of the last comment as
`?cursor=` for the next page.

**Path parameter:** `postId` — UUID.

**Query parameters:**

| Name | Type | Default |
|------|------|---------|
| `pageSize` | int | 20 |
| `cursor` | ISO Instant | none |

**Response `200`:** `List<CommentResponse>` (chronological ASC).

Multi-item example showing normal + edited comments (deleted comments
are physically gone from the response):

```json
[
  {
    "id": "c0a1b2c3-...",
    "postId": "f66aebce-...",
    "authorId": "41ee2a6b-...",
    "author": {
      "id": "41ee2a6b-...",
      "username": "akar.arkanf19",
      "fullName": "akar arkan",
      "profileImage": "..."
    },
    "textContent": "Great post — thank you for sharing!",
    "mediaUrl":  null,
    "mediaType": null,
    "reactionCount": 2,
    "replyCount":    1,
    "likedByMe":  false,
    "deleted":    false,
    "edited":     false,
    "createdAt":  "2026-05-21T14:32:00Z"
  },
  {
    "id": "c1b2c3d4-...",
    "postId": "f66aebce-...",
    "authorId": "9c1f-...",
    "author": {
      "id": "9c1f-...",
      "username": "ahmad",
      "fullName": "Ahmad Rahman",
      "profileImage": "..."
    },
    "textContent": "I'd add that the Imam Malik perspective is also valuable here.",
    "mediaUrl":  null,
    "mediaType": null,
    "reactionCount": 5,
    "replyCount":    0,
    "likedByMe":  true,
    "deleted":    false,
    "edited":     true,
    "createdAt":  "2026-05-21T14:48:00Z"
  }
]
```

> The `deleted: false` field stays in the response shape for backwards
> compatibility, but in the new hard-delete world it should always be
> `false` on rows that ARE returned — deleted comments are physically
> removed from `comments_by_post`.

---

### 14.3 `POST /api/v1/posts/comments/{commentId}/replies` — reply to a comment

**Auth:** 🔵 Authenticated.

**What it does.** Posts a reply under the supplied comment (or under
the supplied **reply's** top-level parent — the depth-1 rule kicks in).
Returns the hydrated `ReplyResponse`.

**Depth-1 enforcement.** Project rule: replies are flat. If
`{commentId}` is itself a reply, the server resolves it back to the
top-level comment and posts the new reply as a sibling. The frontend
can't accidentally produce depth-2 trees.

**Same dedup window** as comments (3 seconds).

**Path parameter:** `commentId` — UUID. Note this URL is `/posts/comments/...` (not `/posts/{postId}/comments/...`).

**Request body (`CreateReplyRequest`):**

```json
{
  "text":     "Agreed — Imam Malik on this is well-documented.",
  "mediaUrl": null
}
```

**Response `200`** (`ReplyResponse`):

```json
{
  "id":       "r0a1b2c3-d4e5-f678-9012-3456789abcde",
  "parentId": "c1b2c3d4-d4e5-f678-9012-3456789abcde",
  "postId":   "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id": "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "username": "akar.arkanf19",
    "fullName": "akar arkan",
    "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
  },
  "textContent": "Agreed — Imam Malik on this is well-documented.",
  "mediaUrl": null,
  "reactionCount": 0,
  "likedByMe": false,
  "deleted": false,
  "edited":  false,
  "createdAt": "2026-05-21T14:50:00Z"
}
```

### Depth-1 rule

If `{commentId}` is itself a reply, the server hoists this new reply
to be a **sibling** of the original under the top-level comment —
`parentId` ends up pointing at the **top-level ancestor**. The
frontend can't produce depth-2 trees even by accident.

### Side effects

- `replies_by_comment` row inserted (under the resolved top-level parent).
- `comment_lookup` row inserted.
- `comment_counters.reply_count++` on the parent.
- `post_counters.comment_count++` on the post (replies count too).
- Broadcasts `REPLY_CREATED`.
- Fires `POST_COMMENT_REPLIED` notification to the parent comment's author.

---

### 14.4 `GET /api/v1/posts/comments/{commentId}/replies` — list replies

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Lists all replies under a top-level comment,
chronological ASC.

**When the frontend uses this.** Lazy-load: by default the comment
list shows just `replyCount` next to each comment, with a "View N
replies" button that calls this endpoint and inlines the replies.

**Path parameter:** `commentId` — UUID (the top-level parent).

**Query parameter:** `pageSize` (int, default 20).

**Response `200`:** `List<ReplyResponse>` chronological ASC.

```json
[
  {
    "id": "r0a1b2c3-...",
    "parentId": "c1b2c3d4-...",
    "postId":   "f66aebce-...",
    "authorId": "41ee2a6b-...",
    "author": { "id": "41ee2a6b-...", "username": "akar.arkanf19", "fullName": "akar arkan", "profileImage": "..." },
    "textContent": "Agreed — Imam Malik on this is well-documented.",
    "mediaUrl": null,
    "reactionCount": 1,
    "likedByMe": false,
    "deleted": false, "edited": false,
    "createdAt": "2026-05-21T14:50:00Z"
  },
  {
    "id": "r1b2c3d4-...",
    "parentId": "c1b2c3d4-...",
    "postId":   "f66aebce-...",
    "authorId": "7d2e-...",
    "author": { "id": "7d2e-...", "username": "fatima", "fullName": "Fatima Yusuf", "profileImage": null },
    "textContent": "Could you point to the specific source?",
    "mediaUrl": null,
    "reactionCount": 0,
    "likedByMe": false,
    "deleted": false, "edited": false,
    "createdAt": "2026-05-21T14:55:00Z"
  }
]
```

---

### 14.5 `PATCH /api/v1/posts/comments/{commentId}` — edit (author-only)

**Auth:** 🟡 Author-only.

**What it does.** Updates the `textContent` of an existing comment or
reply. Sets `edited: true` on the row and broadcasts `COMMENT_EDITED`
on the post's SSE channel so other tabs reconcile without refetching.

**When the frontend uses this.** The "Edit" item on the comment menu.
The composer pre-fills with the existing text; on save, call PATCH
and update the local row.

**Request body (`EditCommentRequest`):**

```json
{ "text": "Edited text (typo fix)" }
```

**Response:** `204 No Content`.

Broadcasts `COMMENT_EDITED`.

---

### 14.6 `DELETE /api/v1/posts/comments/{commentId}` — hard-delete (author-only)

**Auth:** 🟡 Author-only.

**What it does.** Physically removes the comment / reply row from
Cassandra. Concretely:

- **Reply target:** deletes the row from `replies_by_comment`, deletes
  its `comment_lookup` row, decrements `comment_counters.reply_count` on
  the parent and `post_counters.comment_count`.
- **Top-level target:** reads the comment's `reply_count`, deletes the
  row from `comments_by_post`, deletes its `comment_lookup` row, and
  issues a single **range delete** on `replies_by_comment` for
  `parent_id = {commentId}` — one tombstone instead of N. The post's
  `comment_count` decrements by `1 + replyCount` so the visible total
  stays correct.

**When the frontend uses this.** The "Delete" item on the comment
menu. After `204`, **remove the row from the local list** — it is
permanently gone for every viewer. The previous "leave a [deleted]
placeholder" behaviour no longer applies.

**Why the change.** Long active threads with many soft-deleted rows
accumulated bloat (every page scan dragged the dead rows back from
storage). Hard-delete bounds the tombstone count to actual deletes;
those tombstones GC after `gc_grace_seconds`, then disappear.

**Request body:** none.

**Response:** `204 No Content`. Broadcasts `COMMENT_DELETED`.

---

## 15. Saves (bookmarks)

Toggle-save with optional named "collections" (folders).

### 15.1 `POST /api/v1/posts/{postId}/saves` — toggle save

**Auth:** 🔵 Authenticated.

**What it does.** Toggles a bookmark on the post. Optionally pass a
collection name in `?collection=` (defaults to `"Default"`). Returns
the post-toggle `saved` state. Activity feed logs `POST_SAVED` only
when the toggle goes **ON** — unsaves are not logged, preventing
churn on save/unsave cycles.

**LWT-guarded toggle.** Save / unsave use `INSERT … IF NOT EXISTS` and
`DELETE … IF EXISTS` on the lookup row (`saves_by_post_user`). Only
the request whose LWT applied bumps the counter and writes the
`saves_by_user` mirror row — concurrent toggles can never double-bump
`post_counters.save_count`.

**When the frontend uses this.** The bookmark icon on the post card.
On the "Saved" tab UI, users can also pick a target collection
("Quran", "Fiqh", "Tajweed", etc.) via a sheet/menu — that string
becomes `?collection=`.

**Path parameter:** `postId` — UUID.

**Query parameter:** `collection` (string, optional — defaults to `"Default"`).

**Request body:** none.

**Response `200`** (now saved):

```json
{
  "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "saved":  true
}
```

Second call flips to `"saved": false`.

### Side effects on save (toggle ON)

- `saves_by_post_user` row inserted via LWT (`IF NOT EXISTS`) — the lookup is the authority.
- `saves_by_user` mirror row (newest first per viewer).
- `post_counters.save_count++` (only if LWT applied).
- Broadcasts `SAVE_COUNT_UPDATED`.
- Activity row `POST_SAVED` for the viewer.

### Side effects on unsave (toggle OFF)

- `saves_by_post_user` row deleted via LWT (`IF EXISTS`).
- `saves_by_user` mirror row deleted.
- Counter `-1` (only if LWT applied).
- Broadcasts `SAVE_COUNT_UPDATED`.
- Activity feed: **NOT** recorded (prevents save/unsave churn).

---

### 15.2 `DELETE /api/v1/posts/{postId}/saves` — explicit unsave

**Auth:** 🔵 Authenticated. Idempotent.

**What it does.** Removes the bookmark explicitly — no-op if not
currently saved. Useful for "Remove from saved" actions where the
toggle's stateful behaviour would feel wrong (e.g. swipe-to-remove on
the Saved tab).

**Response `200`:**

```json
{ "postId": "f66aebce-...", "saved": false }
```

---

### 15.3 `GET /api/v1/posts/{postId}/saves/me` — "did I save this?"

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Tells the viewer whether they've saved the post.
Anonymous → `{ "saved": false }`. Hot path lookup via the
`saves_by_post_user` point-lookup table.

**When the frontend uses this.** Direct-URL post-detail page when the
cached `savedByMe` flag is missing or stale.

**Authenticated response `200`:**

```json
{ "postId": "f66aebce-...", "userId": "41ee2a6b-...", "saved": true }
```

**Anonymous response `200`:**

```json
{ "postId": "f66aebce-...", "saved": false }
```

---

### 15.4 `GET /api/v1/posts/users/{userId}/saves` — my saved posts (hydrated)

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the user's saved posts as **fully-hydrated
`PostResponse` objects**. Each row carries the save-context fields
`savedAt` (bookmark timestamp) and `savedCollectionName` (folder
name). `savedByMe` is always `true` on this endpoint.

**Why hydrated.** Earlier versions returned the raw `SaveByUserEntity`
rows (with `postId` instead of `id`), which broke React `key={post.id}`
and `/posts/${post.id}` navigation. The hydrated response means the
frontend can render the Saved tab from a single endpoint — no
secondary `/posts/{id}` hydration needed.

**Cursor pagination.** Same as the home feed.

**Frontend tips.**
- Show `savedAt` as the badge ("Saved 2 days ago"), not `createdAt`.
- Use `savedCollectionName` to group rows into the user's collection sections.
- Rows whose underlying post has been hard-deleted are silently dropped — the response is shorter than the raw save count, no broken `/posts/null` links.

**Path parameter:** `userId` — UUID.

**Query parameters:**

| Name | Type | Default |
|------|------|---------|
| `pageSize` | int | 20 |
| `cursor` | ISO Instant | none |

**Response `200`:** `List<PostResponse>` — **fully hydrated**. Each row's
`id` is the post UUID. Save-context fields `savedAt` and
`savedCollectionName` are populated; `savedByMe` is always `true`.

```json
[
  {
    "id": "f66aebce-d659-45b8-8479-75195f5d6d4b",
    "authorId": "9c1f-...",
    "author": {
      "id": "9c1f-...",
      "username": "ahmad",
      "fullName": "Ahmad Rahman",
      "profileImage": "https://cdn.example.com/avatars/9c1f.jpg"
    },
    "postType":   "EMBEDDED",
    "status":     "PUBLISHED",
    "visibility": "PUBLIC",
    "textContent": "On the importance of consistency in daily worship...",
    "audioTrackUrl": null, "audioTrackName": null,
    "locationName": null, "locationLat": null, "locationLng": null,
    "sharedPostId": null, "shareLink": null,
    "mediaUrls":   ["https://cdn.example.com/posts/2f.jpg"],
    "mediaTypes":  ["IMAGE"],
    "reactionCount": 12, "commentCount": 3, "viewCount": 345,
    "saveCount": 7, "shareCount": 1,
    "likedByMe": false, "savedByMe": true,
    "createdAt": "2026-05-19T10:00:00Z",
    "updatedAt": "2026-05-19T10:00:00Z",
    "savedAt": "2026-05-21T14:35:00Z",
    "savedCollectionName": "Quran"
  },
  {
    "id": "aa11bb22-...",
    "authorId": "1234abcd-...",
    "author": {
      "id": "1234abcd-...",
      "username": "yusuf",
      "fullName": "Yusuf Khan",
      "profileImage": "..."
    },
    "postType":   "REEL",
    "status":     "PUBLISHED",
    "visibility": "PUBLIC",
    "textContent": "Three minutes on tajweed basics",
    "mediaUrls":   ["https://cdn.example.com/reels/clip-1234.mp4"],
    "mediaTypes":  ["VIDEO"],
    "audioTrackUrl": null, "audioTrackName": null,
    "locationName": null, "locationLat": null, "locationLng": null,
    "sharedPostId": null, "shareLink": null,
    "reactionCount": 89, "commentCount": 11, "viewCount": 5230,
    "saveCount": 22, "shareCount": 3,
    "likedByMe": true, "savedByMe": true,
    "createdAt": "2026-05-18T09:42:00Z",
    "updatedAt": "2026-05-18T09:42:00Z",
    "savedAt": "2026-05-20T19:12:00Z",
    "savedCollectionName": "Tajweed"
  }
]
```

Saves whose underlying post has been hard-deleted are silently dropped from the response.

---

## 16. Shares

Append-only ledger of platform shares (Share-to-DM / Share-to-X / etc.).
Distinct from a `REPOST` (which creates a new `Post` with `sharedPostId`).

### 16.1 `POST /api/v1/posts/{postId}/shares` — record a share

**Auth:** 🔵 Authenticated.

**What it does.** Records an append-only share event with an optional
`caption`. Distinct from a `REPOST` (which creates a new `Post` row
with `sharedPostId` set). Use this when the user taps "Share" → an
external app, copies the link, or DMs the post.

**When the frontend uses this.** The Share button on the post card.
Fire this **after** the user actually completes the share action
(don't track the intent to share — only the completion).

**Side effects.** Counter bumped, share-count SSE event broadcast,
`POST_SHARED` notification fired to the post author. There is no
"unshare" path — shares are an append-only ledger.

**Path parameter:** `postId` — UUID.

**Request body (`RecordShareRequest`, optional):**

```json
{ "caption": "Excellent read 👌" }
```

Or send no body at all (caption optional).

**Response `200`** (`ShareByPostEntity`):

```json
{
  "postId":    "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "createdAt": "2026-05-21T14:36:00Z",
  "shareId":   "s0a1b2c3-d4e5-f678-9012-3456789abcde",
  "sharerId":  "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "caption":   "Excellent read 👌"
}
```

### Side effects

- `shares_by_post` row inserted (append-only).
- `post_counters.share_count++`.
- Broadcasts `SHARE_COUNT_UPDATED`.
- Fires `POST_SHARED` notification to the post author.
- Activity row `POST_SHARE` for the actor.

---

### 16.2 `GET /api/v1/posts/{postId}/shares` — recent shares

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the most recent shares of the post, newest
first. Each entry includes the `sharerId` and the optional `caption`.

**When the frontend uses this.** The "Shares" analytics panel on the
post-detail page. Pair with `shareCount` for a quick summary.

**Query parameter:** `pageSize` (int, default 20).

**Response `200`:** newest first.

```json
[
  {
    "postId":    "f66aebce-...",
    "createdAt": "2026-05-21T14:36:00Z",
    "shareId":   "s0a1b2-...",
    "sharerId":  "41ee2a6b-...",
    "caption":   "Excellent read 👌"
  },
  {
    "postId":    "f66aebce-...",
    "createdAt": "2026-05-21T12:10:00Z",
    "shareId":   "s1c2d3-...",
    "sharerId":  "9c1f-...",
    "caption":   null
  },
  {
    "postId":    "f66aebce-...",
    "createdAt": "2026-05-20T22:55:00Z",
    "shareId":   "s2e3f4-...",
    "sharerId":  "7d2e-...",
    "caption":   "🔥"
  }
]
```

---

## 17. Media (carousel)

Base path: **`/api/v1/posts/{postId}/media`**.

For ≤4 media items the multipart create endpoint typically inlines them on `posts_by_id.mediaUrls`. The endpoints below are for larger albums and post-publish edits.

### 17.1 `POST /api/v1/posts/{postId}/media` — add one carousel item

**Auth:** 🔵 Authenticated (intended for the post's author — not server-enforced today).

**What it does.** Appends a single media row to the post's carousel.
The frontend would typically have already uploaded the file to R2 and
passes the URL + s3 key here.

**When the frontend uses this.** Carousel-edit UI: post is already
published, the user wants to add a 5th image. For brand-new posts,
use the multipart create in §6.2 instead — it batches the uploads
atomically.

**Request body (`AddMediaRequest`):**

```json
{
  "sortOrder":       0,
  "mediaType":       "IMAGE",
  "url":             "https://cdn.example.com/posts/big.jpg",
  "thumbnailUrl":    null,
  "s3Key":           "posts/media/abc.jpg",
  "durationSeconds": null,
  "fileSizeBytes":   482301,
  "mimeType":        "image/jpeg",
  "altText":         "Picture of the library entrance"
}
```

**Response `200`** (`MediaByPostEntity`):

```json
{
  "postId":          "f66aebce-...",
  "sortOrder":       0,
  "mediaId":         "m0a1b2-...",
  "mediaType":       "IMAGE",
  "url":             "https://cdn.example.com/posts/big.jpg",
  "thumbnailUrl":    null,
  "s3Key":           "posts/media/abc.jpg",
  "durationSeconds": null,
  "fileSizeBytes":   482301,
  "mimeType":        "image/jpeg",
  "altText":         "Picture of the library entrance"
}
```

---

### 17.2 `GET /api/v1/posts/{postId}/media` — list carousel items

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the post's carousel rows in display order
(`sortOrder ASC`). Use this when the post-detail page needs more than
the first item shown in `PostResponse.mediaUrls`.

**Response `200`:** ordered by `sortOrder ASC`.

```json
[
  {
    "postId": "f66aebce-...", "sortOrder": 0, "mediaId": "m0-...",
    "mediaType": "IMAGE", "url": "https://cdn.example.com/posts/img1.jpg",
    "thumbnailUrl": null, "s3Key": "posts/media/img1.jpg",
    "durationSeconds": null, "fileSizeBytes": 482301,
    "mimeType": "image/jpeg", "altText": "Cover photo"
  },
  {
    "postId": "f66aebce-...", "sortOrder": 1, "mediaId": "m1-...",
    "mediaType": "VIDEO", "url": "https://cdn.example.com/posts/clip.mp4",
    "thumbnailUrl": "https://cdn.example.com/posts/clip-thumb.jpg",
    "s3Key": "posts/media/clip.mp4",
    "durationSeconds": 42, "fileSizeBytes": 8429301,
    "mimeType": "video/mp4", "altText": "Lecture clip"
  },
  {
    "postId": "f66aebce-...", "sortOrder": 2, "mediaId": "m2-...",
    "mediaType": "IMAGE", "url": "https://cdn.example.com/posts/img2.jpg",
    "thumbnailUrl": null, "s3Key": "posts/media/img2.jpg",
    "durationSeconds": null, "fileSizeBytes": 382000,
    "mimeType": "image/jpeg", "altText": "Diagram"
  }
]
```

---

### 17.3 `DELETE /api/v1/posts/{postId}/media/{mediaId}` — remove one item

**Auth:** 🔵 Authenticated.

**What it does.** Removes a single carousel row by its
`(postId, sortOrder, mediaId)` tuple. `sortOrder` MUST be passed —
it's the cluster key.

**When the frontend uses this.** "Remove this image" action in the
carousel-edit UI. Idempotent — unknown ids are silent no-ops.

**Query parameter:** `sortOrder` (required — Cassandra cluster key).

**Response:** `204 No Content`. Unknown id silently no-op.

---

### 17.4 `PUT /api/v1/posts/{postId}/media` — replace-all (drag-drop reorder)

**Auth:** 🔵 Authenticated.

**What it does.** Replaces the entire carousel ordering. Server bulk
deletes the existing rows and inserts the new list. Use this for
drag-and-drop reorder UIs.

**Why not a partial reorder?** `sortOrder` is part of the clustering
key in Cassandra — you can't `UPDATE` it in place. Replace-all is the
clean way. Acceptable cost: carousels rarely exceed 20 items.

**Request body:** full new ordered list of `MediaByPostEntity` rows.

```json
[
  {
    "postId": "f66aebce-...", "sortOrder": 0, "mediaId": "m2-...",
    "mediaType": "IMAGE", "url": "https://cdn.example.com/posts/img2.jpg",
    "s3Key": "posts/media/img2.jpg", "mimeType": "image/jpeg"
  },
  {
    "postId": "f66aebce-...", "sortOrder": 1, "mediaId": "m0-...",
    "mediaType": "IMAGE", "url": "https://cdn.example.com/posts/img1.jpg",
    "s3Key": "posts/media/img1.jpg", "mimeType": "image/jpeg"
  }
]
```

Server bulk-deletes the existing rows and inserts the new order.

**Response `200`:** the new ordered list (same shape as the GET).

---

## 18. Hashtags & Mentions

Base path: **`/api/v1`**.

Extraction happens **synchronously** on post create from `textContent`:

- `#word` (alphanumeric + underscore, case-insensitive, stored lowercased) → indexed in `posts_by_hashtag`.
- `@username` — resolved against `UserRepository` to a UUID → mention recorded + notification fired.

**Batched mention resolution.** All `@username` tokens in a single post
are resolved through one `findAllByUsernameIn(...)` Postgres call
(WHERE username IN (?, ?, ?, …)) — not N sequential point-reads. A
post with 10 mentions costs **one** Postgres round-trip, not ten.

**Async notification fan-out.** The author-label lookup needed for the
`USER_MENTIONED` notification body, plus the per-recipient deliver
fan-out, run on the notification executor via
`CassandraNotificationService.deliverAllAsync(...)`. The post-create
response does not wait on them — only the `mentions_by_user` mirror
rows are written synchronously, and those are partitioned by recipient
so the writes can land in parallel without partition contention.

### 18.1 `GET /api/v1/hashtags/{tag}/posts` — posts under a hashtag

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns posts tagged with `#tag`, newest first.
Hashtags are lowercased on write, so the path is case-insensitive at
read time.

**When the frontend uses this.** Hashtag landing pages
(`/h/{tag}`), the "Posts" tab when a user taps a `#hashtag` link.

**Path parameter:** `tag` — string (no `#` prefix; case-insensitive).

**Query parameters:**

| Name | Type | Default |
|------|------|---------|
| `pageSize` | int | 20 |
| `cursor` | ISO Instant | none |

**Response `200`:**

```json
[
  {
    "hashtag":     "fiqh",
    "createdAt":   "2026-05-21T14:30:00Z",
    "postId":      "f66aebce-...",
    "authorId":    "41ee2a6b-...",
    "textPreview": "Reading at the library today 📚 #fiqh @ahmed",
    "mediaUrl":    "https://cdn.example.com/posts/2f.jpg"
  },
  {
    "hashtag":     "fiqh",
    "createdAt":   "2026-05-21T09:42:00Z",
    "postId":      "aa11bb22-...",
    "authorId":    "9c1f-...",
    "textPreview": "60-second recap on a fiqh question",
    "mediaUrl":    "https://cdn.example.com/reels/clip-9c1f.mp4"
  }
]
```

---

### 18.2 `GET /api/v1/hashtags/{tag}/usage` — post count

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the total number of posts that have ever
been tagged with this hashtag — feeds the "{n} posts" line on the
hashtag landing page.

**Performance.** Single counter point-read; no scan. Unknown tag
returns `0` cleanly.

**Response `200`:**

```json
{ "hashtag": "fiqh", "postCount": 1248 }
```

Unknown tag → `{ "hashtag": "...", "postCount": 0 }`.

---

### 18.3 `GET /api/v1/users/{userId}/mentions` — "posts mentioning me"

**Auth:** 🟢 Public (anonymous-safe — but the endpoint is mostly used by the user themselves to view their mention inbox).

**What it does.** Returns the posts that have `@userId`-mentioned the
given user, newest first. The mention inbox.

**When the frontend uses this.** "Mentions" tab in the notifications
panel, or as the data source for the @mentions area on the user's
profile.

**Note.** The mention extraction runs synchronously on post create, so
a mention is visible here within the same request that created the
post.

**Path parameter:** `userId` — UUID.

**Query parameter:** `pageSize` (int, default 20).

**Response `200`:**

```json
[
  {
    "mentionedUserId": "41ee2a6b-...",
    "createdAt":       "2026-05-21T14:30:00Z",
    "postId":          "f66aebce-...",
    "authorId":        "9c1f-...",
    "textPreview":     "@akar.arkanf19 check this out — relevant to our discussion"
  },
  {
    "mentionedUserId": "41ee2a6b-...",
    "createdAt":       "2026-05-20T18:15:00Z",
    "postId":          "bb22cc33-...",
    "authorId":        "7d2e-...",
    "textPreview":     "Question for @akar.arkanf19 about prayer timing"
  }
]
```

---

## 19. Sounds

TikTok-style reusable audio library. Status flow:
`PENDING_REVIEW` → `APPROVED` → optionally `REJECTED` / `ARCHIVED`.

Base path: **`/api/v1/sounds`**.

### 19.1 `POST /api/v1/sounds` — upload a sound

**Auth:** 🔵 Authenticated. **`autoApprove: true` is 🔴 Admin-only by policy** (the controller does not enforce, but the frontend must NOT expose this to regular users — see §2 admin-only list).

**What it does.** Uploads a new sound entry to the library. Default
status is `PENDING_REVIEW` — the sound is reachable by id but does
NOT show up in the category browser until approved.

**When the frontend uses this.** "Upload sound" surface in the
creator-tools area. Regular users: never send `autoApprove: true` —
that's a moderator-only fast-path that skips the review queue.

**Frontend hint.** Set `autoApprove: false` always for regular users.
The admin moderation UI is the only place that should expose
`autoApprove: true`.

**Request body (`UploadSoundRequest`):**

```json
{
  "title":           "Adhan – Mecca",
  "artistName":      "Sheikh Ali",
  "audioUrl":        "https://cdn.example.com/sounds/adhan.mp3",
  "coverArtUrl":     "https://cdn.example.com/sounds/adhan-cover.jpg",
  "durationSeconds": 215,
  "category":        "NASHEED",
  "uploaderId":      "41ee2a6b-...",
  "autoApprove":     false
}
```

| Field | Type | Notes |
|-------|------|-------|
| `title` | string | Display title |
| `artistName` | string | Display artist |
| `audioUrl` | string | R2 URL of the audio file |
| `coverArtUrl` | string | Optional cover image URL |
| `durationSeconds` | int | Duration |
| `category` | string enum `SoundCategory` | |
| `uploaderId` | UUID | Who uploaded it |
| `autoApprove` | boolean | Skip moderation (admin-only in prod) |

**Response `200`** (`SoundEntity`):

```json
{
  "id":              "snd-1234abcd",
  "title":           "Adhan – Mecca",
  "artistName":      "Sheikh Ali",
  "audioUrl":        "https://cdn.example.com/sounds/adhan.mp3",
  "coverArtUrl":     "https://cdn.example.com/sounds/adhan-cover.jpg",
  "durationSeconds": 215,
  "category":        "NASHEED",
  "status":          "PENDING_REVIEW",
  "uploaderId":      "41ee2a6b-...",
  "createdAt":       "2026-05-21T14:40:00Z",
  "updatedAt":       "2026-05-21T14:40:00Z"
}
```

When `autoApprove: true`, `status: "APPROVED"` and the sound is
immediately fanned out to `sounds_by_category`.

---

### 19.2 `GET /api/v1/sounds/{id}` — point-read by id

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the full `SoundEntity` row by id — regardless
of status. Used when a post references the sound but the sound is no
longer in any category browse (e.g. it was rejected or archived).

**Response `200`:** `SoundEntity` (same shape as upload response).

**`404` (bare body)** if sound not found.

---

### 19.3 `POST /api/v1/sounds/{id}/approve` — moderator approve

**Auth:** 🔴 **Admin-only** (controller does NOT enforce — the
frontend MUST gate this behind a moderator/admin role check).

**What it does.** Promotes a `PENDING_REVIEW` sound to `APPROVED`,
fans it into the category browser (`sounds_by_category`), and fires
the `SOUND_APPROVED` notification to the uploader.

**When the frontend uses this.** The admin moderation queue UI.
Should NEVER appear in regular user flows.

**Idempotent.** Already-approved sound is a silent no-op.

**Response:** `204 No Content`. Side effect: row added to
`sounds_by_category`; fires `SOUND_APPROVED` notification to the uploader.

---

### 19.4 `GET /api/v1/sounds/by-category/{category}` — browse

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Lists approved sounds in a given category, newest
first. Backed by `sounds_by_category` — a single partition scan.
Pending / rejected / archived sounds do NOT appear here; they're only
reachable by id via §19.2.

**When the frontend uses this.** The sound picker on the post-create
flow ("Add a sound"), grouped by category tabs.

**Path parameter:** `category` — string enum.

**Query parameters:**

| Name | Type | Default |
|------|------|---------|
| `pageSize` | int | 20 |
| `cursor` | ISO Instant | none |

**Response `200`:**

```json
[
  {
    "category":        "NASHEED",
    "createdAt":       "2026-05-21T14:40:00Z",
    "soundId":         "snd-1234abcd",
    "title":           "Adhan – Mecca",
    "artistName":      "Sheikh Ali",
    "audioUrl":        "https://cdn.example.com/sounds/adhan.mp3",
    "coverArtUrl":     "https://cdn.example.com/sounds/adhan-cover.jpg",
    "durationSeconds": 215
  },
  {
    "category":        "NASHEED",
    "createdAt":       "2026-05-20T09:15:00Z",
    "soundId":         "snd-5678efgh",
    "title":           "Tala'a al-Badru Alayna",
    "artistName":      "Various",
    "audioUrl":        "https://cdn.example.com/sounds/tala.mp3",
    "coverArtUrl":     null,
    "durationSeconds": 184
  }
]
```

---

### 19.5 `GET /api/v1/sounds/{id}/posts` — "all posts using this sound"

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the posts that have adopted this sound,
newest first. This is the TikTok discover-page query — tap a sound,
see every post that used it.

**Frontend hint.** Hydrate each `postId` via `GET /posts/{id}` for
the grid render. The light response shape here keeps the partition
scan fast.

**Query parameter:** `pageSize` (int, default 20).

**Response `200`:**

```json
[
  {
    "soundId":   "snd-1234abcd",
    "createdAt": "2026-05-21T14:42:00Z",
    "postId":    "f66aebce-...",
    "authorId":  "41ee2a6b-..."
  },
  {
    "soundId":   "snd-1234abcd",
    "createdAt": "2026-05-20T11:00:00Z",
    "postId":    "aa11bb22-...",
    "authorId":  "9c1f-..."
  }
]
```

---

### 19.6 `GET /api/v1/sounds/{id}/usage` — use count

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Cheap counter point-read of how many posts have used
the sound. Used for the "used in N posts" line in the sound picker
and trending UIs.

**Response `200`:**

```json
{ "soundId": "snd-1234abcd", "useCount": 42 }
```

---

## 20. Stories

24-hour ephemeral content. **TTL enforced at the Cassandra table level**
(`default_time_to_live = 86400`) — rows tombstone automatically.

Visibility resolution (server-enforced on every read + view-record):

| Visibility | Viewer requirement |
|------------|--------------------|
| `PUBLIC` | Anyone (incl. anonymous) |
| `FOLLOWERS_ONLY` | Viewer follows the author |
| `CLOSE_FRIENDS` | Viewer is in the author's close-friends list |
| `ONLY_ME` | Viewer == author |

Base path: **`/api/v1/stories`**.

### 20.1 `POST /api/v1/stories` (JSON)

**Auth:** 🔵 Authenticated.

**What it does.** Creates a 24-hour story. The Cassandra row carries a
`default_time_to_live = 86400` so the row auto-tombstones — no
background job needed for expiration.

**When the frontend uses this.** The "Add to Story" flow when the
media URL is already known (e.g. previously uploaded). For one-shot
upload + create use the multipart variant in §20.2.

**`visibility` semantics.** PUBLIC anyone, FOLLOWERS_ONLY just
followers, CLOSE_FRIENDS the owner's explicit list, ONLY_ME just the
author. The visibility resolver runs on every read AND every view
record — there's no client-side enforcement.

**Request body (`CreateStoryRequest`):**

```json
{
  "storyType":    "IMAGE",
  "visibility":   "FOLLOWERS_ONLY",
  "mediaUrl":     "https://cdn.example.com/stories/story.jpg",
  "thumbnailUrl": "https://cdn.example.com/stories/story-thumb.jpg",
  "textContent":  "Morning notes from Cairo"
}
```

**Response `200`** (`StoryByAuthorEntity`):

```json
{
  "authorId":     "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "createdAt":    "2026-05-21T14:45:00Z",
  "storyId":      "stor-a1b2c3-d4e5",
  "storyType":    "IMAGE",
  "visibility":   "FOLLOWERS_ONLY",
  "mediaUrl":     "https://cdn.example.com/stories/story.jpg",
  "thumbnailUrl": "https://cdn.example.com/stories/story-thumb.jpg",
  "textContent":  "Morning notes from Cairo",
  "expiresAt":    "2026-05-22T14:45:00Z"
}
```

---

### 20.2 `POST /api/v1/stories` (multipart)

**Auth:** 🔵 Authenticated.

**What it does.** Atomic upload-and-create — the `media` file (and
optional `thumbnail`) are streamed to R2, their public URLs are
written into the story row.

**When the frontend uses this.** "Add to Story" picker flow on
mobile: snap photo / pick video → maybe set caption → publish.

**Form fields:**

| Field | Type | Notes |
|-------|------|-------|
| `storyType` | string | defaults to `"PHOTO"` |
| `visibility` | string | defaults to `"PUBLIC"` |
| `textContent` | string | optional |
| `media` | file | optional — the story media |
| `thumbnail` | file | optional — for VIDEO stories |

```bash
curl -X POST https://api.irc.example.com/api/v1/stories \
  -H "Authorization: Bearer <jwt>" \
  -F 'storyType=IMAGE' \
  -F 'visibility=PUBLIC' \
  -F 'textContent=Morning notes' \
  -F 'media=@story.jpg' \
  -F 'thumbnail=@story-thumb.jpg'
```

**Response `200`:** same shape as JSON create.

---

### 20.3 `GET /api/v1/stories/by-author/{authorId}` — active stories

**Auth:** 🟢 Public (anonymous-safe — anon viewers see only `PUBLIC` stories).

**What it does.** Returns the author's currently-active (un-expired)
stories, **filtered server-side by the viewer's visibility access**
(follow status / close-friends membership / self). Authors see their
own ONLY_ME stories; followers see FOLLOWERS_ONLY; close-friends
members see CLOSE_FRIENDS; anonymous viewers see only PUBLIC.

**When the frontend uses this.** Story tray on the profile page, and
on the home feed's story strip.

**Response `200`:** filtered by viewer visibility.

```json
[
  {
    "authorId":     "41ee2a6b-...",
    "createdAt":    "2026-05-21T14:45:00Z",
    "storyId":      "stor-a1b2-...",
    "storyType":    "IMAGE",
    "visibility":   "PUBLIC",
    "mediaUrl":     "https://cdn.example.com/stories/story.jpg",
    "thumbnailUrl": "https://cdn.example.com/stories/story-thumb.jpg",
    "textContent":  "Morning notes from Cairo",
    "expiresAt":    "2026-05-22T14:45:00Z"
  },
  {
    "authorId":     "41ee2a6b-...",
    "createdAt":    "2026-05-21T09:30:00Z",
    "storyId":      "stor-b2c3-...",
    "storyType":    "VIDEO",
    "visibility":   "FOLLOWERS_ONLY",
    "mediaUrl":     "https://cdn.example.com/stories/clip.mp4",
    "thumbnailUrl": "https://cdn.example.com/stories/clip-thumb.jpg",
    "textContent":  null,
    "expiresAt":    "2026-05-22T09:30:00Z"
  }
]
```

---

### 20.4 `DELETE /api/v1/stories/{storyId}` — author-only delete

**Auth:** 🟡 Author-only.

**What it does.** Removes a story before its 24h TTL kicks in. The
TTL would clean it up automatically — this endpoint is for "I changed
my mind" UX.

**Quirk:** if the caller isn't the author the service throws raw
`SecurityException` → response is `500 INTERNAL_ERROR`. Semantically
this is `403 Forbidden` — frontend can treat any 500 on this endpoint
as a permission failure until the service is fixed.

**Response:** `204 No Content`. (Author-mismatch currently throws
`SecurityException` → `500 INTERNAL_ERROR`; semantically a 403.)

---

### 20.5 `POST /api/v1/stories/{storyId}/views` — record a view

**Auth:** 🔵 Authenticated. Self-views are not logged.

**What it does.** Records that the viewer watched a story. Visibility
is enforced server-side — a viewer who isn't allowed to see the
story gets a silent no-op (no row written, no error).

**When the frontend uses this.** Fire when the story finishes rendering
or after a minimum dwell time (e.g. 1 second) in the viewer.

**Response:** `202 Accepted` (empty body). Visibility enforced server-side.

---

### 20.6 `GET /api/v1/stories/{storyId}/views` — viewer log

**Auth:** 🟢 Anonymous-safe at the controller level (but typically only the author calls this).

**What it does.** Returns the list of users who watched the story,
newest first. The story-author UI shows this as "Viewers" — Instagram
style.

**Frontend hint.** Treat this as author-only on the UI — the
controller doesn't enforce author-check today, but only the author
would meaningfully care about the list.

**Query parameter:** `pageSize` (int, default 50).

**Response `200`:** newest first.

```json
[
  {
    "storyId":   "stor-a1b2-...",
    "viewedAt":  "2026-05-21T15:00:00Z",
    "viewerId":  "9c1f-..."
  },
  {
    "storyId":   "stor-a1b2-...",
    "viewedAt":  "2026-05-21T14:58:00Z",
    "viewerId":  "7d2e-..."
  },
  {
    "storyId":   "stor-a1b2-...",
    "viewedAt":  "2026-05-21T14:50:00Z",
    "viewerId":  "1234-..."
  }
]
```

---

## 21. Close Friends

Owner-only inner-circle list. Drives `CLOSE_FRIENDS`-scoped story visibility.

Base path: **`/api/v1/close-friends`**. Owner is the JWT-authenticated user.

### 21.1 `GET /api/v1/close-friends` — list my close friends

**Auth:** 🔵 Authenticated. Owner-only — the JWT user is always the owner.

**What it does.** Returns the authenticated user's close-friends list.
Privacy-sensitive — only the owner can read their own list (no
per-friend lookup endpoint exists by design).

**When the frontend uses this.** The close-friends management screen,
and the "share to" picker when creating a `CLOSE_FRIENDS`-visibility
story.

**Response `200`:**

```json
[
  { "ownerId": "41ee2a6b-...", "friendId": "9c1f-...",  "addedAt": "2026-05-20T10:00:00Z" },
  { "ownerId": "41ee2a6b-...", "friendId": "7d2e-...",  "addedAt": "2026-05-19T18:30:00Z" },
  { "ownerId": "41ee2a6b-...", "friendId": "1234-...",  "addedAt": "2026-05-18T22:45:00Z" }
]
```

---

### 21.2 `POST /api/v1/close-friends?friendId={uuid}` — add

**Auth:** 🔵 Authenticated.

**What it does.** Adds another user to the JWT user's close-friends
list. Self-add is silently a no-op.

**When the frontend uses this.** The "Add to close friends" toggle on
a user-card / profile, or the multi-select edit screen for the close-
friends list.

**Query parameter:** `friendId` (UUID, required).

**Request body:** none.

**Response:** `204 No Content`. Self-add is silently a no-op.

---

### 21.3 `DELETE /api/v1/close-friends?friendId={uuid}` — remove

**Auth:** 🔵 Authenticated.

**What it does.** Removes the supplied friend from the owner's close-
friends list. Idempotent — removing someone who isn't on the list is
a silent no-op.

**Response:** `204 No Content`.

---

### 21.4 `GET /api/v1/close-friends/is-member?candidateId={uuid}` — predicate

**Auth:** 🟢 Public (anonymous-safe — anon → `false`).

**What it does.** Returns a single boolean: is `candidateId` on the
viewer's close-friends list? Used to colour-code UI ("✓ Close friend")
without exposing the full list. Anonymous viewers get `false`.

**Response `200`:** `true` or `false` (boolean JSON literal).

---

## 22. Story Polls

Two-option Instagram-style polls attached to a story. One vote per user
per poll; changing your mind is allowed (moves the row, no double-counting).
All poll tables inherit the 24h TTL from the parent story.

### 22.1 `POST /api/v1/stories/{storyId}/poll` — attach a poll (author-only)

**Auth:** 🟡 Story-author-only.

**What it does.** Attaches a two-option (A/B) Instagram-style poll to
an existing story. The poll inherits the story's 24h TTL.

**When the frontend uses this.** The "Add poll" sticker in the story
composer. Pass the question + the two option labels.

**Quirk:** non-author callers currently throw `SecurityException` →
response is `500 INTERNAL_ERROR`. Semantically `403 Forbidden`.

**Path parameter:** `storyId` — UUID.

**Request body (`CreatePollRequest`):**

```json
{
  "question": "Which madhhab do you primarily study?",
  "optionA":  "Hanafi",
  "optionB":  "Shafi'i"
}
```

**Response `200`** (`StoryPollEntity`):

```json
{
  "storyId":   "stor-a1b2-...",
  "pollId":    "poll-1234-abcd",
  "question":  "Which madhhab do you primarily study?",
  "optionA":   "Hanafi",
  "optionB":   "Shafi'i",
  "authorId":  "41ee2a6b-...",
  "createdAt": "2026-05-21T14:46:00Z"
}
```

(Non-author currently throws `SecurityException` → `500 INTERNAL_ERROR`; semantically 403.)

---

### 22.2 `GET /api/v1/stories/{storyId}/poll`

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the poll attached to a story (if any).
Use case: the viewer opens a story → if it has a poll, render the
two-option UI.

**Response `200`:** `StoryPollEntity` (same shape).

**`404` bare body** if no poll attached.

---

### 22.3 `POST /api/v1/polls/{pollId}/vote?choice={A|B}` — cast / change vote

**Auth:** 🔵 Authenticated.

**What it does.** Casts the viewer's vote, or changes it if they
already voted. One vote per user — re-voting the same side is a
no-op, switching sides decrements the old and increments the new
atomically.

**When the frontend uses this.** Viewer taps A or B in the poll
sticker. The response carries the fresh tallies so the bars animate
to their new size immediately.

**Path parameter:** `pollId` — UUID.

**Query parameter:** `choice` — must be `"A"` or `"B"`.

**Request body:** none.

**Response `200`:**

```json
{ "choice": "A", "voteA": 12, "voteB": 7 }
```

- Same-side re-vote: no-op, returns latest counts.
- Switching sides: atomically decrements old, increments new.

### Error responses

| Condition | HTTP | `errorCode` | Message |
|-----------|------|-------------|---------|
| `choice` neither `A` nor `B` | `400` | `ILLEGAL_ARGUMENT` | `"Choice must be A or B"` |
| Missing `choice` | `400` | `MISSING_PARAMETER` | — |

---

### 22.4 `GET /api/v1/polls/{pollId}/vote/me` — "what did I vote?"

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the viewer's current vote (`"A"`, `"B"`, or
`null` if not voted / anonymous).

**When the frontend uses this.** When entering a story that has a
poll the viewer may have already voted on — to pre-fill the
selection state.

**Authenticated, voted:**

```json
{
  "pollId":  "poll-1234-abcd",
  "voterId": "41ee2a6b-...",
  "choice":  "A",
  "votedAt": "2026-05-21T14:46:30Z"
}
```

**Anonymous OR haven't voted:**

```json
{
  "pollId":  "poll-1234-abcd",
  "voterId": "41ee2a6b-...",
  "choice":  null
}
```

---

### 22.5 `GET /api/v1/polls/{pollId}/results` — live tallies

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the current A/B tallies. No `choice` field
here because this is the public read — anyone can see the totals.

**When the frontend uses this.** "Show results" tap on the poll
sticker, or to refresh tallies if the SSE stream isn't open.

**Response `200`:**

```json
{ "choice": null, "voteA": 12, "voteB": 7 }
```

(`choice: null` here because this is the public read.)

---

### 22.6 `GET /api/v1/polls/{pollId}/voters/{choice}` — author-only voter list

**Auth:** 🔴 **Admin-only by policy** (the controller does NOT
enforce — the frontend MUST treat this as story-author or admin only).

**What it does.** Returns the list of users who voted a particular
side. Sensitive — exposes who voted what.

**When the frontend uses this.** The story-author insights drawer
("Who voted Hanafi? Who voted Shafi'i?"). Never expose to non-authors.

**Hint.** Today the controller accepts the call from anyone, which is
intentional during the build-out — the frontend is responsible for
gating it. Future work: enforce author-check at the service level.

**Path parameter:** `choice` — `"A"` or `"B"`.

**Query parameter:** `pageSize` (int, default 50).

**Response `200`:**

```json
[
  { "pollId": "poll-1234-abcd", "choice": "A", "votedAt": "2026-05-21T14:46:30Z", "voterId": "41ee2a6b-..." },
  { "pollId": "poll-1234-abcd", "choice": "A", "votedAt": "2026-05-21T14:48:00Z", "voterId": "9c1f-..." },
  { "pollId": "poll-1234-abcd", "choice": "A", "votedAt": "2026-05-21T14:51:10Z", "voterId": "7d2e-..." }
]
```

---

## 23. Highlights

Permanent archives of stories. Survive past the original story's 24h TTL
by **snapshotting** content into `stories_in_highlight`.

Base path: **`/api/v1/highlights`**.

### 23.1 `POST /api/v1/highlights` — create

**Auth:** 🔵 Authenticated. The caller becomes the highlight owner.

**What it does.** Creates a named, persistent "Highlight" collection
on the user's profile — same concept as Instagram Story Highlights.
Stories the user posted during the day expire after 24 h, but
stories *snapshotted into a highlight* live forever.

**When the frontend uses this.** "Create new highlight" on the
profile screen. Pick a `coverUrl` thumbnail and a `title` (e.g.
*"Lecture clips"*, *"Tafsir notes"*). After creation, call §23.3 to
attach stories one at a time.

**Request body (`CreateHighlightRequest`):**

```json
{
  "authorId":     "41ee2a6b-...",
  "title":        "Lecture clips",
  "coverUrl":     "https://cdn.example.com/highlights/lectures-cover.jpg",
  "displayOrder": 0
}
```

**Response `200`** (`HighlightByAuthorEntity`):

```json
{
  "authorId":     "41ee2a6b-...",
  "displayOrder": 0,
  "highlightId":  "hl-a1b2-cdef",
  "title":        "Lecture clips",
  "coverUrl":     "https://cdn.example.com/highlights/lectures-cover.jpg",
  "createdAt":    "2026-05-21T14:48:00Z"
}
```

---

### 23.2 `GET /api/v1/highlights/by-author/{authorId}` — list highlights

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns all highlights for an author, ordered by
`displayOrder ASC`. This is the "row of circles under the bio" on a
profile screen.

**When the frontend uses this.** Profile screen load — render each
highlight as a clickable circle (cover + title). Tap → call §23.4 to
fetch the actual stories inside.

**Response `200`:** ordered by `displayOrder ASC`.

```json
[
  {
    "authorId":     "41ee2a6b-...",
    "displayOrder": 0,
    "highlightId":  "hl-a1b2-...",
    "title":        "Lecture clips",
    "coverUrl":     "https://cdn.example.com/highlights/lectures-cover.jpg",
    "createdAt":    "2026-05-21T14:48:00Z"
  },
  {
    "authorId":     "41ee2a6b-...",
    "displayOrder": 1,
    "highlightId":  "hl-c3d4-...",
    "title":        "Tafsir notes",
    "coverUrl":     "https://cdn.example.com/highlights/tafsir-cover.jpg",
    "createdAt":    "2026-05-18T09:00:00Z"
  }
]
```

---

### 23.3 `POST /api/v1/highlights/{highlightId}/stories/{storyId}?requesterId={uuid}` — snapshot a story

**Auth:** 🟡 Author-only — `requesterId` must match the story's
author. Server rejects with `403` if it doesn't.

**What it does.** Snapshots a live story (or one still within its
24 h window) into the highlight, copying the media URL, type, and
text content into the `stories_in_highlight` table. After this,
even when the original story expires, the highlight copy survives.

**When the frontend uses this.** "Add to highlight" → pick highlight
from sheet → call this. If the source story has already expired
(beyond 24 h and not seeded into any highlight) the server returns
`404` — show a "story no longer available" toast.

**Path parameters:** `highlightId`, `storyId` — UUIDs.

**Query parameter:** `requesterId` (UUID, required — must match the story's author).

**Request body:** none.

**Response `200`** (`StoryInHighlightEntity` — snapshotted copy):

```json
{
  "highlightId":  "hl-a1b2-cdef",
  "createdAt":    "2026-05-21T14:00:00Z",
  "storyId":      "stor-9999-aaaa",
  "authorId":     "41ee2a6b-...",
  "storyType":    "IMAGE",
  "mediaUrl":     "https://cdn.example.com/stories/old-story.jpg",
  "thumbnailUrl": "https://cdn.example.com/stories/old-story-thumb.jpg",
  "textContent":  "Morning notes from Cairo"
}
```

**`404` bare body** if the source story has already expired (or never existed).

Note: the highlight row preserves the original story's `createdAt` so highlights read in true chronological order.

---

### 23.4 `GET /api/v1/highlights/{highlightId}/stories` — list snapshots

**Auth:** 🟢 Public (anonymous-safe).

**What it does.** Returns the snapshotted stories inside a
highlight, in true chronological order (the `createdAt` in the
response is the *original* story's creation time, preserved during
snapshot — see §23.3).

**When the frontend uses this.** When the user taps a highlight
circle on a profile, fetch this and play the snapshots back like a
regular story reel.

**Response `200`:** chronological ASC.

```json
[
  {
    "highlightId":  "hl-a1b2-...",
    "createdAt":    "2026-05-15T10:00:00Z",
    "storyId":      "stor-aaaa-...",
    "authorId":     "41ee2a6b-...",
    "storyType":    "IMAGE",
    "mediaUrl":     "https://cdn.example.com/stories/lecture-1.jpg",
    "thumbnailUrl": "https://cdn.example.com/stories/lecture-1-thumb.jpg",
    "textContent":  "Lecture #1 — intro to fiqh"
  },
  {
    "highlightId":  "hl-a1b2-...",
    "createdAt":    "2026-05-17T14:00:00Z",
    "storyId":      "stor-bbbb-...",
    "authorId":     "41ee2a6b-...",
    "storyType":    "VIDEO",
    "mediaUrl":     "https://cdn.example.com/stories/lecture-2.mp4",
    "thumbnailUrl": "https://cdn.example.com/stories/lecture-2-thumb.jpg",
    "textContent":  null
  }
]
```

---

### 23.5 `DELETE /api/v1/highlights/{highlightId}/stories/{storyId}?createdAt={instant}` — remove a snapshot

**Auth:** 🟡 Author-only. The frontend should only expose this to
the highlight owner.

**What it does.** Removes a single snapshot from a highlight. The
original (live) story is **not** affected — only the highlight copy.

**When the frontend uses this.** Long-press a story inside a
highlight reel → "Remove from highlight." Pass the snapshot's
`createdAt` from the response of §23.4 — it's the clustering key
needed for the precise row-level delete.

**Query parameter:** `createdAt` — ISO Instant (the snapshot's `createdAt`, used as the clustering key for the delete).

**Response:** `204 No Content`.

---

## 24. Realtime event types

Defined in `ak.dev.irc.app.post.realtime.PostRealtimeEventType`.

### Post-level events

| Event | When | Local delta |
|-------|------|-------------|
| `REACTION_ADDED` | Someone liked the post | `reactionCount += 1` |
| `REACTION_CHANGED` | (forward-compat — single-LIKE today) | — |
| `REACTION_REMOVED` | Someone unliked the post | `reactionCount -= 1` |
| `COMMENT_CREATED` | New top-level comment | `commentCount += 1` |
| `COMMENT_EDITED` | Author edited a comment | — |
| `COMMENT_DELETED` | Author hard-deleted a comment | `commentCount -= (1 + replyCount)` |
| `REPLY_CREATED` | New reply to a comment | `commentCount += 1` ; parent `replyCount += 1` |
| `COMMENT_REACTION_ADDED` | Someone liked a comment | comment `reactionCount += 1` |
| `COMMENT_REACTION_CHANGED` | (forward-compat) | — |
| `COMMENT_REACTION_REMOVED` | Someone unliked a comment | comment `reactionCount -= 1` |
| `VIEW_COUNT_UPDATED` | New unique view recorded | `viewCount += 1` |
| `SAVE_COUNT_UPDATED` | Save toggle ON or OFF | `saveCount ± 1` (sign inferred from local state) |
| `SHARE_COUNT_UPDATED` | New share recorded | `shareCount += 1` |
| `POST_UPDATED` | Post body / visibility edited | — |
| `POST_DELETED` | Post hard-deleted (subscribers should close the view) | — |

### `PostRealtimeEvent` payload

Every event uses this single shape. Null fields are stripped from the
wire format (`@JsonInclude(NON_NULL)`):

```java
class PostRealtimeEvent {
    PostRealtimeEventType eventType;
    UUID postId;
    UUID actorId;
    String actorUsername;
    String actorAvatarUrl;
    UUID commentId;                 // for COMMENT_* / REPLY_* / COMMENT_REACTION_*
    UUID parentCommentId;           // for REPLY_CREATED
    String reactionType;            // "LIKE"
    String previousReactionType;    // forward-compat
    String textContent;             // for COMMENT_CREATED / EDITED / REPLY_CREATED / POST_UPDATED
    String mediaUrl;
    String mediaType;
    String mediaThumbnailUrl;
    // ── Counter fields ──
    // These are defined on the Java type for forward-compat but are
    // currently NEVER populated. Cassandra counter columns are eventually
    // consistent, so re-reading the counter right after the increment
    // can return a stale value. Clients apply the +1 / -1 delta locally
    // from the event type (see the "Local delta" column above) and
    // reconcile with the canonical counter on the next REST fetch.
    Long postReactionCount;     // omitted on the wire
    Long postCommentCount;      // omitted on the wire
    Long postShareCount;        // omitted on the wire
    Long postViewCount;         // omitted on the wire
    Long postSaveCount;         // omitted on the wire
    Long commentReactionCount;  // omitted on the wire
    Long commentReplyCount;     // omitted on the wire
    LocalDateTime timestamp;
}
```

### Story-level events (`StoryRealtimeEventType`)

`STORY_VIEWED` · `STORY_REACTED` · `STORY_UNREACTED` · `STORY_REPLIED` · `STORY_POLL_VOTED` · `STORY_EXPIRED` · `STORY_DELETED` · `VIEW_COUNT_UPDATED` · `REACTION_COUNT_UPDATED` · `REPLY_COUNT_UPDATED`

(Per-story SSE endpoint is wired in code but not yet exposed as a controller endpoint — see `BACKEND_ENHANCEMENTS.md` §2.5.)

---

## 25. Notifications fired by the post layer

Notifications flow through the cross-domain
`CassandraNotificationService` (full reference in `USER_API.md`).

| Trigger | `NotificationKind` | Recipient | Group key | Aggregable | Email |
|---------|-------------------|-----------|-----------|------------|-------|
| Followee posts a new post (fanout-on-write) | `POST_NEW` | Each follower | `POST_NEW:{postId}` | yes | no |
| Toggle-like on a post | `POST_REACTED` | Post author | `POST_REACTED:{postId}` | yes | no |
| New top-level comment | `POST_COMMENTED` | Post author | `POST_COMMENTED:{postId}` | yes | yes |
| Reply to a comment | `POST_COMMENT_REPLIED` | Top-level comment author (depth-1 rule) | `POST_COMMENT_REPLIED:{parentId}` | yes | yes |
| Like a comment | `POST_COMMENT_REACTED` | Comment author | `POST_COMMENT_REACTED:{commentId}` | yes | no |
| Record a share | `POST_SHARED` | Post author | `POST_SHARED:{postId}` | yes | yes |
| `@mention` extracted from post body | `USER_MENTIONED` | Each mentioned user | `USER_MENTIONED:{postId}:{userId}` | no | yes |
| Story posted (fanout) | `STORY_PUBLISHED` | Each follower | — | no | no |
| Story reaction | `STORY_REACTED` | Story author | `STORY_REACTED:{storyId}` | yes | no |
| Story reply | `STORY_REPLIED` | Story author | `STORY_REPLIED:{storyId}` | yes | yes |
| Sound approved | `SOUND_APPROVED` | Uploader | — | no | yes |

Aggregation: same `(userId, groupKey)` unread rows merge into one row
inside a 60-minute window — bumps `aggregate_count`, replaces
`last_actor_id`, rewrites `body`, floats to the top of the inbox.

Self-suppression: actor == recipient → notification dropped.

Block check: if recipient ↔ actor are in any block edge → dropped.

**Deferred enrichment.** Every notification trigger above hands a
`Supplier<DeliverRequest>` (or `Supplier<List<DeliverRequest>>` for
batched mentions) to `deliverAsync` / `deliverAllAsync`. The lookups
needed to populate the body — fetching the post for `authorId`, the
actor for `@username`, the parent-comment lookup row — run on the
notification executor, NOT the request thread. A viral post taking 100
likes/sec used to hammer its own `posts_by_id` partition 100 times/sec
from request threads; that read amplification is gone.

---

## 26. Activity feed integration

Every post-side action also writes a row into the per-user activity
feed (`UserActivityService.record*`). Full reference in
`USER_ACTIVITY_API.md`.

| Post action | Activity type emitted | Notes |
|-------------|-----------------------|-------|
| `POST /posts` (create) | `POST_CREATED` | `postType` carried |
| `POST /posts/{id}/reactions` (toggle ON) | `POST_REACTION` | `reactionType=LIKE` |
| `POST /posts/{id}/comments` | `POST_COMMENT` | `commentId` carried |
| `POST /posts/comments/{id}/replies` | `POST_COMMENT` | Replies log as comment activity |
| `POST /posts/{postId}/comments/{commentId}/reactions` (toggle ON) | `POST_COMMENT_REACTION` | Both ids carried |
| `POST /posts/{id}/shares` | `POST_SHARE` | |
| `POST /posts/{id}/saves` (toggle ON) | `POST_SAVED` | Unsaves NOT recorded |
| `POST /posts/{id}/reels/view` (reel watched) | `REEL_WATCH` | `watchedSeconds` carried |
| `@mention` in post body (incoming) | `USER_MENTIONED` | One row per recipient — `@followers` fan-out NOT recorded |

All `record*` calls are `@Async` + `try/catch` — recording an activity never breaks the originating write.

---

## 27. Cassandra tables index

Quick lookup of every table the post package touches:

### Canonical / lookup

| Table | Role |
|-------|------|
| `posts_by_id` | Canonical post row, point-read by id |
| `comment_lookup` | Point-read comment by id (also walks reply → top-level parent) |
| `story_lookup` | Point-read story metadata by id |
| `saves_by_post_user` | "Did U save P?" point-lookup |
| `sounds_by_id` | Canonical sound row |
| `poll_by_story` | One poll per story, point-read by story id |
| `poll_votes_by_poll_user` | "Has U voted on P? Which side?" |

### Feeds / per-entity slices

| Table | Role |
|-------|------|
| `posts_by_author` | Profile feed (DESC `created_at`) |
| `feed_by_user` | Home feed fanout-on-write (30d TTL) |
| `reels_by_day` | Global reels (partition per UTC day) |
| `comments_by_post` | Top-level comments per post (ASC `created_at`) |
| `replies_by_comment` | Replies under a comment (ASC `created_at`, flat depth 1) |
| `reactions_by_post` | "Who liked P?" (clustered by user_id) |
| `reactions_by_user` | "What has U liked recently?" |
| `comment_reactions_by_comment` | Per-comment like rows |
| `saves_by_user` | A user's bookmarks (DESC `created_at`) |
| `shares_by_post` | Shares for a post (DESC `created_at`) |
| `views_by_post` | Unique-view rows |
| `media_by_post` | Carousel media (ASC `sort_order`) |
| `mentions_by_user` | `@mentions` inbox (DESC `created_at`) |
| `posts_by_hashtag` | Tagged posts (DESC `created_at`) |
| `friend_suggestions_by_user` | Precomputed (DESC score) |

### Stories

| Table | Role | TTL |
|-------|------|-----|
| `stories_by_author` | Author partition, DESC `created_at` | 24h |
| `story_lookup` | Point-read by story id | 24h |
| `story_views_by_story` | Viewer log | 24h |
| `close_friends_by_owner` | Owner's close-friends list | — |
| `poll_by_story` / `poll_votes_*` / `poll_voters_*` / `poll_counters` | Poll storage | 24h |
| `highlights_by_author` | Highlight covers | — (permanent) |
| `stories_in_highlight` | Snapshotted story content | — (permanent) |

### Sounds

| Table | Role |
|-------|------|
| `sounds_by_id` | Canonical sound row |
| `sounds_by_category` | Browse-by-category (APPROVED only) |
| `posts_by_sound` | "All posts using sound X" |
| `sound_counters` | Per-sound `use_count` |

### Counters

`post_counters` · `comment_counters` · `sound_counters` · `hashtag_counters` · `poll_counters` · `notification_unread_counter`

All counter writes go through `CounterService` (raw CQL `UPDATE … SET col = col + N`).

---

## 28. Cross-cutting rules

- **Single reaction type** — only `LIKE`. Mirrors post / QnA / research.
- **Replies flat at depth 1** — server hoists deeper attempts.
- **Self-repost allowed** — but skip the self-notification.
- **JWT-derived authorship** — body-supplied ids ignored.
- **Async fanout & search index** — never blocks the create response.
- **R2 rollback on DB failure** — multipart-create deletes uploaded keys
  if the post-insert fails.
- **Counter columns are atomic** via `CounterService` (raw CQL).
- **All side effects (notifications, activity, realtime, ES index)
  wrapped in `try/catch`** — never breaks the originating write.
- **JWT filter is SSE-aware** — stale cookies on `/stream` endpoints
  pass through (no 401) so the `?token=` fallback works.
- **Anonymous-safe reads** — public endpoints return data without auth;
  `likedByMe` / `savedByMe` reflect viewer state when authenticated.
- **Block-aware feeds** — feed endpoints filter out posts by authors
  the viewer is in a block edge with.
- **Cassandra: query-driven schema** — every read pattern has its own
  denormalised table. No `ALLOW FILTERING`. No secondary indexes on
  hot columns.
- **Realtime via Redis pub/sub** — cross-instance fan-out. Per-post
  topic. SSE for the last mile.
- **Notifications coalesce** — same `(userId, groupKey)` unread rows
  merge inside a 60-min window.
- **Counter cache** — `CounterCache` mirrors denormalised counters in
  Redis so reads stay sub-millisecond.
- **LWT-guarded toggles for reactions & saves** — `INSERT IF NOT EXISTS`
  / `DELETE IF EXISTS` on the lookup row makes the toggle write
  race-safe under concurrent submits from the same viewer. The losing
  request is a silent no-op (no counter delta, no broadcast, no
  notification). One extra Paxos round-trip per like/save.
- **Bulk-load feed hydration** — `PostHydrator` pre-fetches counters +
  viewer reactions + viewer saves for an entire page in 3 multi-partition
  `IN` queries. A 20-item page hits Cassandra 3 times for the
  viewer-state hydration instead of 60. Limit: bounded page sizes only
  (~50 items); IN over hundreds of partitions overloads the coordinator.
- **Realtime counter delta model** — `PostRealtimeEvent` does NOT carry
  fresh counter values; the fields are declared on the type but always
  null on the wire. Clients apply +1 / −1 locally from the event type
  (see §24 "Local delta" column) and reconcile via REST on the next
  fetch. Rationale: Cassandra counter reads are eventually consistent,
  so re-reading right after the write returns stale data while costing
  an extra round-trip.
- **Hard-delete for comments & replies** — `DELETE /comments/{id}`
  physically removes the row; top-level deletes also range-delete the
  comment's entire reply partition in one tombstone and adjust
  `post_counters.comment_count` by `1 + replyCount`. No more
  soft-delete bloat in long threads. Tombstones GC after the table's
  `gc_grace_seconds`.
- **Range-delete for notification cleanup** —
  `CassandraNotificationService.deleteAllReadFor(userId)` uses a single
  range tombstone for everything older than the oldest unread, instead
  of per-row deletes. `deleteOlderThan(userId, before)` is exposed for
  scheduled bulk purges. Lookup-table rows expire naturally via their
  90-day TTL — no scatter delete needed.
- **Deferred notification enrichment** — the post / user / comment
  lookups needed to build a notification body are passed as a
  `Supplier<DeliverRequest>` into
  `CassandraNotificationService.deliverAsync(Supplier)` /
  `deliverAllAsync(Supplier)`. They run on the notification executor,
  not the originating request thread. This removes the post-author and
  actor-username reads from the hot path of every like / comment /
  reaction and prevents read amplification on viral posts.
- **Batched mention resolution** — all `@username` tokens in a post are
  resolved in ONE `WHERE username IN (?)` Postgres query through
  `UserRepository.findAllByUsernameIn(...)`. A post with N mentions
  costs one round-trip, not N sequential ones.
- **Keyset-paginated, parallel home-feed fanout** —
  `FeedTimelineService.fanoutAsync` walks followers via
  `UserFollowRepository.findFollowerIdsAfter(authorId, cursor, limit)`
  (keyset on `follower.id ASC`) so per-page cost stays constant at any
  depth. Each 500-follower batch's per-follower writes run through
  `parallelStream` so the Cassandra inserts + Redis publishes + queued
  notifications don't serialize on the single `@Async` thread. Hard
  capped at 50k followers per post.

---

## See also

- `QNA_API.md` — Q&A APIs
- `RESEARCH_API.md` — Research APIs
- `USER_API.md` — User identity, profile, social graph, notifications
- `USER_ACTIVITY_API.md` — Per-user activity feed
- `POST_ERRORS.md` — Complete error & exception reference
- `BACKEND_ENHANCEMENTS.md` — Roadmap of features still to ship
