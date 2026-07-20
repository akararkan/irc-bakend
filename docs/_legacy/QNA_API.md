# QnA API — full reference

Base path: **`/api/v1/questions`**

This document is the single source of truth for the frontend on what
the QnA endpoints accept, what they return, what roles can call them,
and the realtime events they broadcast.

---

## Table of contents

1.  [Overview](#1-overview)
2.  [Authentication & role markers](#2-authentication--role-markers)
3.  [Unified error response](#3-unified-error-response)
4.  [Enums](#4-enums)
5.  [Core DTOs](#5-core-dtos)
6.  [Question lifecycle (create / read / edit / delete)](#6-question-lifecycle)
7.  [Question feeds](#7-question-feeds)
8.  [Question search](#8-question-search)
9.  [Per-question SSE stream](#9-per-question-sse-stream)
10. [Answer controls (lock / max-answers)](#10-answer-controls)
11. [Answers & reanswers](#11-answers--reanswers)
12. [Answer reactions (single LIKE)](#12-answer-reactions)
13. [Accept / unaccept](#13-accept--unaccept)
14. [Multi-scholar best-answer voting](#14-multi-scholar-best-answer-voting)
15. [Answer attachments](#15-answer-attachments)
16. [Answer sources / references](#16-answer-sources--references)
17. [Save / bookmark](#17-save--bookmark)
18. [Share & share-link preview](#18-share--share-link-preview)
19. [Realtime event types](#19-realtime-event-types)
20. [Cassandra denormalized tables](#20-cassandra-denormalized-tables)
21. [Cross-cutting rules](#21-cross-cutting-rules)

---

## 1. Overview

QnA is the **academic Q&A** half of the platform. Questions are
posted to an **OPEN → ANSWERED → CLOSED → ARCHIVED** lifecycle, and
answers may have:

```
Question
  └── Answer (top-level)
        ├── Reanswer (depth-1 reply)
        ├── Attachments  (PDF / DOCX / image / audio / video)
        ├── Sources      (URL / ISBN / MEDIA_FILE / MANUAL)
        ├── Reactions    (single LIKE — no multi-reaction variants)
        └── Best-answer votes (per-scholar)
```

Replies are **flat at depth 1** — Reanswer on a Reanswer becomes a
sibling, never a deeper child. This mirrors the same rule that
governs Post comments.

Counters (answers, views, saves, shares, reactions) are cached in
Redis and read-through to Postgres. Real-time deltas fan out via SSE
on `/api/v1/questions/{questionId}/stream`.

---

## 2. Authentication & role markers

Every endpoint below carries one of these markers:

| Marker | Meaning |
|--------|---------|
| 🟢 Public | No auth required — anonymous-safe. |
| 🔵 Authenticated | Caller must be logged in. `401` if not. |
| 🟡 Author-only | Caller must own the resource. `403` if not the question/answer author. |
| 🔴 Admin / scholar-gated | Caller must hold an elevated role. Today **best-answer voting** is restricted to scholars. |

**Auth headers accepted** (in priority order):

1. `Authorization: Bearer <jwt>`
2. Cookie `access_token=<jwt>` (set by the login flow)
3. `?token=<jwt>` query param (used by `EventSource` since browsers
   can't set headers on SSE)

A few endpoints have **no @PreAuthorize** so they accept anonymous
traffic for read-only flows (feeds, item read, search, share-link
preview).

### Admin / scholar-gated endpoints

| Endpoint | Why |
|---|---|
| `POST /api/v1/questions/{questionId}/answers/{answerId}/best` | Marking an answer as "best" is a scholar vote — only verified scholars should call this. (Today the server doesn't enforce — the frontend MUST gate the UI.) |
| `DELETE /api/v1/questions/{questionId}/answers/{answerId}/best` | Unvoting a best-answer — same scholar gate. |

> **Note.** "Author-only" enforcement is partly client-side. The
> frontend MUST refuse to render destructive controls (edit, delete,
> lock, accept) for non-authors. The server does verify ownership on
> the back end and will return `403` (or a raw `SecurityException`
> mapped to `500` until a refactor lands).

---

## 3. Unified error response

All errors share the same envelope (see `ApiException` /
`GlobalExceptionHandler`):

```json
{
  "timestamp": "2026-05-21T13:42:11.512Z",
  "status":    404,
  "error":     "QUESTION_NOT_FOUND",
  "message":   "Question 5e07… not found",
  "path":      "/api/v1/questions/5e07.../answers"
}
```

Common codes you should branch on in the frontend:

| HTTP | `error` | Meaning |
|------|---------|---------|
| 400  | `VALIDATION_ERROR` | Bean-validation failed (`@NotBlank`, `@Size`, …) |
| 401  | `UNAUTHORIZED` | Missing / expired JWT |
| 403  | `FORBIDDEN` | Caller is not the resource author |
| 404  | `QUESTION_NOT_FOUND` / `ANSWER_NOT_FOUND` / `ATTACHMENT_NOT_FOUND` / `SOURCE_NOT_FOUND` | … |
| 409  | `ANSWERS_LOCKED` | Question has `answersLocked=true` |
| 409  | `MAX_ANSWERS_REACHED` | Answer count has hit `maxAnswers` |
| 422  | `INVALID_PARENT` | Tried to reanswer a deleted / mismatched parent |

---

## 4. Enums

### `QuestionStatus`

```
OPEN | ANSWERED | CLOSED | ARCHIVED
```

| Status | When |
|--------|------|
| `OPEN` | Default after create. |
| `ANSWERED` | At least one answer accepted by the author. |
| `CLOSED` | Author closed the question manually. |
| `ARCHIVED` | Question is hidden from feeds but still readable by URL. |

> **Reaching `maxAnswers` does NOT change `status`** — the question stays
> `OPEN`/`ANSWERED`. Whether new answers can be posted is exposed as the derived
> boolean **`acceptsNewAnswers`** on `QuestionResponse` (§5); the post-answer
> endpoint enforces it server-side too (§11.2). Use that flag, not `status`, to
> decide whether to show the answer composer.

### `AnswerReactionType`

```
LIKE
```

Single-reaction model — **no** LOVE/HAHA/SAD variants. See memory
note: *"academic not entertainment."*

### `SourceType` (shared with Research)

```
URL | ISBN | MEDIA_FILE | MANUAL
```

These are the **only** valid values (`ak.dev.irc.app.research.enums.SourceType`).
`MEDIA_FILE` = an uploaded file is the source; `MANUAL` = a free-text citation
with no link. There is no `BOOK` / `JOURNAL` / `HADITH` / `FILE` value.

### `MediaType` (shared with Research)

```
IMAGE | VIDEO | AUDIO | DOCUMENT | OTHER
```

---

## 5. Core DTOs

### `QuestionResponse`

```json
{
  "id":                 "Q-uuid",
  "authorId":           "U-uuid",
  "authorUsername":     "imam_yusuf",
  "authorFullName":     "Yusuf al-Qaradawi",
  "authorProfileImage": "https://cdn…/avatars/yusuf.jpg",

  "title":              "Is reciting Surah Yasin nightly an established sunnah?",
  "body":               "I keep hearing different opinions…",
  "tags":               ["yasin", "sunnah", "dhikr"],
  "keywords":           "surah yasin, nightly recitation, sunnah",
  "status":             "OPEN",

  "answerCount":  3,
  "viewCount":    1247,
  "saveCount":    18,

  "answersLocked": false,
  "maxAnswers":    null,
  "acceptsNewAnswers": true,
  "hasAcceptedAnswer": false,
  "acceptedAnswerCount": 0,
  "isSaved":       false,

  "createdAt":      "2026-05-21T08:42:00",
  "updatedAt":      "2026-05-21T08:42:00",
  "timeAgo":        "2 hours ago",
  "formattedDate":  "21 May 2026",

  "savedAt":        null
}
```

> `savedAt` is **only** populated by the saved-list endpoints
> (`GET /me/saved`, `/me/saved/collection`). Null everywhere else.

> **`tags`** — normalized topic labels (lowercased, trimmed, deduped, max 30).
> They drive trending + tag feeds via the shared Cassandra tag subsystem and are
> indexed in Elasticsearch. **`keywords`** — a free-text discoverability string
> (max 2000 chars), indexed in ES and boosted in relevance search, but **not**
> part of trending. `tags` is populated on **both** single-question reads **and**
> feed/list responses — the feed batch-loads all tags for the page in one query
> (no per-card fetch). See
> [`SEARCH_API.md` §7](./SEARCH_API.md#7-tags-keywords--trending-cassandra).

> **`hasAcceptedAnswer`** / **`acceptedAnswerCount`** — drive a "resolved" badge
> on feed cards without loading answers. `hasAcceptedAnswer` is `true` iff
> `acceptedAnswerCount > 0`. These are denormalized and maintained on
> accept / unaccept / delete; they are **independent of `status`** (a question
> can be `ANSWERED` — meaning it has answers — without any *accepted* answer).

### `QuestionAnswerResponse`

```json
{
  "id":             "A-uuid",
  "questionId":     "Q-uuid",
  "authorId":       "U-uuid",
  "authorUsername": "scholar_omar",
  "authorFullName": "Omar al-Tunisi",
  "authorProfileImage": "https://cdn…/avatars/omar.jpg",

  "body":           "The hadith narrated in Sunan al-Darimi…",
  "parentAnswerId": null,
  "replyToAnswerId": null,
  "replyToUserId":   null,
  "replyCount":     2,

  "mediaUrl":          null,
  "mediaType":         null,
  "mediaThumbnailUrl": null,
  "voiceUrl":          null,
  "voiceDurationSeconds": null,
  "links":             "https://example.org/a,https://example.org/b",

  "attachments": [],
  "sources":     [],

  "accepted":            false,
  "isBestAnswer":        true,
  "bestAnswerVoteCount": 3,
  "votedByMe":           true,

  "edited":      false,
  "editedAt":    null,
  "deleted":     false,
  "deletedAt":   null,

  "reactionCount": 12,
  "myReaction":   "LIKE",

  "createdAt":     "2026-05-21T09:15:00",
  "updatedAt":     "2026-05-21T09:15:00",
  "timeAgo":       "1 hour ago",
  "formattedDate": "21 May 2026"
}
```

> **`replyToAnswerId` / `replyToUserId`** (E2) — on a reanswer, the answer it was
> *actually* aimed at and that answer's author, captured **before** depth-1
> hoisting. So when a reply-to-a-reply is hoisted to a sibling of the root, the UI
> can still render "replying to @X". Both `null` on top-level answers; for a reply
> to a top-level answer they equal `parentAnswerId` and that answer's author.
>
> **`links`** is a single **comma-separated string of URLs** (not an array) —
> split on `,` to render. May be `null`/empty.

### `AnswerAttachmentResponse`

```json
{
  "id":               "AT-uuid",
  "answerId":         "A-uuid",
  "fileUrl":          "https://cdn…/attachments/yasin-tafsir.pdf",
  "originalFileName": "yasin-tafsir.pdf",
  "mimeType":         "application/pdf",
  "mediaType":        "DOCUMENT",
  "fileSize":         482301,
  "displayOrder":     0,
  "caption":          "Excerpt from Tafsir Ibn Kathir, vol. 6",
  "durationSeconds":  null,
  "thumbnailUrl":     null,
  "createdAt":        "2026-05-21T09:18:00"
}
```

### `AnswerSourceResponse`

```json
{
  "id":               "S-uuid",
  "answerId":         "A-uuid",
  "sourceType":       "ISBN",
  "title":            "Tafsir Ibn Kathir, vol. 6",
  "citationText":     "Ibn Kathir, Tafsir al-Qur'an al-Adheem…",
  "url":              null,
  "isbn":             "978-9960-892-77-7",
  "fileUrl":          null,
  "originalFileName": null,
  "displayOrder":     0,
  "createdAt":        "2026-05-21T09:18:00"
}
```

---

## 6. Question lifecycle

### 6.1 `POST /api/v1/questions` — create question

**Auth:** 🔵 Authenticated. Caller becomes the author.

**What it does.** Creates a new question in `OPEN` status with the
title + body, optionally pre-locked with `answersLocked: true` and a
ceiling on `maxAnswers`. Indexed in Elasticsearch on the same
transaction commit. Any `tags` are **immediately** fanned out to the
Cassandra tag subsystem (trending + tag feeds) — questions are live on
create.

**When the frontend uses this.** "Ask a question" composer submit.

**Request body** (`CreateQuestionRequest`):

```json
{
  "title":        "Is reciting Surah Yasin nightly an established sunnah?",
  "body":         "I keep hearing different opinions…",
  "tags":         ["yasin", "sunnah", "dhikr"],
  "keywords":     "surah yasin, nightly recitation, sunnah",
  "answersLocked": false,
  "maxAnswers":   null
}
```

| Field | Required | Notes |
|---|---|---|
| `title` | yes | ≤ 500 chars |
| `body` | yes | ≤ 10000 chars |
| `tags` | no | ≤ 30 labels; normalized server-side (lowercased, `#` stripped, deduped) |
| `keywords` | no | ≤ 2000 chars free text for search discoverability |
| `answersLocked` | no | default `false` |
| `maxAnswers` | no | `null` = unlimited |

**Response `201`** — `QuestionResponse`.

---

### 6.2 `GET /api/v1/questions/{questionId}` — get one

**Auth:** 🟢 Public.

**What it does.** Returns the question + records a unique view. Dedup key is the
viewer's UUID (authenticated) or `X-Forwarded-For` (anonymous), with a **24-hour**
window (`question_views` TTL, §20). The counter increment is async, so `viewCount`
in **this** response is the **pre-bump** value — the `VIEW_COUNT_UPDATED` SSE event
carries the post-bump count a moment later.

> A client not subscribed to the stream is permanently off-by-one on its own open;
> treat its own view as `viewCount + 1` optimistically, or re-read on next load.
> Anonymous dedup via `X-Forwarded-For` is best-effort and can over-count behind a
> shared proxy/CDN that collapses client IPs.

**When the frontend uses this.** Opening the question detail page.

**Response `200`** — `QuestionResponse`.

---

### 6.3 `PATCH /api/v1/questions/{questionId}` — edit question

**Auth:** 🟡 Author-only.

**What it does.** Partial update of `title`, `body`, `answersLocked`,
`maxAnswers`, `tags`, `keywords`. Omitted fields are left untouched.
When `tags` is present, the Cassandra tag index is fully rebuilt for
this question (`tags: []` clears all tags; omitting `tags` leaves them
unchanged).

**When the frontend uses this.** "Edit question" sheet on a
question authored by the viewer.

**Request body** (`EditQuestionRequest`):

```json
{
  "title":         "Is reciting Surah Yasin nightly a confirmed sunnah?",
  "body":          "Updated context after reading the comments…",
  "tags":          ["yasin", "sunnah", "fiqh-al-sunnah"],
  "keywords":      "surah yasin, nightly recitation",
  "answersLocked": null,
  "maxAnswers":    5
}
```

> **Tag update semantics:** `tags` is a full replacement, not a merge. Send the
> complete desired set. `null`/omitted = unchanged; `[]` = remove all tags.

**Response `200`** — updated `QuestionResponse`.

---

### 6.4 `DELETE /api/v1/questions/{questionId}` — delete

**Auth:** 🟡 Author-only.

**What it does.** Hard-deletes the question. Cascades to answers,
attachments, sources, and Cassandra denormalized rows in
the same transaction.

**When the frontend uses this.** "Delete question" confirm action.
Show the destructive confirmation modal before calling.

**Response:** `204 No Content`.

---

### 6.5 `GET /api/v1/questions/me` — my questions

**Auth:** 🔵 Authenticated.

**What it does.** Page of questions the caller authored, newest first.

**When the frontend uses this.** Profile → "My questions" tab.

**Response `200`** — `Page<QuestionResponse>`.

---

## 7. Question feeds

### 7.1 `GET /api/v1/questions` — public feed (page-based)

**Auth:** 🟢 Public.

**What it does.** Public list of questions ordered by `createdAt DESC`,
with Spring Data pagination (`?page=0&size=20&sort=createdAt,desc`).

**When the frontend uses this.** "Discover" tab. Prefer the cursor
variant (§7.2) for infinite-scroll UIs.

**Response `200`** — `Page<QuestionResponse>`.

---

### 7.2 `GET /api/v1/questions/feed/cursor` — public feed (cursor)

**Auth:** 🟢 Public.

**What it does.** Cursor-paginated variant of §7.1. First page omits
`cursor`; each subsequent call passes back the `nextCursor` from the
previous response. End-of-feed → `nextCursor: null` and
`hasMore: false`.

**When the frontend uses this.** Mobile infinite-scroll feeds where
inserts during scroll would shift page-based pagination.

**Query parameters:**
- `cursor` — ISO LocalDateTime; omit for first page.
- `limit` — default `20`, max `100`.

**Response `200`** — `CursorPage<QuestionResponse>`:

```json
{
  "items":      [ /* QuestionResponse[] */ ],
  "nextCursor": "2026-05-21T07:00:00",
  "hasMore":    true
}
```

---

### 7.3 `GET /api/v1/questions/feed/following` — following feed

**Auth:** 🔵 Authenticated.

**What it does.** Questions authored by users the caller follows.
Excludes blocked users in both directions.

**When the frontend uses this.** "Following" tab on the QnA home
screen.

**Response `200`** — `Page<QuestionResponse>`.

---

## 8. Question search

> **Migrated to the unified search API.** The Q&A-only
> `GET /api/v1/questions/search` endpoint has been **removed**. Full-text
> question search now lives on the cross-index endpoint:
>
> **`GET /api/v1/search?q=…&types=QUESTION&page=…&size=…`**
>
> The response is a list of entity-stamped hits — clients still call
> §6.2 to hydrate each `QUESTION` UUID. See [`SEARCH_API.md`](./SEARCH_API.md)
> for the full contract.
>
> The `irc-qna` index now also carries **`tags`** and **`keywords`**, so a
> query like `?q=hajj&types=QUESTION` matches a question's tags and keywords —
> not just its title/body. They use the same `tags^2` / `keywords^2` boosts as
> research (see [`SEARCH_API.md` §2](./SEARCH_API.md#2-get-apiv1search)).

### 8.1 Tags & trending (Cassandra)

Beyond relevance search, questions participate in the **shared, cross-content
tag system** (questions + research) backed by Cassandra:

| Surface | Endpoint |
|---|---|
| Trending tags (most-used) | `GET /api/v1/tags/trending?scope=QUESTION` (or `ALL`) |
| Everything tagged `X`, newest first | `GET /api/v1/tags/{tag}/content` |
| A tag's usage count | `GET /api/v1/tags/{tag}/usage?scope=QUESTION` |

A question's tags are indexed the moment it's created and removed on delete.
Full contract + frontend guide: [`SEARCH_API.md` §7–§8](./SEARCH_API.md#7-tags-keywords--trending-cassandra).

---

## 9. Per-question SSE stream

### 9.1 `GET /api/v1/questions/{questionId}/stream`

**Auth:** 🟢 Public (anonymous viewers receive the stream, but `myReaction` / `votedByMe` fields in payloads are `null` for them). Use `?token=<jwt>` to authenticate as a specific viewer.

**Content type:** `text/event-stream`.

**What it does.** Subscribes the client to the question's realtime
event channel. Every answer / reanswer / reaction / accept / best-vote
/ lifecycle event published on this question is pushed to
the client. A `connected` handshake fires on subscribe, and a
`heartbeat` every 25 s.

**When the frontend uses this.** Open the question detail page → open
the SSE stream → patch the counters and lists inline as events arrive.
Close on navigate-away.

**Event payload schema** — `QnaRealtimeEvent` (flat; `null` fields are omitted from the wire):

```json
{
  "eventType":      "ANSWER_REACTION_ADDED",
  "questionId":     "Q-uuid",
  "actorId":        "U-uuid",
  "actorUsername":  "scholar_omar",
  "actorAvatarUrl": "https://cdn…/avatars/omar.jpg",

  "answerId":       "A-uuid",
  "parentAnswerId": "ROOT-A-uuid",
  "answer":         { "…": "full QuestionAnswerResponse — see §11.1" },

  "reactionType":        "LIKE",
  "answerReactionCount": 13,
  "timestamp":      "2026-05-21T09:15:00.012Z"
}
```

**Every answer-scoped event** (`ANSWER_CREATED`, `REANSWER_CREATED`,
`ANSWER_EDITED`, `ANSWER_DELETED`, `ANSWER_REACTION_*`, `ANSWER_ACCEPTED`,
`ANSWER_UNACCEPTED`, `BEST_ANSWER_VOTED`, `BEST_ANSWER_UNVOTED`) carries:

- **`answer`** — the full, freshly-recomputed `QuestionAnswerResponse`. **Patch
  that one row in place — no refetch.** Viewer-specific fields (`myReaction`,
  `votedByMe`) are neutral (`null` / `false`); resolve them per current viewer.
  On `ANSWER_DELETED` it's the tombstone (`deleted:true`, body/attachments/sources
  nulled).
- **`parentAnswerId`** — the **root answer id** when the affected answer is a
  reply; `null` for a top-level answer. Route the update with
  `threadId = parentAnswerId ?? answerId`.

There is **no `data` wrapper** — read the fields directly off the event object.
See §19 for the full event-type list.

**The stream does not echo your own actions back to you.** If your SSE
connection is authenticated (`?token=<jwt>`), the server suppresses any event
whose `actorId` matches your `viewerId` (see §21). So you can optimistically
update the UI on your own action and trust that no returning event will
double-apply it. (Caveat: suppression is per-user — a second tab you have open
won't get the live event either; reconcile on its next fetch.)

---

## 10. Answer controls

These three endpoints let the **question author** govern the answer
section.

### 10.1 `POST /api/v1/questions/{questionId}/lock-answers`

**Auth:** 🟡 Author-only.

**What it does.** Locks the answer section. New answers and reanswers
return `409 ANSWERS_LOCKED`. Existing answers stay readable / editable.

**When the frontend uses this.** "Lock answers" toggle in the
question-owner toolbar.

**Response `200`** — updated `QuestionResponse` with
`answersLocked: true`.

---

### 10.2 `DELETE /api/v1/questions/{questionId}/lock-answers` — unlock

**Auth:** 🟡 Author-only.

**What it does.** Reopens the answer section.

**Response `200`** — updated `QuestionResponse` with
`answersLocked: false`.

---

### 10.3 `PATCH /api/v1/questions/{questionId}/answer-limit?maxAnswers=N`

**Auth:** 🟡 Author-only.

**What it does.** Sets (or clears with no param) the ceiling on
answers. Once `answerCount` hits `maxAnswers`, new **top-level** answers are
rejected with `400 ANSWER_LIMIT_REACHED` and the question's
**`acceptsNewAnswers`** flips to `false`. `status` is unchanged (stays
`OPEN`/`ANSWERED`). Reanswers (replies) do **not** count toward the cap.

**When the frontend uses this.** "Limit to N answers" UI on the
question-owner toolbar (e.g. scholar wants only 3 reputable answers).

**Response `200`** — updated `QuestionResponse`.

---

## 11. Answers & reanswers

### 11.1 `GET /api/v1/questions/{questionId}/answers` — list answers

**Auth:** 🟢 Public.

**What it does.** Page of top-level answers (no reanswers). For each
answer the `myReaction` / `votedByMe` fields reflect the caller (or
are null for anonymous).

**Soft-deleted answers are excluded.** This list returns only live rows
(`deletedAt IS NULL`) — deleted answers/replies are **not** returned as
tombstones, so a returned row's `deleted` is always `false` here. The
`deleted:true` shape appears only in the `ANSWER_DELETED` SSE event (§9.1).
`replyCount` counts only non-deleted replies. The replies endpoint (§11.4)
filters the same way.

**When the frontend uses this.** Below the question body on the
detail screen.

**Response `200`** — `Page<QuestionAnswerResponse>`.

---

### 11.2 `POST /api/v1/questions/{questionId}/answers` — post answer

**Auth:** 🔵 Authenticated. **Rejected** when the question isn't accepting new
answers: `400 ANSWERS_LOCKED` (locked) or `400 ANSWER_LIMIT_REACHED`
(`maxAnswers` cap hit). Check **`acceptsNewAnswers`** on the question (§5)
before showing the composer so the user never hits this.

**What it does.** Creates a top-level answer. Optionally attaches
inline `mediaUrl` (if already uploaded elsewhere), `voiceUrl`,
free-form `links`, and a list of `sources`. Use §11.3 to upload media
inline.

**When the frontend uses this.** Bottom composer of the question
detail page → "Answer."

**Request body** (`CreateAnswerRequest`):

```json
{
  "body":  "The hadith narrated in Sunan al-Darimi describes…",
  "parentAnswerId":  null,
  "mediaUrl":           null,
  "mediaType":          null,
  "mediaThumbnailUrl":  null,
  "voiceUrl":           null,
  "voiceDurationSeconds": null,
  "links":              null,
  "sources": [
    {
      "sourceType":   "ISBN",
      "title":        "Sunan al-Darimi",
      "citationText": "Vol 2, hadith 3424…",
      "isbn":         "978-9960-892-77-7"
    }
  ]
}
```

**Response `201`** — `QuestionAnswerResponse`.

---

### 11.3 `POST /api/v1/questions/{questionId}/answers/upload` — answer with media

**Auth:** 🔵 Authenticated.

**Content type:** `multipart/form-data`.

**What it does.** Single multipart call that combines an answer body
with an inline media file (image / video) and an optional voice note.
Mirrors `POST /posts/{id}/comments/upload` so the frontend can reuse
its comment composer for answers.

**When the frontend uses this.** Image / video / voice answer from
mobile.

**Multipart parts:**
- `data` (required) — JSON `CreateAnswerRequest`
- `media` (optional) — `image/*` or `video/*`
- `voice` (optional) — `audio/*`

**Response `201`** — `QuestionAnswerResponse` with `mediaUrl` /
`voiceUrl` populated.

---

### 11.4 `GET /api/v1/questions/{questionId}/answers/{answerId}/reanswers` *(alias `…/replies`)*

**Auth:** 🟢 Public.

**What it does.** Page of reanswers (depth-1 replies) under a given
top-level answer.

**When the frontend uses this.** "Show replies" expander under an
answer.

**Response `200`** — `Page<QuestionAnswerResponse>` (each with
`parentAnswerId` set).

---

### 11.5 `POST /api/v1/questions/{questionId}/answers/{answerId}/reanswers` *(alias `…/replies`)* — reanswer

**Auth:** 🔵 Authenticated.

**What it does.** Creates a reanswer (reply) under a top-level answer.
The server sets `parentAnswerId` from the path — if the body also has
one, the path value wins. Replying to a depth-1 reanswer is hoisted
to a sibling reply on the same root answer (depth-1 cap, see
project memory).

**When the frontend uses this.** "Reply" button on an answer.

**Request body** — same `CreateAnswerRequest` shape as §11.2.

**Response `201`** — `QuestionAnswerResponse`.

---

### 11.6 `POST /api/v1/questions/{questionId}/answers/{answerId}/reanswers/upload` *(alias `…/replies/upload`)*

**Auth:** 🔵 Authenticated.

**Content type:** `multipart/form-data`.

**What it does.** Multipart reanswer variant — same shape as §11.3
but creates a reply under the given parent.

---

### 11.7 `PATCH /api/v1/questions/{questionId}/answers/{answerId}` — edit answer

**Auth:** 🟡 Author-only (answer author).

**What it does.** Updates the answer body via **PATCH** (partial update).
Sets `edited: true` and `editedAt`. The current schema only supports body
edit — to change attachments / sources use §15 / §16.

**Works for replies too.** A reanswer (reply) is stored as an answer row, so
**this same `PATCH …/answers/{answerId}` endpoint edits a reply** — pass the
reanswer's id as `{answerId}`. There is no separate reply-edit route.

**Request body** (`EditAnswerRequest`):

```json
{ "body": "Updated answer body — added missing tashkeel." }
```

**Response `200`** — updated `QuestionAnswerResponse`. The same payload is pushed
to subscribers as an `ANSWER_EDITED` event with the full `answer` embedded (§9.1).

---

### 11.8 `DELETE /api/v1/questions/{questionId}/answers/{answerId}` — delete answer

**Auth:** 🟡 Author-only.

**What it does.** Soft-deletes the answer (`deletedAt` set, `deleted:true`).
The deleted answer is then **excluded from the answers list** (§11.1 filters
`deletedAt IS NULL`) — it is **not** shown as a tombstone there. Its replies are
not hard-deleted, but because the parent no longer appears in the list they are
effectively hidden from the thread. (A live viewer still gets the tombstone via
the `ANSWER_DELETED` SSE event, §9.1, and can swap the row in place.)

**Works for replies too.** Deleting a reply uses this **same
`DELETE …/answers/{answerId}`** endpoint (pass the reanswer's id). There is no
separate reply-delete route.

**Response:** `204 No Content`. Subscribers receive an `ANSWER_DELETED` event
carrying the tombstone `answer` and its `parentAnswerId` (§9.1), so the client
can swap the row for a "[deleted]" placeholder in the right thread.

---

## 12. Answer reactions

Single LIKE reaction — mirrors Post comment reactions. No
HEART/HAHA/etc.

### 12.1 `POST /api/v1/questions/{questionId}/answers/{answerId}/react` — react

**Auth:** 🔵 Authenticated.

**What it does.** Toggles a LIKE reaction onto an answer (idempotent —
re-calling does nothing). Emits `ANSWER_REACTION_ADDED` on the SSE
stream.

**Request body** (optional — type defaults to `LIKE`):

```json
{ "reactionType": "LIKE" }
```

**Response `200`** — updated `QuestionAnswerResponse` with refreshed
`reactionCount` and `myReaction: "LIKE"`.

---

### 12.2 `DELETE /api/v1/questions/{questionId}/answers/{answerId}/react` — un-react

**Auth:** 🔵 Authenticated.

**What it does.** Removes the caller's LIKE on the answer.

**Response `200`** — updated `QuestionAnswerResponse` with
`myReaction: null`.

---

## 13. Accept / unaccept

The **question author** can accept multiple answers. Marking
"accepted" flips the question to `ANSWERED` status.

### 13.1 `POST /api/v1/questions/{questionId}/answers/{answerId}/accept`

**Auth:** 🟡 Author-only (question author).

**What it does.** Marks the given answer as accepted by the question
author. Question status transitions `OPEN → ANSWERED`.

**Response `200`** — updated `QuestionAnswerResponse` with
`accepted: true`.

---

### 13.2 `DELETE /api/v1/questions/{questionId}/answers/{answerId}/accept` — unaccept

**Auth:** 🟡 Author-only.

**What it does.** Removes the accept flag. If no other answers are
accepted, question status returns to `OPEN`.

**Response `200`** — updated `QuestionAnswerResponse`.

---

## 14. Multi-scholar best-answer voting

Distinct from accept — **any verified scholar** may mark an answer as
"best." The vote counter is exposed on the answer
(`bestAnswerVoteCount`), and `votedByMe` reflects the caller's vote.

### 14.1 `POST /api/v1/questions/{questionId}/answers/{answerId}/best`

**Auth:** 🔴 Scholar-only (frontend MUST gate — server enforcement is
pending).

**What it does.** Adds the caller as one of the answer's best-answer
voters. Idempotent — voting twice has no effect.

**When the frontend uses this.** "⭐ Mark as best answer" button shown
only when the viewer is a verified scholar.

**Response `200`** — updated `QuestionAnswerResponse` with bumped
`bestAnswerVoteCount` and `votedByMe: true`.

---

### 14.2 `DELETE /api/v1/questions/{questionId}/answers/{answerId}/best` — unvote

**Auth:** 🔴 Scholar-only.

**What it does.** Removes the caller's best-vote.

**Response `200`** — updated `QuestionAnswerResponse`.

---

## 15. Answer attachments

PDFs, DOCX, images, audio, video, ZIP — anything that lives on R2 /
S3 alongside the answer body.

> **Attachments vs. `MEDIA_FILE` sources (C3).** Two different file mechanisms,
> rendered in two different places:
> - **Attachments** (this section) = supporting downloads bundled *with* the
>   answer — show them under the answer body.
> - **`MEDIA_FILE` sources** (§16) = a hosted scan/PDF of a *cited reference* —
>   show them in the citations panel alongside the other sources.
>
> Same storage backend, different intent. Use attachments for "here are my files",
> sources for "here is the work I'm citing."

### 15.1 `POST /api/v1/questions/{questionId}/answers/{answerId}/attachments`

**Auth:** 🟡 Author-only (answer author).

**Content type:** `multipart/form-data`.

**What it does.** Uploads a single file as an attachment to the
answer. The server detects MIME type, derives `mediaType`, extracts
duration for audio / video, and stores the row.

**Multipart parts / params:**
- `file` (required) — the binary
- `caption` (optional, query) — short description
- `displayOrder` (optional, query) — sort order, default appends to end

**Response `201`** — `AnswerAttachmentResponse`.

---

### 15.2 `GET /api/v1/questions/{questionId}/answers/{answerId}/attachments`

**Auth:** 🟢 Public.

**What it does.** Lists all attachments on the answer, ordered by
`displayOrder`.

**Response `200`** — `List<AnswerAttachmentResponse>`.

---

### 15.3 `PATCH /api/v1/questions/{questionId}/answers/{answerId}/attachments/{attachmentId}`

**Auth:** 🟡 Author-only.

**What it does.** Updates `caption` or `displayOrder` on a single
attachment (no file replacement — delete + re-upload for that).

**Request body** (`UpdateAnswerAttachmentRequest`):

```json
{ "caption": "Updated caption", "displayOrder": 1 }
```

**Response `200`** — updated `AnswerAttachmentResponse`.

---

### 15.4 `DELETE /api/v1/questions/{questionId}/answers/{answerId}/attachments/{attachmentId}`

**Auth:** 🟡 Author-only.

**What it does.** Deletes the attachment row and removes the underlying
R2 / S3 object.

**Response:** `204 No Content`.

---

## 16. Answer sources / references

Bibliographic citations: URL / ISBN / MEDIA_FILE / MANUAL. Shown
in a citations panel under the answer body. (Citations *of cited works* — for
files bundled *with* the answer, use Attachments §15 instead. See the C3 note
at the top of §15.)

A **`MEDIA_FILE`** source is a citation backed by an uploaded file: first
`POST` the source row (§16.1) with `sourceType: "MEDIA_FILE"`, then attach the
binary via **§16.5**, which fills `fileUrl` / `originalFileName`.

### 16.1 `POST /api/v1/questions/{questionId}/answers/{answerId}/sources`

**Auth:** 🟡 Author-only (answer author).

**What it does.** Adds a citation. At least one of `url`, `isbn`, or
`fileUrl` should be populated depending on `sourceType`, but no field
is server-enforced — the UI should validate.

**Request body** (`CreateAnswerSourceRequest`):

```json
{
  "sourceType":   "ISBN",
  "title":        "Tafsir Ibn Kathir, vol. 6",
  "citationText": "Ibn Kathir, Tafsir al-Qur'an al-Adheem, Dar Tayyibah…",
  "isbn":         "978-9960-892-77-7"
}
```

**Response `201`** — `AnswerSourceResponse`.

---

### 16.2 `GET /api/v1/questions/{questionId}/answers/{answerId}/sources`

**Auth:** 🟢 Public.

**What it does.** Returns the answer's bibliography list ordered by
`displayOrder`.

**Response `200`** — `List<AnswerSourceResponse>`.

---

### 16.3 `PATCH /api/v1/questions/{questionId}/answers/{answerId}/sources/{sourceId}`

**Auth:** 🟡 Author-only.

**What it does.** Updates any of `title`, `citationText`, `url`,
`isbn`, or `displayOrder`.

**Request body** (`UpdateAnswerSourceRequest`):

```json
{
  "title":        "Tafsir Ibn Kathir, vol. 6 (revised ed.)",
  "displayOrder": 2
}
```

**Response `200`** — updated `AnswerSourceResponse`.

---

### 16.4 `DELETE /api/v1/questions/{questionId}/answers/{answerId}/sources/{sourceId}`

**Auth:** 🟡 Author-only.

**Response:** `204 No Content`.

---

### 16.5 `POST /api/v1/questions/{questionId}/answers/{answerId}/sources/{sourceId}/file`

**Auth:** 🟡 Author-only (answer author). **Content type:** `multipart/form-data`.

**What it does.** Attaches (or replaces) the binary file for a `MEDIA_FILE`
source — the QnA equivalent of Research's source-file upload. Uploads to R2/S3,
sets the source's `fileUrl`, `originalFileName`, `mimeType`, `fileSize`, and
forces `sourceType = MEDIA_FILE`. Re-uploading replaces the previous object.

**Flow.** `POST` the source row (§16.1) → then call this with the returned
`sourceId` to attach the file.

| Part | Type | Notes |
|---|---|---|
| `file` | binary | The document/scan. Part name **`file`**. |

**Response `200`** — updated `AnswerSourceResponse` (now with `fileUrl` +
`originalFileName` populated).

**Errors:** `400 MISSING_FILE` (empty), `400 SOURCE_MISMATCH` (source not on this
answer), `403` (not the answer author), `404` (answer/source not found).

---

## 17. Save / bookmark

### 17.1 `POST /api/v1/questions/{questionId}/save?collection=<name>`

**Auth:** 🔵 Authenticated. Idempotent — re-calling with a different
`collection` moves the bookmark.

**What it does.** Bookmarks the question into the named collection (or
the default unnamed collection if `collection` is omitted). Emits
`SAVE_COUNT_UPDATED` on the SSE stream.

**When the frontend uses this.** "🔖 Save" tap on a question card.

**Response `201`** — `QuestionResponse` with `isSaved: true`.

---

### 17.2 `DELETE /api/v1/questions/{questionId}/save` — unsave

**Auth:** 🔵 Authenticated.

**What it does.** Removes the caller's bookmark on the question.

**Response `200`** — `QuestionResponse` with `isSaved: false`.

---

### 17.3 `GET /api/v1/questions/me/saved` — saved questions

**Auth:** 🔵 Authenticated.

**What it does.** Page of saved questions, newest-saved first. Each
`QuestionResponse` carries `savedAt` so the frontend can render
"Saved &lt;date&gt;" without an extra fetch.

**When the frontend uses this.** Profile → "Saved" → "Questions" tab.

**Response `200`** — `Page<QuestionResponse>` (with `savedAt`
populated).

---

### 17.4 `GET /api/v1/questions/me/saved/collection?name=<name>`

**Auth:** 🔵 Authenticated.

**What it does.** Same as §17.3 but filtered to a single collection.

**Response `200`** — `Page<QuestionResponse>`.

---

### 17.5 `GET /api/v1/questions/me/saved/collections` — distinct collection names

**Auth:** 🔵 Authenticated.

**What it does.** Lists every collection name the caller has used
(distinct, deduped).

**When the frontend uses this.** Populating the "Save to collection"
picker.

**Response `200`:**

```json
["Default", "For tafsir paper", "Fatwa research"]
```

---

### 17.6 `PATCH /api/v1/questions/me/saved/collections?oldName=…&newName=…`

**Auth:** 🔵 Authenticated.

**What it does.** Renames a collection across every saved row owned
by the caller. Idempotent.

**Response:** `204 No Content`.

---

## 18. Share & share-link preview

### 18.1 `GET /api/v1/questions/{questionId}/share-link` — preview

**Auth:** 🟢 Public.

**What it does.** Returns the unified share-link info (`url`,
`shareToken`, `qrUrl`, …) **without** bumping `shareCount`. Used by
the inline share UI before the user actually copies.

**Response `200`** — `ShareLinkInfo`:

```json
{
  "url":        "https://irc.example.com/q/2u-1a3-...",
  "shareToken": "2u1a3hk9zq",
  "qrUrl":      "https://api.irc.example.com/qr/2u-1a3-..."
}
```

---

### 18.2 `POST /api/v1/questions/{questionId}/share` — record share

**Auth:** 🟢 Public (authenticated caller is recorded if present).

**What it does.** Atomically bumps `shareCount` and returns the same
`ShareLinkInfo` payload. Call when the user actually copies / sends
the link.

**Response `200`** — `ShareLinkInfo`.

---

## 19. Realtime event types

Defined in `ak.dev.irc.app.qna.realtime.QnaRealtimeEventType`. One
event per discrete state change so the frontend can patch the UI
incrementally.

> **Answer-scoped events embed the full answer.** Every event in the *Answer /
> reanswer*, *Reaction*, and *Accept / best-answer* groups below carries the
> fresh **`answer`** (`QuestionAnswerResponse`) and **`parentAnswerId`** (§9.1)
> — patch the row in place, no refetch, and route replies to their root thread.

### Answer / reanswer events

| Event | When |
|---|---|
| `ANSWER_CREATED` | A new top-level answer is posted. |
| `REANSWER_CREATED` | A reply (depth-1) under an answer. |
| `ANSWER_EDITED` | Author edited the answer body. |
| `ANSWER_DELETED` | Author soft-deleted the answer. |

### Reaction events

| Event | When |
|---|---|
| `ANSWER_REACTION_ADDED` | LIKE added. |
| `ANSWER_REACTION_CHANGED` | (forward-compat; single LIKE today) |
| `ANSWER_REACTION_REMOVED` | LIKE removed. |

### Accept / best-answer events

| Event | When |
|---|---|
| `ANSWER_ACCEPTED` | Question author accepted the answer. |
| `ANSWER_UNACCEPTED` | Question author unaccepted. |
| `BEST_ANSWER_VOTED` | A scholar marked best. |
| `BEST_ANSWER_UNVOTED` | A scholar removed best. |

### Question lifecycle

| Event | When |
|---|---|
| `QUESTION_UPDATED` | Title / body / settings changed. |
| `QUESTION_DELETED` | Question deleted. |
| `QUESTION_LOCKED` | Answers locked. |
| `QUESTION_UNLOCKED` | Answers unlocked. |

### Live counters

| Event | When |
|---|---|
| `VIEW_COUNT_UPDATED` | New unique view recorded. |
| `SAVE_COUNT_UPDATED` | Save toggled on or off. |
| `SHARE_COUNT_UPDATED` | Share button pressed. |

---

## 20. Cassandra denormalized tables

The QnA module mirrors high-read state into Cassandra for fast O(1)
viewer-side reads. Spring Data JPA still owns the source of truth;
Cassandra rows are written via `@TransactionalEventListener(AFTER_COMMIT)`.

| Table | Purpose | TTL |
|---|---|---|
| `qna_reactions_by_answer` | "Who reacted to this answer?" — pageable list. | — |
| `qna_reactions_by_user`   | "What did this user react to?" — for profile activity. | — |
| `question_saves_by_user`  | Saved-questions list per viewer. | — |
| `question_save_lookup`    | Per-(user, question) save-state lookup. | — |
| `question_views`          | View-dedupe records (viewer-key → questionId). | 24 h |

**Shared tag tables** (written on create/edit/delete, `content_type = "QUESTION"`):
`content_by_tag`, `tags_by_content`, `tag_counters`, `trending_tags`. These are
shared with Research — documented in [`SEARCH_API.md` §7.6](./SEARCH_API.md#76-how-its-stored-cassandra).

---

## 21. Cross-cutting rules

- **Depth-1 reply cap.** A reanswer to a reanswer is hoisted to a
  sibling reply on the same root answer. Mirrors the post-comment
  rule (`project_reply_nesting_rule.md`).
- **Single reaction type.** LIKE only. No multi-emoji variants.
- **Self-engagement.** Users CAN like / save / share their own
  questions and answers, but self-notifications are skipped server-side.
- **Actor-event suppression (SSE).** The per-question stream does **not**
  echo an action back to the user who performed it: `QnaRealtimeService`
  skips any subscriber whose authenticated `viewerId` equals the event's
  `actorId`. This holds across instances (the Redis fan-out funnels through
  the same delivery method). So a client can safely apply an **optimistic**
  update on its own action without double-counting from a returning event.
  - **Requires an authenticated stream.** The match needs the SSE connection
    to carry the viewer (`?token=<jwt>`). An anonymous stream has no
    `viewerId`, so it would receive its own events — but mutations require
    auth anyway, so in practice the actor's stream is authenticated.
  - **Per-user, not per-tab.** Suppression is by user id, so if the same user
    has the question open in a **second tab**, that tab also won't receive the
    live event and stays stale until its next fetch/refresh. Single-tab (the
    common case) is exact.
- **Counter accuracy.** All counters (answers, views, saves, shares,
  reactions) are atomic via CQL `UPDATE col = col + N` and
  read-through cached in Redis. Treat the SSE counter events as the
  authoritative push delta.
- **Soft-delete answer.** A deleted answer's body, attachments, and
  sources are nulled in responses but the row stays — used to render
  "[deleted]" placeholders without breaking reanswer threads.
- **Author check.** Mutating endpoints throw `SecurityException` if the
  caller is not the resource author; the global handler maps this to a
  `403 FORBIDDEN` (errorCode `FORBIDDEN`) JSON envelope.

---
