# Admin API Reference — Research, Q&A, Tags & Knowledge

Complete request/response reference for five admin controllers:

| Controller | Base path |
|---|---|
| `AdminResearchController` (`app/admin/research`) | `/api/v1/admin/research` |
| `AdminQnaController` (`app/admin/qna`) | `/api/v1/admin/qna` |
| `AdminTrendingController` (`app/admin/trending`) | `/api/v1/admin/trending` |
| `TagAdminController` (`app/common/tag/controller`) | `/api/v1/admin/tags` |
| `AdminKnowledgeController` (`app/admin/knowledge`) | `/api/v1/admin/knowledge` |

Concepts and workflows: [../research-qna.md](../content/research-qna.md) (research & Q&A oversight), [../knowledge-vocabulary.md](../content/knowledge-vocabulary.md) (topics/madhhabs taxonomy), [../frontend-dashboard-guide.md](../frontend/README.md) (UI build guide). All errors arrive in the canonical envelope — see [../../errors/frontend-error-handling.md](../../errors/frontend-error-handling.md).

**Conventions used below**

- **Auth**: every route needs a Bearer JWT. Missing/expired token → `401`. Role not matched by the `@PreAuthorize` gate → `403 ACCESS_DENIED`.
- **Step-up**: endpoints marked *step-up required* are guarded by `@RequiresStepUp`. Arm a fresh marker via `POST /api/v1/security/step-up` first; absent marker → `403 STEP_UP_REQUIRED`.
- **Paging**: `page` (0-based) and `size` query params; default `size=25`, clamped server-side to **1..100**. `Page<T>` examples are truncated to `{"content":[…],"totalElements":N,"totalPages":N,"number":0,"size":25}` — Spring also emits `pageable`, `sort`, `first`, `last`, `numberOfElements`, `empty`, which are omitted here.
- **Timestamps**: JPA `LocalDateTime` fields serialize without a zone (`"2026-08-06T14:30:00"`); Cassandra `Instant`/`OffsetDateTime` fields are UTC `Z`-suffixed.
- **Bean validation** failures (`@NotBlank`, `@Size`) → `400 VALIDATION_FAILED` with a `fieldErrors` array.
- Every mutation writes an `ADMIN_*` row through `AdminAuditor`; research/Q&A takedowns additionally record a moderation decision.

---

## 1. Research moderation — `AdminResearchController`

Base `/api/v1/admin/research`. Class gate: `@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")`. Mutations reuse the owner-path `ResearchService` transitions (acting with the owner's id), so ES removal, cache eviction, trending untag and S3 cleanup match the owner flows exactly.

**`AdminResearchRow`** (used by browse/top/detail) is `@JsonInclude(NON_NULL)` — null fields are omitted (e.g. `scheduledPublishAt` for non-scheduled papers, `publishedAt` for drafts):

```json
{
  "id": "8c1f4c1e-2b6a-4f0e-9d3a-5e7b2a1c9f04",
  "title": "Isnad Analysis of Early Kufan Transmissions",
  "ircId": "IRC-2026-000042",
  "status": "PUBLISHED",
  "visibility": "PUBLIC",
  "researcherId": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
  "researcherUsername": "dr_amina",
  "viewCount": 1204,
  "downloadCount": 310,
  "reactionCount": 88,
  "commentCount": 17,
  "citationCount": 5,
  "publishedAt": "2026-07-30T09:15:00",
  "createdAt": "2026-07-28T21:04:11"
}
```

`status` ∈ `DRAFT | PUBLISHED | ARCHIVED | RETRACTED`; `visibility` ∈ `PUBLIC | FOLLOWERS_ONLY | PRIVATE`; `ircId` format `IRC-{YEAR}-{6-digit-sequence}`.

### GET /api/v1/admin/research
Browse all non-deleted research, newest first, with optional filters.

**Access**: ADMIN or MODERATOR.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `status` | string | — | One of `DRAFT`, `PUBLISHED`, `ARCHIVED`, `RETRACTED` (case-insensitive) |
| `researcherId` | UUID | — | Filter by owner |
| `ircId` | string | — | Exact IRC identifier match |
| `q` | string | — | Case-insensitive substring match on title |
| `page` / `size` | int | 0 / 25 | Size clamped to 100 |

**Request body**: None.

**Response**: `200 OK`

```json
{
  "content": [ { "…": "AdminResearchRow — see above" } ],
  "totalElements": 412,
  "totalPages": 17,
  "number": 0,
  "size": 25
}
```

**Errors**
- `INVALID_STATUS` — 400 — `status` is not a `ResearchStatus` value.

### GET /api/v1/admin/research/top
Leaderboard of PUBLISHED research by downloads or citations.

**Access**: ADMIN or MODERATOR.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `by` | string | `downloads` | `downloads` or `citations` (case-insensitive) |
| `page` / `size` | int | 0 / 25 | Size clamped to 100 |

**Request body**: None.

**Response**: `200 OK` — same `Page<AdminResearchRow>` shape as browse, ordered by `downloadCount DESC` (or `citationCount DESC`), then `createdAt DESC`. Only `PUBLISHED`, non-deleted rows.

**Errors**
- `INVALID_SORT` — 400 — `by` is neither `downloads` nor `citations`.

### GET /api/v1/admin/research/flags
Open (unresolved) integrity flags, oldest first — the review queue.

**Access**: ADMIN or MODERATOR.

**Params**: `page` / `size` (default 25, clamped to 100).

**Request body**: None.

**Response**: `200 OK` — `Page<ResearchFlag>`. The entity has no `NON_NULL` filter, so null fields appear:

```json
{
  "content": [
    {
      "id": "b7e2d9a0-5c31-4f8e-a6d2-1e9f7c4b8a53",
      "researchId": "8c1f4c1e-2b6a-4f0e-9d3a-5e7b2a1c9f04",
      "type": "PLAGIARISM",
      "note": "Overlaps §2 of IRC-2025-000318 without citation",
      "flaggedBy": "0a4b8c1d-2e3f-4a5b-8c9d-6e7f0a1b2c3d",
      "createdAt": "2026-08-05T10:12:45",
      "resolvedAt": null,
      "resolutionNote": null
    }
  ],
  "totalElements": 3,
  "totalPages": 1,
  "number": 0,
  "size": 25
}
```

`type` ∈ `PLAGIARISM | QUALITY`.

### GET /api/v1/admin/research/{id}
Full detail for one research item: row + abstract + extra counters + its complete flag history.

**Access**: ADMIN or MODERATOR.

**Params**: `id` — research UUID (path).

**Request body**: None.

**Response**: `200 OK` — a `Map.of(...)`, so **key order is not guaranteed**:

```json
{
  "research": { "…": "AdminResearchRow — see above" },
  "slug": "isnad-analysis-of-early-kufan-transmissions",
  "abstractText": "This study examines…",
  "saveCount": 42,
  "shareCount": 9,
  "commentsEnabled": true,
  "downloadsEnabled": true,
  "flags": [ { "…": "ResearchFlag — see /flags above" } ]
}
```

`slug`/`abstractText` are `String.valueOf(...)` — a missing value serializes as the literal string `"null"`, never JSON null. `flags` includes resolved flags too, newest first.

**Errors**
- `RESEARCH_NOT_FOUND` — 404 — no non-deleted research with that id.

### GET /api/v1/admin/research/{id}/downloads
Recent download log rows (Cassandra `research_downloads_by_research`), newest first.

**Access**: ADMIN or MODERATOR.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `id` | UUID (path) | — | Research id |
| `limit` | int | 50 | Clamped to 1..100 |

**Request body**: None.

**Response**: `200 OK` — JSON array (not a Page); keys in this order:

```json
[
  {
    "downloadId": "f1e2d3c4-b5a6-4978-8899-aabbccddeeff",
    "userId": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
    "mediaId": "9d8c7b6a-5f4e-4d3c-b2a1-0f9e8d7c6b5a",
    "ipAddress": "203.0.113.42",
    "createdAt": "2026-08-06T09:41:22.318Z"
  }
]
```

**Errors**
- `RESEARCH_NOT_FOUND` — 404.

### POST /api/v1/admin/research/{id}/unpublish
Revert a PUBLISHED paper to `DRAFT` (clears `publishedAt`, removes from ES/trending). Owner gets a system notification.

**Access**: ADMIN or MODERATOR. **Step-up required.**

**Params**: `id` — research UUID (path).

**Request body** (optional — may be omitted entirely):

```json
{ "reason": "Copyright claim under review" }
```

`reason` max 500 chars; when present it is appended to the owner notification and stored on the moderation decision + audit rows.

**Response**: `204 No Content`.

**Errors**
- `RESEARCH_NOT_FOUND` — 404.
- `NOT_PUBLISHED` — 400 — research is not currently `PUBLISHED`.
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.

### POST /api/v1/admin/research/{id}/retract
Mark a PUBLISHED paper `RETRACTED` (permanent scholarly-record state; removed from ES/trending). Owner notified.

**Access**: ADMIN or MODERATOR. **Step-up required.**

**Params**: `id` — research UUID (path).

**Request body** (optional): same `ReasonBody` as unpublish — `{ "reason": "…" }`.

**Response**: `204 No Content`.

**Errors**
- `RESEARCH_NOT_FOUND` — 404.
- `NOT_PUBLISHED` — 400 — only `PUBLISHED` research can be retracted.
- `STEP_UP_REQUIRED` — 403.

### DELETE /api/v1/admin/research/{id}
Hard-delete a research item via the owner-path cascade (S3 files, Postgres child rows, Cassandra engagement, ES, trending tags). Owner notified.

**Access**: ADMIN or MODERATOR. **Step-up required.**

**Params**: `id` — research UUID (path).

**Request body** (optional): `{ "reason": "…" }` (max 500).

**Response**: `204 No Content`.

**Errors**
- `RESEARCH_NOT_FOUND` — 404.
- `STEP_UP_REQUIRED` — 403.

### POST /api/v1/admin/research/{id}/flags
Raise an integrity flag (does not alter the research; feeds the review queue).

**Access**: ADMIN or MODERATOR.

**Params**: `id` — research UUID (path).

**Request body**:

```json
{ "type": "PLAGIARISM", "note": "Overlaps §2 of IRC-2025-000318 without citation" }
```

`type` required, `PLAGIARISM` or `QUALITY`; `note` optional, max 500.

**Response**: `201 Created` — the saved `ResearchFlag` (see `/flags` for the shape); `flaggedBy` is the calling admin, `resolvedAt`/`resolutionNote` null.

**Errors**
- `RESEARCH_NOT_FOUND` — 404.
- `INVALID_INPUT` — 400 — `type` missing ("type is required (PLAGIARISM or QUALITY).").

### POST /api/v1/admin/research/flags/{flagId}/resolve
Close a flag (sets `resolvedAt` now, optionally a resolution note).

**Access**: ADMIN or MODERATOR.

**Params**: `flagId` — flag UUID (path).

**Request body** (optional): `{ "reason": "Citation added in v2 — no action" }` → stored as `resolutionNote`.

**Response**: `200 OK` — the updated `ResearchFlag`:

```json
{
  "id": "b7e2d9a0-5c31-4f8e-a6d2-1e9f7c4b8a53",
  "researchId": "8c1f4c1e-2b6a-4f0e-9d3a-5e7b2a1c9f04",
  "type": "PLAGIARISM",
  "note": "Overlaps §2 of IRC-2025-000318 without citation",
  "flaggedBy": "0a4b8c1d-2e3f-4a5b-8c9d-6e7f0a1b2c3d",
  "createdAt": "2026-08-05T10:12:45",
  "resolvedAt": "2026-08-06T15:02:10",
  "resolutionNote": "Citation added in v2 — no action"
}
```

**Errors**
- `RESEARCHFLAG_NOT_FOUND` — 404.

---

## 2. Q&A moderation — `AdminQnaController`

Base `/api/v1/admin/qna`. Class gate: `@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")`.

> **MODERATOR caveat**: the service-layer bypass (`canManageQuestion`/`canManageAnswer`) only recognizes `Role.ADMIN` (or the content author). A MODERATOR passes the route gate but the four lifecycle mutations and answer delete then fail with `403 ACCESS_FORBIDDEN` from the service. Effectively: browse is MODERATOR-usable, mutations are ADMIN-only today.

**`AdminQuestionRow`** is `@JsonInclude(NON_NULL)` — null fields (e.g. `maxAnswers`) are omitted:

```json
{
  "id": "5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d",
  "title": "Is combining prayers permissible while traveling short distances?",
  "status": "ANSWERED",
  "authorId": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
  "authorUsername": "seeker_92",
  "answerCount": 4,
  "acceptedAnswerCount": 1,
  "answersLocked": false,
  "maxAnswers": 10,
  "viewCount": 356,
  "createdAt": "2026-08-01T18:22:03"
}
```

`status` ∈ `OPEN | ANSWERED | CLOSED | ARCHIVED`.

### GET /api/v1/admin/qna/questions
Browse all non-deleted questions, newest first, with optional filters.

**Access**: ADMIN or MODERATOR.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `status` | string | — | One of `OPEN`, `ANSWERED`, `CLOSED`, `ARCHIVED` (case-insensitive) |
| `authorId` | UUID | — | Filter by author |
| `q` | string | — | Case-insensitive substring match on title |
| `unanswered` | boolean | — | `true` → only `answerCount == 0`; `false` → only answered |
| `lockedOnly` | boolean | — | Filter on `answersLocked` |
| `page` / `size` | int | 0 / 25 | Size clamped to 100 |

**Request body**: None.

**Response**: `200 OK`

```json
{
  "content": [ { "…": "AdminQuestionRow — see above" } ],
  "totalElements": 96,
  "totalPages": 4,
  "number": 0,
  "size": 25
}
```

**Errors**
- `INVALID_STATUS` — 400 — `status` is not a `QuestionStatus` value.

### POST /api/v1/admin/qna/questions/{id}/close
Set a question to `CLOSED` (reason lands in the entity audit trail, moderation log and admin audit).

**Access**: ADMIN or MODERATOR route gate; **service bypass ADMIN-only** (see caveat).

**Params**: `id` — question UUID (path).

**Request body** (optional): `{ "reason": "Duplicate of an existing fatwa thread" }` (max 500).

**Response**: `204 No Content`.

**Errors**
- `QUESTION_NOT_FOUND` — 404.
- `ACCESS_FORBIDDEN` — 403 — caller is neither the author nor `Role.ADMIN`.

### POST /api/v1/admin/qna/questions/{id}/reopen
Reopen a closed/archived question — reverts to `ANSWERED` when it has answers, else `OPEN`.

**Access**: ADMIN or MODERATOR route gate; **service bypass ADMIN-only**.

**Params**: `id` — question UUID (path).

**Request body**: None.

**Response**: `204 No Content`.

**Errors**
- `QUESTION_NOT_FOUND` — 404.
- `ACCESS_FORBIDDEN` — 403.

### POST /api/v1/admin/qna/questions/{id}/archive
Set a question to `ARCHIVED`.

**Access**: ADMIN or MODERATOR route gate; **service bypass ADMIN-only**.

**Params**: `id` — question UUID (path).

**Request body**: None.

**Response**: `204 No Content`.

**Errors**
- `QUESTION_NOT_FOUND` — 404.
- `ACCESS_FORBIDDEN` — 403.

### DELETE /api/v1/admin/qna/questions/{id}
Delete a question with the full owner-path cascade (S3 attachments/sources/answer media sweep, Postgres child rows, ES removal).

**Access**: ADMIN or MODERATOR route gate; **service bypass ADMIN-only**. **Step-up required.**

**Params**: `id` — question UUID (path).

**Request body** (optional): `{ "reason": "Spam" }` (max 500).

**Response**: `204 No Content`.

**Errors**
- `QUESTION_NOT_FOUND` — 404.
- `ACCESS_FORBIDDEN` — 403.
- `STEP_UP_REQUIRED` — 403.

### DELETE /api/v1/admin/qna/answers/{answerId}
Soft-delete one answer: reactions purged, acceptance (if any) revoked with `acceptedAnswerCount` adjusted, and an answer-deleted event published (RabbitMQ fan-out).

**Access**: ADMIN or MODERATOR route gate; **service bypass ADMIN-only**. **Step-up required.**

**Params**: `answerId` — answer UUID (path). The parent question id is resolved server-side.

**Request body** (optional): `{ "reason": "Off-topic polemic" }` (max 500).

**Response**: `204 No Content`.

**Errors**
- `ANSWER_NOT_FOUND` — 404 — no non-deleted answer with that id.
- `QUESTION_NOT_FOUND` — 404 — parent question gone.
- `ACCESS_FORBIDDEN` — 403.
- `STEP_UP_REQUIRED` — 403.

---

## 3. Trending overrides — `AdminTrendingController`

Base `/api/v1/admin/trending`. Class gate: `@PreAuthorize("hasRole('ADMIN')")`. `PIN`/`BAN` overrides are consulted by `TrendingTagJob` on every snapshot rebuild (≤10 min refresh), so they take effect within one interval — or immediately after `POST /rebuild`.

**`TrendingTagOverride`** serializes with all fields (nulls included) **plus a derived `active` boolean** (from `isActive()`: not revoked and not expired):

```json
{
  "id": "c9d0e1f2-a3b4-4c5d-8e6f-7a8b9c0d1e2f",
  "tagNormalized": "hadith-grading",
  "scope": "QUESTION",
  "type": "PIN",
  "pinnedRank": 2,
  "reason": "Conference week spotlight",
  "createdBy": "0a4b8c1d-2e3f-4a5b-8c9d-6e7f0a1b2c3d",
  "expiresAt": "2026-09-01T00:00:00",
  "revokedAt": null,
  "createdAt": "2026-08-06T12:00:00",
  "active": true
}
```

`scope` ∈ `ALL | QUESTION | RESEARCH | POST | REEL` (`ALL` applies everywhere); `type` ∈ `PIN | BAN`; `pinnedRank` only meaningful for `PIN`.

### GET /api/v1/admin/trending/overrides
List overrides.

**Access**: ADMIN.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `active` | boolean | `true` | `true` → non-revoked only (expired-but-unrevoked rows still appear, with `"active": false`); `false` → all rows including revoked |

**Request body**: None.

**Response**: `200 OK` — JSON array of `TrendingTagOverride` (see above). Not paged.

### POST /api/v1/admin/trending/overrides
Create a PIN or BAN override.

**Access**: ADMIN. **Step-up required.**

**Request body**:

```json
{
  "tag": "#hadith-grading",
  "scope": "QUESTION",
  "type": "PIN",
  "rank": 2,
  "expiresAt": "2026-09-01T00:00:00",
  "reason": "Conference week spotlight"
}
```

| Field | Required | Notes |
|---|---|---|
| `tag` | yes | Normalized server-side: trimmed, lowercased, leading `#` stripped, ≤100 chars |
| `scope` | no | Defaults to `ALL`; one of `ALL/QUESTION/RESEARCH/POST/REEL` (case-insensitive) |
| `type` | yes | `PIN` or `BAN` (case-insensitive) |
| `rank` | no | Stored as `pinnedRank` (PIN splice position) |
| `expiresAt` | no | Auto-expiry; null = until revoked |
| `reason` | no | Max 500 |

**Response**: `201 Created` — the saved `TrendingTagOverride` (see above).

**Errors**
- `VALIDATION_FAILED` — 400 — `tag` or `type` blank.
- `INVALID_SCOPE` — 400 — scope not in the allowed set.
- `INVALID_TYPE` — 400 — type not `PIN`/`BAN`.
- `INVALID_TAG` — 400 — tag empty after normalization or >100 chars.
- `STEP_UP_REQUIRED` — 403.

### DELETE /api/v1/admin/trending/overrides/{id}
Revoke an override (soft — sets `revokedAt`; row stays for audit).

**Access**: ADMIN.

**Params**: `id` — override UUID (path).

**Request body**: None.

**Response**: `204 No Content`.

**Errors**
- `TRENDINGTAGOVERRIDE_NOT_FOUND` — 404.

### POST /api/v1/admin/trending/rebuild
Force an immediate trending-snapshot rebuild so overrides apply without waiting out the refresh interval.

**Access**: ADMIN.

**Request body**: None.

**Response**: `202 Accepted` — empty body (no job id; the rebuild runs inline in the request thread and has completed by the time the response returns). Silently no-ops if the ops job-pause registry has `trending-rebuild` paused.

---

## 4. Tag maintenance — `TagAdminController`

Base `/api/v1/admin/tags`. Gates are **per-method** `@PreAuthorize("hasRole('ADMIN')")` (no class-level gate). One-shot maintenance ops on the unified tag subsystem (`content_by_tag` + usage counters).

### POST /api/v1/admin/tags/backfill-posts
Re-index every post's hashtags into `content_by_tag` (migration for posts created before unified-tag fanout existed). Full token-range scan of `posts_by_id`; row writes are idempotent UPSERTs, **but the trending counter bumps are NOT idempotent — do not run twice unless necessary**.

**Access**: ADMIN. **Step-up required.**

**Request body**: None.

**Response**: `200 OK` (synchronous — returns after the scan completes); keys in this order:

```json
{
  "postsScanned": 18234,
  "postsWithHashtags": 6410,
  "tagRowsWritten": 11097,
  "startedAt": "2026-08-06T12:34:56.789012Z"
}
```

`startedAt` is stamped when the response is built (i.e. at completion, despite the name). Per-post failures are logged and skipped, not fatal.

**Errors**
- `STEP_UP_REQUIRED` — 403.

### POST /api/v1/admin/tags/{tag}/hide
Hide a tag from trending: records a `BAN` override for the given scope (idempotent — skipped if an active BAN for that tag+scope already exists). Takes effect on the next trending rebuild.

**Access**: ADMIN.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `tag` | string (path) | — | Normalized: trimmed, lowercased, leading `#` stripped, ≤100 chars |
| `scope` | string | `ALL` | `ALL/QUESTION/RESEARCH/POST/REEL` |

**Request body**: None.

**Response**: `204 No Content`. The stored override carries `reason: "hidden via admin tags API"`.

**Errors**
- `INVALID_TAG` — 400 — tag empty after normalization or >100 chars.
- `INVALID_SCOPE` — 400.

### DELETE /api/v1/admin/tags/{tag}/hide
Unhide: revokes every active `BAN` override on the tag **whose scope matches exactly** (an `ALL`-scope BAN is not cleared by `?scope=QUESTION` — unhide with `scope=ALL` for those).

**Access**: ADMIN.

**Params**: same as hide (`tag` path, `scope` query default `ALL`).

**Request body**: None.

**Response**: `204 No Content` (also when nothing matched).

**Errors**
- `INVALID_TAG` — 400.
- `INVALID_SCOPE` — 400.

### POST /api/v1/admin/tags/merge
Merge tag `from` into `to`: rewrites `content_by_tag` rows and transfers per-scope + `ALL` usage counters. Capped at **5000 rows per call** — re-run while `truncated` is `true`. Counter tables can't move atomically; documented drift window is one trending refresh.

**Access**: ADMIN. **Step-up required.**

**Params**

| Param | Type | Required | Notes |
|---|---|---|---|
| `from` | string | yes | Source tag (normalized like all tag inputs) |
| `to` | string | yes | Destination tag |

**Request body**: None (query params only).

**Response**: `200 OK`; keys in this order:

```json
{
  "from": "hadeeth",
  "to": "hadith",
  "rowsMoved": 5000,
  "truncated": true
}
```

**Errors**
- `400` with **empty body** (no envelope) — `from` and `to` normalize to the same tag.
- `INVALID_TAG` — 400 — either tag invalid.
- `STEP_UP_REQUIRED` — 403.

---

## 5. Knowledge vocabulary — `AdminKnowledgeController`

Base `/api/v1/admin/knowledge`. Class gate: `@PreAuthorize("hasRole('ADMIN')")`. Curation console for the trilingual Topics/Madhhabs taxonomy. Every mutation evicts the Redis vocabulary caches (`knowledge-topics`, `knowledge-madhhabs`). Retire is **soft** (`archivedAt`) — hard delete is deliberately not offered because profiles reference rows by id; there is no un-retire endpoint.

The two entities serialize identically (Madhhab shown; Topic is the same shape):

```json
{ "id": 3, "nameEn": "Shafi'i", "nameAr": "الشافعي", "nameCkb": "شافیعی", "archivedAt": null }
```

### GET /api/v1/admin/knowledge/topics
All topics (including retired) with live usage counts from `user_topic_specializations`.

**Access**: ADMIN.

**Request body**: None.

**Response**: `200 OK` — JSON array; per-row keys in this order:

```json
[
  {
    "id": 7,
    "nameEn": "Hadith Sciences",
    "nameAr": "علوم الحديث",
    "nameCkb": "زانستەکانی فەرموودە",
    "retired": false,
    "archivedAt": null,
    "usageCount": 128
  }
]
```

`retired` is derived (`archivedAt != null`). If the usage query fails, counts silently fall back to 0.

### GET /api/v1/admin/knowledge/madhhabs
All madhhabs (including retired) with usage counts from `user_profiles.madhhab_id`.

**Access**: ADMIN.

**Request body**: None.

**Response**: `200 OK` — same row shape as `/topics`.

### POST /api/v1/admin/knowledge/topics
Add a topic (all three names required; values trimmed).

**Access**: ADMIN. **Step-up required.**

**Request body**:

```json
{ "nameEn": "Quranic Exegesis", "nameAr": "التفسير", "nameCkb": "تەفسیر" }
```

`nameEn` ≤150 chars; `nameAr`, `nameCkb` ≤100; all `@NotBlank`.

**Response**: `201 Created` — the saved `Topic` entity:

```json
{ "id": 12, "nameEn": "Quranic Exegesis", "nameAr": "التفسير", "nameCkb": "تەفسیر", "archivedAt": null }
```

**Errors**
- `VALIDATION_FAILED` — 400 — blank/oversized names.
- `DUPLICATE_VOCAB_NAME` — 400 — case-insensitive `nameEn` already exists ("A topic named '%s' already exists.").
- `STEP_UP_REQUIRED` — 403.

### POST /api/v1/admin/knowledge/madhhabs
Add a madhhab.

**Access**: ADMIN. **Step-up required.**

**Request body**: same `VocabBody` as topics — `{ "nameEn": "Zahiri", "nameAr": "الظاهري", "nameCkb": "زاهیری" }`.

**Response**: `201 Created` — the saved `Madhhab` entity (same shape as Topic).

**Errors**
- `VALIDATION_FAILED` — 400.
- `DUPLICATE_VOCAB_NAME` — 400 — "A madhhab named '%s' already exists."
- `STEP_UP_REQUIRED` — 403.

### PATCH /api/v1/admin/knowledge/topics/{id}
Rename a topic. Partial: only non-blank provided fields are applied — omitted **or blank** fields stay unchanged (blank does not clear).

**Access**: ADMIN. **Step-up required.**

**Params**: `id` — integer topic id (path).

**Request body** (any subset):

```json
{ "nameEn": "Qur'anic Exegesis (Tafsir)" }
```

**Response**: `200 OK` — the updated `Topic` entity.

**Errors**
- `TOPIC_NOT_FOUND` — 404.
- `DUPLICATE_VOCAB_NAME` — 400 — new `nameEn` collides with another topic.
- `VALIDATION_FAILED` — 400 — oversized names.
- `STEP_UP_REQUIRED` — 403.

### PATCH /api/v1/admin/knowledge/madhhabs/{id}
Rename a madhhab — identical semantics to the topic patch.

**Access**: ADMIN. **Step-up required.**

**Params**: `id` — integer madhhab id (path).

**Request body**: same `VocabPatch` subset shape.

**Response**: `200 OK` — the updated `Madhhab` entity.

**Errors**
- `MADHHAB_NOT_FOUND` — 404.
- `DUPLICATE_VOCAB_NAME` — 400.
- `VALIDATION_FAILED` — 400.
- `STEP_UP_REQUIRED` — 403.

### POST /api/v1/admin/knowledge/topics/{id}/retire
Soft-retire a topic (sets `archivedAt`; leaves pickers, stays resolvable by id so existing profile references never orphan).

**Access**: ADMIN. **Step-up required.**

**Params**: `id` — integer topic id (path).

**Request body**: None.

**Response**: `204 No Content`.

**Errors**
- `TOPIC_NOT_FOUND` — 404.
- `STEP_UP_REQUIRED` — 403.

### POST /api/v1/admin/knowledge/madhhabs/{id}/retire
Soft-retire a madhhab.

**Access**: ADMIN. **Step-up required.**

**Params**: `id` — integer madhhab id (path).

**Request body**: None.

**Response**: `204 No Content`.

**Errors**
- `MADHHAB_NOT_FOUND` — 404.
- `STEP_UP_REQUIRED` — 403.

### POST /api/v1/admin/knowledge/cache/evict
Manually clear the `knowledge-topics` and `knowledge-madhhabs` cache regions (escape hatch — every write above already evicts automatically).

**Access**: ADMIN.

**Request body**: None.

**Response**: `204 No Content`.
