# Q&A Package — Full API Documentation

This is the complete reference for everything under `ak.dev.irc.app.qna` — the
scholar-driven Questions & Answers module.

## Recent additions

| Section | What changed |
|---------|--------------|
| [§12 — Saves](#12-saves-bookmarks) | Saved-list endpoints (`/me/saved`, `/me/saved/collection`) now populate `savedAt` on each `QuestionResponse` row. |
| [§20 — `QuestionResponse` shape](#dtos) | Added nullable `savedAt` field (the bookmark time). |
| [§15 — Notifications + Activity](#15-notifications) | Every QnA mutation now records a row on the user activity feed (`QNA_QUESTION_SAVED` is new). |

It covers:

- [Domain model overview](#1-domain-model-overview)
- [Questions](#2-questions)
- [Question feeds & search](#3-question-feeds--search)
- [Answer controls (lock, max-answers)](#4-answer-controls)
- [Answers (top-level + reanswers / replies)](#5-answers)
- [Reactions on answers](#6-reactions-on-answers)
- [Accept / Unaccept](#7-accept--unaccept)
- [Multi-scholar best-answer voting](#8-best-answer-voting)
- [Feedback](#9-feedback)
- [Attachments (files per answer)](#10-attachments)
- [Sources / references](#11-sources--references)
- [Saves (bookmarks) & collections](#12-saves-bookmarks)
- [Share link](#13-share)
- [Views](#14-views)
- [Notifications (kinds emitted by QnA)](#15-notifications)
- [Realtime (SSE)](#16-realtime-sse)
- [Enums (full catalog)](#17-enums)
- [JPA entities](#18-jpa-entities)
- [Cassandra entities (mirror tables)](#19-cassandra-mirror-tables)
- [Request / response DTOs](#20-dtos)

All endpoints live under `/api/v1/questions/...`. Mutation endpoints require
`@PreAuthorize("isAuthenticated()")`. The authenticated user is extracted
from the JWT principal — any body-supplied user/author ids are ignored.

---

## 1. Domain model overview

The Q&A module is a forum-style discussion engine. The hierarchy is:

```
Question
 ├── Answer (top-level)
 │    ├── Reanswer (= reply; flat at depth 1)
 │    ├── Attachments (PDF/Word/ZIP/video/audio/image)
 │    ├── Sources / references (Hadith, Quran, URL, DOI, ISBN, ...)
 │    ├── Feedback (typed feedback from the question author / scholars)
 │    ├── Reactions (single LIKE per user)
 │    └── BestAnswerVote (one row per (answer, scholar))
 └── QuestionSave (bookmark per (user, question))
```

Key design rules:

- **Single reaction type** — `AnswerReactionType` is `LIKE` only.
  Mirrors the post and research packages — "academic not entertainment".
- **Reanswers flat at depth 1** — a reanswer-to-a-reanswer is silently hoisted
  back up to the original top-level answer.
- **Soft delete** for questions and answers (`deletedAt` timestamp). Hard
  deletes never happen from the API.
- **Counter columns are denormalised** on `Question` (`answerCount`, `viewCount`,
  `saveCount`, `shareCount`) and `QuestionAnswer` (`reactionCount`, `replyCount`,
  `bestAnswerVoteCount`) so list endpoints don't N+1.
- **Atomic counter updates** through repository JPQL — entity setter + save
  was racy under concurrent reactions.
- **`CounterCache`** mirrors the denormalised counts in Redis so the next read
  doesn't have to hit Postgres.
- **Block-aware** — a viewer in a block edge with the question author gets
  `404` for the question and the question is dropped from feeds.
- **Restriction-aware listings** — answers from users the question author has
  restricted are hidden from everyone except the question author and the
  answer author themselves.
- **Multi-scholar best answer** — *any* scholar (or admin) may mark *any*
  top-level answer as a best answer; reanswers are not eligible.
- **Multiple acceptance** — the question author's `accept` flag and the
  scholar best-vote are independent and additive (a question can have many
  accepted answers and many best-voted answers).

---

## 2. Questions

### Statuses — `QuestionStatus`

`OPEN` · `ANSWERED` · `CLOSED` · `ARCHIVED`.

Status transitions:

| Trigger | New status |
|---------|-----------|
| Creation | `OPEN` |
| First top-level answer | `ANSWERED` (set in `addAnswer`) |
| Manual lifecycle endpoints | `CLOSED`, `ARCHIVED` (admin) |

### Endpoints (base: `/api/v1/questions`)

#### Create

`POST /api/v1/questions`

Body (`CreateQuestionRequest`):

```json
{
  "title": "What is the ruling on ...?",         // required, ≤ 500 chars
  "body": "Detailed background ...",             // required, ≤ 10000 chars
  "answersLocked": false,                        // default false
  "maxAnswers": 5                                // null = unlimited
}
```

Side effects:

- Publishes `QuestionCreatedEvent` on RabbitMQ
  (`RabbitMQConstants.QNA_QUESTION_CREATED`) — the
  `NotificationEventConsumer` listens and fans out `QUESTION_NEW` notifications.
- `userActivityService.recordQnaQuestionCreated(...)`
- `@mention` scan + publish over **title + body** with `allowFollowersToken=true`
  (questions can `@followers`).
- Indexes the question in Elasticsearch (`irc-qna`, async).

Response: `201 QuestionResponse`.

#### Get one

`GET /api/v1/questions/{questionId}`

Block-aware. Bumps a deduped view counter (see [Views](#14-views)) and
broadcasts `VIEW_COUNT_UPDATED` on the question's realtime channel.

#### Edit

`PATCH /api/v1/questions/{questionId}`

Body (`EditQuestionRequest`): any of `title` / `body` / `answersLocked` /
`maxAnswers`. Author-only (admins can manage too). Triggers a delta
`@mention` scan against the previous text.

Broadcasts `QUESTION_UPDATED`.

#### Delete

`DELETE /api/v1/questions/{questionId}`

Soft delete (sets `deletedAt`). Author or admin. Publishes
`QuestionDeletedEvent`. Broadcasts `QUESTION_DELETED`.

#### "Mine"

`GET /api/v1/questions/me?page=&size=` — viewer's own questions (auth).

---

## 3. Question feeds & search

### Standard paginated feed

`GET /api/v1/questions?page=0&size=20`

Block-aware — drops questions whose author is in a block edge with the viewer.
Anonymous viewers see everyone.

### Cursor-paginated feed (preferred for infinite scroll)

`GET /api/v1/questions/feed/cursor?cursor={iso-datetime}&limit=20`

First page: omit `cursor`. Next page: pass `nextCursor` from the previous
response. End-of-feed: `nextCursor: null`, `hasMore: false`.

Response wrapper:

```json
{ "items": [QuestionResponse...], "nextCursor": "2026-05-19T18:23:11", "hasMore": true }
```

### Following feed

`GET /api/v1/questions/feed/following?page=&size=` (auth) — questions from
users the viewer follows.

### Full-text search (Elasticsearch)

`GET /api/v1/questions/search?q=zakat&page=0&size=20`

Returns `{ query, page, size, results: [<UUIDs>] }`. Search index
(`QnaSearchDocument`, index `irc-qna`) is updated async on create/edit/delete.

---

## 4. Answer controls

Question-author and admin-only.

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{questionId}/lock-answers` | Lock new answers. Returns updated `QuestionResponse`. Broadcasts `QUESTION_LOCKED`. |
| `DELETE` | `/{questionId}/lock-answers` | Unlock. Broadcasts `QUESTION_UNLOCKED`. |
| `PATCH`  | `/{questionId}/answer-limit?maxAnswers=N` | Set or clear (`maxAnswers=null` / `≤0`) the cap. |

When locked or when `answerCount ≥ maxAnswers`, `addAnswer` for **top-level
answers** throws `ANSWER_LIMIT_REACHED`. Reanswers are not counted toward
the limit.

---

## 5. Answers

### Threading rule

- Top-level answer: `parentAnswerId = null`.
- Reanswer (reply): `parentAnswerId` set to a top-level answer's id.
- If the client passes the id of an existing **reanswer** as the parent, the
  server hoists the parent up to the top-level answer — depth-1 is enforced
  server-side and a malicious / buggy client cannot produce depth-2.

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET`    | `/{questionId}/answers?page=&size=` | List top-level answers. Restriction-aware. |
| `POST`   | `/{questionId}/answers` | Create answer (JSON). Body: `CreateAnswerRequest`. |
| `POST`   | `/{questionId}/answers/upload` (multipart) | Create answer + inline media + voice note in one request. Parts: `data` (JSON `CreateAnswerRequest`), `media` (image/video, optional), `voice` (audio, optional). |
| `GET`    | `/{questionId}/answers/{answerId}/reanswers` (alias `…/replies`) | List reanswers under an answer. |
| `POST`   | `/{questionId}/answers/{answerId}/reanswers` (alias `…/replies`) | Add a reanswer. Sets `parentAnswerId` server-side. |
| `POST`   | `/{questionId}/answers/{answerId}/reanswers/upload` (alias `…/replies/upload`) | Multipart variant. |
| `PATCH`  | `/{questionId}/answers/{answerId}` | Edit. Author/admin only. Body: `EditAnswerRequest`. |
| `DELETE` | `/{questionId}/answers/{answerId}` | Soft-delete. Author / question-author / admin. |

### `CreateAnswerRequest`

```json
{
  "body": "...",                     // required, ≤ 10000 chars
  "parentAnswerId": null,            // set for reanswers (or use the /reanswers endpoint)
  "mediaUrl": "...",                 // legacy single-media support
  "mediaType": "IMAGE",              // IMAGE | VIDEO
  "mediaThumbnailUrl": "...",
  "voiceUrl": "...",
  "voiceDurationSeconds": 42,
  "links": "https://x.com, https://y.com",   // comma-separated URLs
  "sources": [ CreateAnswerSourceRequest, ... ]
}
```

### Side effects on create

- Atomic `answer_count++` on the question (only for top-level answers).
- Question transitions to `ANSWERED` after the first top-level answer.
- `replyCount++` on the parent (for reanswers).
- Publishes `QuestionAnsweredEvent` (`QNA_QUESTION_ANSWERED`) — the
  notification consumer dispatches `QUESTION_ANSWERED` to the question author.
- `userActivityService.recordQnaAnswerCreated(...)`
- `@mention` scan over the answer body (no `@followers` token).
- Broadcasts `ANSWER_CREATED` or `REANSWER_CREATED` (with fresh
  `questionAnswerCount` for top-level).

### Block guards

- Creating a reanswer that crosses a block edge with the parent's author
  fails with `REANSWER_BLOCKED_RELATIONSHIP`.
- Best-answer votes that cross a block edge fail with
  `BEST_ANSWER_BLOCKED_RELATIONSHIP`.

---

## 6. Reactions on answers

Apply equally to top-level answers AND reanswers.

`AnswerReactionType` is **`LIKE`** only — the request body's `reactionType`
defaults to `LIKE` and any other value behaves the same.

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{questionId}/answers/{answerId}/react` | Like. Body (optional): `{reactionType:"LIKE"}`. Returns the updated `QuestionAnswerResponse` (with `myReaction`, fresh `reactionCount`). Idempotent — second call is a no-op. |
| `DELETE` | `/{questionId}/answers/{answerId}/react` | Unlike. Idempotent — if no row exists, broadcasts the authoritative count anyway so stale UIs can reconcile. |

Write path:

- `answer_reactions` row (composite PK `(answer_id, user_id)`).
- Atomic JPQL `update reaction_count = reaction_count ± 1` (clamp-at-zero
  on the decrement query) — entity setter + save was racy.
- `CounterCache` mirror update for the live count.
- Publishes `AnswerReactedEvent` / `AnswerUnreactedEvent` → notification
  consumer dispatches `ANSWER_REACTED` to the answer author.
- `userActivityService.recordQnaAnswerReaction(...)`.
- Broadcasts `ANSWER_REACTION_ADDED` / `ANSWER_REACTION_REMOVED` with fresh
  `answerReactionCount`.

A Cassandra mirror is provided by `QnaReactionByAnswerEntity` (point-lookup)
and `QnaReactionByUserEntity` (user history).

---

## 7. Accept / Unaccept

Per-question-author flag (kept for back-compat with the original single-best
answer UX). Multiple answers can be `accepted=true` on the same question
since the introduction of multi-scholar voting.

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{questionId}/answers/{answerId}/accept`   | Question-author / admin only. Reanswers cannot be accepted (`REANSWER_NOT_ACCEPTABLE`). Publishes `AnswerAcceptedEvent` → notification consumer dispatches `ANSWER_ACCEPTED` to the answer author. Broadcasts `ANSWER_ACCEPTED`. |
| `DELETE` | `/{questionId}/answers/{answerId}/accept`   | Unaccept. Broadcasts `ANSWER_UNACCEPTED`. |

---

## 8. Best-answer voting

Multi-scholar — any SCHOLAR / ADMIN / SUPER_ADMIN may mark any top-level
answer as a best answer. Multiple scholars may vote independently for the
same or different answers; the count is exposed via
`QuestionAnswer.bestAnswerVoteCount` (mirrored from `BestAnswerVote` rows).

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{questionId}/answers/{answerId}/best` | Vote. Idempotent — repeat vote returns the current state without double-counting. Reanswers throw `REANSWER_NOT_ELIGIBLE`. Block guards apply against answer-author AND question-author. |
| `DELETE` | `/{questionId}/answers/{answerId}/best` | Unvote. |

Role gate: `findBestAnswerVoterOrThrow` requires `SCHOLAR` / `ADMIN` /
`SUPER_ADMIN`. Researchers may answer but are not authoritative on "best".

Write fan-out:

- `best_answer_votes` row (composite PK `(answer_id, voter_id)`).
- `answer.bestAnswerVoteCount` ++/--.
- Publishes `BestAnswerVotedEvent` (routing depends on action).
- `userActivityService.recordQnaBestAnswerVote(...)`
- Broadcasts `BEST_ANSWER_VOTED` / `BEST_ANSWER_UNVOTED` with fresh
  `bestAnswerVoteCount`.

On the response:

- `isBestAnswer = bestAnswerVoteCount > 0` — true the moment at least one
  scholar (or the question author via `accepted=true`) has marked it.
- `votedByMe` — the current viewer's own vote state.

---

## 9. Feedback

Question authors (and scholars) give typed feedback on individual answers —
quality signal that's surfaced alongside the answer.

`FeedbackType` enum: `EXCELLENT`, `HELPFUL`, `NEEDS_IMPROVEMENT`,
`INCORRECT`, `OFF_TOPIC`.

Unique constraint: **one feedback row per (answer, author)** —
`uk_feedback_answer_author`.

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{questionId}/answers/{answerId}/feedback` | Add feedback. Body: `{feedbackType, body}` (body ≤ 5000 chars). Returns `AnswerFeedbackResponse`. |
| `GET`    | `/{questionId}/answers/{answerId}/feedback` | List feedback on an answer. |
| `PATCH`  | `/{questionId}/answers/{answerId}/feedback/{feedbackId}` | Edit feedback. Author of the feedback only. |
| `DELETE` | `/{questionId}/answers/{answerId}/feedback/{feedbackId}` | Delete feedback. |

Side effects:

- Publishes `AnswerFeedbackAddedEvent` → `ANSWER_FEEDBACK_RECEIVED`
  notification to the answer author.
- `userActivityService.recordQnaAnswerFeedback(...)`.
- Broadcasts `ANSWER_FEEDBACK_ADDED` / `_EDITED` / `_DELETED` carrying
  `feedbackId` + `feedbackType` on the question's realtime channel.

---

## 10. Attachments

File uploads attached to an answer — PDFs, Word docs, ZIPs, videos, audios,
images. Stored on S3/R2 with `s3Key` + `fileUrl` (CDN/public URL) + thumbnail
for videos/docs.

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{questionId}/answers/{answerId}/attachments` (multipart) | Upload. Parts: `file` (required), form fields `caption`, `displayOrder`. |
| `GET`    | `/{questionId}/answers/{answerId}/attachments` | List (ordered ASC by `displayOrder`). |
| `PATCH`  | `/{questionId}/answers/{answerId}/attachments/{attachmentId}` | Update caption / displayOrder. |
| `DELETE` | `/{questionId}/answers/{answerId}/attachments/{attachmentId}` | Delete. |

`MediaType` (shared from research package) is inferred from the MIME type
(`image/*` → `IMAGE`, `video/*` → `VIDEO`, `audio/*` → `AUDIO`,
`application/pdf` → `PDF`, etc.).

---

## 11. Sources / references

Citation list per answer — Hadith reference, Quran verse, URL, DOI, ISBN, or
an uploaded source file. `SourceType` is shared from the research package.

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{questionId}/answers/{answerId}/sources` | Add a source. Body: `CreateAnswerSourceRequest`. |
| `GET`    | `/{questionId}/answers/{answerId}/sources` | List (ordered ASC by `displayOrder`). |
| `PATCH`  | `/{questionId}/answers/{answerId}/sources/{sourceId}` | Update. Any of `sourceType` / `title` / `citationText` / `url` / `doi` / `isbn` / `displayOrder`. |
| `DELETE` | `/{questionId}/answers/{answerId}/sources/{sourceId}` | Delete. |

`CreateAnswerSourceRequest`:

```json
{
  "sourceType": "HADITH",                // required
  "title": "Sahih Bukhari, Hadith 1395", // required, ≤ 500 chars
  "citationText": "...full citation...",
  "url": "https://...",
  "doi": "10.xxxx/xxxxx",
  "isbn": "978-..."
}
```

When the source is an uploaded file, `fileUrl` / `s3Key` /
`originalFileName` / `mimeType` / `fileSize` populate on the entity (upload
is handled by an adjacent storage path; the controller here is JSON-only).

---

## 12. Saves (bookmarks)

Mirrors `/posts/{id}/saves` and the research-package save endpoints —
same collections pattern.

Composite PK `(question_id, user_id)` on `QuestionSave` prevents
double-saves. The service is idempotent at the DB layer — re-saving a
question already bookmarked is a no-op that still returns the updated
payload (so the front-end can call this blindly).

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{questionId}/save?collection=Hadith` | Save (bookmark). `collection` defaults to `"Default"`. Returns updated `QuestionResponse`. |
| `DELETE` | `/{questionId}/save` | Remove the viewer's bookmark. Idempotent. |
| `GET`    | `/me/saved?page=&size=` | Viewer's saved questions (newest first). Each row carries `savedAt`. |
| `GET`    | `/me/saved/collection?name=Hadith&page=&size=` | Saved questions filtered by collection. Each row carries `savedAt`. |
| `GET`    | `/me/saved/collections` | Distinct collection names the viewer has used. |
| `PATCH`  | `/me/saved/collections?oldName=&newName=` | Rename a collection across every save row. |

### Saved-list response shape

Every row in `/me/saved` and `/me/saved/collection` is a
`QuestionResponse` with the additional save-context field:

| Field | Type | Meaning |
|-------|------|---------|
| `savedAt` | `LocalDateTime` | When the viewer bookmarked the question (the `QuestionSave` row's `createdAt`). Distinct from the question's own `createdAt`. Null on every other endpoint. |

The `isSaved` flag is always `true` on these endpoints (every row is
by definition saved by the viewer). The frontend can render
`"Saved {savedAt}"` directly without a follow-up fetch.

### Side effects on save (toggle ON only)

| Storage | Effect |
|---------|--------|
| `question_saves`        | New JPA row (composite PK `(question_id, user_id)`) |
| `question.saveCount`    | `+ 1` (atomic JPQL `adjustSaveCount`) |
| `CounterCache`          | Mirror updated in Redis |
| Cassandra mirror        | `QuestionSaveByUserEntity` + `QuestionSaveLookupEntity` (eventual) |
| Realtime                | Broadcasts `SAVE_COUNT_UPDATED` with fresh `questionSaveCount` on the question's stream |
| Activity feed           | `QNA_QUESTION_SAVED` row inserted for the viewer |

### Side effects on unsave

| Storage | Effect |
|---------|--------|
| `question_saves`        | Row deleted |
| `question.saveCount`    | `- 1` (clamp at zero) |
| Realtime                | `SAVE_COUNT_UPDATED` broadcast |
| Activity feed           | **NOT** recorded (toggle-ON only) |

---

## 13. Share

Mirrors `PostController.copyShareLink` — separate "preview" (no counter
bump) and "record" (atomic bump + return).

| Method | Path | Purpose |
|--------|------|---------|
| `GET`  | `/{questionId}/share-link` | Returns the share URL without bumping the counter. Used by inline share UI. |
| `POST` | `/{questionId}/share` | Atomically `shareCount++` and returns the share URL. Broadcasts `SHARE_COUNT_UPDATED`. |

`ShareLinkInfo` response shape:

```json
{
  "backendUrl":  "https://api.../q/<uuid>",
  "frontendUrl": "https://app.../questions/<uuid>",
  "token":       "<uuid>",
  "shareCount":  <long>
}
```

---

## 14. Views

Persistent unique-viewer ledger — one row per `(question, user)` pair
forever. Replaces the previous Redis 1h window so each authenticated user
counts as a single view forever.

Recording is triggered automatically by `GET /api/v1/questions/{id}`:

- Authenticated viewers: dedupe key = `userId`.
- Anonymous viewers: dedupe key = client fingerprint
  (`X-Forwarded-For` first hop, falling back to `RemoteAddr`).

JPA table: `question_views` (PK `(question_id, user_id)`).
Cassandra mirror: `QuestionViewEntity` (`question_views_by_question`).

Side effects on a fresh view:

- `question.viewCount++` in its own `REQUIRES_NEW` transaction (so a read
  failure can't corrupt the counter).
- Broadcasts `VIEW_COUNT_UPDATED` with fresh `questionViewCount`.

---

## 15. Notifications

The QnA module emits domain events via `QuestionEventPublisher` (RabbitMQ
on `IRC_EXCHANGE` with kind-specific routing keys). The
`NotificationEventConsumer` listens, applies recipient resolution +
suppression, and dispatches the actual notification through the standard
pipeline.

### `NotificationKind` values fired by QnA

| Kind | Triggered by | Recipient | Aggregable | Email |
|------|--------------|-----------|------------|-------|
| `QUESTION_NEW` | `publishQuestionCreated` (new question landed in an area the recipient follows) | followers of the asker / interested users | no | no |
| `QUESTION_ANSWERED` | `publishQuestionAnswered` (someone answered your question) | Question author | no | yes |
| `ANSWER_REPLIED` | A reanswer is created under your answer (fan-out by the consumer from the reanswer event) | Parent answer author | yes | yes |
| `ANSWER_REACTED` | `publishAnswerReacted` (someone liked your answer) | Answer author | yes | no |
| `ANSWER_ACCEPTED` | `publishAnswerAccepted` (your answer was marked accepted by the question author) | Answer author | no | yes |
| `ANSWER_FEEDBACK_RECEIVED` | `publishFeedbackAdded` (someone left feedback on your answer) | Answer author | no | no |

### Group-key patterns (for the aggregable kinds)

```
ANSWER_REACTED:{answerId}
ANSWER_REPLIED:{parentAnswerId}
```

`ANSWER_ACCEPTED`, `QUESTION_ANSWERED`, `ANSWER_FEEDBACK_RECEIVED`, and
`QUESTION_NEW` are non-aggregable — every event deserves its own inbox row.

### RabbitMQ routing keys (`RabbitMQConstants`)

```
QNA_QUESTION_CREATED
QNA_QUESTION_ANSWERED
QNA_QUESTION_DELETED
QNA_ANSWER_ACCEPTED
QNA_ANSWER_DELETED
QNA_ANSWER_REACTED
QNA_ANSWER_UNREACTED
QNA_BEST_ANSWER_VOTED         // routing key chosen at runtime by voted/unvoted
QNA_FEEDBACK_ADDED
```

The publisher runs inside a `TransactionSynchronization.afterCommit` so the
notification only fires once the DB transaction is durable.

### Inbox / SSE endpoints

The user-facing notification API is shared across all modules and lives at
`/api/v1/notifications` (see the [Post API doc](./POST_API.md#14-notifications)
for full reference of those endpoints — listing, mark-read, delete, SSE
stream).

---

## 16. Realtime (SSE)

### Stream endpoint

`GET /api/v1/questions/{questionId}/stream`  ·  `Content-Type: text/event-stream`

A `connected` handshake fires on subscribe. A `heartbeat` event fires every
~25s. Stale emitters are removed silently. Cross-instance fan-out is via
Redis pub/sub (`QnaRealtimePublisher` / `QnaRealtimeSubscriber`).

The actor's own subscription is filtered out server-side
(`actorId.equals(viewerId)` → skip) so the originating tab doesn't render
its own event twice.

### `QnaRealtimeEventType` — full catalog

```
ANSWER_CREATED, REANSWER_CREATED, ANSWER_EDITED, ANSWER_DELETED,
ANSWER_REACTION_ADDED, ANSWER_REACTION_CHANGED, ANSWER_REACTION_REMOVED,
ANSWER_ACCEPTED, ANSWER_UNACCEPTED,
BEST_ANSWER_VOTED, BEST_ANSWER_UNVOTED,
ANSWER_FEEDBACK_ADDED, ANSWER_FEEDBACK_EDITED, ANSWER_FEEDBACK_DELETED,
QUESTION_UPDATED, QUESTION_DELETED, QUESTION_LOCKED, QUESTION_UNLOCKED,
VIEW_COUNT_UPDATED, SAVE_COUNT_UPDATED, SHARE_COUNT_UPDATED
```

### `QnaRealtimeEvent` payload

All fields nullable, `@JsonInclude(NON_NULL)`.

```
eventType                 QnaRealtimeEventType
questionId                UUID
actorId, actorUsername, actorAvatarUrl
answerId, parentAnswerId
feedbackId
reactionType              // "LIKE"
previousReactionType      // for REACTION_CHANGED (forward-compat)
body                      // ANSWER_CREATED / EDITED / REANSWER_CREATED
feedbackType              // FEEDBACK_* events
questionAnswerCount       // fresh count after a top-level create / delete
questionViewCount         // VIEW_COUNT_UPDATED
questionSaveCount         // SAVE_COUNT_UPDATED
shareCount                // SHARE_COUNT_UPDATED
answerReactionCount       // ANSWER_REACTION_*
answerReplyCount          // REANSWER_CREATED / ANSWER_DELETED
bestAnswerVoteCount       // BEST_ANSWER_VOTED / _UNVOTED
timestamp                 // LocalDateTime, default now
```

---

## 17. Enums

| Enum | Values | Notes |
|------|--------|-------|
| `QuestionStatus` | `OPEN`, `ANSWERED`, `CLOSED`, `ARCHIVED` | Becomes `ANSWERED` after the first top-level answer. |
| `AnswerReactionType` | `LIKE` | **Single-reaction-type project rule** — only one value. |
| `FeedbackType` | `EXCELLENT`, `HELPFUL`, `NEEDS_IMPROVEMENT`, `INCORRECT`, `OFF_TOPIC` | One feedback row per `(answer, author)`. |

Shared enums from sibling packages:

- `ak.dev.irc.app.research.enums.MediaType` — used on `AnswerAttachment`
  (`IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT`, ...).
- `ak.dev.irc.app.research.enums.SourceType` — used on `AnswerSource`
  (`BOOK`, `JOURNAL`, `URL`, `DOI`, `QURAN`, `HADITH`, `MANUSCRIPT`, ...).

---

## 18. JPA entities

### `Question`

Table: `questions`, indexes: `idx_question_author`, `idx_question_status`,
`idx_question_deleted`.

Key columns:

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID | PK |
| `author_id` | UUID | FK → `users` |
| `title`, `body` | text | required |
| `status` | enum `QuestionStatus` | default `OPEN` |
| `answer_count`, `view_count`, `save_count`, `share_count` | Long | denormalised, atomic updates |
| `answers_locked` | bool | default false |
| `max_answers` | Integer | null = unlimited |
| `deleted_at` | timestamp | null = active |
| `@OneToMany answers` | `List<QuestionAnswer>` | cascade ALL + orphanRemoval |

### `QuestionAnswer`

Table: `question_answers`, indexes on `question_id`, `author_id`,
`deleted_at`, `parent_answer_id`.

Key columns:

| Column | Type | Notes |
|--------|------|-------|
| `id`, `question_id`, `author_id` | UUID | |
| `parent_answer_id` | UUID | nullable. Null = top-level. |
| `body` | text | required, ≤ 10000 |
| Single-media legacy fields | `media_url`, `media_s3_key`, `media_type`, `media_thumbnail_url`, `media_thumbnail_s3_key` | |
| Voice | `voice_url`, `voice_s3_key`, `voice_duration_seconds` | |
| `links` | text | comma-separated URLs |
| `reaction_count`, `reply_count`, `best_answer_vote_count` | Long | denormalised |
| `is_accepted` | bool | question-author flag |
| `is_edited`, `edited_at`, `deleted_at` | | |
| `@OneToMany attachments` | `AnswerAttachment` |  |
| `@OneToMany sources` | `AnswerSource` | |
| `@OneToMany feedbacks` | `AnswerFeedback` | |

Helper increment / decrement methods are defined on the entity but the
**service layer always uses atomic JPQL updates** (`updateReactionCount`,
`updateReplyCount`, `adjustAnswerCount`) — the entity setters are kept only
for use after `entityManager.refresh()`.

### `AnswerReaction`

Table: `answer_reactions`. Composite PK `(answer_id, user_id)` via
`AnswerReactionId`. `reactionType` column is always `LIKE`.

### `AnswerAttachment`

Table: `answer_attachments`. Per-answer file rows.

### `AnswerSource`

Table: `answer_sources`. Per-answer citations.

### `AnswerFeedback`

Table: `answer_feedbacks`. Unique constraint
`uk_feedback_answer_author (answer_id, author_id)` — one feedback row per
(answer, author) pair.

### `BestAnswerVote`

Table: `best_answer_votes`. Composite PK `(answer_id, voter_id)` via
`BestAnswerVoteId`. Mirrored count: `QuestionAnswer.bestAnswerVoteCount`.

### `QuestionSave`

Table: `question_saves`. Composite PK `(question_id, user_id)`. Carries
`collection_name` (defaults to `"Default"`).

### `QuestionView`

Table: `question_views`. Composite PK `(question_id, user_id)`. Replaces
the previous Redis 1h dedupe so each user counts once forever.

---

## 19. Cassandra mirror tables

Used for cross-DC scale-out reads. Source-of-truth is still Postgres; these
are read-optimised mirrors.

| Table | Role |
|-------|------|
| `qna_reactions_by_answer` | "Did user U react to answer A?" point lookup |
| `qna_reactions_by_user`   | "What did user U recently react to?" (DESC `created_at`) |
| `question_saves_by_user`  | A user's saved questions, newest first |
| `question_saves_lookup`   | "Has user U saved question Q?" point lookup |
| `question_views_by_question` | Unique-viewer set per question |

---

## 20. DTOs

Response shapes are records (immutable). Every response carries the author
profile inline so the frontend never round-trips to `/users/{id}` just to
render a name/avatar.

### `QuestionResponse`

```java
record QuestionResponse(
    UUID id,
    UUID authorId,
    String authorUsername,
    String authorFullName,
    String authorProfileImage,
    String title,
    String body,
    QuestionStatus status,
    Long answerCount,
    Long viewCount,
    Long saveCount,
    boolean answersLocked,
    Integer maxAnswers,
    boolean isSaved,             // viewer-relative
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String timeAgo,              // pre-computed "2 hours ago" string
    String formattedDate,
    /** When the viewer bookmarked this question. Populated only by
     *  saved-list endpoints ({@code GET /me/saved},
     *  {@code /me/saved/collection}). Null on every other endpoint. */
    LocalDateTime savedAt
) {}
```

### `QuestionAnswerResponse`

```java
record QuestionAnswerResponse(
    UUID id, UUID questionId, UUID authorId,
    String authorUsername, String authorFullName, String authorProfileImage,
    String body,

    // threading
    UUID parentAnswerId,
    long replyCount,

    // legacy single-media + voice + links
    String mediaUrl, String mediaType, String mediaThumbnailUrl,
    String voiceUrl, Integer voiceDurationSeconds,
    String links,

    // rich attachments and citations
    List<AnswerAttachmentResponse> attachments,
    List<AnswerSourceResponse>     sources,

    // status
    boolean accepted,
    boolean isBestAnswer,                  // = bestAnswerVoteCount > 0
    long    bestAnswerVoteCount,
    boolean votedByMe,                     // current viewer's vote state

    boolean edited, LocalDateTime editedAt,
    boolean deleted, LocalDateTime deletedAt,
    long feedbackCount,
    long reactionCount,
    AnswerReactionType myReaction,         // null if not reacted
    LocalDateTime createdAt, LocalDateTime updatedAt,
    String timeAgo, String formattedDate
) {}
```

### `AnswerAttachmentResponse`

```java
record AnswerAttachmentResponse(
    UUID id, UUID answerId,
    String fileUrl, String originalFileName, String mimeType,
    MediaType mediaType, Long fileSize,
    Integer displayOrder, String caption,
    Integer durationSeconds,   // video/audio
    String thumbnailUrl,
    LocalDateTime createdAt
) {}
```

### `AnswerSourceResponse`

```java
record AnswerSourceResponse(
    UUID id, UUID answerId,
    SourceType sourceType,
    String title, String citationText,
    String url, String doi, String isbn,
    String fileUrl, String originalFileName,
    Integer displayOrder,
    LocalDateTime createdAt
) {}
```

### `AnswerFeedbackResponse`

```java
record AnswerFeedbackResponse(
    UUID id, UUID answerId,
    UUID authorId, String authorUsername, String authorFullName, String authorProfileImage,
    FeedbackType feedbackType,
    String body,
    LocalDateTime createdAt, LocalDateTime updatedAt
) {}
```

### Request DTOs

| DTO | Used by | Required fields |
|-----|---------|-----------------|
| `CreateQuestionRequest` | `POST /questions` | `title` (≤500), `body` (≤10000) |
| `EditQuestionRequest`   | `PATCH /questions/{id}` | any subset of `title`, `body`, `answersLocked`, `maxAnswers` |
| `CreateAnswerRequest`   | `POST /answers` (incl. multipart `data` part) | `body` (≤10000), optional `parentAnswerId`, media, voice, links, sources |
| `EditAnswerRequest`     | `PATCH /answers/{id}` | `body` (≤5000) |
| `ReactToAnswerRequest`  | `POST /answers/{id}/react` | `reactionType` (defaults to `LIKE`) |
| `AddFeedbackRequest`    | `POST /feedback`, `PATCH /feedback/{id}` | `feedbackType`, optional `body` (≤5000) |
| `CreateAnswerSourceRequest` | `POST /sources` | `sourceType`, `title` (≤500) |
| `UpdateAnswerSourceRequest` | `PATCH /sources/{id}` | any subset of source fields + `displayOrder` |
| `UpdateAnswerAttachmentRequest` | `PATCH /attachments/{id}` | `caption` (≤500), `displayOrder` |

### Shared types

- `ak.dev.irc.app.post.dto.CursorPage<T>` — generic `{items, nextCursor,
  hasMore}` cursor wrapper, reused by `/feed/cursor`.
- `ak.dev.irc.app.share.ShareLinkInfo` — `{backendUrl, frontendUrl, token,
  shareCount}` for the share endpoint.

---

## 21. Activity feed integration

Every QnA mutation writes a row into the per-user activity history.
Full reference in [USER_ACTIVITY_API.md](./USER_ACTIVITY_API.md).

| QnA action | Activity type | Notes |
|------------|---------------|-------|
| `POST /questions` | `QNA_QUESTION_CREATED` | `questionId` carried. |
| `POST /{questionId}/save` (toggle ON) | `QNA_QUESTION_SAVED` | Unsaves NOT recorded. |
| `POST /{questionId}/answers` | `QNA_ANSWER_CREATED` *or* `QNA_REANSWER_CREATED` (when `parentAnswerId != null`) | Both `questionId` + `answerId` carried. |
| `POST /{questionId}/answers/{answerId}/react` (toggle ON) | `QNA_ANSWER_REACTION` | `qnaReactionType = LIKE`. |
| `POST /{questionId}/answers/{answerId}/best` / `unmark` | `QNA_BEST_ANSWER_VOTE` | One row per vote / unvote — the `voted` flag in the publisher event tells consumers which. |
| `POST /{questionId}/answers/{answerId}/feedback` | `QNA_ANSWER_FEEDBACK` | |
| `@mention` in question / answer body (incoming) | `USER_MENTIONED` | One row per recipient; question text scans for `@followers`, answer text does NOT. |

All `record*` calls are `@Async` + try/catch.

---

## See also

- [POST_API.md](./POST_API.md) — Post / Stories / Reels APIs
- [RESEARCH_API.md](./RESEARCH_API.md) — Research APIs
- [USER_API.md](./USER_API.md) — User identity, profile, social graph, notifications
- [USER_ACTIVITY_API.md](./USER_ACTIVITY_API.md) — Per-user activity feed
- [POST_ERRORS.md](./POST_ERRORS.md) — Complete error & exception reference
- [BACKEND_ENHANCEMENTS.md](./BACKEND_ENHANCEMENTS.md) — Roadmap

---

## Cross-cutting QnA rules

- **Single reaction type (`LIKE`)** mirrors the post / research packages.
- **Reanswers flat at depth 1** — server hoists deeper attempts up.
- **Soft delete** — questions and answers carry `deletedAt` and are excluded
  from listings, but stay readable by id for audit / linkbacks.
- **Atomic JPQL counter updates** for all `*_count` columns. The entity
  helper methods (`incrementReactions`, etc.) are not used in the create /
  delete write paths.
- **`@mention` scan** — questions allow `@followers`; answers do not.
- **Block-aware reads** + restriction-aware answer listings.
- **Multi-best-answer** — `accepted` (question-author flag) and
  `bestAnswerVoteCount` (multi-scholar) are independent and additive.
- **Best-answer voting role gate** — `SCHOLAR` / `ADMIN` / `SUPER_ADMIN` only.
- **RabbitMQ events fire `afterCommit`** so a rolled-back transaction never
  produces a notification.
- **All side effects wrapped in try/catch** — recording an activity row
  or firing a notification never breaks the originating write.
