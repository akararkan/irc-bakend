# Admin API Reference — Content & Moderation

API reference for the two content-plane admin controllers:

- `app/admin/content/AdminContentController` — base path **`/api/v1/admin/content`** (posts, comments, stories, highlight pins, platform keyword blocklist)
- `app/admin/moderation/AdminModerationController` — base path **`/api/v1/admin/moderation`** (unified moderation queue, bulk actions)

Concepts, policy, and data-flow: [../content-moderation.md](../content-moderation.md). Dashboard integration (auth, step-up UX, retry flow): [../frontend-dashboard-guide.md](../frontend-dashboard-guide.md). Error envelope (`ApiErrorResponse`, `errorCode` branching): [../../errors/frontend-error-handling.md](../../errors/frontend-error-handling.md).

**Conventions (apply to every endpoint below)**

- **Auth**: Bearer JWT. Both controllers are class-annotated `@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")` — any other role gets a 403 envelope.
- **Step-up**: endpoints marked `@RequiresStepUp` additionally require a fresh step-up marker (Redis `stepup:{userId}`, default TTL 300 s). Missing marker → `403 STEP_UP_REQUIRED`. Arm it with `POST /api/v1/security/step-up` (`{"password": "…"}` or `{"code": "…"}`) and retry — see the [frontend guide](../frontend-dashboard-guide.md).
- **Null omission**: the app sets `spring.jackson.default-property-inclusion: non_null` globally (and `QueueRow`/`BulkResult` repeat it via `@JsonInclude(NON_NULL)`) — **any null field is absent from the JSON**, so type everything optional.
- **Timestamps**: UTC ISO-8601 with `Z` suffix. `LocalDateTime` fields are forced to `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`; `Instant` fields serialize as standard ISO instants (may carry micro/nanosecond precision).
- **Page sizes**: every `pageSize` is clamped server-side to `[1, 100]` (`Pages.clamp`).
- **Audit**: every mutation writes an `ADMIN_*` audit row via `AdminAuditor`; verdicts additionally write the [decision log](#decisions-log--evidence).

---

## Posts browse & inspect

Posts live in Cassandra partitioned by author — there is **no global post index**, so browsing is always author-scoped (find the author via user search or a report first).

### GET /api/v1/admin/content/posts

Author-scoped keyset browse of posts/reels/etc., newest first.

**Access**: ADMIN or MODERATOR. No step-up.

**Params**

| Param | In | Type | Default | Constraints / notes |
|---|---|---|---|---|
| `authorId` | query | UUID | — | **Required.** Partition anchor. |
| `status` | query | string | — | Optional case-insensitive exact match on `PostStatus`: `DRAFT`, `PUBLISHED`, `ARCHIVED`, `REMOVED`. Applied after the Cassandra slice, so a filtered page may return fewer than `pageSize` rows. |
| `cursor` | query | ISO date-time | — | Optional keyset cursor, e.g. `2026-08-05T14:23:05` (`LocalDateTime`, **no zone suffix**, interpreted as UTC). Returns rows with `createdAt` strictly older. Pass the `createdAt` of the last row of the previous page. |
| `pageSize` | query | int | `20` | Clamped to `[1, 100]`. |

**Request body**: None.

**Response** — `200 OK`, a plain JSON array of `AdminPostRow` (no page wrapper; empty array when exhausted):

```json
[
  {
    "id": "7f3d9a2c-1b4e-4c8a-9f6d-5e2a8b7c4d10",
    "authorId": "c1a2b3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
    "postType": "REEL",
    "status": "PUBLISHED",
    "visibility": "PUBLIC",
    "textContent": "New tafsir series — part 3 #tafsir",
    "mediaUrls": ["https://cdn.example.com/media/7f3d9a2c/reel.mp4"],
    "createdAt": "2026-08-05T14:23:05.123456Z",
    "updatedAt": "2026-08-05T14:23:05.123456Z",
    "counters": {
      "reactions": 128,
      "comments": 12,
      "shares": 5,
      "views": 3400,
      "saves": 22
    }
  }
]
```

- `postType`: `TEXT`, `EMBEDDED`, `VOICE_POST`, `REEL`, `REPOST`, `STORY`. `visibility`: `PUBLIC`, `FOLLOWERS_ONLY`, `ONLY_ME`.
- `counters` always has exactly the five keys `reactions`, `comments`, `shares`, `views`, `saves` (missing counts coerced to `0`); it is `{}` when the post has no counter row. Built with `Map.of` — key order on the wire is not guaranteed.
- `textContent`, `mediaUrls`, `updatedAt` are omitted when null (global NON_NULL).

**Errors**

- `AUTHOR_SCOPE_REQUIRED` — 400 — `authorId` missing ("posts are partitioned by author"). In practice Spring also rejects the absent required param with a 400 before the service check.
- Envelope shape: [frontend-error-handling.md](../../errors/frontend-error-handling.md).

### GET /api/v1/admin/content/posts/{postId}

Single-post inspect (any status, including `REMOVED`).

**Access**: ADMIN or MODERATOR. No step-up.

**Params**

| Param | In | Type | Notes |
|---|---|---|---|
| `postId` | path | UUID | — |

**Request body**: None.

**Response** — `200 OK`, one `AdminPostRow` (same shape as above).

**Errors**

- `POST_NOT_FOUND` — 404 — no such post id (`details`: `{"resource":"Post","field":"id","value":"…"}`).

---

## Takedown / restore

Post takedown is the **reversible** moderation primitive: `PUBLISHED → REMOVED → PUBLISHED`. Counters and reactions are retained across the round-trip by design, so a restore keeps engagement intact.

### POST /api/v1/admin/content/posts/{postId}/remove

Take a post (or reel) down: sets `status=REMOVED`, de-indexes it from search, removes trending/hashtag tags, and drops the reel from `reels_by_day` when `postType=REEL`. Idempotent — removing an already-`REMOVED` post is a silent no-op `204`.

**Access**: ADMIN or MODERATOR. **`@RequiresStepUp`.**

**Params**

| Param | In | Type | Notes |
|---|---|---|---|
| `postId` | path | UUID | — |

**Request body** — optional (`ModerationActionRequest`; both fields optional, whole body may be absent):

```json
{
  "reason": "Hate speech — targeted harassment of a user",
  "reportId": "5d1e8f3b-7a2c-4d9e-b6f0-1c3a5e7d9b21"
}
```

- `reason`: max 500 chars (truncated to 500 at persist). Included in the author's notification when present.
- `reportId`: optional link back to the originating report; stored on the decision row.

**Response** — 204 No Content.

**Side effects**: decision row `ADMIN_POST_REMOVE` (metadata `type={postType}`), audit row `ADMIN_POST_REMOVE`, system notification to the author ("Your post was removed").

**Errors**

- `POST_NOT_FOUND` — 404 — no such post.
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.

### POST /api/v1/admin/content/posts/{postId}/restore

Reverse a takedown: `REMOVED → PUBLISHED`, re-index into search, re-tag hashtags (as `REEL` or `POST` scope), and re-insert the `reels_by_day` row for reels.

**Access**: ADMIN or MODERATOR. No step-up.

**Params**

| Param | In | Type | Notes |
|---|---|---|---|
| `postId` | path | UUID | — |

**Request body**: None.

**Response** — 204 No Content.

**Side effects**: decision row `ADMIN_POST_RESTORE`, audit row `ADMIN_POST_RESTORE`, system notification to the author ("Your post was restored").

**Errors**

- `POST_NOT_FOUND` — 404 — no such post.
- `POST_NOT_REMOVED` — 400 — post is not currently `REMOVED` ("Only REMOVED posts can be restored.").

---

## Comments & stories

Comment and story deletion are **irreversible** (comments hard-delete with their reply subtree; stories TTL out), so both are step-up-gated and snapshot-first: a `moderation_evidence` row is frozen **before** the destructive write (see [Decisions log & evidence](#decisions-log--evidence)).

### DELETE /api/v1/admin/content/comments/{commentId}

Hard-delete a comment via the author's own delete path (identical tombstone + counter mechanics: counter drops by `1 + replyCount`, replies range-deleted).

**Access**: ADMIN or MODERATOR. **`@RequiresStepUp`.**

**Params**

| Param | In | Type | Notes |
|---|---|---|---|
| `commentId` | path | UUID | — |

**Request body** — optional, same `ModerationActionRequest` shape as post takedown:

```json
{
  "reason": "Spam links",
  "reportId": "5d1e8f3b-7a2c-4d9e-b6f0-1c3a5e7d9b21"
}
```

**Response** — 204 No Content.

**Side effects**: evidence snapshot (`COMMENT` payload — best-effort), decision row `ADMIN_COMMENT_DELETE`, audit row `ADMIN_COMMENT_DELETE`, system notification to the author.

**Errors**

- `COMMENT_NOT_FOUND` — 404 — no such comment.
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.

### DELETE /api/v1/admin/content/stories/{storyId}

Delete a story through the author path (story rows + poll rows + tray fan-out), then sweep and remove **every highlight pin** of that story — expired stories live on inside highlights, so the sweep is what actually purges old content.

**Access**: ADMIN or MODERATOR. **`@RequiresStepUp`.**

**Params**

| Param | In | Type | Notes |
|---|---|---|---|
| `storyId` | path | UUID | — |

**Request body** — optional, same `ModerationActionRequest` shape:

```json
{
  "reason": "Nudity",
  "reportId": "5d1e8f3b-7a2c-4d9e-b6f0-1c3a5e7d9b21"
}
```

**Response** — 204 No Content.

**Side effects**: evidence snapshot (`STORY` payload), decision row `ADMIN_STORY_DELETE` (metadata `highlightPinsRemoved={n}`), audit row `ADMIN_STORY_DELETE`, system notification to the author.

**Errors**

- `STORY_NOT_FOUND` — 404 — no such story.
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.

### DELETE /api/v1/admin/content/highlights/{highlightId}/stories/{storyId}

Unpin a single story from one highlight (the surgical alternative to deleting the story). The story itself is untouched.

**Access**: ADMIN or MODERATOR. No step-up.

**Params**

| Param | In | Type | Notes |
|---|---|---|---|
| `highlightId` | path | UUID | — |
| `storyId` | path | UUID | — |

**Request body** — optional `ModerationActionRequest`; only `reason` is used here (`reportId` is accepted but ignored):

```json
{ "reason": "Policy-violating story pinned in a public highlight" }
```

**Response** — 204 No Content.

**Side effects**: decision row `ADMIN_HIGHLIGHT_ITEM_REMOVE` (targetRef `{highlightId}:{storyId}`), audit row `ADMIN_HIGHLIGHT_ITEM_REMOVE`. No author notification.

**Errors**

- `HIGHLIGHTITEM_NOT_FOUND` — 404 — the story is not pinned in that highlight.

---

## Blocklist

Platform-wide keyword blocklist, enforced at content-create time (unlike the per-user `hidden_keywords`, which is display-side). Keywords are normalized with the Arabic/Kurdish-aware `KeywordNormalizer`, matching is normalized-substring (`contains`). Severity semantics: **`BLOCK`** rejects content at create (`400 CONTENT_BLOCKED_BY_POLICY` to the posting user); **`FLAG`** lets it publish but emits a hit into the [moderation queue](#moderation-queue). Enforcement is fail-open on infrastructure errors and cached in Redis for 60 s — edits take up to a minute to bite.

### GET /api/v1/admin/content/blocklist

List all blocklist entries.

**Access**: ADMIN or MODERATOR. No step-up.

**Request body**: None.

**Response** — `200 OK`, JSON array of `PlatformKeyword` entities:

```json
[
  {
    "id": "3e8b1f6a-2c4d-4e5f-9a7b-8c6d5e4f3a21",
    "keywordDisplay": "buy followers",
    "keywordNormalized": "buy followers",
    "severity": "BLOCK",
    "note": "engagement-fraud spam",
    "addedBy": "c1a2b3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
    "createdAt": "2026-08-01T09:12:45.000Z"
  }
]
```

`severity` ∈ `FLAG` | `BLOCK`. `note` and `addedBy` are omitted when null.

**Errors**: none specific.

### POST /api/v1/admin/content/blocklist

Add a keyword. Upsert on the normalized form: if the normalized keyword already exists, its `severity` and `note` are updated instead (response is still `201` with the updated row).

**Access**: ADMIN or MODERATOR. **`@RequiresStepUp`** (BLOCK severity gates publishing platform-wide).

**Request body** (`KeywordRequest`):

```json
{
  "keyword": "buy followers",
  "severity": "BLOCK",
  "note": "engagement-fraud spam"
}
```

- `keyword`: required, trimmed; display form truncated to 100 chars.
- `severity`: `FLAG` | `BLOCK`; omitted/null defaults to `FLAG`.
- `note`: optional, max 200 chars.

**Response** — `201 Created`, the saved `PlatformKeyword` (same shape as the list item above).

**Side effects**: Redis cache invalidation, audit row `ADMIN_BLOCKLIST_ADD`.

**Errors**

- `INVALID_INPUT` — 400 — blank/missing `keyword` ("keyword is required").
- `INVALID_KEYWORD` — 400 — "Keyword normalizes to nothing." (e.g. punctuation-only input).
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.

### PATCH /api/v1/admin/content/blocklist/{id}

Update severity and/or note of an existing entry. The `keyword` field of the body is ignored — the keyword itself is immutable (delete + re-add to change it). Null fields are left unchanged.

**Access**: ADMIN or MODERATOR. No step-up.

**Params**

| Param | In | Type | Notes |
|---|---|---|---|
| `id` | path | UUID | Blocklist entry id. |

**Request body** (`KeywordRequest`; send only what you change):

```json
{
  "severity": "FLAG",
  "note": "downgraded — too many false positives"
}
```

**Response** — `200 OK`, the updated `PlatformKeyword`.

**Side effects**: cache invalidation, audit row `ADMIN_BLOCKLIST_UPDATE`.

**Errors**

- `PLATFORMKEYWORD_NOT_FOUND` — 404 — no such entry.

### DELETE /api/v1/admin/content/blocklist/{id}

Remove a blocklist entry.

**Access**: ADMIN or MODERATOR. No step-up.

**Params**

| Param | In | Type | Notes |
|---|---|---|---|
| `id` | path | UUID | Blocklist entry id. |

**Request body**: None.

**Response** — 204 No Content.

**Side effects**: cache invalidation, audit row `ADMIN_BLOCKLIST_REMOVE`.

**Errors**

- `PLATFORMKEYWORD_NOT_FOUND` — 404 — no such entry.

### POST /api/v1/admin/content/blocklist/test

Dry-run the normalizer's contains-scan against sample text — see which entries would match, without publishing anything.

**Access**: ADMIN or MODERATOR. No step-up.

**Request body** (`KeywordTestRequest`):

```json
{ "text": "DM me to buy followers cheap!!" }
```

**Response** — `200 OK`. Keys (in code order): `matches` — list of `"{keywordDisplay} ({severity})"` strings — and `matched`:

```json
{
  "matches": ["buy followers (BLOCK)"],
  "matched": true
}
```

No match → `{"matches": [], "matched": false}`.

**Errors**: none specific.

---

## Moderation queue

The unified inbox: three feeders flattened into one row shape (`QueueRow`).

### GET /api/v1/admin/moderation/queue

Fetch queue rows from up to three sources: **reports** (open content reports grouped by `(targetType, targetId, reason)`), **media** (`FAILED_MODERATION` media assets), **keywords** (unresolved FLAG-keyword hits).

**Access**: ADMIN or MODERATOR. No step-up.

**Params**

| Param | In | Type | Default | Constraints / notes |
|---|---|---|---|---|
| `source` | query | string | — | Optional filter: `reports`, `media`, or `keywords` (case-insensitive). Omitted = all three feeders. |
| `targetType` | query | string | — | Optional; uppercased server-side; applies **only to the reports feeder** (`ReportTargetType`: `USER`, `POST`, `COMMENT`, `RESEARCH`, `QUESTION`, `ANSWER`, `MESSAGE`, `CHANNEL`, `STORY`). When omitted, report groups are filtered to the content plane (`POST`/`COMMENT`/`STORY`); pass it explicitly to see other planes. Media/keyword rows are unaffected by it. |
| `page` | query | int | `0` | Applied **per feeder**. |
| `pageSize` | query | int | `50` | Clamped to `[1, 100]`, per feeder — a combined page can hold up to 3×`pageSize` rows. |

**Request body**: None.

**Response** — `200 OK`, JSON array of `QueueRow` in feeder order — reports (oldest group first), then media (newest first), then keywords (oldest hit first); there is no global sort. `@JsonInclude(NON_NULL)` — null timestamps are omitted. One realistic row per source:

```json
[
  {
    "source": "reports",
    "targetType": "POST",
    "targetRef": "7f3d9a2c-1b4e-4c8a-9f6d-5e2a8b7c4d10",
    "reason": "HARASSMENT",
    "reportCount": 4,
    "state": "SUBMITTED",
    "firstSeen": "2026-08-05T09:14:22.000Z",
    "lastSeen": "2026-08-06T18:40:03.000Z"
  },
  {
    "source": "media",
    "targetType": "MEDIA",
    "targetRef": "b4c5d6e7-f8a9-4b0c-8d1e-2f3a4b5c6d7e",
    "reason": "FAILED_MODERATION",
    "reportCount": 1,
    "state": "FAILED_MODERATION",
    "firstSeen": "2026-08-06T11:02:17.000Z",
    "lastSeen": "2026-08-06T11:05:44.000Z"
  },
  {
    "source": "keywords",
    "targetType": "POST",
    "targetRef": "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d",
    "reason": "KEYWORD:buy followers",
    "reportCount": 1,
    "state": "FLAGGED",
    "firstSeen": "2026-08-06T12:30:00.000Z",
    "lastSeen": "2026-08-06T12:30:00.000Z"
  }
]
```

Per-source field semantics:

| Field | reports | media | keywords |
|---|---|---|---|
| `targetType` | report target type | always `MEDIA` | flagged content's type |
| `targetRef` | target entity id (string) | media asset id | flagged content id |
| `reason` | `ReportReason` (`SPAM`, `HARASSMENT`, `HATE_SPEECH`, `MISINFORMATION`, `NUDITY_SEXUAL`, `VIOLENCE`, `IMPERSONATION`, `SELF_HARM`, `COPYRIGHT`, `OTHER`) | literal `FAILED_MODERATION` | `KEYWORD:{normalizedKeyword}` |
| `reportCount` | total reports in the group | `1` | `1` |
| `state` | group's min open state (`SUBMITTED` or `TRIAGED`) | `FAILED_MODERATION` (`MediaStatus`) | literal `FLAGGED` |
| `firstSeen` / `lastSeen` | oldest / newest report `createdAt` | asset `createdAt` / `updatedAt` | hit `createdAt` (both) |

**Errors**: none specific.

**Note**: keyword rows have no id in the row shape — resolving a hit (below) requires the hit id, which currently must be correlated out-of-band (e.g. from the DB); acting on the *content* instead (takedown/delete) uses `targetRef` directly.

### POST /api/v1/admin/moderation/queue/keywords/{hitId}/resolve

Mark a keyword hit handled — it disappears from the queue's `keywords` feeder. Unconditionally `204`: an unknown `hitId` is a silent no-op (no 404).

**Access**: ADMIN or MODERATOR. No step-up.

**Params**

| Param | In | Type | Notes |
|---|---|---|---|
| `hitId` | path | UUID | `platform_keyword_hits.id`. |

**Request body**: None.

**Response** — 204 No Content.

**Errors**: none specific (missing hit is a no-op).

---

## Bulk actions

### POST /api/v1/admin/moderation/bulk

Run one moderation action over up to 100 targets. Each target is processed independently through the same singular service paths documented above (identical side effects: decision rows, audit rows, notifications, evidence snapshots); one bad target never aborts the rest.

**Access**: ADMIN or MODERATOR. **`@RequiresStepUp`.**

**Request body** (`BulkRequest`):

```json
{
  "action": "TAKEDOWN",
  "targets": [
    { "type": "POST", "id": "7f3d9a2c-1b4e-4c8a-9f6d-5e2a8b7c4d10" },
    { "type": "POST", "id": "0c3e7d2e-5f4b-4a6c-9d8e-3b2c4d5e6f70" }
  ],
  "reason": "Coordinated spam ring"
}
```

- `action` (required, trimmed, case-insensitive) with allowed target `type` per action:

| Action | Target type | Delegates to |
|---|---|---|
| `TAKEDOWN` | `POST` | post remove (reversible) |
| `RESTORE` | `POST` | post restore |
| `DELETE` | `COMMENT` or `STORY` | irreversible delete (snapshot-first) |
| `SOUND_APPROVE` | `SOUND` | sound approval |
| `SOUND_REJECT` | `SOUND` | sound rejection (`reason` doubles as the reject reason code) |

- `targets`: 1–100 `{type, id}` pairs; `type` is trimmed/uppercased, `id` must be a UUID string.
- `reason`: optional, max 500 chars; forwarded to `TAKEDOWN` / `DELETE` / `SOUND_REJECT` (ignored by `RESTORE` / `SOUND_APPROVE`).

**Response** — `200 OK` **always** (even if every target fails); per-target `BulkResult` list in input order. `@JsonInclude(NON_NULL)` — `error` is omitted on `"ok"` rows:

```json
[
  { "type": "POST", "id": "7f3d9a2c-1b4e-4c8a-9f6d-5e2a8b7c4d10", "outcome": "ok" },
  {
    "type": "POST",
    "id": "0c3e7d2e-5f4b-4a6c-9d8e-3b2c4d5e6f70",
    "outcome": "error",
    "error": "Post not found with id: 0c3e7d2e-5f4b-4a6c-9d8e-3b2c4d5e6f70"
  }
]
```

Per-target failures are caught and reported as `outcome: "error"` rows with the exception message — including what would be envelope errors on the singular endpoints: unknown action (`INVALID_BULK_ACTION` message "Unknown action. Allowed: TAKEDOWN, RESTORE, DELETE, SOUND_APPROVE, SOUND_REJECT."), wrong target type (`INVALID_BULK_TARGET`, e.g. "This action needs a POST target." / "DELETE supports COMMENT and STORY targets."), non-UUID id, `*_NOT_FOUND`, `POST_NOT_REMOVED`, sound-state errors. **None of these produce a 4xx** — inspect `outcome` per row.

**Side effects**: one audit row `ADMIN_MODERATION_BULK` summarizing `{action} on {n} targets, ok={k}`, plus the per-target side effects of each delegated action.

**Errors**

- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker (the only pre-flight failure besides auth; everything else is per-row).

---

## Decisions log & evidence

Not an HTTP surface (no read endpoint yet) — this is the **write-side ledger** every endpoint above feeds, documented here so you know what each call leaves behind. Writer: `app/admin/moderation/ModerationRecorder`. Decisions are written inline (a verdict without its log row is a defect); evidence snapshots are best-effort (a snapshot failure never blocks a takedown).

### `moderation_decisions` — one row per verdict, never deleted

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Generated. |
| `target_type` | string(30) | `POST`, `COMMENT`, `STORY`, `HIGHLIGHT_ITEM`, `SOUND`, … |
| `target_ref` | string(64) | Target id as a string (fits non-UUID refs such as chat Snowflakes). |
| `action` | string(60) | Blueprint audit action — see mapping below. |
| `reason` | string(500), nullable | Truncated to 500. |
| `report_id` | UUID, nullable | Originating report, when supplied. |
| `actor_id` | UUID, nullable | The admin who acted. |
| `metadata` | string(500), nullable | Free-form context snapshot. |
| `created_at` | timestamp | Set at persist. |

Endpoint → decision row:

| Endpoint | `action` | `metadata` |
|---|---|---|
| `POST …/posts/{id}/remove` (+ bulk `TAKEDOWN`) | `ADMIN_POST_REMOVE` | `type={postType}` |
| `POST …/posts/{id}/restore` (+ bulk `RESTORE`) | `ADMIN_POST_RESTORE` | — |
| `DELETE …/comments/{id}` (+ bulk `DELETE COMMENT`) | `ADMIN_COMMENT_DELETE` | — |
| `DELETE …/stories/{id}` (+ bulk `DELETE STORY`) | `ADMIN_STORY_DELETE` | `highlightPinsRemoved={n}` |
| `DELETE …/highlights/{hid}/stories/{sid}` | `ADMIN_HIGHLIGHT_ITEM_REMOVE` (targetRef `{hid}:{sid}`) | — |
| bulk `SOUND_APPROVE` / `SOUND_REJECT` | `ADMIN_SOUND_APPROVE` / `ADMIN_SOUND_REJECT` | sound blast-radius snapshot |

### `moderation_evidence` — pre-deletion snapshots, append-only

Frozen **before** irreversible deletes (comments hard-delete with their reply subtree; stories TTL out). Text and references only — media bytes are never copied. No admin endpoint may mutate it.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Generated. |
| `target_type` | string(30) | `COMMENT` or `STORY` (from these endpoints). |
| `target_ref` | string(64) | Deleted entity id. |
| `author_id` | UUID, nullable | Content author. |
| `payload` | text | JSON snapshot — shapes below. |
| `media_refs` | text, nullable | Comma-separated media/R2 references (unused by these endpoints — always null here). |
| `captured_by` | UUID, nullable | The admin. |
| `captured_at` | timestamp | Set at persist. |

Payload shapes as written by `AdminContentService`:

```json
{ "postId": "7f3d9a2c-…", "authorId": "c1a2b3d4-…", "text": "the comment text", "reply": false }
```

(comment delete — `text` is `null` for replies, whose body is not re-read before the range-delete)

```json
{ "authorId": "c1a2b3d4-…", "visibility": "PUBLIC", "expiresAt": "2026-08-07T12:30:00Z" }
```

(story delete)
