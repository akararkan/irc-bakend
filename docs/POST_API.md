# Post Package — Full API Documentation

This is the complete reference for everything under `ak.dev.irc.app.post` (the
Cassandra-backed social-media layer) plus the cross-cutting notification engine.

## Recent additions

| Section | What changed |
|---------|--------------|
| [§1 — `PATCH /api/v1/posts/{id}`](#edit-partial-update--patch-apiv1postsid) | New endpoint — author-only partial update; broadcasts `POST_UPDATED`. |
| [§1 — SSE `?token=` fallback](#live-event-stream) | `GET /{id}/stream` now accepts `?token=<jwt>` for browser `EventSource`. JWT filter passes through stale cookies on `/stream` endpoints. |
| [§5 — Saves now hydrated](#5-saves-bookmarks) | `GET /api/v1/posts/users/{userId}/saves` returns `List<PostResponse>` (not raw `SaveByUserEntity`). Adds `savedAt` + `savedCollectionName` to `PostResponse`. |
| [§17 — `PostResponse` shape](#postresponse) | Added `savedAt` + `savedCollectionName` (nullable). |
| [Cross-cutting] | Every mutation now records an activity row — see `USER_ACTIVITY_API.md` for the full catalog. |

It covers:

- [Post types & creation](#1-posts)
- [Feeds (home, profile, reels, search, suggestions)](#2-feeds)
- [Reactions (post + comment)](#3-reactions)
- [Comments & Replies](#4-comments--replies)
- [Saves (bookmarks)](#5-saves-bookmarks)
- [Shares](#6-shares)
- [Views](#7-views)
- [Media (carousels)](#8-media)
- [Hashtags & Mentions](#9-hashtags--mentions)
- [Sounds (TikTok-style audio library)](#10-sounds)
- [Stories (+ Close Friends, Polls)](#11-stories)
- [Highlights (permanent story archives)](#12-highlights)
- [Friend Suggestions](#13-friend-suggestions)
- [Notifications](#14-notifications)
- [Realtime (SSE)](#15-realtime-sse)
- [Enums (full catalog)](#16-enums)
- [DTOs (response shapes)](#17-dtos)
- [Counters (how live counts are maintained)](#18-counters)
- [Cassandra tables index](#19-cassandra-tables-index)

All endpoints live under `/api/v1/...`. Auth is JWT-based — the authenticated
user is extracted from the principal (`@AuthenticationPrincipal User`); whenever
an endpoint needs an `authorId` / `sharerId` / etc., it derives it from the JWT,
not the request body.

---

## 1. Posts

Posts are the canonical unit of social content. One write hits multiple
denormalised Cassandra tables (so reads can stay single-partition).

### Post types — `PostType` enum

| Value         | Meaning |
|---------------|---------|
| `TEXT`        | Pure text |
| `EMBEDDED`    | Text + media (image / video / carousel) |
| `VOICE_POST`  | Primary content is voice/audio |
| `REEL`        | Short-form video — additionally fans into the global `reels_by_day` feed |
| `REPOST`      | Re-share of another user's post (`sharedPostId` set). Self-reposts are explicitly allowed |
| `STORY`       | Ephemeral 24h content (see [Stories](#11-stories)) |

### Visibility — `PostVisibility`

`PUBLIC` · `FOLLOWERS_ONLY` · `ONLY_ME`.

`ONLY_ME` still fans out to the author's own home feed so they see their own
posts on `/feed`.

### Status — `PostStatus`

`DRAFT` · `PUBLISHED` · `ARCHIVED` · `REMOVED`. New posts are written as
`PUBLISHED`.

### Media types per post — `PostMediaType`

`IMAGE` · `VIDEO` · `AUDIO_TRACK` · `DOCUMENT`. Stored in
`PostByIdEntity.mediaTypes` as parallel arrays with `mediaUrls`.

### Endpoints (base: `/api/v1/posts`)

#### Create — JSON

`POST /api/v1/posts`  ·  Content-Type: `application/json`

Body (`CreatePostCommand`):

```json
{
  "postType": "EMBEDDED",
  "visibility": "PUBLIC",
  "textContent": "Hello world #greeting @ahmed",
  "audioTrackUrl": null,
  "audioTrackName": null,
  "locationName": "Erbil",
  "locationLat": 36.19,
  "locationLng": 44.0,
  "sharedPostId": null,
  "shareLink": null,
  "mediaUrls": ["https://cdn.../a.jpg"],
  "mediaTypes": ["IMAGE"],
  "soundId": null
}
```

- `authorId` is derived from the JWT — any value in the body is ignored.
- If `soundId` is set, the post is registered against the sound library
  (`posts_by_sound` + `sound_counters.use_count++`).
- Hashtags & `@mentions` are extracted from `textContent` synchronously
  (per-tag feeds + mentioned-user inboxes updated immediately).

Response: `200 PostResponse` (hydrated).

#### Create — Multipart

`POST /api/v1/posts`  ·  Content-Type: `multipart/form-data`

Form fields (all optional except `postType`): `postType`, `visibility`,
`textContent`, `audioTrackUrl`, `audioTrackName`, `locationName`, `locationLat`,
`locationLng`, `sharedPostId`, `shareLink`, `soundId`.

Files are accepted from **any** of these part names: `files`, `files[]`,
`media`, `media[]`, `file`, `video`, `videos`, `image`, `images`. Each file
is uploaded to R2 and its public URL/type appended to `mediaUrls`/`mediaTypes`.

Robustness: if the DB write fails after a successful R2 upload, every
uploaded key is deleted to avoid orphan storage. Response is then a structured
`500 { error, message, rolledBackFiles }`. R2 failure returns
`502 { error: "upload_failed" }`.

#### Read

- `GET /api/v1/posts/{id}` — single post, hydrated (`PostResponse`).
- `DELETE /api/v1/posts/{id}` — hard-delete, author-only (`403` otherwise).
  Cleans `posts_by_id`, `posts_by_author`, and async-deindexes Elasticsearch.
  `feed_by_user` rows die naturally via 30-day TTL.

#### Edit (partial update) — `PATCH /api/v1/posts/{id}`

Author-only partial update. Each non-null body field overwrites the
corresponding column on `posts_by_id`. Null fields are left untouched.

```
PATCH /api/v1/posts/{id}
Content-Type: application/json
Authorization: Bearer <jwt>

{
  "textContent":   "edited body...",
  "visibility":    "FOLLOWERS_ONLY",
  "audioTrackUrl":  null,
  "audioTrackName": null,
  "locationName":  "Erbil",
  "locationLat":   36.19,
  "locationLng":   44.00,
  "mediaUrls":     ["https://cdn.../new.jpg"],
  "mediaTypes":    ["IMAGE"]
}
```

Response: `200 PostResponse` (hydrated, with fresh `updatedAt`).

Side effects on a successful edit:

- `posts_by_id.updatedAt = now`.
- Best-effort mirror onto `posts_by_author` so the profile feed reflects
  the edit on next read. Mirror failure is logged but does not fail the
  request.
- Async Elasticsearch re-index.
- Broadcasts `POST_UPDATED` on the post's realtime channel
  (`PostRealtimeEventType.POST_UPDATED`) so every open viewer
  reconciles without refetching.

Error responses:

| Condition | HTTP | Body |
|-----------|------|------|
| Not authenticated | `401` | bare body |
| Post not found | `404` | bare body |
| Caller is not the author | `403` | bare body — caught from `SecurityException("Not the author")` and translated |
| Body malformed | `400` | `ApiErrorResponse` with `MALFORMED_JSON` |

#### Live event stream

`GET /api/v1/posts/{id}/stream`  · `Content-Type: text/event-stream`

SSE stream of every event on this post — see
[Realtime](#15-realtime-sse) for the full event-type catalog.

**Auth — two options:**

1. **JWT principal** — standard `Authorization: Bearer <jwt>` header OR
   the `access_token` HttpOnly cookie. Anonymous viewers ARE allowed
   on this endpoint (post streams are public).
2. **`?token=<jwt>`** query param — fallback for browser `EventSource`,
   which cannot send custom headers. Useful when the user is logged in
   via cookie but the cookie hasn't been forwarded by a corporate proxy,
   or when opening a stream from a notebook/CLI tool.

If a stale cookie is sent, the JWT filter is now SSE-aware: it logs at
`DEBUG` and passes the request through (instead of writing a `401` that
the browser would surface as a confusing "CORS error / null status").
The controller then accepts the `?token=` fallback or treats the
viewer as anonymous.

The response sets:

```
Content-Type: text/event-stream
X-Accel-Buffering: no
Cache-Control:    no-cache, no-store, must-revalidate
Connection:       keep-alive
```

(so Railway / Nginx / Cloudflare don't buffer the stream.)

---

## 2. Feeds

### Profile feed

`GET /api/v1/posts/by-author/{authorId}?pageSize=20&cursor={instant}`

Newest-first list of one user's posts. Backed by `posts_by_author` —
clustered DESC by `created_at`. Returns `List<FeedItemResponse>`.

### Home feed

`GET /api/v1/posts/feed?pageSize=20&cursor={instant}`
`GET /api/v1/posts/feed/cursor` — legacy alias.

Fanout-on-write timeline. Viewer is the JWT user (or `?userId=` for legacy
clients). Read path:

1. Redis `ZSET` (`feed:timeline:{userId}`, last ~100 post ids by score=ts)
2. Fall back to `feed_by_user` partition slice
3. Backfill Redis on miss

Even `ONLY_ME` posts are inserted into the author's own feed.

When a user follows someone new, the last ~50 of the followee's posts are
backfilled into the new follower's feed via `FeedTimelineService.backfillFollowerFeed`.

### Reels — global discover

`GET /api/v1/posts/reels?day=YYYY-MM-DD&pageSize=20`
`GET /api/v1/posts/feed/reels` — legacy alias.

Backed by `reels_by_day` — partitioned by UTC date so partition size stays
bounded. Default `day` = today (UTC). Returns `List<FeedItemResponse>`.

### Full-text search

`GET /api/v1/posts/search?q=quran&page=0&size=20`

Elasticsearch-backed. Response: `{ query, page, size, results: [<UUIDs>] }`.

Index is updated asynchronously on post create/delete.

### Friend suggestions

- `GET /api/v1/posts/suggestions?userId={uuid}&limit=20` — read precomputed,
  already sorted by mutual-count DESC.
- `POST /api/v1/posts/suggestions/recompute?userId={uuid}` — trigger async
  recompute (called from `/follow` webhook). Returns `202`.

Algorithm: friends-of-friends collaborative filtering. Min 2 mutuals, top 50
stored per user.

---

## 3. Reactions

**Project rule:** there is exactly **one** reaction type — `LIKE`. Single,
boolean, "academic not entertainment". Presence of a row is the like.

### On a post

- `POST   /api/v1/posts/{postId}/reactions` — toggle. Returns `{postId, userId, liked}`.
- `DELETE /api/v1/posts/{postId}/reactions` — explicit unlike (idempotent).
- `GET    /api/v1/posts/{postId}/reactions/me` — "did I like this?"
- `GET    /api/v1/posts/users/{userId}/reactions?pageSize=20` —
  reaction history (newest first).

Write path: `reactions_by_post` + `reactions_by_user` + `post_counters.reaction_count` ±1.

### On a comment

- `POST   /api/v1/posts/{postId}/comments/{commentId}/reactions` — toggle.
- `DELETE /api/v1/posts/{postId}/comments/{commentId}/reactions` — explicit unlike.

Write path: `comment_reactions_by_comment` + `comment_counters.reaction_count` ±1.

### Side effects on like

1. Realtime event broadcast on `posts:{postId}` (`REACTION_ADDED` /
   `REACTION_REMOVED`, or the `COMMENT_REACTION_*` variants).
2. Notification fired to the post (or comment) author —
   `POST_REACTED` / `POST_COMMENT_REACTED`. Aggregated, in-app only (no email).

### No LWT / CAS

Cassandra LWT is avoided on the like path; a stale double-like is cheaper
than a Paxos round-trip per request. A periodic counter-reconciler job sweeps
drift.

---

## 4. Comments & Replies

**Depth-1 rule:** replies are flat. A reply to a reply lands as a **sibling**
under the original top-level comment, never as a deeper child. Resolved via
`comment_lookup` before write.

**Dedup guard:** identical text from the same author on the same post within
the dedup window is silently merged — repeat submissions return the existing
row instead of creating duplicates (`DedupGuard`).

### Comment endpoints (under `/api/v1/posts`)

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{postId}/comments` | Create top-level comment. Body: `{text, mediaUrl, mediaType}`. Returns `CommentResponse`. |
| `GET`    | `/{postId}/comments?pageSize=20&cursor=` | List top-level comments (chronological ASC, cursor-paginated). |
| `POST`   | `/comments/{commentId}/replies` | Reply to a comment. Body: `{text, mediaUrl}`. Returns `ReplyResponse`. Depth-1 rule applied here. |
| `GET`    | `/comments/{commentId}/replies?pageSize=20` | List replies under a comment. |
| `PATCH`  | `/comments/{commentId}` | Edit. Author-only. Body: `{text}`. |
| `DELETE` | `/comments/{commentId}` | Soft-delete. Author-only. Body is nulled, `is_deleted=true`. Counters decrement. |

### Write fan-out

| For each…           | Tables written |
|---------------------|----------------|
| Top-level comment   | `comments_by_post`, `comment_lookup`, `post_counters.comment_count++` |
| Reply               | `replies_by_comment`, `comment_lookup`, `comment_counters.reply_count++`, `post_counters.comment_count++` |
| Edit                | Same row updated; realtime `COMMENT_EDITED` event |
| Soft-delete         | Text nulled, `is_deleted=true`; counters decrement; replies under a deleted top-level stay readable |

### Realtime events emitted

`COMMENT_CREATED`, `COMMENT_EDITED`, `COMMENT_DELETED`, `REPLY_CREATED`,
`COMMENT_REACTION_ADDED`, `COMMENT_REACTION_REMOVED` — see
[Realtime](#15-realtime-sse).

### Notifications emitted

| Trigger | Kind | Recipient | Aggregable | Email? |
|---------|------|-----------|------------|--------|
| Top-level comment | `POST_COMMENTED` | Post author | yes | yes |
| Reply             | `POST_COMMENT_REPLIED` | Top-level comment author (depth-1 rule applies — *not* the intermediate reply author) | yes | yes |
| Comment liked     | `POST_COMMENT_REACTED` | Comment author | yes | no |

---

## 5. Saves (bookmarks)

Toggle-save with optional named "collections" (folders).

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/api/v1/posts/{postId}/saves?collection=Quran` | Toggle save. Returns `{postId, userId, saved}`. |
| `DELETE` | `/api/v1/posts/{postId}/saves`                  | Explicit unsave (idempotent — no-op if not currently saved). |
| `GET`    | `/api/v1/posts/{postId}/saves/me`               | "Did I save this?" — anonymous-safe (returns `{saved: false}` for anon). |
| `GET`    | `/api/v1/posts/users/{userId}/saves?pageSize=20&cursor=` | A user's saved posts — **hydrated** (newest first). |

#### `GET /api/v1/posts/users/{userId}/saves` response shape

Returns `List<PostResponse>` — **fully hydrated** rows so `response[i].id`
is the **post UUID** (not the raw save row's `postId`). Each row carries
two save-context fields populated only by this endpoint:

| Field | Type | Meaning |
|-------|------|---------|
| `savedAt`              | `Instant`  | When the viewer bookmarked the post (= the save row's `createdAt`). |
| `savedCollectionName`  | `String`   | Collection / folder name on the save row (defaults to `"Default"`). |

Plus the usual `PostResponse` shape: author summary, counters,
`likedByMe`, `savedByMe = true` (always — every row here is by
definition saved by the viewer), etc.

Saves whose underlying post has been hard-deleted are silently dropped
from the response (Cassandra has no FK; the save mirror can outlive the
post).

#### Side effects on save (toggle ON only)

| Storage | Effect |
|---------|--------|
| `saves_by_user`        | New row inserted (newest first per viewer) |
| `saves_by_post_user`   | New point-lookup row inserted |
| `post_counters.save_count` | `+ 1` (atomic counter) |
| Realtime               | Broadcasts `SAVE_COUNT_UPDATED` on the post's stream |
| Activity feed          | `POST_SAVED` row inserted for the viewer |

#### Side effects on unsave

| Storage | Effect |
|---------|--------|
| `saves_by_user`        | Row deleted |
| `saves_by_post_user`   | Row deleted |
| `post_counters.save_count` | `- 1` (atomic counter) |
| Realtime               | `SAVE_COUNT_UPDATED` broadcast |
| Activity feed          | **NOT** recorded (toggle-ON only — prevents activity churn on save/unsave cycles) |

Changing a collection = unsave + save with the new name (no separate
update path). Future work: see
[BACKEND_ENHANCEMENTS.md §3.2](./BACKEND_ENHANCEMENTS.md#32-saved-collections-management)
for the dedicated collections API.

---

## 6. Shares

Append-only ledger of platform shares (Share-to-DM / Share-to-X / etc.).
Distinct from a `REPOST` (which creates a brand-new `Post` with
`sharedPostId` set).

| Method | Path | Purpose |
|--------|------|---------|
| `POST`  | `/api/v1/posts/{postId}/shares` | Record a share. Body (optional): `{caption}`. Sharer derived from JWT. Returns `ShareByPostEntity`. |
| `GET`   | `/api/v1/posts/{postId}/shares?pageSize=20` | Recent shares (newest first). |

Side effects: `post_counters.share_count++`, realtime `SHARE_COUNT_UPDATED`,
notification `POST_SHARED` to the author (aggregated, email-eligible).

No "unshare" path (uncommon UX).

---

## 7. Views

Unique-viewer count — only the first view from a given user in a 7-day
window counts.

`POST /api/v1/posts/{postId}/views`

Returns `{postId, userId?, counted, viewCount}`. Anonymous viewers get the
count back but don't bump it.

Idempotency strategy:

1. Redis `SET NX` on `view:{postId}:{userId}` with 7-day TTL — answers
   "have we already counted this user this week?" in O(1).
2. On NX success: write `views_by_post` row + bump `post_counters.view_count`.
3. On NX failure: no-op.
4. On Redis outage: fall back to a `views_by_post` point-read (slower but
   still correct).

Realtime `VIEW_COUNT_UPDATED` event published on the post's stream.

---

## 8. Media

For larger albums or post-publish edits. Posts created with `≤ 4` media items
typically inline them on `posts_by_id.mediaUrls`; this endpoint lets you
attach more or reorder.

Base: `/api/v1/posts/{postId}/media`

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `` | Add one media item. Body: `{sortOrder, mediaType, url, thumbnailUrl, s3Key, durationSeconds, fileSizeBytes, mimeType, altText}`. |
| `GET`    | `` | List media for a post (sorted ASC by `sortOrder`). |
| `DELETE` | `/{mediaId}?sortOrder=N` | Remove one item. |
| `PUT`    | `` | Replace the entire ordered list (drag-and-drop reorder). Body: full `List<MediaByPostEntity>`. |

Reorder caveat: `sort_order` is part of the clustering key in Cassandra so
it can't be `UPDATE`d in place — `PUT` performs a bulk delete + re-insert.

---

## 9. Hashtags & Mentions

Extraction happens synchronously on post create from `textContent` via
`CassandraHashtagService.indexEntitiesForPost`:

- `#word` (alphanumeric + underscore, case-insensitive, stored lowercased)
- `@username` — resolved against `UserRepository` to a UUID.

### Endpoints (base: `/api/v1`)

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/hashtags/{tag}/posts?pageSize=20&cursor=` | Posts tagged `#tag`, newest first. |
| `GET` | `/hashtags/{tag}/usage` | `{hashtag, postCount}` — for trending UI. |
| `GET` | `/users/{userId}/mentions?pageSize=20` | "Posts that mention me" inbox. |

Write fan-out:

- per `#tag`: `posts_by_hashtag` + `hashtag_counters.post_count++`.
- per `@user`: `mentions_by_user` + `USER_MENTIONED` notification.

`USER_MENTIONED` is not aggregated — each mention deserves its own inbox row;
email-eligible (gated by the user's MENTIONS toggle).

---

## 10. Sounds

TikTok-style reusable audio library. Status flow: `PENDING_REVIEW` → `APPROVED`
(or `REJECTED` / `ARCHIVED`).

`SoundCategory`: `NASHEED`, `QURAN_RECITATION`, `LECTURE_CLIP`, `NATURE`,
`ORIGINAL`, `PLATFORM_MUSIC`.

### Endpoints (base: `/api/v1/sounds`)

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `` | Upload. Body: `{title, artistName, audioUrl, coverArtUrl, durationSeconds, category, uploaderId, autoApprove}`. `autoApprove=true` skips moderation (admin-only in prod). |
| `GET`    | `/{id}` | Point-read by id. |
| `POST`   | `/{id}/approve` | Mark `APPROVED`; fans out to `sounds_by_category`. |
| `GET`    | `/by-category/{category}?pageSize=20&cursor=` | Browse approved sounds in a category, newest first. |
| `GET`    | `/{id}/posts?pageSize=20` | "All posts using this sound" — the discover-page query. |
| `GET`    | `/{id}/usage` | `{soundId, useCount}` for trending UI. |

Adoption (called automatically from `CassandraPostService.createPost` when
the create command carries `soundId`):

- writes `posts_by_sound` row,
- `sound_counters.use_count++`.

Approval notification: `SOUND_APPROVED` → uploader (system category, email).

---

## 11. Stories

24-hour ephemeral content. **TTL is enforced at the Cassandra table level**
(`default_time_to_live = 86400`) — rows tombstone automatically; no expiry
job needed.

### Story types — `StoryType`

`TEXT`, `IMAGE`, `VIDEO`, `LINKED_POST`, `LINKED_REEL`, `LINKED_QNA`,
`LINKED_RESEARCH`, `KNOWLEDGE_PILL` (Scholar knowledge flashcards — IRC exclusive).

### Story visibility — `StoryVisibility`

`PUBLIC`, `FOLLOWERS_ONLY`, `CLOSE_FRIENDS`, `ONLY_ME`. (Wider than
`PostVisibility` — stories add a CLOSE_FRIENDS scope.)

Visibility resolution (server-enforced on every read + view-record):

| Visibility       | Viewer requirement |
|------------------|--------------------|
| `PUBLIC`         | Anyone (incl. anonymous) |
| `FOLLOWERS_ONLY` | Viewer follows the author (Postgres `UserFollow` check) |
| `CLOSE_FRIENDS`  | Viewer is in the author's close-friends list |
| `ONLY_ME`        | Viewer == author |

### Story overlays — `StoryOverlayType`

`TEXT`, `EMOJI`, `STICKER`, `LINK`, `MENTION`, `HASHTAG`, `POLL`, `QUESTION`,
`COUNTDOWN`, `LOCATION`.

### Viewer relationship — `ViewerRelationship`

`AUTHOR` / `CLOSE_FRIEND` / `FOLLOWER` / `PUBLIC` — used by the visibility
resolver.

### Endpoints

#### Stories (base: `/api/v1`)

| Method | Path | Purpose |
|--------|------|---------|
| `POST` (JSON)      | `/stories` | Create story. Body: `{storyType, visibility, mediaUrl, thumbnailUrl, textContent}`. authorId from JWT. |
| `POST` (multipart) | `/stories` | Same, with file uploads as `media` + optional `thumbnail` parts. |
| `GET`              | `/stories/by-author/{authorId}` | Author's active stories, filtered by viewer visibility. |
| `DELETE`           | `/stories/{storyId}` | Author-only delete before TTL. |
| `POST`             | `/stories/{storyId}/views` | Record a view. Visibility-enforced. Self-views are not logged. |
| `GET`              | `/stories/{storyId}/views?pageSize=50` | Viewer log (newest first). |

#### Close friends (base: `/api/v1/close-friends`)

All scoped to the JWT user (the "owner"). Privacy: only the owner can read
their list.

| Method | Path | Purpose |
|--------|------|---------|
| `GET`    | `` | List my close friends. |
| `POST`   | `?friendId={uuid}` | Add a friend. |
| `DELETE` | `?friendId={uuid}` | Remove a friend. |
| `GET`    | `/is-member?candidateId={uuid}` | Boolean predicate — used by the UI for colour-coding. |

#### Story polls (base: `/api/v1`)

Two-option Instagram-style polls attached to a story. One vote per user;
changing your mind is allowed (moves the row, no double-counting). All
poll tables inherit the 24h TTL from the parent story.

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/stories/{storyId}/poll` | Author-only. Body: `{question, optionA, optionB}`. |
| `GET`  | `/stories/{storyId}/poll`  | Get the attached poll. |
| `POST` | `/polls/{pollId}/vote?choice=A|B` | Cast or change vote. Returns `{choice, voteA, voteB}`. |
| `GET`  | `/polls/{pollId}/vote/me` | "What did I vote?" |
| `GET`  | `/polls/{pollId}/results` | Live tallies — no auth needed. |
| `GET`  | `/polls/{pollId}/voters/{choice}?pageSize=50` | Author-only voter list per side. **Note:** auth check is not yet enforced in the controller — caller must enforce. |

---

## 12. Highlights

Permanent archives of stories. Survive past the original story's 24h TTL by
snapshotting content into `stories_in_highlight`.

Base: `/api/v1/highlights`

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `` | Create. Body: `{authorId, title, coverUrl, displayOrder}`. |
| `GET`    | `/by-author/{authorId}` | List highlights for an author (ordered by `displayOrder` ASC). |
| `POST`   | `/{highlightId}/stories/{storyId}?requesterId={uuid}` | Snapshot a still-active story into a highlight. Author-only. |
| `GET`    | `/{highlightId}/stories` | List stories in a highlight (chronological ASC). |
| `DELETE` | `/{highlightId}/stories/{storyId}?createdAt={instant}` | Remove a story from a highlight. |

Snapshot semantics: when adding a story, its content (mediaUrl, thumbnailUrl,
textContent, storyType, authorId, original `createdAt`) is **copied** into
`stories_in_highlight` so the highlight remains self-contained after the
source row TTLs away.

---

## 13. Friend Suggestions

(See [Feeds → suggestions](#friend-suggestions).)

Backed by `friend_suggestions_by_user` — precomputed, partitioned by user,
clustered by `score DESC`. Recompute runs `@Async` and is fired from follow
mutations or a background batch.

---

## 14. Notifications

The post layer publishes notifications through `CassandraNotificationService`.
Notifications themselves are **owned by the user package**: the read/listing
endpoints live at `/api/v1/notifications` (see below). The post package only
generates and persists them.

### Delivery pipeline (one event)

1. **Self-suppression** — don't notify the actor about their own action.
2. **Block check** — drop if recipient ↔ actor are in any block relationship.
3. **Aggregation** — if `NotificationKind.aggregable() && groupKey != null`
   AND a same-group notification exists in the 60-min window, coalesce:
   bump `aggregate_count`, replace `last_actor_id`, rewrite `body`.
4. **Persist** to `notifications_by_user`, `notification_lookup`,
   `notif_active_group_by_user`.
5. **Unread counter** `++` (only on a fresh row; coalesce doesn't bump).
6. **Realtime push** on Redis channel `irc:notifications:{userId}` — every
   open SSE tab receives it.
7. **Email**, fire-and-forget, iff all of:
   - `kind.emailEligible()`
   - `recipient.emailNotificationsEnabled` (master toggle)
   - the category-specific toggle is on (`emailSocialEnabled` /
     `emailMentionsEnabled` / `emailSystemEnabled`)
   - per-group Redis throttle was claimable
     (`notif:email:throttle:{userId}:{groupKey}`, 1-hour TTL — protects users
     from N emails for N rapid likes; first one fires, rest are gated).

### `DeliverRequest`

```java
public record DeliverRequest(
    UUID             userId,         // recipient
    NotificationKind kind,
    String           title,
    String           body,
    UUID             actorId,        // who triggered it (may be null)
    String           resourceType,   // "Post" | "Comment" | ...
    UUID             resourceId,
    String           groupKey        // required if kind.aggregable()
) {}
```

`groupKey` patterns used across the codebase:

```
{KIND}:{resourceId}        — e.g. "POST_REACTED:abc-123"
{KIND}:{parentId}          — e.g. "POST_COMMENT_REPLIED:cmt-xyz"
{KIND}:{postId}:{userId}   — e.g. "USER_MENTIONED:p-1:u-42"
```

### `NotificationKind` — full catalog

Each kind carries three metadata flags:

- `prefCategory` ∈ {`SOCIAL`, `MENTIONS`, `SYSTEM`} — which email toggle gates it.
- `aggregable` — coalesce N events in a 60-min window into one row?
- `emailEligible` — does this kind ever email?

#### Post-related (the ones the post package fires)

| Kind                    | Fires when | Recipient | Group key | Aggregable | Email |
|-------------------------|------------|-----------|-----------|------------|-------|
| `POST_NEW`              | A user you follow publishes a post (fanned out per follower from `FeedTimelineService`) | Each follower | `POST_NEW:{postId}` | yes | **no** (would spam) |
| `POST_REACTED`          | Someone toggles a like ON your post | Post author | `POST_REACTED:{postId}` | yes | no |
| `POST_COMMENTED`        | Top-level comment on your post | Post author | `POST_COMMENTED:{postId}` | yes | yes |
| `POST_COMMENT_REPLIED`  | Reply to your comment (depth-1: notifies the **top-level** comment author) | Parent comment author | `POST_COMMENT_REPLIED:{parentId}` | yes | yes |
| `POST_COMMENT_REACTED`  | Like on your comment | Comment author | `POST_COMMENT_REACTED:{commentId}` | yes | no |
| `POST_SHARED`           | Someone shares your post | Post author | `POST_SHARED:{postId}` | yes | yes |
| `USER_MENTIONED`        | You're `@`-mentioned in a post | Each mentioned user | `USER_MENTIONED:{postId}:{userId}` | **no** | yes |

#### Story-related

| Kind | Trigger | Aggregable | Email |
|------|---------|------------|-------|
| `STORY_PUBLISHED` | Followee published a new story | no | no |
| `STORY_REACTED`   | Reaction to your story | yes | no |
| `STORY_REPLIED`   | Reply to your story | yes | yes |

#### Other domains (kept here for completeness)

| Kind | Category | Aggregable | Email |
|------|----------|------------|-------|
| `NEW_FOLLOWER` | SOCIAL | no | yes |
| `PUBLICATION_LIKED` | SOCIAL | yes | no |
| `PUBLICATION_COMMENTED` | SOCIAL | yes | yes |
| `PUBLICATION_COMMENT_REACTED` | SOCIAL | yes | no |
| `PUBLICATION_CITED` | SOCIAL | no | yes |
| `RESEARCH_CONTRIBUTOR_ADDED` | SOCIAL | no | yes |
| `QUESTION_NEW` | SOCIAL | no | no |
| `QUESTION_ANSWERED` | SOCIAL | no | yes |
| `ANSWER_REPLIED` | SOCIAL | yes | yes |
| `ANSWER_REACTED` | SOCIAL | yes | no |
| `ANSWER_ACCEPTED` | SOCIAL | no | yes |
| `ANSWER_FEEDBACK_RECEIVED` | SOCIAL | no | no |
| `SOUND_APPROVED` | SYSTEM | no | yes |
| `SYSTEM_MESSAGE` | SYSTEM | no | yes |
| `SYSTEM_ANNOUNCEMENT` | SYSTEM | no | yes |
| `ACCOUNT_WARNING` | SYSTEM | no | yes |

### Notification REST API (base: `/api/v1/notifications`, `@PreAuthorize(isAuthenticated)`)

#### Real-time SSE stream

`GET /api/v1/notifications/stream`  ·  `produces: text/event-stream`

- Auth: JWT principal OR `?token=<accessToken>` (since `EventSource` can't
  send headers).
- Sets `X-Accel-Buffering: no` so proxies don't hold events.

Event types delivered:

| Event name      | Payload |
|-----------------|---------|
| `connected`     | Handshake on subscribe |
| `notification`  | New (or coalesced) `NotificationResponse` |
| `unread-count`  | `{count: N}` after every state change |
| `read`          | `{ids:[...], allRead, deleted:false}` (cross-tab sync) |
| `deleted`       | `{ids:[...], allRead, deleted:true}` |
| `heartbeat`     | Keepalive every ~25s |

#### Listing

`GET /api/v1/notifications`

Query params:

- `category=POSTS|QNA|RESEARCH|MENTIONS|SOCIAL|SYSTEM`
- `type=POST_REACTED` (repeatable — falls under `NotificationType`)
- `unread=true`
- standard `page=`, `size=` (default 20)

Other listing endpoints:

| Method | Path | Purpose |
|--------|------|---------|
| `GET`   | `/unread` | Only unread |
| `GET`   | `/unread/count?category=…` | `{count}` |

#### Mark as read

| Method | Path | Body | Purpose |
|--------|------|------|---------|
| `PATCH` | `/read-all` | — | Mark every notification read |
| `PATCH` | `/{id}/read` | — | Mark one read |
| `PATCH` | `/read` | `{ids: [...]}` | Bulk; returns `{updated}` (already-read skipped) |
| `PATCH` | `/category/{category}/read` | — | Returns `{updated}` |

#### Delete

| Method | Path | Purpose |
|--------|------|---------|
| `DELETE` | `/{id}` | Delete one |
| `DELETE` | `/read` | Purge every already-read notification; returns `{deleted}` |

### Storage tables

| Table | Role |
|-------|------|
| `notifications_by_user` | Per-user inbox, partition per user, clustered DESC by `created_at`. 90-day TTL at table level. |
| `notification_lookup`   | Point-read by `notification_id` (the URL only carries the id). |
| `notif_active_group_by_user` | Pointer `(user_id, group_key) → notificationId, createdAt`. TTL'd 60min so the next event past the window starts a fresh row. |
| `notification_unread_counter` | Per-user unread counter (Cassandra counter column). |

### `NotificationEntity` shape

```java
class NotificationEntity {
    UUID    userId;            // PK partition
    Instant createdAt;         // PK cluster (DESC)
    UUID    notificationId;    // PK cluster
    String  type;              // NotificationKind.name()
    String  title;
    String  body;
    UUID    actorId;           // who triggered the first event
    UUID    lastActorId;       // most-recent actor for aggregated rows
    Long    aggregateCount;    // 1 = unique, N>1 = coalesced
    String  resourceType;      // "Post" / "Comment" / ...
    UUID    resourceId;
    String  groupKey;          // coalescing key
    Boolean read;
}
```

---

## 15. Realtime (SSE)

Two SSE channels carry post-layer events:

### Per-post stream

`GET /api/v1/posts/{id}/stream` (subscribed via `PostRealtimeService`)

Cross-instance fan-out: `PostRealtimePublisher` / `PostRealtimeSubscriber`
over Redis pub/sub. The originating actor is filtered out server-side
(`actorId.equals(viewerId)` → skip) to avoid double-rendering.

Heartbeat every 25s. Reconnect time hint = 3s (resilient to JVM restarts).

#### `PostRealtimeEventType`

```
REACTION_ADDED, REACTION_CHANGED, REACTION_REMOVED,
COMMENT_CREATED, COMMENT_EDITED, COMMENT_DELETED,
REPLY_CREATED,
COMMENT_REACTION_ADDED, COMMENT_REACTION_CHANGED, COMMENT_REACTION_REMOVED,
VIEW_COUNT_UPDATED, SHARE_COUNT_UPDATED, SAVE_COUNT_UPDATED,
POST_UPDATED, POST_DELETED
```

#### `PostRealtimeEvent` payload (all fields nullable, `@JsonInclude(NON_NULL)`)

```
eventType                 PostRealtimeEventType
postId                    UUID
actorId, actorUsername, actorAvatarUrl
commentId, parentCommentId
reactionType, previousReactionType    // "LIKE" only today; previousReactionType retained for forward compat
textContent, mediaUrl, mediaType, mediaThumbnailUrl
postReactionCount, postCommentCount, postShareCount, postViewCount, postSaveCount
commentReactionCount, commentReplyCount
timestamp                 LocalDateTime (default: now)
```

### Per-story stream

`StoryRealtimeService` mirrors the same pattern for stories with its own
event-type enum:

```
STORY_VIEWED, STORY_REACTED, STORY_UNREACTED, STORY_REPLIED,
STORY_POLL_VOTED, STORY_EXPIRED, STORY_DELETED,
VIEW_COUNT_UPDATED, REACTION_COUNT_UPDATED, REPLY_COUNT_UPDATED
```

### Per-user notifications channel

See [Notifications → SSE stream](#real-time-sse-stream) above. Backed by
Redis `irc:notifications:{userId}` so any instance can deliver.

### Feed-level realtime

`FeedTimelineService` publishes a `FEED_NEW_POST` payload to
`FeedRealtimePublisher.publishToUser(viewerId)` for each follower fan-out
row, so open feed pages get the new post instantly without polling.

---

## 16. Enums

All enums live under `ak.dev.irc.app.post.enums`.

| Enum | Values | Notes |
|------|--------|-------|
| `PostType` | `TEXT`, `EMBEDDED`, `VOICE_POST`, `REEL`, `REPOST`, `STORY` | |
| `PostVisibility` | `PUBLIC`, `FOLLOWERS_ONLY`, `ONLY_ME` | |
| `PostStatus` | `DRAFT`, `PUBLISHED`, `ARCHIVED`, `REMOVED` | new posts → `PUBLISHED` |
| `PostMediaType` | `IMAGE`, `VIDEO`, `AUDIO_TRACK`, `DOCUMENT` | |
| `PostReactionType` | `LIKE` | **only one type — single-reaction project rule** |
| `StoryType` | `TEXT`, `IMAGE`, `VIDEO`, `LINKED_POST`, `LINKED_REEL`, `LINKED_QNA`, `LINKED_RESEARCH`, `KNOWLEDGE_PILL` | |
| `StoryVisibility` | `PUBLIC`, `FOLLOWERS_ONLY`, `CLOSE_FRIENDS`, `ONLY_ME` | |
| `StoryOverlayType` | `TEXT`, `EMOJI`, `STICKER`, `LINK`, `MENTION`, `HASHTAG`, `POLL`, `QUESTION`, `COUNTDOWN`, `LOCATION` | |
| `ViewerRelationship` | `AUTHOR`, `CLOSE_FRIEND`, `FOLLOWER`, `PUBLIC` | |
| `SoundCategory` | `NASHEED`, `QURAN_RECITATION`, `LECTURE_CLIP`, `NATURE`, `ORIGINAL`, `PLATFORM_MUSIC` | |
| `SoundStatus` | `PENDING_REVIEW`, `APPROVED`, `REJECTED`, `ARCHIVED` | |

---

## 17. DTOs

Response shapes returned to the frontend, hydrated by `PostHydrator` (joins
canonical Cassandra rows with Postgres `User` profile data and live counter
rows).

### `AuthorSummary`

```java
record AuthorSummary(
    UUID   id,
    String username,
    String fullName,
    String profileImage
) {}
```

Embedded inside every post / comment / reply response so the frontend never
has to round-trip to `/users/{id}` just to render a name/avatar.

### `PostResponse`

```java
record PostResponse(
    UUID    id,
    UUID    authorId,
    AuthorSummary author,
    String  postType,
    String  status,
    String  visibility,
    String  textContent,
    String  audioTrackUrl,
    String  audioTrackName,
    String  locationName,
    Double  locationLat,
    Double  locationLng,
    UUID    sharedPostId,
    String  shareLink,
    List<String> mediaUrls,
    List<String> mediaTypes,
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
    // ── Save-context fields (populated only by saved-list endpoints) ──
    /** When the viewer bookmarked this post. Null on every endpoint
     *  except {@code GET /api/v1/posts/users/{userId}/saves}. */
    Instant savedAt,
    /** Collection / folder name on the save row (defaults to "Default"). */
    String  savedCollectionName
) {}
```

### `FeedItemResponse`

Lighter shape for lists.

```java
record FeedItemResponse(
    UUID    id,
    UUID    authorId,
    AuthorSummary author,
    String  postType,
    String  textPreview,
    String  mediaUrl,
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
    Boolean deleted,
    Boolean edited,
    Instant createdAt
) {}
```

### `ReplyResponse`

```java
record ReplyResponse(
    UUID    id,
    UUID    parentId,
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

### `CursorPage<T>`

Generic cursor-paginated wrapper used by some endpoints.

```java
class CursorPage<T> {
    List<T> items;
    LocalDateTime nextCursor;   // null = end of feed
    boolean hasMore;
}
```

---

## 18. Counters

Cassandra counter columns can only be modified via
`UPDATE … SET col = col + N WHERE pk = ?` — Spring Data `save()` does NOT
work on counter tables. All counter writes go through `CounterService`.

| Counter | Bump on | Decrement on |
|---------|---------|--------------|
| `post_counters.reaction_count` | post like | unlike |
| `post_counters.comment_count`  | comment or reply created | comment/reply soft-deleted |
| `post_counters.share_count`    | `recordShare` | — (append-only) |
| `post_counters.view_count`     | first unique view (Redis NX) | — |
| `post_counters.save_count`     | save toggle ON | save toggle OFF |
| `comment_counters.reaction_count` | comment like | unlike |
| `comment_counters.reply_count`    | reply created | reply soft-deleted |
| `sound_counters.use_count`        | post created with `soundId` | (reconciler) |
| `hashtag_counters.post_count`     | each tag extracted on post create | (reconciler) |
| `poll_counters.vote_a / vote_b`   | vote cast for that side | vote moved away from that side |
| `notification_unread_counter.unread` | new (non-coalesced) notification | mark-read |

Cassandra counters are **not idempotent** — never retry blindly on write
failures. Idempotency lives at the API layer (e.g. "did this user already
like?" before incrementing).

---

## 19. Cassandra tables index

Quick lookup of every table the post package touches, with its purpose:

### Canonical / lookup

| Table | Role |
|-------|------|
| `posts_by_id` | Canonical post row, point-read by id |
| `comment_lookup` | Point-read comment by id (also walks reply → top-level parent) |
| `story_lookup` | Point-read story metadata by id |
| `saves_by_post_user` | "Did U save P?" point-lookup |
| `notification_lookup` | Point-read notification by id |
| `sounds_by_id` | Canonical sound row |
| `poll_by_story` | One poll per story, point-read by story id |
| `poll_votes_by_poll_user` | "Has U voted on P? Which side?" |

### Feeds / per-entity slices

| Table | Role |
|-------|------|
| `posts_by_author` | Profile feed (partition per author, DESC `created_at`) |
| `feed_by_user` | Home feed fanout-on-write (partition per viewer, 30d TTL) |
| `reels_by_day` | Global reels (partition per UTC day) |
| `comments_by_post` | Top-level comments per post (ASC `created_at`) |
| `replies_by_comment` | Replies under a comment (ASC `created_at`, flat depth 1) |
| `reactions_by_post` | "Who liked P?" (clustered by user_id) |
| `reactions_by_user` | "What has U liked recently?" (DESC `created_at`) |
| `comment_reactions_by_comment` | Per-comment like rows |
| `saves_by_user` | A user's bookmarks (DESC `created_at`) |
| `shares_by_post` | Shares for a post (DESC `created_at`) |
| `views_by_post` | Unique-view rows (clustered by user) |
| `media_by_post` | Carousel media (ASC `sort_order`) |
| `mentions_by_user` | `@mentions` inbox (DESC `created_at`) |
| `posts_by_hashtag` | Tagged posts (DESC `created_at`) |
| `friend_suggestions_by_user` | Precomputed (DESC score) |

### Stories

| Table | Role | TTL |
|-------|------|-----|
| `stories_by_author` | Author partition, DESC `created_at` | 24h |
| `story_lookup` | Point-read by story id | 24h |
| `story_views_by_story` | Viewer log (DESC `viewed_at`) | 24h |
| `close_friends_by_owner` | Owner's close-friends list | — |
| `poll_by_story` / `poll_votes_*` / `poll_voters_*` / `poll_counters` | Poll storage | 24h |
| `highlights_by_author` | Highlight covers | — (permanent) |
| `stories_in_highlight` | Snapshotted story content | — (permanent) |

### Sounds

| Table | Role |
|-------|------|
| `sounds_by_id` | Canonical sound row |
| `sounds_by_category` | Browse-by-category (DESC `created_at`, APPROVED only) |
| `posts_by_sound` | "All posts using sound X" (DESC `created_at`) |
| `sound_counters` | Per-sound `use_count` |

### Counters

`post_counters`, `comment_counters`, `sound_counters`, `hashtag_counters`,
`poll_counters`, `notification_unread_counter`.

### Notifications

`notifications_by_user`, `notification_lookup`, `notif_active_group_by_user`,
`notification_unread_counter`.

---

## 20. Activity feed integration

Every meaningful post-side action also writes a row into the per-user
activity history (`UserActivityService.record*`) so the user can scroll
back through everything they did. Full reference in
[USER_ACTIVITY_API.md](./USER_ACTIVITY_API.md).

| Post action | Activity type emitted | Notes |
|-------------|-----------------------|-------|
| `POST /posts` (create) | `POST_CREATED` | `postType` denormalised on `reaction_type` column. |
| `POST /posts/{id}/reactions` (toggle ON) | `POST_REACTION` | `reactionType = LIKE`. |
| `POST /posts/{id}/comments` | `POST_COMMENT` | `commentId` carried. |
| `POST /posts/comments/{id}/replies` | `POST_COMMENT` | Yes — replies count as comment activity (the schema doesn't differentiate; the row carries the reply id on `commentId`). |
| `POST /posts/{postId}/comments/{commentId}/reactions` (toggle ON) | `POST_COMMENT_REACTION` | Both `postId` and `commentId` carried. |
| `POST /posts/{id}/shares` | `POST_SHARE` | |
| `POST /posts/{id}/saves` (toggle ON) | `POST_SAVED` | Unsaves NOT recorded — toggle-ON only. |
| `POST /posts/{id}/reels/view` (reel watched) | `REEL_WATCH` | `watchedSeconds` carried. |
| `@mention` in post body (incoming) | `USER_MENTIONED` | One row per recipient. Followers fan-out (`@followers`) is NOT recorded — would flood. |

All `record*` calls are `@Async` and wrapped in `try/catch` — a
recording failure can never break the originating write.

---

## Cross-cutting project rules (worth remembering)

- **Single reaction type**: only `LIKE`. All entities (post, research, Q&A,
  comment) use one reaction — "academic not entertainment".
- **Replies flat at depth 1**: a reply-to-reply always lands as a sibling.
- **Self-repost allowed**: users can repost their own posts (Twitter/FB-style),
  but skip the self-notification.
- **JWT-derived authorship**: every create endpoint derives `authorId` /
  `sharerId` / etc. from the JWT principal — body-supplied IDs are ignored.
- **Async fanout & search index**: never block the create response.
- **R2 rollback on DB failure**: multipart-create deletes uploaded keys if
  the post-insert fails, so the bucket never grows orphans.
- **Counter columns are atomic** via `CounterService` (raw `UPDATE … SET col = col + N`).
  Spring Data `save()` does NOT work on counter tables.
- **All side effects (notifications, activity, realtime, ES index) wrapped
  in `try/catch`** — recording a follow-up never breaks the originating write.
- **JWT filter is SSE-aware** — stale cookies on `/stream` endpoints
  pass through (no 401) so the controller's `?token=` fallback can
  authenticate. Avoids the "CORS error / null status" antipattern in
  Firefox.

---

## See also

- [QNA_API.md](./QNA_API.md) — Q&A APIs
- [RESEARCH_API.md](./RESEARCH_API.md) — Research APIs
- [USER_API.md](./USER_API.md) — User identity, profile, social graph, notifications
- [USER_ACTIVITY_API.md](./USER_ACTIVITY_API.md) — Per-user activity feed
- [POST_ERRORS.md](./POST_ERRORS.md) — Complete error & exception reference
- [BACKEND_ENHANCEMENTS.md](./BACKEND_ENHANCEMENTS.md) — Roadmap of features still to ship
