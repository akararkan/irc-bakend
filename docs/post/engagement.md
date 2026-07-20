# Post Engagement API — Reactions, Views, Comments, Saves, Shares

Every interaction surface on a post: likes (single `LIKE` type), unique-view
tracking, comments + flat replies, bookmarks with collections, and the share ledger.
All counters live in `post_counters` / `comment_counters` and are bumped with atomic
CQL counter updates.

- **Base path:** `/api/v1/posts`
- **Auth:** `Authorization: Bearer <JWT>`. Reads are anonymous-safe; every mutation
  requires authentication (comment edit/delete are author-only).
- **Errors:** unified envelope — see [Error handling](../errors/error-handling.md).
- **Page-size clamp:** every `pageSize` is clamped into **1..100**; values above 100
  are silently reduced to 100.

**Cross-cutting rules**

- **Single reaction type.** The only reaction is `LIKE` — presence of the row *is*
  the like. No emoji variants ("academic, not entertainment").
- **LWT-guarded toggles.** Like/save toggles use Cassandra lightweight transactions
  (`INSERT … IF NOT EXISTS` on the way on, `DELETE … IF EXISTS` on the way off).
  Only the request whose LWT actually applied owns the counter delta, the SSE
  broadcast and the notification — two concurrent toggles from the same user can
  never double-bump a counter. The losing request is a silent no-op that still
  returns the correct post-toggle state.
- **Delta-model realtime.** SSE events carry the event type, not fresh counter
  values — clients apply +1/−1 locally (see [realtime.md](./realtime.md)).
- **Rate limits:** comment/reply creation uses the `comment` bucket (10 per 30 s);
  save/share writes use the `social` bucket (30 per 60 s). Exceeding either returns
  `429 RATE_LIMITED` with `details.retryAfterSeconds`.

Related: [Posts CRUD](./posts.md) · [Feed](./feed.md) · [Reels](./reels.md) ·
[Media](./media.md) · [Realtime SSE](./realtime.md)

---

## 1. Reactions

### 1.1 `POST /api/v1/posts/{postId}/reactions` — toggle like

```
POST /api/v1/posts/{postId}/reactions
```

**Auth:** required.

Toggle: not-liked → liked, liked → not-liked. Always returns the **post-toggle**
state. The heart-icon pattern: optimistically flip, call this, reconcile with the
response.

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | Path — the post to (un)like |

**Request body:** none.

**Response `200`** (now liked; a second call returns `"liked": false`):

```json
{
  "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "liked":  true
}
```

**Side effects (like)**

- `reactions_by_post` row inserted via LWT; `reactions_by_user` mirror row written.
- `post_counters.reaction_count`++ (only if the LWT applied).
- Broadcasts **`REACTION_ADDED`** on the post's SSE channel.
- `POST_REACTED` notification to the post author (enrichment runs async on the
  notification executor; no self-notification when you like your own post's author id).

**Side effects (unlike):** LWT delete, mirror row removed, counter −1, broadcasts
**`REACTION_REMOVED`**. No notification.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 400 | `TYPE_MISMATCH` | `postId` malformed |

---

### 1.2 `DELETE /api/v1/posts/{postId}/reactions` — explicit unlike

```
DELETE /api/v1/posts/{postId}/reactions
```

**Auth:** required. **Idempotent** — a no-op if the post isn't currently liked.

Explicit unlike-only semantics for undo flows where the toggle's statefulness would
be wrong. Internally flips the toggle (LWT-guarded) only when a like exists.

**Response `200`:**

```json
{ "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b", "liked": false }
```

**Side effects:** same as the unlike branch of §1.1 when a like existed; none
otherwise.

**Errors:** `401 AUTH_UNAUTHORIZED` without a JWT.

---

### 1.3 `GET /api/v1/posts/{postId}/reactions/me` — "did I like this?"

```
GET /api/v1/posts/{postId}/reactions/me
```

**Auth:** optional (anonymous-safe). Cheap point-read on `reactions_by_post`.

**Response `200`** (authenticated):

```json
{ "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b", "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690", "liked": true }
```

Anonymous: `{ "postId": "…", "liked": false }`.

---

### 1.4 `GET /api/v1/posts/users/{userId}/reactions` — reaction history

```
GET /api/v1/posts/users/{userId}/reactions?pageSize=20
```

**Auth:** none (public).

Recent posts a user has liked, newest first. Light `(userId, createdAt, postId)`
tuples — hydrate individual posts via `GET /posts/{id}` as they scroll into view.
Rows may reference posts that were later hard-deleted; treat a `404` on hydration as
"skip this row".

| Param | Type | Description |
|-------|------|-------------|
| `userId` | UUID | Path — whose history |
| `pageSize` | int | Query — default `20`, clamped to 1..100 |

**Response `200`:**

```json
[
  { "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690", "createdAt": "2026-07-20T14:30:00Z", "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b" },
  { "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690", "createdAt": "2026-07-20T11:05:00Z", "postId": "bb22cc33-4455-6677-8899-aabbccddeeff" }
]
```

---

### 1.5 `POST /api/v1/posts/{postId}/comments/{commentId}/reactions` — toggle comment like

```
POST /api/v1/posts/{postId}/comments/{commentId}/reactions
```

**Auth:** required. Works on top-level comments **and** replies. Same single-`LIKE`,
same LWT guard as post reactions.

**Response `200`:**

```json
{ "commentId": "c0a1b2c3-d4e5-f678-9012-3456789abcde", "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690", "liked": true }
```

**Side effects (like):** `comment_reactions_by_comment` LWT insert,
`comment_counters.reaction_count`++, broadcasts **`COMMENT_REACTION_ADDED`**, fires
`POST_COMMENT_REACTED` notification to the comment author.
**Unlike:** LWT delete, counter −1, broadcasts **`COMMENT_REACTION_REMOVED`**.

**Errors:** `401 AUTH_UNAUTHORIZED` without a JWT; `400 TYPE_MISMATCH` on malformed ids.

---

### 1.6 `DELETE /api/v1/posts/{postId}/comments/{commentId}/reactions` — explicit comment unlike

```
DELETE /api/v1/posts/{postId}/comments/{commentId}/reactions
```

**Auth:** required. Idempotent — no-op if not currently liked.

**Response `200`:**

```json
{ "commentId": "c0a1b2c3-d4e5-f678-9012-3456789abcde", "liked": false }
```

---

## 2. Views

### 2.1 `POST /api/v1/posts/{postId}/views` — record a view

```
POST /api/v1/posts/{postId}/views
```

**Auth:** optional. Anonymous viewers get the live count back but never bump it.

Records a **unique** view: the counter increments only on a user's first view of the
post inside a **7-day dedup window**. Fire-and-forget when the post enters the
viewport; `counted` is informational.

**Dedup mechanics (Redis NX):**

1. `SET NX` on `view:{postId}:{userId}` with a 7-day TTL — O(1), no Paxos hot-key
   bottleneck (which is why this isn't an LWT).
2. NX success → `views_by_post` row written + `post_counters.view_count`++.
3. NX failure (key existed) → duplicate; no-op.
4. Redis unreachable → fall back to a `views_by_post` point-read (slower, still correct).

> **Behavior note — dedup key released on write failure.** The NX key is claimed
> *before* the Cassandra write. If that write then fails, the key is **deleted** so
> the user's view isn't suppressed for the whole 7-day TTL with nothing recorded — a
> retry counts normally.

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | Path — the viewed post |

**Request body:** none.

**Response `200`** — first view / repeat view / anonymous:

```json
{ "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b", "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690", "counted": true,  "viewCount": 346 }
```

```json
{ "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b", "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690", "counted": false, "viewCount": 346 }
```

```json
{ "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b", "counted": false, "viewCount": 346 }
```

**Side effects (fresh view only):** `views_by_post` row, `view_count`++, broadcasts
**`VIEW_COUNT_UPDATED`** on the post's SSE channel (no counter value — clients apply
+1 locally).

---

## 3. Comments & Replies

**Depth-1 rule (project convention):** reply nesting caps at depth 1. Replying *to a
reply* creates a **sibling** under the same top-level comment — the server resolves
the true parent through `comment_lookup`, so the frontend can't produce depth-2 trees
even by accident.

**Duplicate-submit guard:** the same author posting the same text on the same
post/comment within a **3-second Redis window** (double-click, retry-on-timeout) does
not create a second row. The guard scans the **newest** rows of the partition to find
and return the just-created duplicate — so the caller gets the existing
comment/reply back instead of a copy, and the counter is not double-bumped. (The
newest-rows scan matters: the duplicate was created moments ago and sits at the tail
of the thread, which an oldest-first scan would miss on any thread longer than a page.)

### 3.1 `POST /api/v1/posts/{postId}/comments` — create top-level comment

```
POST /api/v1/posts/{postId}/comments
Content-Type: application/json
```

**Auth:** required. `authorId` comes from the JWT. Rate-limited (`comment` bucket,
10 per 30 s).

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | Path — the post being commented on |

**Request body (`CreateCommentRequest`):**

```json
{ "text": "Great post — thank you for sharing!", "mediaUrl": null, "mediaType": null }
```

| Field | Type | Description |
|-------|------|-------------|
| `text` | string | Comment body |
| `mediaUrl` | string | Optional inline image / video URL |
| `mediaType` | string | `IMAGE` / `VIDEO` when media is present |

**Response `200`** (`CommentResponse`, fully hydrated):

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
  "createdAt":  "2026-07-20T14:32:00Z"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Comment id |
| `author` | object | Inlined `AuthorSummary` |
| `reactionCount` / `replyCount` | long | Live counters from `comment_counters` |
| `likedByMe` | boolean | Viewer-relative |
| `deleted` / `edited` | boolean | `deleted` is always `false` on returned rows (deletes are physical) |

**Side effects:** `comments_by_post` + `comment_lookup` rows,
`post_counters.comment_count`++, broadcasts **`COMMENT_CREATED`**, fires
`POST_COMMENTED` notification to the post author (async enrichment).

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 429 | `RATE_LIMITED` | More than 10 comments in 30 s |
| 400 | `MALFORMED_JSON` / `TYPE_MISMATCH` | Bad body / bad `postId` |

---

### 3.2 `GET /api/v1/posts/{postId}/comments` — list comments

```
GET /api/v1/posts/{postId}/comments?pageSize=20&cursor=2026-07-20T14:32:00Z
```

**Auth:** none (public).

Top-level comments in chronological order (oldest first — natural reading order),
cursor-paginated. Hydration is bulk: counters, the viewer's comment likes and author
profiles load in one IN-query each per page. Deleted comments are **physically gone**
— no `[deleted]` placeholders.

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | Path |
| `pageSize` | int | Default `20`, clamped to 1..100 |
| `cursor` | ISO-8601 Instant | `createdAt` of the last comment from the previous page |

**Response `200`:** `List<CommentResponse>` (shape as §3.1).

---

### 3.3 `POST /api/v1/posts/comments/{commentId}/replies` — reply

```
POST /api/v1/posts/comments/{commentId}/replies
Content-Type: application/json
```

**Auth:** required. Rate-limited (`comment` bucket). Note the URL has **no**
`{postId}` segment — the post is resolved from `comment_lookup`.

Replies to a comment. If `{commentId}` is itself a reply, the new reply is hoisted to
a **sibling** under the same top-level parent (depth-1 rule) — the response's
`parentId` always points at the top-level ancestor.

| Param | Type | Description |
|-------|------|-------------|
| `commentId` | UUID | Path — comment *or reply* being answered |

**Request body (`CreateReplyRequest`):**

```json
{ "text": "Agreed — Imam Malik on this is well-documented.", "mediaUrl": null }
```

**Response `200`** (`ReplyResponse`):

```json
{
  "id":       "r0a1b2c3-d4e5-f678-9012-3456789abcde",
  "parentId": "c1b2c3d4-d4e5-f678-9012-3456789abcde",
  "postId":   "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": { "id": "41ee2a6b-2cd9-417b-861c-d1293c623690", "username": "akar.arkanf19", "fullName": "akar arkan", "profileImage": "https://cdn.example.com/avatars/41ee.jpg" },
  "textContent": "Agreed — Imam Malik on this is well-documented.",
  "mediaUrl": null,
  "reactionCount": 0,
  "likedByMe": false,
  "deleted": false,
  "edited":  false,
  "createdAt": "2026-07-20T14:50:00Z"
}
```

**Side effects:** `replies_by_comment` + `comment_lookup` rows,
`comment_counters.reply_count`++ on the parent, `post_counters.comment_count`++
(replies count toward the post total), broadcasts **`REPLY_CREATED`**, fires
`POST_COMMENT_REPLIED` notification to the parent comment's author.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 400 | `ILLEGAL_ARGUMENT` | `commentId` does not exist ("Comment not found") |
| 429 | `RATE_LIMITED` | Comment-bucket throttle |

---

### 3.4 `GET /api/v1/posts/comments/{commentId}/replies` — list replies

```
GET /api/v1/posts/comments/{commentId}/replies?pageSize=20
```

**Auth:** none (public). Lazy-load pattern: show `replyCount` per comment with a
"View N replies" button that calls this.

| Param | Type | Description |
|-------|------|-------------|
| `commentId` | UUID | Path — the top-level parent |
| `pageSize` | int | Default `20`, clamped to 1..100 |

**Response `200`:** `List<ReplyResponse>` (shape as §3.3), chronological ASC.

---

### 3.5 `PATCH /api/v1/posts/comments/{commentId}` — edit (author-only)

```
PATCH /api/v1/posts/comments/{commentId}
Content-Type: application/json
```

**Auth:** required; **author-only**. Works on comments and replies.

**Request body (`EditCommentRequest`):**

```json
{ "text": "Edited text (typo fix)" }
```

**Response:** `204 No Content`.

**Side effects:** row text updated (`edited: true` on next read); broadcasts
**`COMMENT_EDITED`** with the new text.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 403 | `FORBIDDEN` | Caller is not the comment's author |
| 400 | `ILLEGAL_ARGUMENT` | Comment does not exist |

---

### 3.6 `DELETE /api/v1/posts/comments/{commentId}` — hard-delete (author-only)

```
DELETE /api/v1/posts/comments/{commentId}
```

**Auth:** required; **author-only**.

**Physically removes** the comment or reply (the old soft-delete pattern — rows kept
with `is_deleted=true` — is gone; it bloated long threads forever):

- **Reply target:** row deleted from `replies_by_comment` + its lookup row;
  `comment_counters.reply_count`−1 on the parent and `post_counters.comment_count`−1.
- **Top-level target:** row deleted from `comments_by_post` + lookup row, and the
  comment's entire reply partition is **range-deleted in a single tombstone**. The
  post's `comment_count` decrements by **1 + replyCount** so the visible total stays
  accurate.

After the `204`, remove the row from the local list — it is permanently gone for
every viewer.

**Response:** `204 No Content`.

**Side effects:** counter decrements above; broadcasts **`COMMENT_DELETED`**.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 403 | `FORBIDDEN` | Caller is not the author |
| 400 | `ILLEGAL_ARGUMENT` | Comment does not exist |

---

## 4. Saves (bookmarks)

### 4.1 `POST /api/v1/posts/{postId}/saves` — toggle save

```
POST /api/v1/posts/{postId}/saves?collection=Quran
```

**Auth:** required. Rate-limited (`social` bucket, 30/min). LWT-guarded like
reactions — concurrent toggles can't double-bump `save_count`.

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | Path |
| `collection` | string | Query, optional — folder name; omitted = the default bucket |

**Request body:** none.

**Response `200`** (now saved; second call flips to `false`):

```json
{ "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b", "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690", "saved": true }
```

**Side effects (save):** `saves_by_post_user` LWT insert (the authority) +
`saves_by_user` mirror row, `post_counters.save_count`++, broadcasts
**`SAVE_COUNT_UPDATED`** with `"saved": true` (direction flag — clients apply +1),
activity row `POST_SAVED`.
**Unsave:** LWT delete + mirror delete, counter −1, broadcasts `SAVE_COUNT_UPDATED`
with `"saved": false`; unsaves are *not* logged to the activity feed.

**Errors:** `401 AUTH_UNAUTHORIZED`; `429 RATE_LIMITED`.

---

### 4.2 `GET /api/v1/posts/{postId}/saves/me` — "did I save this?"

```
GET /api/v1/posts/{postId}/saves/me
```

**Auth:** optional (anonymous-safe). Point-lookup on `saves_by_post_user`.

**Response `200`** (authenticated):

```json
{ "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b", "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690", "saved": true }
```

Anonymous: `{ "postId": "…", "saved": false }`.

---

### 4.3 `DELETE /api/v1/posts/{postId}/saves` — explicit unsave

```
DELETE /api/v1/posts/{postId}/saves
```

**Auth:** required. **Idempotent** — no-op when not currently saved (checked before
flipping the toggle).

**Response `200`:**

```json
{ "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b", "saved": false }
```

**Side effects:** same as the unsave branch of §4.1 when a save existed.

---

### 4.4 `GET /api/v1/posts/users/{userId}/saves` — saved posts (hydrated)

```
GET /api/v1/posts/users/{userId}/saves?pageSize=20&cursor=2026-07-20T14:35:00Z
```

**Auth:** none (public).

The user's bookmarks, newest-saved first, returned as **fully-hydrated
`PostResponse` objects** — `response[i].id` is the *post* UUID (React keys and
`/posts/{id}` links work directly). Save-context metadata rides on `savedAt` and
`savedCollectionName`; `savedByMe` is always `true` here. Saves whose underlying
post has been hard-deleted are silently dropped.

| Param | Type | Description |
|-------|------|-------------|
| `userId` | UUID | Path — whose saves |
| `pageSize` | int | Default `20`, clamped to 1..100 |
| `cursor` | ISO-8601 Instant | The `savedAt` of the last row from the previous page |

**Response `200`** (`List<PostResponse>`):

```json
[
  {
    "id": "f66aebce-d659-45b8-8479-75195f5d6d4b",
    "authorId": "9c1f1a2b-3344-5566-7788-99aabbccddee",
    "author": { "id": "9c1f1a2b-3344-5566-7788-99aabbccddee", "username": "ahmad", "fullName": "Ahmad Rahman", "profileImage": "https://cdn.example.com/avatars/9c1f.jpg" },
    "postType": "EMBEDDED",
    "status": "PUBLISHED",
    "visibility": "PUBLIC",
    "textContent": "On the importance of consistency in daily worship...",
    "mediaUrls": ["https://cdn.example.com/posts/2f.jpg"],
    "mediaTypes": ["IMAGE"],
    "reactionCount": 12, "commentCount": 3, "viewCount": 345,
    "saveCount": 7, "shareCount": 1,
    "likedByMe": false, "savedByMe": true,
    "createdAt": "2026-07-18T10:00:00Z",
    "updatedAt": "2026-07-18T10:00:00Z",
    "savedAt": "2026-07-20T14:35:00Z",
    "savedCollectionName": "Quran"
  }
]
```

Show `savedAt` as the badge ("Saved 2 days ago") and group by `savedCollectionName`.

---

## 5. Shares

An **append-only ledger** of platform-share events (share-to-DM, external app, copy
link). Distinct from a `REPOST`, which creates a new post with `sharedPostId` set.
There is no "unshare".

### 5.1 `POST /api/v1/posts/{postId}/shares` — record a share

```
POST /api/v1/posts/{postId}/shares
Content-Type: application/json
```

**Auth:** required. The sharer is derived from the JWT — any body-supplied id is
ignored. Rate-limited (`social` bucket).

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | Path — the shared post |

**Request body (`RecordShareRequest`, optional — may be omitted entirely):**

```json
{ "caption": "Excellent read 👌" }
```

**Response `200`** (the ledger row):

```json
{
  "postId":    "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "createdAt": "2026-07-20T14:36:00Z",
  "shareId":   "s0a1b2c3-d4e5-f678-9012-3456789abcde",
  "sharerId":  "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "caption":   "Excellent read 👌"
}
```

**Side effects:** `shares_by_post` row appended, `post_counters.share_count`++,
broadcasts **`SHARE_COUNT_UPDATED`**, fires `POST_SHARED` notification to the post
author.

**Errors:** `401 AUTH_UNAUTHORIZED`; `429 RATE_LIMITED`.

---

### 5.2 `GET /api/v1/posts/{postId}/shares` — recent shares

```
GET /api/v1/posts/{postId}/shares?pageSize=20
```

**Auth:** none (public).

Most recent shares of the post, newest first — the share-stats panel.

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | Path |
| `pageSize` | int | Default `20`, clamped to 1..100 |

**Response `200`:**

```json
[
  { "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b", "createdAt": "2026-07-20T14:36:00Z", "shareId": "s0a1b2c3-d4e5-f678-9012-3456789abcde", "sharerId": "41ee2a6b-2cd9-417b-861c-d1293c623690", "caption": "Excellent read 👌" },
  { "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b", "createdAt": "2026-07-20T12:10:00Z", "shareId": "s1c2d3e4-f5a6-b789-0123-456789abcdef", "sharerId": "9c1f1a2b-3344-5566-7788-99aabbccddee", "caption": null }
]
```

---

### 5.3 `GET /api/v1/posts/{postId}/share-link` — preview the share link

```
GET /api/v1/posts/{postId}/share-link
```

**Auth:** none (public). Does **not** bump the counter — for rendering the share
sheet before the user actually copies/sends the link.

Returns the unified `ShareLinkInfo` payload (identical shape across posts, research
and Q&A, so one frontend share component works everywhere):

**Response `200`:**

```json
{
  "shortUrl":     "https://api.irc.example.com/p/f66aebce-d659-45b8-8479-75195f5d6d4b",
  "canonicalUrl": "https://app.irc.example.com/posts/f66aebce-d659-45b8-8479-75195f5d6d4b",
  "token":        "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "shareCount":   1
}
```

| Field | Type | Description |
|-------|------|-------------|
| `shortUrl` | string | Public OG-tagged URL hosted by the backend — safe for chats/tweets |
| `canonicalUrl` | string | Frontend URL the share lands on — use for in-app "Open" |
| `token` | string | Bare share token |
| `shareCount` | long | Current denormalized share counter |

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 404 | `POST_NOT_FOUND` | Post does not exist |

---

### 5.4 `POST /api/v1/posts/{postId}/share` — share via unified link

```
POST /api/v1/posts/{postId}/share
Content-Type: application/json
```

**Auth:** required. Rate-limited (`social` bucket).

The "user actually copied / sent the link" action: atomically appends a share-ledger
row, bumps `share_count`, and returns the unified `ShareLinkInfo` with the **fresh**
count.

**Request body:** optional `{ "caption": "..." }` (same as §5.1).

**Response `200`:** `ShareLinkInfo` (shape as §5.3) with the post-bump `shareCount`.

**Side effects:** everything in §5.1 (ledger row, counter, **`SHARE_COUNT_UPDATED`**
broadcast, `POST_SHARED` notification).

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 404 | `POST_NOT_FOUND` | Post does not exist |
| 429 | `RATE_LIMITED` | Social-bucket throttle |
