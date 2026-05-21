# User Activity API — Full Documentation

The complete reference for everything under `ak.dev.irc.app.activity`
— the per-user engagement history feed that records every meaningful
action a user takes across the platform (post / story / research / Q&A /
search / mention / save / follow / reel-watch / etc.), exposes them as a
filterable timeline, and broadcasts each new row to the user's open tabs
in real time over SSE.

---

## Table of contents

1. [Domain model overview](#1-domain-model-overview)
2. [The 28 activity types](#2-the-28-activity-types)
3. [Where each activity is recorded (source-of-truth)](#3-where-each-activity-is-recorded)
4. [REST API — listing](#4-rest-api--listing)
5. [REST API — delete](#5-rest-api--delete)
6. [REST API — SSE stream](#6-rest-api--sse-stream)
7. [Reel-view sub-API](#7-reel-view-sub-api)
8. [`UserActivityResponse` — full response shape](#8-useractivityresponse--full-response-shape)
9. [Server-rendered labels (kills "unknown" toasts)](#9-server-rendered-labels)
10. [Cassandra storage design](#10-cassandra-storage-design)
11. [Realtime architecture](#11-realtime-architecture)
12. [Error responses](#12-error-responses)
13. [Frontend integration guide](#13-frontend-integration-guide)

All endpoints live under `/api/v1/users/me/activity/...` (the activity
feed) and `/api/v1/users/me/reels/watched/...` + `/api/v1/posts/{postId}/reels/view`
(the reel-watch sub-API). All endpoints require auth.

---

## 1. Domain model overview

The activity feed is **per-user**, **append-only**, **newest-first**,
and **filterable** by:

- type (single or multi-select)
- date range (`from` / `to`)
- standard `page` / `size`

Every domain service that wants to log a user action calls into
`UserActivityService.record*(...)`. That method writes to three
Cassandra tables in one shot and broadcasts an SSE event on the user's
channel.

```
Domain service (e.g. CassandraSaveService.toggleSave)
        │
        ▼
UserActivityService.recordPostSaved(userId, postId)   [@Async]
        │
        ▼
write(...)  ── inserts into:
        │
        ├── activity_by_user                 (everything, partitioned by user)
        ├── activity_by_user_and_type        (per-type filter, partitioned by user+type)
        └── activity_lookup                  (point-read by activity_id for delete)
        │
        ▼
UserActivityRealtimeBroadcaster.broadcast(userId, event)
        │
        ▼ (Redis pub/sub  →  every instance)
        │
        ▼
UserActivityRealtimeService.push(userId, event)  →  SSE emitter
```

Key design rules:

- **Async writes** — every `record*` method is `@Async` so the original
  request (save / react / comment / search / etc.) returns immediately.
- **Best-effort** — every wire-site wraps the call in `try/catch` so
  recording a row never breaks the originating write.
- **Soft de-dup at higher layers** — reactions, comments, etc. already
  de-dup at their own service. The activity feed records whatever the
  service writes.
- **`@JsonInclude(NON_NULL)`** on the realtime event payload — only the
  fields populated for a given activity type are sent over the wire.
- **Cassandra-only storage** — three denormalised tables for the three
  query patterns (all-mine, by-type, by-id).

---

## 2. The 28 activity types

Defined in `ak.dev.irc.app.activity.enums.UserActivityType`. Sorted by
domain:

### Post creation + engagement

| Enum value | Meaning |
|------------|---------|
| `POST_CREATED` | User published a post (any `PostType` incl. `REEL`). |
| `POST_REACTION` | User liked a post. |
| `POST_COMMENT` | User commented on a post. |
| `POST_COMMENT_REACTION` | User liked a comment. |
| `POST_SHARE` | User shared a post. |
| `POST_SAVED` | User bookmarked a post (toggle ON only — unsaves not logged). |
| `REEL_WATCH` | User watched a reel. |

### Discovery / social graph

| Enum value | Meaning |
|------------|---------|
| `GLOBAL_SEARCH` | User did a global search query. |
| `HASHTAG_SEARCH` | User searched a `#hashtag`. |
| `MENTION_LOOKUP` | **Outgoing:** user typed an `@` and picked a user from the picker. |
| `USER_MENTIONED` | **Incoming:** another user `@`-mentioned this user. |
| `PROFILE_VIEW` | User opened another user's profile. |
| `FOLLOWED_USER` | User started following another user. |

### Q&A

| Enum value | Meaning |
|------------|---------|
| `QNA_QUESTION_CREATED` | User asked a question. |
| `QNA_QUESTION_SAVED` | User bookmarked a question. |
| `QNA_ANSWER_CREATED` | User answered a question (top-level). |
| `QNA_REANSWER_CREATED` | User replied to an answer (reanswer). |
| `QNA_ANSWER_REACTION` | User liked an answer. |
| `QNA_BEST_ANSWER_VOTE` | Scholar voted an answer as best. |
| `QNA_ANSWER_FEEDBACK` | User left feedback on an answer. |

### Research

| Enum value | Meaning |
|------------|---------|
| `RESEARCH_PUBLISHED` | User published a research paper. |
| `RESEARCH_SAVED` | User bookmarked a research paper. |
| `RESEARCH_REACTION` | User liked a research paper. |
| `RESEARCH_COMMENT` | User commented on a research paper. |
| `RESEARCH_COMMENT_REACTION` | User liked a research comment. |

### Stories

| Enum value | Meaning |
|------------|---------|
| `STORY_VIEWED` | User watched a story. |
| `STORY_REACTED` | User reacted to a story. |
| `STORY_REPLIED` | User replied to a story (DM-style). |
| `STORY_POLL_VOTED` | User voted in a story poll. |

### Sounds

| Enum value | Meaning |
|------------|---------|
| `SOUND_USED` | User used a sound on a post (TikTok-style). |

---

## 3. Where each activity is recorded

Source-of-truth wire-sites — i.e. which service / method emits the
activity row.

| Activity type | Source service · method |
|--------------|---------------------------|
| `POST_CREATED` | `CassandraPostService.createPost` (step 8, async via `recordPostCreated`) |
| `POST_REACTION` | `CassandraReactionService.togglePostReaction` |
| `POST_COMMENT` | `CassandraCommentService.createComment` |
| `POST_COMMENT_REACTION` | `CassandraReactionService.toggleCommentReaction` |
| `POST_SHARE` | `CassandraShareService.recordShare` |
| `POST_SAVED` | `CassandraSaveService.toggleSave` (toggle ON only) |
| `REEL_WATCH` | `ReelViewServiceImpl.recordWatch` |
| `GLOBAL_SEARCH` | Search service (records query + searchScope + hitCount) |
| `HASHTAG_SEARCH` | Hashtag search path |
| `MENTION_LOOKUP` | Mention picker UI server endpoint |
| `USER_MENTIONED` | `MentionService.scanAndPublish` — one row per direct recipient. Followers fan-out is NOT recorded (would flood). |
| `PROFILE_VIEW` | Profile-view tracker on `GET /users/{id}/profile` |
| `FOLLOWED_USER` | `UserSocialServiceImpl.follow` (unfollow not logged) |
| `QNA_QUESTION_CREATED` | `QuestionServiceImpl.createQuestion` |
| `QNA_QUESTION_SAVED` | `QuestionServiceImpl.saveQuestion` (toggle ON only) |
| `QNA_ANSWER_CREATED` / `QNA_REANSWER_CREATED` | `QuestionServiceImpl.addAnswer` (one or the other depending on `parentAnswerId`) |
| `QNA_ANSWER_REACTION` | `QuestionServiceImpl.reactToAnswer` |
| `QNA_BEST_ANSWER_VOTE` | `QuestionServiceImpl.markBestAnswer` / `unmarkBestAnswer` |
| `QNA_ANSWER_FEEDBACK` | `QuestionServiceImpl.addFeedback` |
| `RESEARCH_PUBLISHED` | `ResearchServiceImpl.publish` |
| `RESEARCH_SAVED` | `ResearchServiceImpl.saveResearch` (toggle ON only) |
| `RESEARCH_REACTION` | `ResearchServiceImpl.react` |
| `RESEARCH_COMMENT` | `ResearchServiceImpl.addComment` |
| `RESEARCH_COMMENT_REACTION` | `ResearchServiceImpl.reactToComment` |
| `STORY_VIEWED` / `STORY_REACTED` / `STORY_REPLIED` | Story service (when wired) |
| `STORY_POLL_VOTED` | `CassandraStoryPollService.castVote` |
| `SOUND_USED` | `CassandraPostService.createPost` when the create command carries a `soundId` |

---

## 4. REST API — listing

### `GET /api/v1/users/me/activity`

Paginated user-activity history for the authenticated user. Newest first.

#### Query parameters (all optional)

| Param | Type | Description |
|-------|------|-------------|
| `type` | `UserActivityType` | Single type filter. **Back-compat** — prefer `types` for new clients. |
| `types` | repeatable / comma-separated `UserActivityType` | Multiple types — unioned (OR semantics). When supplied, overrides `type`. |
| `from` | ISO-8601 instant (e.g. `2026-05-01T00:00:00Z`) | Inclusive lower bound on `createdAt`. |
| `to` | ISO-8601 instant | Inclusive upper bound on `createdAt`. |
| `page` | int (default 0) | Spring pagination — page number. |
| `size` | int (default 20) | Spring pagination — page size. |

#### Auth

`@PreAuthorize("isAuthenticated()")` — anonymous → `401`.

#### Response

`Page<UserActivityResponse>` — see [§8](#8-useractivityresponse--full-response-shape).

#### Examples

```bash
# 1. Everything, newest first
GET /api/v1/users/me/activity

# 2. Just my post comments
GET /api/v1/users/me/activity?type=POST_COMMENT

# 3. Multi-type union
GET /api/v1/users/me/activity?types=POST_REACTION,POST_COMMENT,QNA_ANSWER_REACTION

# 4. Since May 1
GET /api/v1/users/me/activity?from=2026-05-01T00:00:00Z

# 5. May only (inclusive both ends)
GET /api/v1/users/me/activity?from=2026-05-01T00:00:00Z&to=2026-05-31T23:59:59Z

# 6. Reels + incoming mentions in May, page-2
GET /api/v1/users/me/activity?types=REEL_WATCH,USER_MENTIONED&from=2026-05-01T00:00:00Z&to=2026-05-31T23:59:59Z&page=1&size=50

# 7. This week, 50 rows at a time
GET /api/v1/users/me/activity?from=2026-05-15T00:00:00Z&size=50
```

#### Filter resolution

The server resolves filters in this order:

1. If `types` is present and non-empty → use it (OR-union across types).
2. Else if `type` is present → use it.
3. Else → all types.

#### Query path internals

The service picks the most efficient Cassandra partition scan based on
the filter shape:

| `types` filter | `from`/`to` filter | Table scanned | Query |
|----------------|--------------------|----------------|-------|
| none | both | `activity_by_user` | `WHERE user_id = ? AND created_at >= ? AND created_at <= ? LIMIT ?` |
| none | `from` only | `activity_by_user` | `WHERE user_id = ? AND created_at >= ? LIMIT ?` |
| none | `to` only | `activity_by_user` | `WHERE user_id = ? AND created_at <= ? LIMIT ?` |
| none | none | `activity_by_user` | `WHERE user_id = ? LIMIT ?` |
| single type | (any) | `activity_by_user_and_type` | `WHERE user_id = ? AND activity_type = ?` + same range filter |
| multi-type | (any) | N parallel scans on `activity_by_user_and_type` | One scan per type, k-way merge by `createdAt DESC`, trim to `size` |

All scans are **single-partition** (no `ALLOW FILTERING`, no secondary
indexes). The multi-type union is the only path that does N round-trips;
it's bounded by the number of distinct types in the filter and is
typically ≤ 3 in practice.

---

## 5. REST API — delete

### `DELETE /api/v1/users/me/activity/{activityId}`

Delete a single activity row from the viewer's history. Auth required.
Returns `204 No Content`.

- `404 USERACTIVITY_NOT_FOUND` if the activity id doesn't exist.
- `403 ACCESS_FORBIDDEN` if the activity belongs to a different user
  (`"You cannot delete another user's activity"`).

The service walks all three tables: `activity_by_user`,
`activity_by_user_and_type`, `activity_lookup` — the row is fully gone
after this call.

### `DELETE /api/v1/users/me/activity?type=POST_REACTION`

Bulk-delete every activity row owned by the viewer (or every row of a
specific type when `?type=...` is supplied).

Response: `200 { "deleted": <count> }`.

Internally: walks pages of 200 rows × up to 50 pages (cap of 10 000 rows
per call) — safer than an unbounded scan. Call again for the next chunk
if `deleted == 10000`.

---

## 6. REST API — SSE stream

### `GET /api/v1/users/me/activity/stream`

Server-Sent Events stream that pushes every new activity row to the
client the moment its Cassandra row is committed. Cross-instance fan-out
runs over Redis pub/sub.

#### Auth (two options)

1. **JWT principal** — standard `Authorization: Bearer <jwt>` header
   OR the `access_token` HttpOnly cookie. Used when the controller
   has `@AuthenticationPrincipal` populated by the JWT filter.
2. **`?token=<jwt>`** — fallback for browser `EventSource` which
   cannot send custom headers. The server validates the access token
   from the query param.

If neither works → server writes `401` with body
`"Authentication required. Pass access token as ?token=<jwt>."`.

The JWT filter is now **SSE-aware** — when an expired/invalid cookie is
sent on a `/stream` endpoint, the filter passes through instead of
writing a 401, so the `?token=` fallback can succeed.

#### Response headers

```
Content-Type: text/event-stream
X-Accel-Buffering: no
Cache-Control: no-cache, no-store, must-revalidate
Pragma: no-cache
Connection: keep-alive
```

Set explicitly so Railway / Nginx / Cloudflare don't buffer.

#### Event shape (`UserActivityRealtimeEvent`)

```json
{
  "activityId":   "<uuid>",
  "userId":       "<recipient-uuid>",
  "activityType": "POST_SAVED",
  "postId":       "<post-uuid>",
  "commentId":    null,
  "targetUserId": null,
  "query":        null,
  "searchScope":  null,
  "hitCount":     null,
  "reactionType": null,
  "watchedSeconds": null,
  "timestamp":    "2026-05-21T14:30:00",
  "activity":     <full UserActivityResponse>
}
```

`@JsonInclude(NON_NULL)` strips null fields. The `activity` field is the
full hydrated response — same shape as the listing endpoint — so the
client can render the new row without a follow-up fetch.

---

## 7. Reel-view sub-API

Reel watch history is its own table (`reel_views_by_user`) so it can
store extra columns (`watched_seconds`) without bloating the generic
activity table. It also writes a `REEL_WATCH` row into the generic
activity feed for the unified timeline.

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/api/v1/posts/{postId}/reels/view` | Record a watch event. Body (optional): `{ "watchedSeconds": 12 }`. Returns `201 ReelViewResponse`. |
| `GET`    | `/api/v1/users/me/reels/watched?page=&size=` | Page of watched reels (newest first). |
| `DELETE` | `/api/v1/users/me/reels/watched/{reelViewId}` | Remove one watch row. Returns `204`. |
| `DELETE` | `/api/v1/users/me/reels/watched` | Clear all watch history. Returns `200 { "deleted": N }`. |

### `RecordReelViewRequest`

```json
{ "watchedSeconds": 12 }   // @Min(0); optional
```

### `ReelViewResponse`

```java
record ReelViewResponse(
    UUID id,
    Integer watchedSeconds,
    ReelSummary reel,                 // { id, textPreview, thumbnailUrl, mediaUrl, durationSeconds, author }
    LocalDateTime watchedAt,
    String timeAgo,
    String formattedDate
) {}
```

`ReelSummary.author` is `{ id, username, fullName, avatarUrl }`.

---

## 8. `UserActivityResponse` — full response shape

The single record returned by every activity-list endpoint. Heavily
optional — only the fields relevant to the row's `activityType` are
populated.

```java
record UserActivityResponse(
    // ── Identity ────────────────────────────────────────────────────
    UUID id,                          // the activity row's UUID
    UserActivityType activityType,    // see §2
    String label,                     // server-rendered label, see §9
    String subtitle,                  // optional longer copy

    // ── Post-side context ──────────────────────────────────────────
    PostReactionType reactionType,    // POST_REACTION / POST_COMMENT_REACTION
                                      //   also: POST_CREATED reuses this column
                                      //   for the postType string (TEXT/REEL/etc.)
    Integer watchedSeconds,           // REEL_WATCH
    PostSummary post,                 // POST_*, REEL_WATCH, USER_MENTIONED on a post
    CommentSummary comment,           // POST_COMMENT, POST_COMMENT_REACTION

    // ── Discovery context ───────────────────────────────────────────
    String query,                     // GLOBAL_SEARCH / HASHTAG_SEARCH / MENTION_LOOKUP
                                      //   also: USER_MENTIONED carries the source-type
                                      //   label here ("POST", "POST_COMMENT", ...)
    String searchScope,               // GLOBAL_SEARCH — comma-separated SearchTypes
    Integer hitCount,                 // SEARCH / MENTION_LOOKUP — # of results
    AuthorSummary targetUser,         // MENTION_LOOKUP (clicked user) /
                                      //   PROFILE_VIEW (viewed user) /
                                      //   FOLLOWED_USER (followed user) /
                                      //   USER_MENTIONED (the mentioner)

    // ── Q&A context ─────────────────────────────────────────────────
    QuestionSummary question,         // QNA_*
    AnswerSummary answer,             // QNA_ANSWER_* / QNA_REANSWER_CREATED
    String qnaReactionType,           // QNA_ANSWER_REACTION

    // ── Research context ───────────────────────────────────────────
    ResearchSummary research,         // RESEARCH_* (incl. RESEARCH_SAVED)
    CommentSummary researchComment,   // RESEARCH_COMMENT / RESEARCH_COMMENT_REACTION

    // ── Display ────────────────────────────────────────────────────
    LocalDateTime createdAt,
    String timeAgo,                   // "5 minutes ago"
    String formattedDate              // "May 21, 2026 — 14:30"
) {}
```

### Nested summaries

```java
record PostSummary(
    UUID id, PostType postType, String textPreview,
    String thumbnailUrl, AuthorSummary author
) {}

record CommentSummary(
    UUID id, String textPreview
) {}

record AuthorSummary(
    UUID id, String username, String fullName, String avatarUrl
) {}

record QuestionSummary(
    UUID id, String title, AuthorSummary author
) {}

record AnswerSummary(
    UUID id, UUID parentAnswerId, String bodyPreview,
    boolean accepted, long bestAnswerVoteCount,
    AuthorSummary author
) {}

record ResearchSummary(
    UUID id, String ircId, String title,
    String coverImageUrl, AuthorSummary author
) {}
```

> The summaries are **id-only on most rows** today (cheaper than joining
> Postgres on every row). The frontend hydrates per-id via the
> appropriate domain endpoint (`/posts/{id}`, `/questions/{id}`,
> `/researches/{id}`) using the same lazy/cached pattern it uses for
> feeds.

### Sample responses per type

```json
// POST_SAVED
{ "id": "...", "activityType": "POST_SAVED",
  "label": "Saved a post", "subtitle": "Bookmarked for later",
  "post": { "id": "..." },
  "createdAt": "2026-05-21T14:30:00",
  "timeAgo": "5 minutes ago", "formattedDate": "May 21, 2026 — 14:30" }

// USER_MENTIONED — incoming
{ "id": "...", "activityType": "USER_MENTIONED",
  "label": "Mentioned you", "subtitle": "You were tagged",
  "targetUser": { "id": "<mentioner-uuid>" },
  "query": "POST",                          // source-type label
  "post": { "id": "<source-post-uuid>" },
  "createdAt": "...", ... }

// REEL_WATCH
{ "id": "...", "activityType": "REEL_WATCH",
  "label": "Watched a reel", "subtitle": "Reel view recorded",
  "post": { "id": "..." }, "watchedSeconds": 12,
  "createdAt": "...", ... }

// QNA_ANSWER_REACTION
{ "id": "...", "activityType": "QNA_ANSWER_REACTION",
  "label": "Liked an answer",
  "question": { "id": "..." }, "answer": { "id": "..." },
  "qnaReactionType": "LIKE",
  "createdAt": "...", ... }

// RESEARCH_PUBLISHED
{ "id": "...", "activityType": "RESEARCH_PUBLISHED",
  "label": "Published a research paper",
  "research": { "id": "..." },
  "createdAt": "...", ... }

// GLOBAL_SEARCH
{ "id": "...", "activityType": "GLOBAL_SEARCH",
  "label": "Searched",
  "query": "zakat fiqh", "searchScope": "POSTS,QUESTIONS",
  "hitCount": 42,
  "createdAt": "...", ... }
```

---

## 9. Server-rendered labels

Every response includes a server-rendered `label` and `subtitle` per
activity type so the frontend never has to maintain a hardcoded
enum→string map. Killing this gap was the fix for the "unknown" toast
on save activities.

| Activity type | `label` | `subtitle` |
|---------------|---------|------------|
| `POST_CREATED` | `"Published a post"` | — |
| `POST_REACTION` | `"Liked a post"` | — |
| `POST_COMMENT` | `"Commented on a post"` | — |
| `POST_COMMENT_REACTION` | `"Liked a comment"` | — |
| `POST_SHARE` | `"Shared a post"` | — |
| `POST_SAVED` | `"Saved a post"` | `"Bookmarked for later"` |
| `REEL_WATCH` | `"Watched a reel"` | `"Reel view recorded"` |
| `GLOBAL_SEARCH` | `"Searched"` | — |
| `HASHTAG_SEARCH` | `"Searched a hashtag"` | — |
| `MENTION_LOOKUP` | `"Looked up a user"` | — |
| `USER_MENTIONED` | `"Mentioned you"` | `"You were tagged"` |
| `PROFILE_VIEW` | `"Visited a profile"` | `"Profile visit"` |
| `FOLLOWED_USER` | `"Followed a user"` | `"New follow"` |
| `QNA_QUESTION_CREATED` | `"Asked a question"` | — |
| `QNA_QUESTION_SAVED` | `"Saved a question"` | `"Bookmarked for later"` |
| `QNA_ANSWER_CREATED` | `"Answered a question"` | — |
| `QNA_REANSWER_CREATED` | `"Replied to an answer"` | — |
| `QNA_ANSWER_REACTION` | `"Liked an answer"` | — |
| `QNA_BEST_ANSWER_VOTE` | `"Marked a best answer"` | — |
| `QNA_ANSWER_FEEDBACK` | `"Gave answer feedback"` | — |
| `RESEARCH_PUBLISHED` | `"Published a research paper"` | — |
| `RESEARCH_SAVED` | `"Saved a research paper"` | `"Bookmarked for later"` |
| `RESEARCH_REACTION` | `"Liked a research paper"` | — |
| `RESEARCH_COMMENT` | `"Commented on a research paper"` | — |
| `RESEARCH_COMMENT_REACTION` | `"Liked a research comment"` | — |
| `STORY_VIEWED` | `"Watched a story"` | — |
| `STORY_REACTED` | `"Reacted to a story"` | — |
| `STORY_REPLIED` | `"Replied to a story"` | — |
| `STORY_POLL_VOTED` | `"Voted in a story poll"` | — |
| `SOUND_USED` | `"Used a sound"` | — |

Unknown enum strings (i.e. future types older deployments haven't been
taught) fall back to `label = "Activity"` and `subtitle = null`. The
mapper also writes a `WARN` log so the gap is visible in the server log
before it ships to the frontend.

---

## 10. Cassandra storage design

Three tables — one per query pattern. Every `record*` call writes to all
three in the same transaction-equivalent (no LWT — Cassandra row inserts
are idempotent at the (pk) level).

### `activity_by_user`

The "all my history" read path.

```cql
CREATE TABLE activity_by_user (
    user_id          UUID,
    created_at       TIMESTAMP,
    activity_id      UUID,
    activity_type    TEXT,
    post_id          UUID,
    comment_id       UUID,
    reaction_type    TEXT,
    watched_seconds  INT,
    query            TEXT,
    search_scope     TEXT,
    hit_count        INT,
    target_user_id   UUID,
    question_id      UUID,
    answer_id        UUID,
    qna_reaction_type TEXT,
    research_id      UUID,
    research_comment_id UUID,
    PRIMARY KEY (user_id, created_at, activity_id)
) WITH CLUSTERING ORDER BY (created_at DESC, activity_id ASC);
```

Reading "my history" = one partition scan. `created_at` range filters
work natively (it's the cluster key).

### `activity_by_user_and_type`

Same row shape, partitioned by `(user_id, activity_type)` so the
single-type listing is also one partition scan.

```cql
CREATE TABLE activity_by_user_and_type (
    user_id          UUID,
    activity_type    TEXT,
    created_at       TIMESTAMP,
    activity_id      UUID,
    ... (same columns as activity_by_user)
    PRIMARY KEY ((user_id, activity_type), created_at, activity_id)
) WITH CLUSTERING ORDER BY (created_at DESC, activity_id ASC);
```

### `activity_lookup`

Point lookup by activity id alone — used by the delete path so the user
doesn't have to know which partition / created_at to target.

```cql
CREATE TABLE activity_lookup (
    activity_id      UUID PRIMARY KEY,
    user_id          UUID,
    activity_type    TEXT,
    created_at       TIMESTAMP
);
```

### `reel_views_by_user`

Separate table for the reel-watch sub-API. Carries `watched_seconds`.

```cql
CREATE TABLE reel_views_by_user (
    user_id          UUID,
    created_at       TIMESTAMP,
    reel_view_id     UUID,
    post_id          UUID,
    watched_seconds  INT,
    PRIMARY KEY (user_id, created_at, reel_view_id)
) WITH CLUSTERING ORDER BY (created_at DESC, reel_view_id ASC);
```

### Column reuse — denormalisation tricks

A few activity types reuse columns that "officially" belong to another
domain, to avoid schema growth:

- **`POST_CREATED`** stores the `postType` string (`"TEXT"`, `"REEL"`,
  etc.) in the `reaction_type` column. The mapper reads it back via the
  same column.
- **`USER_MENTIONED`** stores the source-type label (`"POST"`,
  `"POST_COMMENT"`, `"QUESTION"`, `"RESEARCH"`, ...) in the `query`
  column. The source object's id is stored on whichever id column
  matches: `post_id` for posts/comments, `question_id` for QnA,
  `research_id` for research. The `target_user_id` column stores the
  mentioner's user id (the recipient is `user_id`).

This keeps `activity_by_user` to 16 columns and avoids per-type tables.

---

## 11. Realtime architecture

```
write(...)                                    @ originating instance
   │
   ▼
broadcast(userId, event)                      @ UserActivityRealtimeBroadcaster
   │
   ▼ (publish to Redis  irc:activity:{userId})
   ▼
UserActivityRealtimeSubscriber                @ every instance listens
   │
   ▼
UserActivityRealtimeService.push(userId, ev)  @ local instance only
   │
   ▼
All open SseEmitter(s) for that userId        @ one per tab/device
```

Why Redis pub/sub: a single user can have an SSE connection open on
instance A while the activity was written on instance B — Redis
broadcasts to both. The local `UserActivityRealtimeService` keeps an
in-memory `Map<UUID, List<SseEmitter>>`, so push is O(1) per emitter
once the message arrives.

### SSE event names

Single named event: `activity`. The payload is `UserActivityRealtimeEvent`.

```
event: activity
data: { "activityId":"...","activityType":"POST_SAVED",... }
```

### Heartbeat

Standard 25s `heartbeat` ping (Spring `SseEmitter`'s default behaviour
for managed services). Keeps proxies / Cloudflare from killing the
connection.

### Reconnect

`EventSource` handles reconnection client-side. The server doesn't
maintain per-user state across reconnects — the client missed-while-down
must be backfilled via the REST listing endpoint (with a `from=<lastSeen>`
filter).

---

## 12. Error responses

All errors follow the standard `ApiErrorResponse` shape — see
[POST_ERRORS.md §2](./POST_ERRORS.md#2-the-unified-error-response-shape-apierrorresponse)
for the full field reference. Activity-specific codes:

| Status | `errorCode` | When |
|--------|-------------|------|
| `400`  | `MISSING_PARAMETER`         | `?activityId` / `?type` malformed type |
| `400`  | `TYPE_MISMATCH`             | `from`/`to` not a valid ISO instant; `type`/`types` not a valid enum value |
| `401`  | `AUTH_REQUIRED`             | No JWT supplied AND `?token=` absent (SSE only) |
| `401`  | (bare body)                 | `if (user == null) return 401` in the controller |
| `403`  | `ACCESS_FORBIDDEN`          | Deleting another user's activity row |
| `404`  | `USERACTIVITY_NOT_FOUND`    | Delete by id where the activity doesn't exist |
| `500`  | `INTERNAL_ERROR`            | Cassandra read/write failure |

### Specific gotchas

- **SSE with expired cookie:** previously the JWT filter would write a
  `401 AUTH_TOKEN_INVALID`, which Firefox surfaced as a misleading
  "CORS error / status null" because SSE can't render a 401 body. The
  filter is now SSE-aware — it passes through stale cookies so the
  `?token=` fallback can succeed. Frontend should still log the user
  back in to refresh the cookie.
- **Date-range upside-down:** if `from > to`, the partition scan
  returns `[]` (Cassandra clustering-key range is inclusive but bounded
  — no rows match). Not an error; the page is just empty.

---

## 13. Frontend integration guide

### TypeScript types

```ts
export type UserActivityType =
  | 'POST_CREATED' | 'POST_REACTION' | 'POST_COMMENT' | 'POST_COMMENT_REACTION'
  | 'POST_SHARE' | 'POST_SAVED' | 'REEL_WATCH'
  | 'GLOBAL_SEARCH' | 'HASHTAG_SEARCH' | 'MENTION_LOOKUP' | 'USER_MENTIONED'
  | 'PROFILE_VIEW' | 'FOLLOWED_USER'
  | 'QNA_QUESTION_CREATED' | 'QNA_QUESTION_SAVED'
  | 'QNA_ANSWER_CREATED'   | 'QNA_REANSWER_CREATED' | 'QNA_ANSWER_REACTION'
  | 'QNA_BEST_ANSWER_VOTE' | 'QNA_ANSWER_FEEDBACK'
  | 'RESEARCH_PUBLISHED' | 'RESEARCH_SAVED' | 'RESEARCH_REACTION'
  | 'RESEARCH_COMMENT'   | 'RESEARCH_COMMENT_REACTION'
  | 'STORY_VIEWED' | 'STORY_REACTED' | 'STORY_REPLIED' | 'STORY_POLL_VOTED'
  | 'SOUND_USED';

export interface UserActivityResponse {
  id:           string;
  activityType: UserActivityType;
  label:        string;           // always set
  subtitle?:    string;

  reactionType?:   'LIKE' | string;
  watchedSeconds?: number;

  post?:    { id: string; postType?: string; textPreview?: string;
              thumbnailUrl?: string; author?: AuthorSummary };
  comment?: { id: string; textPreview?: string };

  query?:        string;
  searchScope?:  string;
  hitCount?:     number;
  targetUser?:   AuthorSummary;

  question?: { id: string; title?: string; author?: AuthorSummary };
  answer?:   { id: string; parentAnswerId?: string; bodyPreview?: string;
               accepted?: boolean; bestAnswerVoteCount?: number;
               author?: AuthorSummary };
  qnaReactionType?: string;

  research?:        { id: string; ircId?: string; title?: string;
                      coverImageUrl?: string; author?: AuthorSummary };
  researchComment?: { id: string; textPreview?: string };

  createdAt:      string;
  timeAgo:        string;
  formattedDate:  string;
}

export interface AuthorSummary {
  id:        string;
  username?: string;
  fullName?: string;
  avatarUrl?:string;
}
```

### Listing with filters

```ts
async function fetchActivity(params: {
  types?: UserActivityType[];
  from?: Date; to?: Date;
  page?: number; size?: number;
}) {
  const qs = new URLSearchParams();
  if (params.types?.length) qs.set('types', params.types.join(','));
  if (params.from)          qs.set('from', params.from.toISOString());
  if (params.to)            qs.set('to',   params.to.toISOString());
  if (params.page != null)  qs.set('page', String(params.page));
  if (params.size != null)  qs.set('size', String(params.size));
  return http.get<Page<UserActivityResponse>>(
    `/api/v1/users/me/activity?${qs}`);
}
```

### Rendering a row

The label is always set, so a minimal row component is:

```tsx
function ActivityRow({ a }: { a: UserActivityResponse }) {
  return (
    <div>
      <div>{a.label}</div>
      {a.subtitle && <div className="muted">{a.subtitle}</div>}
      <div className="time">{a.timeAgo}</div>
      <ActivityLink activity={a} />
    </div>
  );
}

function ActivityLink({ activity: a }: { activity: UserActivityResponse }) {
  if (a.post)     return <Link to={`/posts/${a.post.id}`}>open</Link>;
  if (a.question) return <Link to={`/questions/${a.question.id}`}>open</Link>;
  if (a.research) return <Link to={`/researches/${a.research.id}`}>open</Link>;
  if (a.targetUser) return <Link to={`/users/${a.targetUser.id}`}>open</Link>;
  return null;
}
```

The frontend does NOT need a hardcoded `activityType → label` map — use
`a.label` directly.

### SSE subscription

```ts
const url = `/api/v1/users/me/activity/stream?token=${encodeURIComponent(accessToken)}`;
const es  = new EventSource(url, { withCredentials: true });

es.addEventListener('activity', (ev) => {
  const evt = JSON.parse(ev.data) as UserActivityRealtimeEvent;
  prependToActivityFeed(evt.activity);   // already a full UserActivityResponse
});

es.onerror = () => {
  // Server restart / token expired / network — close and reopen with a fresh token.
  es.close();
};
```

### Date-range picker pattern

```ts
// Last 7 days
fetchActivity({ from: dayjs().subtract(7, 'day').toDate() });

// May 2026 only
fetchActivity({
  from: dayjs('2026-05-01').startOf('day').toDate(),
  to:   dayjs('2026-05-31').endOf('day').toDate(),
});

// Specific types in a date range
fetchActivity({
  types: ['POST_SAVED', 'RESEARCH_SAVED', 'QNA_QUESTION_SAVED'],
  from:  dayjs().subtract(30, 'day').toDate(),
});
```

### Cross-tab sync

The SSE stream pushes to every open tab the user has. After receiving a
new event on any tab, prepend it to the local feed. Other tabs that
issued the originating action (e.g. saved a post) will still get the
SSE echo — de-dup client-side by `activityId`.

---

## Summary — what's where

| File | What it does |
|------|--------------|
| `enums/UserActivityType.java` | The 28-value enum |
| `cassandra/entity/UserActivityEntity.java` | `activity_by_user` row |
| `cassandra/entity/UserActivityByTypeEntity.java` | `activity_by_user_and_type` row |
| `cassandra/entity/ActivityLookupEntity.java` | `activity_lookup` row |
| `cassandra/entity/ReelViewEntity.java` | `reel_views_by_user` row |
| `cassandra/repository/UserActivityCassandraRepository.java` | All-types partition scans + date-range queries |
| `cassandra/repository/UserActivityByTypeRepository.java` | Single-type partition scans + date-range queries |
| `cassandra/repository/ActivityLookupRepository.java` | Point-lookup by activity_id |
| `cassandra/repository/ReelViewCassandraRepository.java` | Reel-watch reads/writes |
| `service/UserActivityService.java` | Public contract — listMyActivity overloads, every `record*` method |
| `service/impl/UserActivityServiceImpl.java` | Cassandra + realtime broadcast |
| `service/ReelViewService.java` + impl | Reel-watch sub-service |
| `mapper/UserActivityMapper.java` | Entity → response + label rendering |
| `dto/UserActivityResponse.java` | The response record |
| `dto/ReelViewResponse.java` | Reel-watch response |
| `dto/RecordReelViewRequest.java` | `POST /reels/view` body |
| `realtime/UserActivityRealtimeService.java` | Local SSE emitter manager |
| `realtime/UserActivityRealtimeBroadcaster.java` | Publishes to Redis |
| `realtime/UserActivityRealtimePublisher.java` | Redis publisher wrapper |
| `realtime/UserActivityRealtimeSubscriber.java` | Redis subscriber → push |
| `realtime/UserActivityRealtimeEvent.java` | SSE wire payload |
| `controller/UserActivityController.java` | REST + SSE endpoints |
| `controller/ReelViewController.java` | Reel-watch endpoints |
