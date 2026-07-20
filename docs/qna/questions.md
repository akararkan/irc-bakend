# Q&A API — Questions

Question lifecycle, feeds, and author controls for the academic Q&A module.

**Base path:** `/api/v1/questions`

A question moves through the `QuestionStatus` lifecycle:

| Status | Meaning |
|---|---|
| `OPEN` | Default after create. No top-level answers yet. |
| `ANSWERED` | Has at least one (non-deleted) top-level answer. Set automatically when a top-level answer is posted; reverts to `OPEN` when the last top-level answer is deleted. |
| `CLOSED` | Question no longer accepts answers (`QUESTION_CLOSED` on answer attempts). Defined in the enum; no public endpoint currently sets it. |
| `ARCHIVED` | Hidden/retired question; also rejects new answers. Defined in the enum; no public endpoint currently sets it. |

Reaching the `maxAnswers` cap does **not** change `status` — clients should read the
server-computed `acceptsNewAnswers` flag instead of re-deriving composer visibility.

Question `tags` fan out to the unified Cassandra tag/trending subsystem
(`ContentType.QUESTION`) on create, and are re-indexed on edit when the tag set changes.
Full-text search lives on `GET /api/v1/search?types=QUESTION` (unified search API), not here.

**Auth model:** JWT resolved from the `access_token` cookie first, then the
`Authorization: Bearer <jwt>` header. Endpoints marked *Optional* below work anonymously
but return viewer-specific fields (`isSaved`) as `false` for anonymous callers.
Errors use the shared envelope — see [Error handling](../errors/error-handling.md).

Sibling docs: [Answers](./answers.md) · [Engagement](./engagement.md) · [Realtime](./realtime.md)

---

## Create question

```
POST /api/v1/questions
```

**Auth:** Required — and role-gated in the service: only `SCHOLAR` and `ADMIN` may create
questions (`findScholarOrThrow`). `RESEARCHER` and `USER` receive `403` even with a valid
token. (Researchers can *answer* questions — see [Answers](./answers.md).)

Creates an `OPEN` question, publishes a `QUESTION_CREATED` RabbitMQ event, indexes it in
Elasticsearch, and fans normalized tags out to the Cassandra trending/tag-feed subsystem.

### Request body

| Field | Type | Required | Notes |
|---|---|---|---|
| `title` | string | yes | Max 500 chars. Trimmed. |
| `body` | string | yes | Max 10 000 chars. Trimmed. |
| `tags` | string[] | no | Max 30. Normalized: lowercased, trimmed, deduplicated. Drive trending + tag feeds. |
| `keywords` | string | no | Free-text, max 2 000 chars. Search discoverability only — not part of trending. |
| `answersLocked` | boolean | no | Default `false`. Lock answers from the start. |
| `maxAnswers` | integer | no | `null` = unlimited. Caps **top-level** answers only (reanswers never count). |

```json
{
  "title": "Is reciting Surah Yasin nightly an established sunnah?",
  "body": "I keep hearing different opinions from different teachers...",
  "tags": ["yasin", "sunnah", "dhikr"],
  "keywords": "surah yasin, nightly recitation",
  "answersLocked": false,
  "maxAnswers": 5
}
```

### Response — `201 Created` (`QuestionResponse`)

```json
{
  "id": "3f6f8a3e-9d1c-4a51-b8f1-0f0f4d1c2a10",
  "authorId": "8c1a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8",
  "authorUsername": "imam_yusuf",
  "authorFullName": "Yusuf al-Qaradawi",
  "authorProfileImage": "https://cdn.example.com/avatars/yusuf.jpg",
  "title": "Is reciting Surah Yasin nightly an established sunnah?",
  "body": "I keep hearing different opinions from different teachers...",
  "status": "OPEN",
  "answerCount": 0,
  "viewCount": 0,
  "saveCount": 0,
  "answersLocked": false,
  "maxAnswers": 5,
  "acceptsNewAnswers": true,
  "hasAcceptedAnswer": false,
  "acceptedAnswerCount": 0,
  "isSaved": false,
  "createdAt": "2026-07-20T08:42:00",
  "updatedAt": "2026-07-20T08:42:00",
  "timeAgo": "just now",
  "formattedDate": "20 Jul 2026",
  "savedAt": null,
  "tags": ["yasin", "sunnah", "dhikr"],
  "keywords": "surah yasin, nightly recitation"
}
```

### `QuestionResponse` fields

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Question id. |
| `authorId` / `authorUsername` / `authorFullName` / `authorProfileImage` | — | Denormalized author card. |
| `title`, `body` | string | Trimmed text. |
| `status` | enum | `OPEN` \| `ANSWERED` \| `CLOSED` \| `ARCHIVED`. |
| `answerCount` | long | Top-level answers only (reanswers excluded). |
| `viewCount` | long | Deduplicated views — see *Get one question*. |
| `saveCount` | long | Bookmark count. |
| `answersLocked` | boolean | Author lock toggle. |
| `maxAnswers` | integer\|null | `null` = unlimited. |
| `acceptsNewAnswers` | boolean | Server-computed: `true` only when `status == OPEN` (or `ANSWERED`), answers not locked, and cap not reached. Use this to show/hide the composer. |
| `hasAcceptedAnswer` | boolean | `true` iff `acceptedAnswerCount > 0` — "resolved" badge without loading answers. |
| `acceptedAnswerCount` | long | Author may accept multiple answers. Independent of `status`. |
| `isSaved` | boolean | Whether the current viewer bookmarked it. Always `false` for anonymous. |
| `savedAt` | datetime\|null | Populated **only** by saved-list endpoints (see [Engagement](./engagement.md#list-saved-questions)). |
| `tags` | string[] | Normalized tags. Batch-loaded on feed pages (no per-card query). |
| `keywords` | string | Author's discoverability string. |
| `timeAgo`, `formattedDate` | string | Server-rendered display helpers. |

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Missing/oversized `title`/`body`, > 30 tags, etc. (`fieldErrors` populated). |
| 401 | `AUTH_UNAUTHORIZED` | No/invalid token. |
| 403 | `ACCESS_FORBIDDEN` | Caller is not `SCHOLAR`/`ADMIN` ("Only scholars can post questions"). |

### Side effects

- Cassandra tag fan-out: `contentTagService.tag(ContentType.QUESTION, ...)` — feeds trending (scope `QUESTION`) and tag feeds.
- RabbitMQ `QUESTION_CREATED` event (after commit).
- Elasticsearch async index.
- Activity log entry for the author.
- `@mention` scan over title + body — mention notifications; the `@followers` fan-out token **is** honored here (top-level publication).

---

## Public feed (offset)

```
GET /api/v1/questions
```

**Auth:** Optional.

Newest-first page of all non-deleted questions. When the viewer is authenticated, questions
from users with a block edge (either direction) are excluded, and `isSaved` is batch-resolved
for the page in one query.

### Query parameters

| Param | Type | Default | Notes |
|---|---|---|---|
| `page` | int | 0 | Spring `Pageable`. |
| `size` | int | 20 | Page size. |

### Response — `200 OK`

Standard Spring `Page<QuestionResponse>`:

```json
{
  "content": [ { "id": "...", "title": "...", "tags": ["hajj"], "isSaved": false, "...": "..." } ],
  "totalElements": 132,
  "totalPages": 7,
  "number": 0,
  "size": 20,
  "first": true,
  "last": false
}
```

---

## Public feed (keyset / cursor)

```
GET /api/v1/questions/feed/cursor
```

**Auth:** Optional. Preferred for infinite scroll — stable under concurrent inserts.

### Query parameters

| Param | Type | Default | Notes |
|---|---|---|---|
| `cursor` | ISO date-time | — | Omit for the first page; pass the previous response's `nextCursor` for the next page. |
| `limit` | int | 20 | Clamped server-side to 1–50. |

### Response — `200 OK` (`CursorPage<QuestionResponse>`)

```json
{
  "items": [ { "id": "...", "title": "...", "...": "..." } ],
  "nextCursor": "2026-07-19T21:03:11.482",
  "hasMore": true
}
```

| Field | Type | Notes |
|---|---|---|
| `items` | `QuestionResponse[]` | Newest-first, keyset on `createdAt`. |
| `nextCursor` | datetime\|null | `null` at end of feed. |
| `hasMore` | boolean | `false` at end of feed. |

Block filtering and batched `isSaved` resolution behave exactly as in the offset feed.

---

## Following feed

```
GET /api/v1/questions/feed/following
```

**Auth:** Required.

Questions authored by users the viewer follows, **plus the viewer's own questions** (so a
user who follows no one still sees their own). The following-ids set comes from a cached,
block-filtered Redis lookup (`FollowingIdsCache`, ~1 min TTL) — no per-row block scan.

Query parameters: `page` (default 0), `size` (default 20). Response: `Page<QuestionResponse>`.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | Missing/invalid token. |

---

## My questions

```
GET /api/v1/questions/me
```

**Auth:** Required. **No scholar gate on this read** — any authenticated user can list
their own questions (returns an empty page if they have none or were de-promoted). The
`SCHOLAR` gate applies only to *creation*.

Query parameters: `page` (default 0), `size` (default 20). Response: `Page<QuestionResponse>`
of the caller's non-deleted questions, newest first.

---

## Get one question

```
GET /api/v1/questions/{questionId}
```

**Auth:** Optional. If the authenticated viewer has a block edge with the question author
(either direction), the endpoint returns `404` — existence is hidden.

Returns the `QuestionResponse` and bumps the **deduplicated** view counter as a side effect:

- **Authenticated viewer** — counted once per `(question, user)` **forever**, backed by a
  durable `question_views` ledger row (`INSERT ... ON CONFLICT DO NOTHING` semantics).
- **Anonymous viewer** — 1-hour Redis dedupe keyed by client fingerprint (first
  `X-Forwarded-For` entry, falling back to the remote address).

The bump runs in a separate `REQUIRES_NEW` transaction and is best-effort: a counter/Redis
failure never breaks the read. When counted, the fresh total is written through to the Redis
counter cache and a `VIEW_COUNT_UPDATED` SSE event is broadcast (see [Realtime](./realtime.md)).

### Response — `200 OK`: `QuestionResponse` (see field table above; `isSaved` resolved for the viewer).

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 404 | `QUESTION_NOT_FOUND` | Unknown/deleted id — or the viewer is block-related to the author. |

---

## Edit question

```
PATCH /api/v1/questions/{questionId}
```

**Auth:** Required — question author or `ADMIN`.

Partial update: every field is optional; omitted (`null`) fields are left unchanged.

### Request body (`EditQuestionRequest`)

| Field | Type | Notes |
|---|---|---|
| `title` | string | Max 500. JSON aliases accepted: `name`, `questionTitle`. Blank → `400 EMPTY_TITLE`. |
| `body` | string | Max 10 000. Aliases: `text`, `content`, `description`. Blank → `400 EMPTY_BODY`. |
| `answersLocked` | boolean | Toggle the lock. |
| `maxAnswers` | integer | `<= 0` clears the cap (unlimited). |
| `tags` | string[] | Replacement set, max 30. `null` = unchanged; `[]` clears. Triggers a full Cassandra tag re-index. |
| `keywords` | string | Max 2 000. `null` = unchanged. |

```json
{ "title": "Is nightly Yasin recitation an established sunnah?", "tags": ["yasin", "sunnah"] }
```

### Response — `200 OK`: updated `QuestionResponse`.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `EMPTY_TITLE` / `EMPTY_BODY` | Field present but blank. |
| 400 | `VALIDATION_FAILED` | Size limits exceeded. |
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `ACCESS_FORBIDDEN` | Not the author or an admin. |
| 404 | `QUESTION_NOT_FOUND` | Unknown/deleted id. |

### Side effects

- SSE `QUESTION_UPDATED` (carries the new `body`).
- Cassandra tags: full `retag` when the tag set changed; title-preview refresh when only the title changed.
- Mention **delta** scan — only newly added `@handles` are notified.
- Elasticsearch async re-index.

---

## Delete question (cascade)

```
DELETE /api/v1/questions/{questionId}
```

**Auth:** Required — question author or `ADMIN`.

**Hard delete** in a single transaction — the question and every dependent row are removed
(answers, reanswers, reactions, attachments, sources, saves, view-ledger rows), so a partial
cascade can never orphan children. Cleanup order:

1. **S3 sweep** (best-effort, before rows drop): answer attachments, answer inline media/voice, source files.
2. **Postgres cascade** in FK-safe order: reactions → attachments → sources → saves → views → answers (parent self-FK nulled in bulk first) → the question row.
3. **Best-effort external cleanup** (never fails the request): Elasticsearch delete, Cassandra tag `untag`, notification rows for the question, RabbitMQ `QUESTION_DELETED` event, SSE `QUESTION_DELETED` broadcast.

### Response — `204 No Content`.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `ACCESS_FORBIDDEN` | Not the author or an admin. |
| 404 | `QUESTION_NOT_FOUND` | Unknown/already-deleted id. |

---

## Lock answers

```
POST /api/v1/questions/{questionId}/lock-answers
```

**Auth:** Required — question author or `ADMIN`.

Sets `answersLocked = true`. Subsequent answer attempts fail with `400 ANSWERS_LOCKED`.
Broadcasts SSE `QUESTION_LOCKED`.

**Response — `200 OK`:** updated `QuestionResponse` (`answersLocked: true`, `acceptsNewAnswers: false`).

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `ACCESS_FORBIDDEN` | Not the author or an admin ("Only the question author can lock answers"). |
| 404 | `QUESTION_NOT_FOUND` | Unknown id. |

---

## Unlock answers

```
DELETE /api/v1/questions/{questionId}/lock-answers
```

**Auth:** Required — question author or `ADMIN`.

Sets `answersLocked = false` and broadcasts SSE `QUESTION_UNLOCKED`.
Same responses/errors as *Lock answers*.

---

## Set / clear answer limit

```
PATCH /api/v1/questions/{questionId}/answer-limit
```

**Auth:** Required — question author or `ADMIN`.

### Query parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `maxAnswers` | integer | no | Omit, or pass `<= 0`, to **clear** the cap (unlimited). Positive value sets the cap on top-level answers. |

```
PATCH /api/v1/questions/3f6f.../answer-limit?maxAnswers=3   → cap at 3
PATCH /api/v1/questions/3f6f.../answer-limit                → unlimited
```

**Response — `200 OK`:** updated `QuestionResponse` (`maxAnswers`, `acceptsNewAnswers` recomputed).

When the cap is hit, new top-level answers fail with `400 ANSWER_LIMIT_REACHED`
([Answers](./answers.md#post-a-top-level-answer)); reanswers are never counted against the cap.
No SSE event is emitted for the limit change itself.

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `ACCESS_FORBIDDEN` | Not the author or an admin. |
| 404 | `QUESTION_NOT_FOUND` | Unknown id. |
