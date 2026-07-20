# Research API — Social Interactions

Reactions, comments, saves, views, and downloads on a research paper.

**Base path:** `/api/v1/researches/{researchId}`

Sibling documents:

- [Lifecycle](./research.md) — create / update / publish / read
- [Media, sources & contributors](./media-sources-contributors.md)
- [Feeds & discovery](./feeds-discovery.md) — saved lists, share & cite live there
- [Realtime (SSE)](./realtime.md) — the events emitted by everything on this page

## Authentication & shared rules

Bearer JWT (`Authorization: Bearer <token>`) or `access_token` cookie. Errors use the
[unified envelope](../errors/error-handling.md).

Rules that apply across this page:

- **Published only.** Reacting, commenting, saving, and downloading require the paper to be
  `PUBLISHED` → otherwise `400 NOT_PUBLISHED`.
- **Single reaction type.** `ReactionType` has exactly one value: `LIKE` — for papers *and*
  comments ("academic not entertainment"). No multi-reaction variants.
- **Block guards.** Reacting / commenting / saving across a block edge with the researcher
  (either direction) is rejected with `403` and a specific error code
  (`RESEARCH_REACTION_BLOCKED_RELATIONSHIP`, `RESEARCH_COMMENT_BLOCKED_RELATIONSHIP`,
  `RESEARCH_SAVE_BLOCKED_RELATIONSHIP`, `RESEARCH_COMMENT_REACTION_BLOCKED_RELATIONSHIP`).
- **Rate limits.** Reactions, comments, and saves pass a per-user rate limiter →
  `429` when exceeded.
- **Replies are flat at depth 1.** Replying to a reply is hoisted server-side to a sibling
  reply under the root comment — depth-2 trees cannot exist.
- **Self-engagement is allowed** (react/save/comment on your own paper); self-notifications
  are skipped downstream.
- **SSE counters are absolute.** Events emitted here carry the post-action counter values —
  see [realtime](./realtime.md#counter-semantics).

---

## Reactions

### React (LIKE)

```
POST /api/v1/researches/{researchId}/reactions
```

**Auth:** Bearer JWT (any authenticated user).

Adds the caller's LIKE. **Idempotent** — re-calling when already reacted is a no-op that
still re-broadcasts the authoritative count so optimistic UIs can reconcile.

**Concurrency fix:** a concurrent duplicate reaction (two first-reactions racing past the
existence check; the loser hits the primary-key constraint) is **no longer an error** — the
row exists, which is a successful outcome for an idempotent toggle, so the call returns
`201` instead of a 400.

**Request body** (optional — defaults to `LIKE`):

```json
{ "reactionType": "LIKE" }
```

**Response — `201 Created`** (empty body).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_REACTION` | Missing research id |
| 400 | `NOT_PUBLISHED` | Paper isn't published |
| 403 | `RESEARCH_REACTION_BLOCKED_RELATIONSHIP` | Block edge with the researcher |
| 404 | `RESOURCE_NOT_FOUND` | Research or user not found |
| 409 | — | Optimistic-lock conflict on the counter (retry) |
| 429 | — | Reaction rate limit |

**Side effects:** `reactionCount` +1 (skipped on idempotent repeat); Redis counter cache
write-through; `REACTION_ADDED` SSE event with fresh counters; RabbitMQ reaction event →
notification to the researcher (skipped for self-reactions).

### Remove reaction

```
DELETE /api/v1/researches/{researchId}/reactions
```

**Auth:** Bearer JWT.

Removes the caller's LIKE. Idempotent — no row, no error, no decrement.

**Response — `200 OK`** — fresh `ResearchResponse` (post-decrement counters,
`currentUserReacted: false`).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_INPUT` | Missing ids |
| 404 | `RESOURCE_NOT_FOUND` | Research not found |

**Side effects:** `reactionCount` −1 (when a row existed); `REACTION_REMOVED` SSE event with
fresh counters.

### Reaction breakdown

```
GET /api/v1/researches/{researchId}/reactions/breakdown
```

**Auth:** none (public).

Per-type reaction tally. Exposed as a map for forward compatibility, but today the only key
is `LIKE`.

**Response — `200 OK`:**

```json
{ "LIKE": 213 }
```

---

## Comments

### List comments

```
GET /api/v1/researches/{researchId}/comments
```

**Auth:** none (public). Optional JWT enables owner view + `myReaction`.

Page of **top-level** comments, newest first, each carrying its full `replies` array inline
(the depth-1 cap keeps threads bounded — there is no "load more replies" endpoint, and
`replyCount` always agrees with `replies.length`).

- **The research owner sees hidden comments** (`isHidden: true` rows); everyone else gets
  only non-hidden rows. Soft-deleted comments never appear.
- **Batched viewer reactions:** the authenticated viewer's own `myReaction` flags for the
  entire page are now fetched in **one** `IN`-clause query instead of one point-read per
  comment. No behavior change — pure round-trip reduction.

| Param | In | Default | Notes |
|---|---|---|---|
| `page` / `size` / `sort` | query | `0` / `20` | Spring pagination |

**Response — `200 OK`** — `Page<CommentResponse>`:

```json
{
  "content": [
    {
      "id":         "C-uuid",
      "researchId": "R-uuid",
      "userId":     "U-uuid",
      "userFullName":     "Ali Saleem",
      "userUsername":     "ali",
      "userProfileImage": "https://cdn…/ali.jpg",
      "content":    "Beautiful methodology — well sourced.",
      "mediaUrl": null, "mediaType": null, "mediaThumbnailUrl": null,
      "voiceUrl": null, "voiceDurationSeconds": null,
      "likeCount":  12,
      "replyCount": 2,
      "myReaction": "LIKE",
      "isEdited": false, "editedAt": null,
      "isHidden": false, "hiddenAt": null,
      "parentId": null,
      "replies": [ { "…": "CommentResponse, parentId set" } ],
      "createdAt": "2026-05-21T10:15:00",
      "timeAgo": "5 minutes ago",
      "formattedDate": "21 May 2026"
    }
  ],
  "totalElements": 31, "totalPages": 2, "number": 0, "size": 20
}
```

| Field | Notes |
|---|---|
| `likeCount` | Total reactions on the comment (name kept for wire compatibility) |
| `myReaction` | The viewer's reaction (`"LIKE"`) or `null`; always `null` for anonymous |
| `mediaUrl` / `mediaType` / `mediaThumbnailUrl` | Optional image/video attachment |
| `voiceUrl` / `voiceDurationSeconds` | Optional voice comment |

### Add a comment / reply

```
POST /api/v1/researches/{researchId}/comments
```

**Auth:** Bearer JWT.

Creates a top-level comment, or a reply when `parentId` is set. Passing the id of a depth-1
reply as `parentId` hoists the new comment to a sibling under the root. At least one of
text / media / voice content is required. Double-clicks and retries are absorbed by a dedup
window — a duplicate returns the recently created comment instead of writing a second copy.

```json
{
  "content":  "Beautiful methodology.",
  "parentId": null,
  "mediaUrl": null, "mediaS3Key": null, "mediaType": null,
  "mediaThumbnailUrl": null, "mediaThumbnailS3Key": null,
  "voiceUrl": null, "voiceS3Key": null, "voiceDurationSeconds": null,
  "voiceTranscript": null, "waveformData": null
}
```

| Field | Notes |
|---|---|
| `content` | ≤ 5 000 chars; optional if media or voice present |
| `parentId` | `null` for top-level; a comment id for replies |
| `media*` | Pre-uploaded image/video URL + S3 key; `mediaType` is `"IMAGE"` or `"VIDEO"` |
| `voice*` | Pre-uploaded voice recording metadata |

**Response — `201 Created`** — `CommentResponse`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `EMPTY_COMMENT` | No text, media, or voice |
| 400 | `COMMENT_TOO_LONG` | > 5 000 chars |
| 400 | `NOT_PUBLISHED` | Paper isn't published |
| 400 | `COMMENTS_DISABLED` | `commentsEnabled: false` on the paper |
| 400 | `INVALID_PARENT` / `PARENT_DELETED` | Parent belongs elsewhere / is deleted |
| 403 | `RESEARCH_COMMENT_BLOCKED_RELATIONSHIP` | Block edge with the researcher |
| 404 | `RESOURCE_NOT_FOUND` | Research / parent comment not found |
| 429 | — | Comment rate limit |

**Side effects:** `commentCount` +1 (and parent `replyCount` +1 for replies) with Redis
write-through; `COMMENT_CREATED` or `REPLY_CREATED` SSE event embedding the full
`CommentResponse`; RabbitMQ comment event → notification to the researcher; `@mention`
notifications for handles in the comment body (no `@followers` fan-out from comments).

### Add a comment with file upload

```
POST /api/v1/researches/{researchId}/comments/upload
```

**Auth:** Bearer JWT. `multipart/form-data`.

Same as *Add a comment*, but the server uploads the attachments for you.

| Part | Required | Notes |
|---|---|---|
| `data` | yes | JSON `AddCommentRequest` (as above) |
| `media` | no | image or video — `mediaType` derived from the MIME type |
| `voice` | no | audio recording |

**Response — `201 Created`** — `CommentResponse` with `mediaUrl` / `voiceUrl` populated.
Errors and side effects: same as *Add a comment*.

### Edit a comment

```
PATCH /api/v1/researches/{researchId}/comments/{commentId}
```

**Auth:** Bearer JWT — comment author only.

Updates the text; sets `isEdited: true` + `editedAt`.

```json
{ "content": "Updated comment body." }
```

**Response — `200 OK`** — updated `CommentResponse`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_INPUT` / `EMPTY_COMMENT` / `COMMENT_TOO_LONG` | Bad body |
| 400 | `COMMENT_DELETED` | Comment already deleted |
| 403 | `FORBIDDEN` | Not the author, or wrong research |
| 404 | `RESOURCE_NOT_FOUND` | Comment not found |

**Side effects:** `COMMENT_EDITED` SSE event embedding the fresh `CommentResponse`; mention
**delta** scan (only newly introduced handles are notified).

### Delete a comment

```
DELETE /api/v1/researches/{researchId}/comments/{commentId}
```

**Auth:** Bearer JWT — comment author **or** the research owner.

Soft-deletes the comment, wipes all reactions on it, and (for replies) decrements the
parent's `replyCount`.

**Response — `204 No Content`.**

| Status | `errorCode` | When |
|---|---|---|
| 400 | `ALREADY_DELETED` | Already deleted |
| 403 | `FORBIDDEN` | Neither comment author nor research owner |
| 404 | `RESOURCE_NOT_FOUND` | Comment not found |

**Side effects:** `commentCount` −1 with Redis write-through (comment reaction counter zeroed
too); `COMMENT_DELETED` SSE event with `parentCommentId` + fresh `commentReplyCount` and
counters.

### Hide / unhide a comment (moderation)

```
POST /api/v1/researches/{researchId}/comments/{commentId}/hide
```

```
POST /api/v1/researches/{researchId}/comments/{commentId}/unhide
```

**Auth:** Bearer JWT — comment author **or** the research owner.

Hides a comment from public view without deleting it (sets `isHidden` / `hiddenAt`).
Hidden comments remain visible to the research owner in
[`GET /comments`](#list-comments). Hide is idempotent; unhide on a non-hidden comment
returns `400 NOT_HIDDEN`.

**Response — `204 No Content`** (both).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `COMMENT_DELETED` | Hiding a deleted comment |
| 400 | `NOT_HIDDEN` | Unhiding a non-hidden comment |
| 403 | `FORBIDDEN` | Neither comment author nor research owner |
| 404 | `RESOURCE_NOT_FOUND` | Comment not found |

### React to a comment

```
POST /api/v1/researches/{researchId}/comments/{commentId}/reactions
```

**Auth:** Bearer JWT.

Adds a LIKE on the comment. Idempotent — a repeat call is a DB no-op that still
re-broadcasts the authoritative count (refreshed from Postgres, not cache).

**Request body** (optional): `{ "reactionType": "LIKE" }`

**Response — `201 Created`** (empty body).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_INPUT` / `COMMENT_DELETED` | Bad input / deleted comment |
| 403 | `FORBIDDEN` / `RESEARCH_COMMENT_REACTION_BLOCKED_RELATIONSHIP` | Wrong research / block edge with comment author or researcher |
| 404 | `RESOURCE_NOT_FOUND` | Comment not found |
| 429 | — | Reaction rate limit |

**Side effects:** comment `likeCount` +1 (atomic) with Redis write-through;
`COMMENT_REACTION_ADDED` SSE event carrying `commentReactionCount` (and the deprecated
`commentLikeCount` alias); RabbitMQ event → notification to the comment author.

### Remove a comment reaction

```
DELETE /api/v1/researches/{researchId}/comments/{commentId}/reactions
```

**Auth:** Bearer JWT.

Removes the caller's LIKE from the comment. Idempotent — when there is no row to remove the
server still broadcasts the authoritative count so out-of-sync UIs reconcile.

**Response — `200 OK`** — updated `CommentResponse`.

**Side effects:** comment `likeCount` −1 (clamped at 0) when a row existed;
`COMMENT_REACTION_REMOVED` SSE event with `commentReactionCount`.

---

## Save / bookmark

### Save

```
POST /api/v1/researches/{researchId}/save
```

**Auth:** Bearer JWT.

Bookmarks the research into a named collection (default collection `"Default"`).
**Idempotent** — already-saved returns success with the current state, and a **concurrent
duplicate save no longer errors**: the racing insert that hits the unique constraint is
treated as idempotent success.

| Param | In | Required | Notes |
|---|---|---|---|
| `collection` | query | no | Collection name; blank → `"Default"` |

**Response — `201 Created`** — `ResearchResponse` with `currentUserSaved: true`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_INPUT` / `NOT_PUBLISHED` | Bad input / paper not published |
| 403 | `RESEARCH_SAVE_BLOCKED_RELATIONSHIP` | Block edge with the researcher |
| 404 | `RESOURCE_NOT_FOUND` | Research not found |
| 429 | — | Social-action rate limit |

**Side effects:** `saveCount` +1 (skipped on idempotent repeat); `SAVE_COUNT_UPDATED` SSE
event with fresh counters; the save is recorded on the user's activity history (saves only —
unsaves are not logged). Saved lists are served from
[`GET /me/saved`](./feeds-discovery.md#saved-papers).

### Unsave

```
DELETE /api/v1/researches/{researchId}/save
```

**Auth:** Bearer JWT.

Idempotent — removing a non-existent save still returns `200` and re-broadcasts the
authoritative count.

**Response — `200 OK`** — `ResearchResponse` with `currentUserSaved: false`.

**Side effects:** `saveCount` −1 (when a row existed); `SAVE_COUNT_UPDATED` SSE event.

---

## Views

### Record a view

```
POST /api/v1/researches/{researchId}/view
```

**Auth:** none (public). Optional JWT switches to per-user dedupe.

Fire-and-forget when the detail page opens. **Deduped server-side**:

- **Authenticated** — counted once per `(research, user)` **forever** via a durable
  `research_views` ledger; re-opening the paper a year later is still a single view.
- **Anonymous** — 1-hour Redis dedupe keyed by client IP (`X-Forwarded-For` →
  `X-Real-IP` → remote address). Fail-open when Redis is down.

View counting is best-effort: a counter failure never breaks the request.

**Response — `200 OK`** (empty body).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_RESEARCH_ID` | Malformed id |

**Side effects (first view only):** `viewCount` +1; Redis counter write-through;
`VIEW_COUNT_UPDATED` SSE event with the fresh absolute count.

---

## Downloads

### Record a download and get the file URL

```
POST /api/v1/researches/{researchId}/download
```

**Auth:** none (public). Optional JWT switches to per-user dedupe.

Records a download of a **specific media file** and returns a **30-minute pre-signed URL**
the client fetches to get the bytes.

| Param | In | Required | Notes |
|---|---|---|---|
| `mediaId` | query | **yes** | Downloads are tracked per physical file (PDF / video / audio / zip / book), not per research. Missing → `400 MISSING_MEDIA_ID`. |

**Counter dedupe is server-side:** re-clicking (double-clicks, React StrictMode dev
double-fires, retries) still returns a URL — the user always gets their file — but the
counter bumps **at most once** per `(research, media, user)` per **90 days** for
authenticated callers, and once per `(research, media, IP)` per **1 hour** for anonymous.
Fail-open when Redis is down.

**Response — `200 OK`:**

```json
{ "url": "https://r2.example.com/research/R-uuid/media/paper.pdf?X-Amz-Signature=…" }
```

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_RESEARCH_ID` / `MISSING_MEDIA_ID` | Missing ids |
| 400 | `NOT_PUBLISHED` | Paper isn't published |
| 400 | `DOWNLOADS_DISABLED` | `downloadsEnabled: false` on the paper |
| 400 | `FILE_NOT_AVAILABLE` | Media row has no stored file |
| 403 | `FORBIDDEN` | Media belongs to a different research |
| 404 | `RESOURCE_NOT_FOUND` | Research or media not found |
| 500 | `URL_GENERATION_ERROR` | Pre-signed URL generation failed |

**Side effects (counted downloads only):** a download event is published to RabbitMQ →
`downloadCount` +1 and a `DOWNLOAD_COUNT_UPDATED` SSE event a moment later. Deduped repeats
skip the event entirely (no DB row, no counter bump, no broadcast).
