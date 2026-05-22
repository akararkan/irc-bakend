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
15. [Question-author feedback on answers](#15-question-author-feedback-on-answers)
16. [Answer attachments](#16-answer-attachments)
17. [Answer sources / references](#17-answer-sources--references)
18. [Save / bookmark](#18-save--bookmark)
19. [Share & share-link preview](#19-share--share-link-preview)
20. [Realtime event types](#20-realtime-event-types)
21. [Cassandra denormalized tables](#21-cassandra-denormalized-tables)
22. [Cross-cutting rules](#22-cross-cutting-rules)

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
        ├── Sources      (BOOK / DOI / URL / ISBN / FILE …)
        ├── Reactions    (single LIKE — no multi-reaction variants)
        ├── Best-answer votes (per-scholar)
        └── Feedback     (EXCELLENT / HELPFUL / NEEDS_IMPROVEMENT / INCORRECT / OFF_TOPIC)
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
| 🟡 Author-only | Caller must own the resource. `403` if not the question/answer/feedback author. |
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
| 404  | `QUESTION_NOT_FOUND` / `ANSWER_NOT_FOUND` / `FEEDBACK_NOT_FOUND` / `ATTACHMENT_NOT_FOUND` / `SOURCE_NOT_FOUND` | … |
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
| `CLOSED` | Author closed manually (or `maxAnswers` reached). |
| `ARCHIVED` | Question is hidden from feeds but still readable by URL. |

### `AnswerReactionType`

```
LIKE
```

Single-reaction model — **no** LOVE/HAHA/SAD variants. See memory
note: *"academic not entertainment."*

### `FeedbackType`

```
EXCELLENT | HELPFUL | NEEDS_IMPROVEMENT | INCORRECT | OFF_TOPIC
```

Used by the **question author** to rate individual answers. Different
from reactions (which any user can leave).

### `SourceType` (shared with Research)

```
BOOK | JOURNAL | WEBSITE | URL | DOI | ISBN | FILE | HADITH | QURAN | …
```

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
  "status":             "OPEN",

  "answerCount":  3,
  "viewCount":    1247,
  "saveCount":    18,

  "answersLocked": false,
  "maxAnswers":    null,
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
  "replyCount":     2,

  "mediaUrl":          null,
  "mediaType":         null,
  "mediaThumbnailUrl": null,
  "voiceUrl":          null,
  "voiceDurationSeconds": null,
  "links":             null,

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

  "feedbackCount": 1,
  "reactionCount": 12,
  "myReaction":   "LIKE",

  "createdAt":     "2026-05-21T09:15:00",
  "updatedAt":     "2026-05-21T09:15:00",
  "timeAgo":       "1 hour ago",
  "formattedDate": "21 May 2026"
}
```

### `AnswerFeedbackResponse`

```json
{
  "id":             "F-uuid",
  "answerId":       "A-uuid",
  "authorId":       "U-uuid",
  "authorUsername": "imam_yusuf",
  "authorFullName": "Yusuf al-Qaradawi",
  "authorProfileImage": "https://cdn…/avatars/yusuf.jpg",
  "feedbackType":   "EXCELLENT",
  "body":           "Mashallah, very thorough — jazak Allah khayr.",
  "createdAt":      "2026-05-21T10:00:00",
  "updatedAt":      "2026-05-21T10:00:00"
}
```

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
  "sourceType":       "BOOK",
  "title":            "Tafsir Ibn Kathir, vol. 6",
  "citationText":     "Ibn Kathir, Tafsir al-Qur'an al-Adheem…",
  "url":              null,
  "doi":              null,
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
transaction commit.

**When the frontend uses this.** "Ask a question" composer submit.

**Request body** (`CreateQuestionRequest`):

```json
{
  "title":        "Is reciting Surah Yasin nightly an established sunnah?",
  "body":         "I keep hearing different opinions…",
  "answersLocked": false,
  "maxAnswers":   null
}
```

**Response `201`** — `QuestionResponse`.

---

### 6.2 `GET /api/v1/questions/{questionId}` — get one

**Auth:** 🟢 Public.

**What it does.** Returns the question + records a unique view (dedupe
key = viewer's UUID, or `X-Forwarded-For` for anonymous viewers).
Counter increment is async — `viewCount` in the response is the
**pre-bump** value; the SSE stream gets a `VIEW_COUNT_UPDATED` event a
moment later.

**When the frontend uses this.** Opening the question detail page.

**Response `200`** — `QuestionResponse`.

---

### 6.3 `PATCH /api/v1/questions/{questionId}` — edit question

**Auth:** 🟡 Author-only.

**What it does.** Partial update of `title`, `body`, `answersLocked`,
`maxAnswers`. Omitted fields are left untouched.

**When the frontend uses this.** "Edit question" sheet on a
question authored by the viewer.

**Request body** (`EditQuestionRequest`):

```json
{
  "title":         "Is reciting Surah Yasin nightly a confirmed sunnah?",
  "body":          "Updated context after reading the comments…",
  "answersLocked": null,
  "maxAnswers":    5
}
```

**Response `200`** — updated `QuestionResponse`.

---

### 6.4 `DELETE /api/v1/questions/{questionId}` — delete

**Auth:** 🟡 Author-only.

**What it does.** Hard-deletes the question. Cascades to answers,
attachments, sources, feedback, and Cassandra denormalized rows in
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
> The `irc-qna` Elasticsearch index is unchanged; only the query-time
> entry point moved.

---

## 9. Per-question SSE stream

### 9.1 `GET /api/v1/questions/{questionId}/stream`

**Auth:** 🟢 Public (anonymous viewers receive the stream, but `myReaction` / `votedByMe` fields in payloads are `null` for them). Use `?token=<jwt>` to authenticate as a specific viewer.

**Content type:** `text/event-stream`.

**What it does.** Subscribes the client to the question's realtime
event channel. Every answer / reanswer / reaction / accept / best-vote
/ feedback / lifecycle event published on this question is pushed to
the client. A `connected` handshake fires on subscribe, and a
`heartbeat` every 25 s.

**When the frontend uses this.** Open the question detail page → open
the SSE stream → patch the counters and lists inline as events arrive.
Close on navigate-away.

**Event payload schema** — `QnaRealtimeEvent`:

```json
{
  "eventType":   "ANSWER_CREATED",
  "questionId":  "Q-uuid",
  "answerId":    "A-uuid",
  "actorId":     "U-uuid",
  "timestamp":   "2026-05-21T09:15:00.012Z",
  "data": {
    /* event-specific payload (full answer DTO, counter delta, etc.) */
  }
}
```

See §20 for the full event-type list.

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
answers. Once `answerCount` hits `maxAnswers` the section behaves as
locked — new answers return `409 MAX_ANSWERS_REACHED`.

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

**When the frontend uses this.** Below the question body on the
detail screen.

**Response `200`** — `Page<QuestionAnswerResponse>`.

---

### 11.2 `POST /api/v1/questions/{questionId}/answers` — post answer

**Auth:** 🔵 Authenticated. **Blocked if** `answersLocked` or
`maxAnswers` reached → `409`.

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
      "sourceType":   "BOOK",
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

**What it does.** Updates the answer body. Sets `edited: true` and
`editedAt`. The current schema only supports body edit — to change
attachments / sources use §16 / §17.

**Request body** (`EditAnswerRequest`):

```json
{ "body": "Updated answer body — added missing tashkeel." }
```

**Response `200`** — updated `QuestionAnswerResponse`.

---

### 11.8 `DELETE /api/v1/questions/{questionId}/answers/{answerId}` — delete answer

**Auth:** 🟡 Author-only.

**What it does.** Soft-deletes the answer: `body` is nulled out,
attachments / sources are hidden from response, `deleted: true` and
`deletedAt` are set. Reanswers under it survive (so threads remain
readable) — they just lose their parent's content.

**Response:** `204 No Content`.

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

## 15. Question-author feedback on answers

Distinct from reactions — the **question author** can rate each
answer with a `FeedbackType` and an optional comment. Used for the
"How well did this answer your question?" widget.

### 15.1 `POST /api/v1/questions/{questionId}/answers/{answerId}/feedback`

**Auth:** 🟡 Author-only (question author).

**What it does.** Adds a feedback row. The author may add multiple
feedback rows on the same answer if the UI exposes that.

**Request body** (`AddFeedbackRequest`):

```json
{
  "feedbackType": "EXCELLENT",
  "body":         "Mashallah — thoroughly researched."
}
```

**Response `201`** — `AnswerFeedbackResponse`.

---

### 15.2 `GET /api/v1/questions/{questionId}/answers/{answerId}/feedback` — list

**Auth:** 🟢 Public.

**What it does.** Returns all feedback rows on the answer.

**Response `200`** — `List<AnswerFeedbackResponse>`.

---

### 15.3 `PATCH /api/v1/questions/{questionId}/answers/{answerId}/feedback/{feedbackId}`

**Auth:** 🟡 Author-only (feedback author).

**What it does.** Updates the feedback type or body.

**Request body** — same `AddFeedbackRequest` shape.

**Response `200`** — updated `AnswerFeedbackResponse`.

---

### 15.4 `DELETE /api/v1/questions/{questionId}/answers/{answerId}/feedback/{feedbackId}`

**Auth:** 🟡 Author-only.

**Response:** `204 No Content`.

---

## 16. Answer attachments

PDFs, DOCX, images, audio, video, ZIP — anything that lives on R2 /
S3 alongside the answer body.

### 16.1 `POST /api/v1/questions/{questionId}/answers/{answerId}/attachments`

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

### 16.2 `GET /api/v1/questions/{questionId}/answers/{answerId}/attachments`

**Auth:** 🟢 Public.

**What it does.** Lists all attachments on the answer, ordered by
`displayOrder`.

**Response `200`** — `List<AnswerAttachmentResponse>`.

---

### 16.3 `PATCH /api/v1/questions/{questionId}/answers/{answerId}/attachments/{attachmentId}`

**Auth:** 🟡 Author-only.

**What it does.** Updates `caption` or `displayOrder` on a single
attachment (no file replacement — delete + re-upload for that).

**Request body** (`UpdateAnswerAttachmentRequest`):

```json
{ "caption": "Updated caption", "displayOrder": 1 }
```

**Response `200`** — updated `AnswerAttachmentResponse`.

---

### 16.4 `DELETE /api/v1/questions/{questionId}/answers/{answerId}/attachments/{attachmentId}`

**Auth:** 🟡 Author-only.

**What it does.** Deletes the attachment row and removes the underlying
R2 / S3 object.

**Response:** `204 No Content`.

---

## 17. Answer sources / references

Bibliographic citations: BOOK / DOI / URL / ISBN / FILE, etc. Shown
in a citations panel under the answer body.

### 17.1 `POST /api/v1/questions/{questionId}/answers/{answerId}/sources`

**Auth:** 🟡 Author-only (answer author).

**What it does.** Adds a citation. At least one of `url`, `doi`,
`isbn`, or `fileUrl` should be populated depending on `sourceType`,
but no field is server-enforced — the UI should validate.

**Request body** (`CreateAnswerSourceRequest`):

```json
{
  "sourceType":   "BOOK",
  "title":        "Tafsir Ibn Kathir, vol. 6",
  "citationText": "Ibn Kathir, Tafsir al-Qur'an al-Adheem, Dar Tayyibah…",
  "isbn":         "978-9960-892-77-7"
}
```

**Response `201`** — `AnswerSourceResponse`.

---

### 17.2 `GET /api/v1/questions/{questionId}/answers/{answerId}/sources`

**Auth:** 🟢 Public.

**What it does.** Returns the answer's bibliography list ordered by
`displayOrder`.

**Response `200`** — `List<AnswerSourceResponse>`.

---

### 17.3 `PATCH /api/v1/questions/{questionId}/answers/{answerId}/sources/{sourceId}`

**Auth:** 🟡 Author-only.

**What it does.** Updates any of `title`, `citationText`, `url`,
`doi`, `isbn`, or `displayOrder`.

**Request body** (`UpdateAnswerSourceRequest`):

```json
{
  "title":        "Tafsir Ibn Kathir, vol. 6 (revised ed.)",
  "displayOrder": 2
}
```

**Response `200`** — updated `AnswerSourceResponse`.

---

### 17.4 `DELETE /api/v1/questions/{questionId}/answers/{answerId}/sources/{sourceId}`

**Auth:** 🟡 Author-only.

**Response:** `204 No Content`.

---

## 18. Save / bookmark

### 18.1 `POST /api/v1/questions/{questionId}/save?collection=<name>`

**Auth:** 🔵 Authenticated. Idempotent — re-calling with a different
`collection` moves the bookmark.

**What it does.** Bookmarks the question into the named collection (or
the default unnamed collection if `collection` is omitted). Emits
`SAVE_COUNT_UPDATED` on the SSE stream.

**When the frontend uses this.** "🔖 Save" tap on a question card.

**Response `201`** — `QuestionResponse` with `isSaved: true`.

---

### 18.2 `DELETE /api/v1/questions/{questionId}/save` — unsave

**Auth:** 🔵 Authenticated.

**What it does.** Removes the caller's bookmark on the question.

**Response `200`** — `QuestionResponse` with `isSaved: false`.

---

### 18.3 `GET /api/v1/questions/me/saved` — saved questions

**Auth:** 🔵 Authenticated.

**What it does.** Page of saved questions, newest-saved first. Each
`QuestionResponse` carries `savedAt` so the frontend can render
"Saved &lt;date&gt;" without an extra fetch.

**When the frontend uses this.** Profile → "Saved" → "Questions" tab.

**Response `200`** — `Page<QuestionResponse>` (with `savedAt`
populated).

---

### 18.4 `GET /api/v1/questions/me/saved/collection?name=<name>`

**Auth:** 🔵 Authenticated.

**What it does.** Same as §18.3 but filtered to a single collection.

**Response `200`** — `Page<QuestionResponse>`.

---

### 18.5 `GET /api/v1/questions/me/saved/collections` — distinct collection names

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

### 18.6 `PATCH /api/v1/questions/me/saved/collections?oldName=…&newName=…`

**Auth:** 🔵 Authenticated.

**What it does.** Renames a collection across every saved row owned
by the caller. Idempotent.

**Response:** `204 No Content`.

---

## 19. Share & share-link preview

### 19.1 `GET /api/v1/questions/{questionId}/share-link` — preview

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

### 19.2 `POST /api/v1/questions/{questionId}/share` — record share

**Auth:** 🟢 Public (authenticated caller is recorded if present).

**What it does.** Atomically bumps `shareCount` and returns the same
`ShareLinkInfo` payload. Call when the user actually copies / sends
the link.

**Response `200`** — `ShareLinkInfo`.

---

## 20. Realtime event types

Defined in `ak.dev.irc.app.qna.realtime.QnaRealtimeEventType`. One
event per discrete state change so the frontend can patch the UI
incrementally.

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

### Feedback events

| Event | When |
|---|---|
| `ANSWER_FEEDBACK_ADDED` | Question author added feedback. |
| `ANSWER_FEEDBACK_EDITED` | Edited feedback. |
| `ANSWER_FEEDBACK_DELETED` | Deleted feedback. |

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

## 21. Cassandra denormalized tables

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

---

## 22. Cross-cutting rules

- **Depth-1 reply cap.** A reanswer to a reanswer is hoisted to a
  sibling reply on the same root answer. Mirrors the post-comment
  rule (`project_reply_nesting_rule.md`).
- **Single reaction type.** LIKE only. No multi-emoji variants.
- **Self-engagement.** Users CAN like / save / share their own
  questions and answers, but self-notifications are skipped server-side.
- **Counter accuracy.** All counters (answers, views, saves, shares,
  reactions, feedback) are atomic via CQL `UPDATE col = col + N` and
  read-through cached in Redis. Treat the SSE counter events as the
  authoritative push delta.
- **Soft-delete answer.** A deleted answer's body, attachments, and
  sources are nulled in responses but the row stays — used to render
  "[deleted]" placeholders without breaking reanswer threads.
- **Author check.** Most mutating endpoints throw `SecurityException`
  if the caller is not the resource author. Until a refactor lands,
  this falls to the catch-all `500` envelope — semantically a `403`.

---
