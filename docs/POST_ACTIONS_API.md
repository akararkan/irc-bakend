# Post Actions API

Covers every social action a user can take on a post: **views**, **reactions (likes)**, **comments**, **replies**, **comment reactions**, **saves (bookmarks)**, **reposts**, **shares**, and the **realtime SSE stream** that delivers live updates for all of the above.

Base path: `/api/v1/posts`  
Auth header: `Authorization: Bearer <JWT>` (required unless noted).

---

## Table of Contents

1. [Core Concepts](#1-core-concepts)
2. [Post Views](#2-post-views)
3. [Post Reactions (Like / Unlike)](#3-post-reactions-like--unlike)
4. [Comments](#4-comments)
5. [Replies](#5-replies)
6. [Comment Reactions](#6-comment-reactions)
7. [Post Saves (Bookmarks)](#7-post-saves-bookmarks)
8. [Reposts](#8-reposts)
9. [Shares](#9-shares)
10. [Realtime SSE Stream](#10-realtime-sse-stream)
11. [Response Shapes](#11-response-shapes)
12. [Cassandra Tables](#12-cassandra-tables)
13. [Notification Triggers](#13-notification-triggers)
14. [Side-Effect Map](#14-side-effect-map)
15. [Error Reference](#15-error-reference)

---

## 1. Core Concepts

### Reaction model
Only **one reaction type (LIKE)** exists across all entities — posts, comments, and replies. There are no emoji variants. The presence of a row IS the like; the type is always `"LIKE"`.

### LWT toggle (race safety)
Like and save toggles use Cassandra Lightweight Transactions (`INSERT … IF NOT EXISTS` / `DELETE … IF EXISTS`). If two requests from the same user race to like the same post simultaneously, exactly one wins the Paxos round. Only the winner bumps the counter and fires the broadcast. There is no double-counting.

### Counter model (eventual consistency)
`post_counters` and `comment_counters` are Cassandra counter tables. Reads are eventually consistent — a count read immediately after an increment may return a stale value. The SSE stream therefore emits **event type only** (e.g. `REACTION_ADDED`), not a fresh count. Clients apply `+1` / `-1` locally and reconcile via REST on the next full fetch.

### Flat comment depth
Nesting is capped at **depth 1**. A reply to a reply lands as a sibling of the existing reply under the same top-level comment, not a deeper child. This is enforced by resolving the true parent via `comment_lookup` before write.

### Dedup guard
Comments and replies carry a server-side dedup check. If the same author submits identical text to the same target within a short window (Redis-backed key with TTL), the second request returns the existing row instead of creating a duplicate.

### Hard delete
Comment / reply deletes are **physical** — the row is removed from Cassandra, not soft-deleted. Deleting a top-level comment also range-deletes its entire reply partition in a single tombstone and adjusts `comment_count` by `1 + replyCount`.

---

## 2. Post Views

### 2.1 Record a view

```
POST /api/v1/posts/{postId}/views
```

Auth: optional (anonymous callers get the count back but don't bump it).

Records a unique view. Repeat calls from the same authenticated user within a 7-day dedup window return `counted: false` and don't inflate the counter.

**Dedup strategy:**
1. Redis `SET NX "view:{postId}:{userId}"` with 7-day TTL — sub-millisecond check.
2. If Redis is unavailable, falls back to a Cassandra point read on `views_by_post`.

**Path params**

| Param | Type | Description |
|---|---|---|
| `postId` | UUID | The post being viewed |

**No request body.**

**Response `200 OK`** (authenticated user)

```json
{
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "counted": true,
  "viewCount": 142
}
```

**Response `200 OK`** (anonymous)

```json
{
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "counted": false,
  "viewCount": 142
}
```

| Field | Description |
|---|---|
| `counted` | `true` if this call incremented the view counter; `false` if it was a duplicate or anonymous |
| `viewCount` | Current view count read from `post_counters` after the write |

**Side effects (when `counted: true`):**
- `post_counters.view_count` incremented via Cassandra counter update
- `views_by_post` row written (`postId`, `userId`, `firstViewedAt`)
- SSE broadcast on `/{postId}/stream` → `VIEW_COUNT_UPDATED`

---

## 3. Post Reactions (Like / Unlike)

### 3.1 Toggle like on a post

```
POST /api/v1/posts/{postId}/reactions
```

Auth: **required**.

Toggles the caller's like. If the user has not liked the post, likes it. If already liked, unlikes it. The response always reflects the state **after** the toggle.

**Path params**

| Param | Type | Description |
|---|---|---|
| `postId` | UUID | Target post |

**No request body.**

**Response `200 OK`**

```json
{
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "liked": true
}
```

| Field | Description |
|---|---|
| `liked` | `true` = post is now liked; `false` = post is now unliked |

**Side effects (when `liked: true` — first like):**
- `reactions_by_post` row inserted via `INSERT … IF NOT EXISTS`
- `reactions_by_user` row inserted (for user's reaction history feed)
- `post_counters.reaction_count` incremented
- SSE → `REACTION_ADDED` on `/{postId}/stream`
- Notification → `POST_REACTED` to post author (skipped if liker = author)

**Side effects (when `liked: false` — unlike):**
- `reactions_by_post` row deleted via `DELETE … IF EXISTS`
- `reactions_by_user` row deleted
- `post_counters.reaction_count` decremented
- SSE → `REACTION_REMOVED`

---

### 3.2 Check if caller liked a post

```
GET /api/v1/posts/{postId}/reactions/me
```

Auth: optional (returns `liked: false` for anonymous callers without error).

**Response `200 OK`**

```json
{
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "liked": true
}
```

---

### 3.3 Explicit unlike (idempotent)

```
DELETE /api/v1/posts/{postId}/reactions
```

Auth: **required**.

Unconditionally unlikes. No-op if the user was not currently liking the post (idempotent — always returns `liked: false`).

**Response `200 OK`**

```json
{
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "liked": false
}
```

---

### 3.4 User's reaction history

```
GET /api/v1/posts/users/{userId}/reactions
```

Auth: optional.

Returns the posts the user has liked, newest first.

**Query params**

| Param | Default | Description |
|---|---|---|
| `pageSize` | `20` | Max rows to return |

**Response `200 OK`**

```json
[
  {
    "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "createdAt": "2026-05-26T12:00:00Z",
    "postId": "751fe033-51d4-421f-b2e6-feb447c5c526"
  }
]
```

---

## 4. Comments

### 4.1 Create a top-level comment

```
POST /api/v1/posts/{postId}/comments
```

Auth: **required**. Author is derived from JWT — body-supplied authorId is ignored.

**Path params**

| Param | Type | Description |
|---|---|---|
| `postId` | UUID | The post to comment on |

**Request body**

```json
{
  "text": "Great insight! Have you considered the implications for X?",
  "mediaUrl": "https://cdn.example.com/image.jpg",
  "mediaType": "IMAGE"
}
```

| Field | Required | Description |
|---|---|---|
| `text` | no* | Comment text (max recommended 2000 chars) |
| `mediaUrl` | no | URL of an attached image/video |
| `mediaType` | no | `"IMAGE"` \| `"VIDEO"` \| `"AUDIO"` |

*At least one of `text` or `mediaUrl` should be present.

**Response `200 OK`** → [`CommentResponse`](#111-commentresponse)

```json
{
  "id": "a1b2c3d4-0000-0000-0000-000000000001",
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id": "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "username": "akararkan",
    "firstName": "Ak",
    "lastName": "A",
    "avatarUrl": "https://cdn.example.com/avatars/ak.jpg",
    "verified": false
  },
  "textContent": "Great insight! Have you considered the implications for X?",
  "mediaUrl": null,
  "mediaType": null,
  "reactionCount": 0,
  "replyCount": 0,
  "likedByMe": false,
  "deleted": false,
  "edited": false,
  "createdAt": "2026-05-26T15:45:00Z"
}
```

**Side effects:**
- `comments_by_post` row written
- `comment_lookup` row written (maps `commentId → postId`, `authorId`, `createdAt`, `isReply=false`)
- `post_counters.comment_count` incremented
- SSE → `COMMENT_CREATED` on `/{postId}/stream`
- Notification → `POST_COMMENTED` to post author

**Dedup:** identical text from the same author to the same post within the TTL window returns the existing comment row without creating a duplicate.

---

### 4.2 List comments on a post

```
GET /api/v1/posts/{postId}/comments
```

Auth: optional.

Returns top-level comments in chronological order (oldest first), cursor-paginated.

**Query params**

| Param | Default | Description |
|---|---|---|
| `pageSize` | `20` | Max comments to return |
| `cursor` | — | `createdAt` timestamp of the last item from the previous page (ISO-8601). Omit for the first page. |

**First page:**
```
GET /api/v1/posts/751fe033.../comments?pageSize=20
```

**Next page:**
```
GET /api/v1/posts/751fe033.../comments?pageSize=20&cursor=2026-05-26T15:45:00Z
```

**Response `200 OK`** → array of [`CommentResponse`](#111-commentresponse)

```json
[
  {
    "id": "a1b2c3d4-...",
    "postId": "751fe033-...",
    "authorId": "41ee2a6b-...",
    "author": { "username": "akararkan", ... },
    "textContent": "Great insight!",
    "mediaUrl": null,
    "mediaType": null,
    "reactionCount": 5,
    "replyCount": 2,
    "likedByMe": true,
    "deleted": false,
    "edited": false,
    "createdAt": "2026-05-26T15:45:00Z"
  }
]
```

To check if there are more pages: if the response length equals `pageSize`, there are likely more. Use the last item's `createdAt` as the next `cursor`.

---

### 4.3 Edit a comment

```
PATCH /api/v1/posts/comments/{commentId}
```

Auth: **required** (author only — returns `403` if caller ≠ comment author).

Works for both top-level comments and replies. Resolved via `comment_lookup` so the caller only needs the `commentId`.

**Path params**

| Param | Type | Description |
|---|---|---|
| `commentId` | UUID | Comment or reply to edit |

**Request body**

```json
{
  "text": "Updated content here."
}
```

**Response `204 No Content`**

**Side effects:**
- `comments_by_post` (or `replies_by_comment`) row updated: `text_content` set, `edited = true`
- SSE → `COMMENT_EDITED` on the post's stream (carries the new `textContent`)

---

### 4.4 Delete a comment

```
DELETE /api/v1/posts/comments/{commentId}
```

Auth: **required** (author only — returns `403` if caller ≠ comment author).

Hard delete. Works for both top-level comments and replies.

**Path params**

| Param | Type | Description |
|---|---|---|
| `commentId` | UUID | Comment or reply to delete |

**Response `204 No Content`**

**Side effects when deleting a top-level comment:**
- `comments_by_post` row hard-deleted
- `comment_lookup` row deleted
- `replies_by_comment` entire partition range-deleted in one tombstone
- `post_counters.comment_count` decremented by `1 + replyCount`

**Side effects when deleting a reply:**
- `replies_by_comment` row hard-deleted
- `comment_lookup` row deleted
- `comment_counters.reply_count` decremented by 1
- `post_counters.comment_count` decremented by 1

**SSE → `COMMENT_DELETED` on the post's stream** (in both cases)

---

## 5. Replies

### 5.1 Post a reply

```
POST /api/v1/posts/comments/{commentId}/replies
```

Auth: **required**.

Reply to any comment or existing reply. If `commentId` is itself a reply, the new reply attaches to the **same parent** (depth-1 rule). The server resolves the true parent automatically via `comment_lookup`.

**Path params**

| Param | Type | Description |
|---|---|---|
| `commentId` | UUID | The comment OR reply being replied to |

**Request body**

```json
{
  "text": "Totally agree with this point.",
  "mediaUrl": null
}
```

| Field | Required | Description |
|---|---|---|
| `text` | no* | Reply text |
| `mediaUrl` | no | Attached media URL |

**Response `200 OK`** → [`ReplyResponse`](#112-replyresponse)

```json
{
  "id": "b2c3d4e5-0000-0000-0000-000000000002",
  "parentId": "a1b2c3d4-0000-0000-0000-000000000001",
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id": "41ee2a6b-...",
    "username": "akararkan",
    "firstName": "Ak",
    "lastName": "A",
    "avatarUrl": "https://cdn.example.com/avatars/ak.jpg",
    "verified": false
  },
  "textContent": "Totally agree with this point.",
  "mediaUrl": null,
  "reactionCount": 0,
  "likedByMe": false,
  "deleted": false,
  "edited": false,
  "createdAt": "2026-05-26T15:47:00Z"
}
```

**Depth-1 enforcement example:**

```
Comment A (top-level)
  └─ Reply B  ← POST /comments/A/replies
  └─ Reply C  ← POST /comments/B/replies  (B is a reply → resolves to parent A → sibling of B)
```

**Side effects:**
- `replies_by_comment` row written under the resolved `parentId`
- `comment_lookup` row written (`isReply=true`, `parentId` = resolved top-level)
- `comment_counters.reply_count` incremented on parent comment
- `post_counters.comment_count` incremented (replies count toward the post total)
- SSE → `REPLY_CREATED` on the post's stream
- Notification → `POST_COMMENT_REPLIED` to the **parent comment's author**

---

### 5.2 List replies under a comment

```
GET /api/v1/posts/comments/{commentId}/replies
```

Auth: optional.

Returns replies under a top-level comment in chronological order.

**Path params**

| Param | Type | Description |
|---|---|---|
| `commentId` | UUID | The top-level comment |

**Query params**

| Param | Default | Description |
|---|---|---|
| `pageSize` | `20` | Max replies to return |

**Response `200 OK`** → array of [`ReplyResponse`](#112-replyresponse)

```json
[
  {
    "id": "b2c3d4e5-...",
    "parentId": "a1b2c3d4-...",
    "postId": "751fe033-...",
    "authorId": "41ee2a6b-...",
    "author": { "username": "akararkan", ... },
    "textContent": "Totally agree with this point.",
    "mediaUrl": null,
    "reactionCount": 1,
    "likedByMe": false,
    "deleted": false,
    "edited": false,
    "createdAt": "2026-05-26T15:47:00Z"
  }
]
```

> Replies use a first-page-only query (no cursor param). If you need paginated replies on very active threads, pass a larger `pageSize`.

---

## 6. Comment Reactions

### 6.1 Toggle like on a comment

```
POST /api/v1/posts/{postId}/comments/{commentId}/reactions
```

Auth: **required**.

Toggles the caller's like on a comment (also works for reply IDs in `commentId`). LWT-guarded — same race safety as post reactions.

**Path params**

| Param | Type | Description |
|---|---|---|
| `postId` | UUID | Post that owns the comment (required for SSE routing) |
| `commentId` | UUID | Comment or reply to toggle like on |

**No request body.**

**Response `200 OK`**

```json
{
  "commentId": "a1b2c3d4-0000-0000-0000-000000000001",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "liked": true
}
```

**Side effects (when `liked: true`):**
- `comment_reactions_by_comment` row inserted via `INSERT … IF NOT EXISTS`
- `comment_counters.reaction_count` incremented
- SSE → `COMMENT_REACTION_ADDED` on `/{postId}/stream`
- Notification → `POST_COMMENT_REACTED` to the comment's author

**Side effects (when `liked: false`):**
- `comment_reactions_by_comment` row deleted via `DELETE … IF EXISTS`
- `comment_counters.reaction_count` decremented
- SSE → `COMMENT_REACTION_REMOVED`

---

### 6.2 Explicit unlike a comment (idempotent)

```
DELETE /api/v1/posts/{postId}/comments/{commentId}/reactions
```

Auth: **required**.

Unconditionally removes the like. No-op if not currently liked.

**Response `200 OK`**

```json
{
  "commentId": "a1b2c3d4-0000-0000-0000-000000000001",
  "liked": false
}
```

---

## 7. Post Saves (Bookmarks)

A save is a private bookmark — only the saving user sees it. Saves never notify the post author. Each save fans out to **two** Cassandra tables so both "my saved posts" (list) and "did I save this?" (point read) are single-partition queries:

- `saves_by_user` — the user's bookmark list, newest first.
- `saves_by_post_user` — a point-lookup row that answers "did user U save post P?" and remembers `created_at` so unsave is a single read + two deletes.
- `post_counters.save_count` — incremented/decremented on each toggle.

### 7.1 Toggle a save

```
POST /api/v1/posts/{postId}/saves
```

Auth: **required** (`401` for anonymous callers).

Toggles the caller's bookmark. If not saved, saves it; if already saved, unsaves it. The response reflects the state **after** the toggle. LWT-guarded (`INSERT … IF NOT EXISTS` / `DELETE … IF EXISTS` on the lookup row) so concurrent toggles from the same user can't double-bump the counter — only the request that actually flipped the row owns the counter update, mirror write, and broadcast.

**Path params**

| Param | Type | Description |
|---|---|---|
| `postId` | UUID | Post to bookmark |

**Query params**

| Param | Default | Description |
|---|---|---|
| `collection` | — | Optional folder name. Omitted/`null` = the default "All" bucket. Stored on both save rows. |

**No request body.**

**Response `200 OK`**

```json
{
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "saved": true
}
```

| Field | Description |
|---|---|
| `saved` | `true` = post is now bookmarked; `false` = now un-bookmarked |

**Side effects (when `saved: true` — first save):**
- `saves_by_post_user` row inserted via `INSERT … IF NOT EXISTS`
- `saves_by_user` row inserted (with `collection_name`)
- `post_counters.save_count` incremented
- User activity recorded (`recordPostSaved`) — best-effort; unsaves are **not** logged
- SSE → `SAVE_COUNT_UPDATED` on `/{postId}/stream`

**Side effects (when `saved: false` — unsave):**
- `saves_by_post_user` row deleted via `DELETE … IF EXISTS`
- `saves_by_user` row deleted (located via the `created_at` remembered in the lookup row)
- `post_counters.save_count` decremented
- SSE → `SAVE_COUNT_UPDATED`

> **Changing a save's collection** = unsave + save with the new `collection` name. There is no dedicated "move" endpoint (cheap enough not to warrant one).

---

### 7.2 Check if the caller saved a post

```
GET /api/v1/posts/{postId}/saves/me
```

Auth: optional — anonymous callers get `saved: false` (and no `userId`) without an error.

**Response `200 OK`** (authenticated)

```json
{
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "userId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "saved": true
}
```

**Response `200 OK`** (anonymous)

```json
{
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "saved": false
}
```

Backed by a single point read on `saves_by_post_user`.

---

### 7.3 Explicit unsave (idempotent)

```
DELETE /api/v1/posts/{postId}/saves
```

Auth: **required**.

Unconditionally removes the bookmark. No-op if the post was not currently saved (always returns `saved: false`).

**Response `200 OK`**

```json
{
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "saved": false
}
```

---

### 7.4 List a user's saved posts

```
GET /api/v1/posts/users/{userId}/saves
```

Auth: optional.

Returns the user's bookmarks **as fully-hydrated posts**, newest first, cursor-paginated. Each item is a [`PostResponse`](#113-postresponse-full-post-detail) — so `response[i].id` is the post UUID, with the save context carried on `savedAt` and `savedCollectionName`.

**Path params**

| Param | Type | Description |
|---|---|---|
| `userId` | UUID | The user whose saves to list |

**Query params**

| Param | Default | Description |
|---|---|---|
| `pageSize` | `20` | Max rows to return |
| `cursor` | — | `savedAt` timestamp (ISO-8601) of the last item from the previous page. Omit for the first page. |

**Response `200 OK`** → array of [`PostResponse`](#113-postresponse-full-post-detail)

```json
[
  {
    "id": "751fe033-51d4-421f-b2e6-feb447c5c526",
    "authorId": "9a2c...",
    "author": { "username": "akararkan", "...": "..." },
    "postType": "POST",
    "textContent": "...",
    "saveCount": 12,
    "savedByMe": true,
    "savedAt": "2026-05-26T15:50:00Z",
    "savedCollectionName": "Reading list",
    "...": "...all other PostResponse fields..."
  }
]
```

> Saves whose underlying post has been hard-deleted are silently dropped from the response (so the count of returned items may be less than `pageSize` even when more pages exist).

---

## 8. Reposts

A **repost** is *not* a toggle/ledger action like saves and shares — it creates a brand-new post that points back at the original. There is **no dedicated repost endpoint**. You repost by creating a post with `postType = "REPOST"` and `sharedPostId` set to the original post's ID.

```
POST /api/v1/posts          (application/json)
POST /api/v1/posts          (multipart/form-data)
```

See [FEED_API.md](./FEED_API.md) / [POST_API.md](./POST_API.md) for the full create-post contract. The repost-relevant fields on the create command:

| Field | Type | Description |
|---|---|---|
| `postType` | string | `"REPOST"` |
| `sharedPostId` | UUID | The original post being reposted |
| `shareLink` | string \| null | Optional external link to the original |
| `textContent` | string \| null | Optional caption / quote text on the repost |

The new post is stored in `posts_by_id` with `shared_post_id` / `share_link` columns set (`CassandraPostService.createPost`), and is returned as a standard [`PostResponse`](#113-postresponse-full-post-detail). Clients detect a repost via `postType == "REPOST"` and use `sharedPostId` to fetch/link the original.

**What a repost does *not* do (by current design):**
- ❌ No repost counter — `post_counters` tracks `reaction_count`, `comment_count`, `view_count`, `save_count`, `share_count` only. There is no `repost_count` on the original.
- ❌ No repost-specific SSE event — there is no `REPOST_COUNT_UPDATED`.
- ❌ No notification — there is no `POST_REPOSTED` `NotificationKind`; the original author is **not** notified.

**Self-repost is allowed** — a user may repost their own post (Twitter/Facebook style). There is no `SELF_REPOST` guard.

> **Repost vs. Share:** a *repost* publishes a new post row to your profile and followers' feeds; a *share* (§9) is an append-only ledger event recording that you sent a post elsewhere (DM, external app). They are independent mechanisms.

---

## 9. Shares

A **share** records that a user tapped *Share* on a post — sending it to another surface (DM, external app, link copy) with an optional caption. The share log is an **append-only ledger**: there is no "unshare". The same user can share the same post any number of times; each call writes a new row with a unique `shareId`.

### 9.1 Record a share

```
POST /api/v1/posts/{postId}/shares
```

Auth: **required** (`401` for anonymous). Sharer is derived from the JWT — any body-supplied sharer ID is ignored.

**Path params**

| Param | Type | Description |
|---|---|---|
| `postId` | UUID | Post being shared |

**Request body** (optional — the whole body may be omitted)

```json
{
  "caption": "You all need to read this 👇"
}
```

| Field | Required | Description |
|---|---|---|
| `caption` | no | Optional note attached to the share |

**Response `200 OK`** → the created [`ShareByPostEntity`](#shares_by_post)

```json
{
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "createdAt": "2026-05-26T15:52:00Z",
  "shareId": "c4d5e6f7-0000-0000-0000-000000000003",
  "sharerId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "caption": "You all need to read this 👇"
}
```

**Side effects:**
- `shares_by_post` row appended
- `post_counters.share_count` incremented (every share — no dedup)
- SSE → `SHARE_COUNT_UPDATED` on `/{postId}/stream` — **uniquely carries `postShareCount`** (see note below)
- Notification → `POST_SHARED` to the post author (skipped if sharer = author), group key `POST_SHARED:{postId}`, aggregated in a 60-minute window

> **SSE exception:** unlike every other realtime event, `SHARE_COUNT_UPDATED` includes the freshly-read `postShareCount`. The broadcast reads `post_counters.share_count` after the increment and attaches it. Because counters are eventually consistent it can still be momentarily stale, so clients may either trust the value or fall back to a local `+1`.

---

### 9.2 List recent shares on a post

```
GET /api/v1/posts/{postId}/shares
```

Auth: optional (public — used by the share-stats panel).

Returns recent share-ledger rows for the post, newest first.

**Query params**

| Param | Default | Description |
|---|---|---|
| `pageSize` | `20` | Max share rows to return |

**Response `200 OK`** → array of [`ShareByPostEntity`](#shares_by_post)

```json
[
  {
    "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
    "createdAt": "2026-05-26T15:52:00Z",
    "shareId": "c4d5e6f7-...",
    "sharerId": "41ee2a6b-...",
    "caption": "You all need to read this 👇"
  }
]
```

---

## 10. Realtime SSE Stream

### 10.1 Subscribe to a post's live event stream

```
GET /api/v1/posts/{id}/stream
```

`Content-Type: text/event-stream`

Auth: optional — unauthenticated viewers can still subscribe (post stream is public). Because `EventSource` in browsers cannot send `Authorization` headers, pass the JWT via query param instead:

```
GET /api/v1/posts/751fe033.../stream?token=<jwt>
```

The server validates the token and attaches a `viewerId` to the SSE connection. Anonymous connections (`viewerId=null`) receive all events but don't trigger view counting.

**Path params**

| Param | Type | Description |
|---|---|---|
| `id` | UUID | Post to stream |

**No request body. Response is a persistent SSE connection.**

Server sends `Cache-Control: no-cache`, `X-Accel-Buffering: no` so nginx / CDN don't buffer events.

---

### 10.2 SSE event payload

Every event arrives as:

```
data: { ...PostRealtimeEvent fields... }
```

**Full event shape (null fields omitted from wire):**

```json
{
  "eventType": "REACTION_ADDED",
  "postId": "751fe033-51d4-421f-b2e6-feb447c5c526",
  "actorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "actorUsername": null,
  "commentId": null,
  "parentCommentId": null,
  "reactionType": "LIKE",
  "textContent": null,
  "mediaUrl": null,
  "mediaType": null,
  "timestamp": "2026-05-26T15:48:00"
}
```

**All event types:**

| `eventType` | Trigger | Key fields populated |
|---|---|---|
| `REACTION_ADDED` | User liked the post | `actorId`, `reactionType` |
| `REACTION_REMOVED` | User unliked the post | `actorId`, `reactionType` |
| `COMMENT_CREATED` | New top-level comment | `commentId`, `actorId`, `textContent`, `mediaUrl`, `mediaType` |
| `COMMENT_EDITED` | Comment text updated | `commentId`, `actorId`, `textContent` |
| `COMMENT_DELETED` | Comment hard-deleted | `commentId`, `actorId` |
| `REPLY_CREATED` | New reply | `commentId` (reply ID), `parentCommentId`, `actorId`, `textContent` |
| `COMMENT_REACTION_ADDED` | Comment liked | `commentId`, `actorId`, `reactionType` |
| `COMMENT_REACTION_REMOVED` | Comment unliked | `commentId`, `actorId` |
| `VIEW_COUNT_UPDATED` | New unique view recorded | `actorId` (null for anon) |
| `SHARE_COUNT_UPDATED` | Post shared | `actorId`, **`postShareCount`** ¹ |
| `SAVE_COUNT_UPDATED` | Post saved/unsaved | `actorId`, `saved` (`true`=saved, `false`=unsaved) |
| `POST_UPDATED` | Author edited the post | `actorId`, `textContent` |
| `POST_DELETED` | Author deleted the post | `actorId` |

**Counter fields are NOT included in SSE events** — apply `+1` / `-1` locally on each `*_ADDED` / `*_REMOVED` event and reconcile with the full REST response on next page load.

> ¹ **The one exception is `SHARE_COUNT_UPDATED`**, which carries `postShareCount` — the share counter re-read from `post_counters` right after the increment. Because counters are eventually consistent this value may be momentarily stale, so clients can trust it or fall back to a local `+1`. (Reposts emit no event at all — see §8.)

---

### 10.3 Client-side SSE example (JavaScript)

```js
const es = new EventSource(`/api/v1/posts/${postId}/stream?token=${jwt}`);

es.onmessage = ({ data }) => {
  const event = JSON.parse(data);
  switch (event.eventType) {
    case 'REACTION_ADDED':
      setLikeCount(n => n + 1);
      break;
    case 'REACTION_REMOVED':
      setLikeCount(n => n - 1);
      break;
    case 'COMMENT_CREATED':
      prependComment(event);
      setCommentCount(n => n + 1);
      break;
    case 'COMMENT_DELETED':
      removeComment(event.commentId);
      break;
    case 'REPLY_CREATED':
      appendReply(event.parentCommentId, event);
      break;
    case 'VIEW_COUNT_UPDATED':
      setViewCount(n => n + 1);
      break;
    case 'POST_DELETED':
      showDeletedBanner();
      es.close();
      break;
  }
};
```

---

## 11. Response Shapes

### 11.1 CommentResponse

```json
{
  "id":            "UUID — comment identifier",
  "postId":        "UUID — post this comment belongs to",
  "authorId":      "UUID",
  "author": {
    "id":          "UUID",
    "username":    "string",
    "firstName":   "string",
    "lastName":    "string",
    "avatarUrl":   "string | null",
    "verified":    "boolean"
  },
  "textContent":   "string | null",
  "mediaUrl":      "string | null",
  "mediaType":     "IMAGE | VIDEO | AUDIO | null",
  "reactionCount": "long — likes on this comment",
  "replyCount":    "long — replies under this comment",
  "likedByMe":     "boolean — true if authenticated caller has liked this comment",
  "deleted":       "boolean",
  "edited":        "boolean",
  "createdAt":     "ISO-8601 instant"
}
```

### 11.2 ReplyResponse

```json
{
  "id":            "UUID — reply identifier",
  "parentId":      "UUID — top-level comment this reply hangs under",
  "postId":        "UUID",
  "authorId":      "UUID",
  "author":        "AuthorSummary (same shape as above)",
  "textContent":   "string | null",
  "mediaUrl":      "string | null",
  "reactionCount": "long",
  "likedByMe":     "boolean",
  "deleted":       "boolean",
  "edited":        "boolean",
  "createdAt":     "ISO-8601 instant"
}
```

> Replies do not have a `replyCount` field (depth is capped at 1 — no sub-replies).

### 11.3 PostResponse (full post detail)

Returned by `GET /api/v1/posts/{id}` — includes the full `mediaUrls` / `mediaTypes` arrays and all counters. Counter fields (`reactionCount`, `commentCount`, `viewCount`, `saveCount`, `shareCount`) and viewer flags (`likedByMe`, `savedByMe`) are fully hydrated by `PostHydrator` in one Cassandra bulk round-trip.

```json
{
  "id":            "UUID",
  "authorId":      "UUID",
  "author":        "AuthorSummary",
  "postType":      "POST | REEL | RESEARCH | QUESTION",
  "status":        "PUBLISHED | DRAFT | ARCHIVED",
  "visibility":    "PUBLIC | FOLLOWERS | CLOSE_FRIENDS | PRIVATE",
  "textContent":   "string | null",
  "audioTrackUrl": "string | null",
  "audioTrackName":"string | null",
  "locationName":  "string | null",
  "locationLat":   "number | null",
  "locationLng":   "number | null",
  "sharedPostId":  "UUID | null",
  "shareLink":     "string | null",
  "mediaUrls":     ["string", ...],
  "mediaTypes":    ["IMAGE | VIDEO | AUDIO | OTHER", ...],
  "reactionCount": "long",
  "commentCount":  "long",
  "viewCount":     "long",
  "saveCount":     "long",
  "shareCount":    "long",
  "likedByMe":     "boolean",
  "savedByMe":     "boolean",
  "createdAt":     "ISO-8601 instant",
  "updatedAt":     "ISO-8601 instant",
  "savedAt":       "ISO-8601 instant | null — only populated on saved-list endpoint",
  "savedCollectionName": "string | null"
}
```

---

## 12. Cassandra Tables

### `views_by_post`

| Column | Type | Role |
|---|---|---|
| `post_id` | UUID | Partition key |
| `user_id` | UUID | Clustering key |
| `first_viewed_at` | timestamp | When the first view was recorded |

One row per `(post, user)` — duplicate views within 7 days never create a new row (Redis dedup wins first).

---

### `reactions_by_post`

| Column | Type | Role |
|---|---|---|
| `post_id` | UUID | Partition key |
| `user_id` | UUID | Clustering key |
| `created_at` | timestamp | When the like was recorded |

---

### `reactions_by_user`

| Column | Type | Role |
|---|---|---|
| `user_id` | UUID | Partition key |
| `created_at` | timestamp | Clustering key DESC |
| `post_id` | UUID | Clustering key |

User's reaction history, newest first.

---

### `post_counters`

| Column | Type | Role |
|---|---|---|
| `post_id` | UUID | Partition key (counter table — no clustering) |
| `reaction_count` | counter | Total likes |
| `comment_count` | counter | Total comments + replies |
| `view_count` | counter | Unique views |
| `save_count` | counter | Total saves |
| `share_count` | counter | Total shares |

---

### `saves_by_user`

| Column | Type | Role |
|---|---|---|
| `user_id` | UUID | Partition key |
| `created_at` | timestamp | Clustering key DESC |
| `post_id` | UUID | Clustering key |
| `collection_name` | text | User-named folder; null = default "All" bucket |

The user's bookmark list, newest first. Backs `GET /users/{userId}/saves`.

---

### `saves_by_post_user`

| Column | Type | Role |
|---|---|---|
| `post_id` | UUID | Partition key |
| `user_id` | UUID | Clustering key |
| `created_at` | timestamp | Remembered so unsave can locate the `saves_by_user` row in one read |
| `collection_name` | text | Mirror of the collection on the save |

Point-lookup table answering "did user U save post P?" in a single read. LWT toggle (`INSERT … IF NOT EXISTS` / `DELETE … IF EXISTS`) targets this row.

---

### `shares_by_post`

| Column | Type | Role |
|---|---|---|
| `post_id` | UUID | Partition key |
| `created_at` | timestamp | Clustering key DESC |
| `share_id` | UUID | Clustering key |
| `sharer_id` | UUID | Who shared |
| `caption` | text | Optional note attached to the share |

Append-only share ledger — one row per share event, no dedup, no delete path.

---

### `comments_by_post`

| Column | Type | Role |
|---|---|---|
| `post_id` | UUID | Partition key |
| `created_at` | timestamp | Clustering key ASC |
| `comment_id` | UUID | Clustering key |
| `author_id` | UUID | |
| `text_content` | text | |
| `media_url` | text | |
| `media_type` | text | |
| `deleted` | boolean | |
| `edited` | boolean | |

---

### `replies_by_comment`

| Column | Type | Role |
|---|---|---|
| `parent_id` | UUID | Partition key (top-level comment ID) |
| `created_at` | timestamp | Clustering key ASC |
| `reply_id` | UUID | Clustering key |
| `author_id` | UUID | |
| `post_id` | UUID | |
| `text_content` | text | |
| `media_url` | text | |
| `deleted` | boolean | |
| `edited` | boolean | |

---

### `comment_lookup`

| Column | Type | Role |
|---|---|---|
| `comment_id` | UUID | Partition key |
| `post_id` | UUID | |
| `parent_id` | UUID | null for top-level; parent's comment_id for replies |
| `author_id` | UUID | |
| `created_at` | timestamp | Used to locate the row in the clustering table |
| `is_reply` | boolean | |

Used by edit, delete, and notification fan-out to resolve a `commentId` → `(postId, parentId, authorId, createdAt)` in O(1) without scanning the full post partition.

---

### `comment_reactions_by_comment`

| Column | Type | Role |
|---|---|---|
| `comment_id` | UUID | Partition key |
| `user_id` | UUID | Clustering key |
| `created_at` | timestamp | |

---

### `comment_counters`

| Column | Type | Role |
|---|---|---|
| `comment_id` | UUID | Partition key |
| `reaction_count` | counter | |
| `reply_count` | counter | |

---

## 13. Notification Triggers

All notifications are delivered **asynchronously** — the enrichment (post/user/comment lookups needed to build the body text) runs on the `taskExecutor` thread pool, not the request thread.

| Action | Recipient | `NotificationKind` | Body example |
|---|---|---|---|
| Like a post | Post author | `POST_REACTED` | `@akararkan liked your post` |
| Like a comment | Comment author | `POST_COMMENT_REACTED` | `@akararkan liked your comment` |
| Comment on post | Post author | `POST_COMMENTED` | `@akararkan commented: "Great insight…"` |
| Reply to comment | Parent comment author | `POST_COMMENT_REPLIED` | `@akararkan replied: "Totally agree…"` |
| Share a post | Post author | `POST_SHARED` | `@akararkan shared your post` |

`POST_SHARED` is **aggregable** (group key `POST_SHARED:{postId}`, 60-minute window) and **email-eligible** (`PrefCategory.SOCIAL`). **Saves and reposts fire no notification** — saving is private, and there is no `POST_REPOSTED` kind.

**Self-notifications are skipped** — if the actor is the same user as the recipient, `deliverAsync` returns `null` before writing any notification row. (Applies to `POST_SHARED` too: sharing your own post notifies no one.)

---

## 14. Side-Effect Map

| Endpoint | Cassandra writes | Redis | SSE event | Notification |
|---|---|---|---|---|
| `POST /{postId}/views` | `views_by_post`, `post_counters.view_count++` | `SET NX view:{postId}:{userId}` (7d TTL) | `VIEW_COUNT_UPDATED` | — |
| `POST /{postId}/reactions` (like) | `reactions_by_post`, `reactions_by_user`, `post_counters.reaction_count++` | — | `REACTION_ADDED` | `POST_REACTED` |
| `POST /{postId}/reactions` (unlike) | DELETE `reactions_by_post`, DELETE `reactions_by_user`, `post_counters.reaction_count--` | — | `REACTION_REMOVED` | — |
| `DELETE /{postId}/reactions` | same as unlike path above | — | `REACTION_REMOVED` | — |
| `POST /{postId}/comments` | `comments_by_post`, `comment_lookup`, `post_counters.comment_count++` | — | `COMMENT_CREATED` | `POST_COMMENTED` |
| `PATCH /comments/{commentId}` | UPDATE `comments_by_post` or `replies_by_comment` | — | `COMMENT_EDITED` | — |
| `DELETE /comments/{commentId}` (top-level) | DELETE `comments_by_post`, DELETE `comment_lookup`, DELETE `replies_by_comment` (range), `post_counters.comment_count -= 1 + replyCount` | — | `COMMENT_DELETED` | — |
| `DELETE /comments/{commentId}` (reply) | DELETE `replies_by_comment`, DELETE `comment_lookup`, `comment_counters.reply_count--`, `post_counters.comment_count--` | — | `COMMENT_DELETED` | — |
| `POST /comments/{commentId}/replies` | `replies_by_comment`, `comment_lookup`, `comment_counters.reply_count++`, `post_counters.comment_count++` | — | `REPLY_CREATED` | `POST_COMMENT_REPLIED` |
| `POST /{postId}/comments/{commentId}/reactions` (like) | `comment_reactions_by_comment`, `comment_counters.reaction_count++` | — | `COMMENT_REACTION_ADDED` | `POST_COMMENT_REACTED` |
| `POST /{postId}/comments/{commentId}/reactions` (unlike) | DELETE `comment_reactions_by_comment`, `comment_counters.reaction_count--` | — | `COMMENT_REACTION_REMOVED` | — |
| `DELETE /{postId}/comments/{commentId}/reactions` | same as unlike above | — | `COMMENT_REACTION_REMOVED` | — |
| `POST /{postId}/saves` (save) | `saves_by_post_user` (LWT `IF NOT EXISTS`), `saves_by_user`, `post_counters.save_count++` | — | `SAVE_COUNT_UPDATED` | — |
| `POST /{postId}/saves` (unsave) | DELETE `saves_by_post_user` (LWT `IF EXISTS`), DELETE `saves_by_user`, `post_counters.save_count--` | — | `SAVE_COUNT_UPDATED` | — |
| `DELETE /{postId}/saves` | same as unsave path above | — | `SAVE_COUNT_UPDATED` | — |
| `POST /{postId}/shares` | `shares_by_post` (append), `post_counters.share_count++` | — | `SHARE_COUNT_UPDATED` (carries `postShareCount`) | `POST_SHARED` |
| `POST /posts` (repost: `postType=REPOST`, `sharedPostId` set) | `posts_by_id` (+ author/feed fan-out — see FEED_API) | — | — | — |

---

## 15. Error Reference

| Status | Scenario |
|---|---|
| `400 Bad Request` | Missing required path param or malformed UUID |
| `401 Unauthorized` | JWT missing or expired on an auth-required endpoint |
| `403 Forbidden` | Attempting to edit or delete another user's comment |
| `404 Not Found` | `postId` or `commentId` does not exist |
| `500 Internal Server Error` | Cassandra or counter write failure |

SSE connections do not return HTTP error codes after the connection is established — if the post is deleted while a subscriber is connected, the server emits `POST_DELETED` and the client should close the `EventSource`.
