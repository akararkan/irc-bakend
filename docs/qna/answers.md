# Q&A API — Answers, Reanswers & Accept Flow

Answering, replying (flat at depth 1), editing/deleting answers, and the author-accept flow.

**Base path:** `/api/v1/questions/{questionId}`

Thread shape:

```
Question
  └── Answer (top-level)            ← counts toward answerCount / maxAnswers
        └── Reanswer (depth-1 reply) ← flat: a reply to a reply is hoisted to a sibling
```

**Replies are flat at depth 1.** If a client posts a reanswer whose parent is itself a
reanswer, the server hoists the parent up to the top-level answer, so every reply is a
sibling under the root. The original target is preserved in `replyToAnswerId` /
`replyToUserId` so the UI can still render "replying to @X".

**Who can answer:** the service gates answer authorship to `SCHOLAR`, `RESEARCHER`, and
`ADMIN` roles (`findAnswerAuthorOrThrow`). Plain `USER` accounts get `403`.

**Endorsement model:** the **only** endorsement is the question author accepting an answer
(`accept`/`unaccept` below). There is **no** answer rating, feedback, or scholar
best-answer voting system.

Errors use the shared envelope — see [Error handling](../errors/error-handling.md).
Sibling docs: [Questions](./questions.md) · [Engagement](./engagement.md) · [Realtime](./realtime.md)

---

## `QuestionAnswerResponse`

Returned by every answer endpoint (and embedded in answer-scoped SSE events).

```json
{
  "id": "a41b2c3d-...",
  "questionId": "3f6f8a3e-...",
  "authorId": "77aa1b2c-...",
  "authorUsername": "scholar_omar",
  "authorFullName": "Omar al-Tunisi",
  "authorProfileImage": "https://cdn.example.com/avatars/omar.jpg",
  "body": "The hadith narrated in Sunan al-Darimi describes...",
  "parentAnswerId": null,
  "replyToAnswerId": null,
  "replyToUserId": null,
  "replyCount": 2,
  "mediaUrl": null,
  "mediaType": null,
  "mediaThumbnailUrl": null,
  "voiceUrl": null,
  "voiceDurationSeconds": null,
  "links": "https://example.org/a,https://example.org/b",
  "attachments": [],
  "sources": [],
  "accepted": false,
  "edited": false,
  "editedAt": null,
  "deleted": false,
  "deletedAt": null,
  "reactionCount": 12,
  "myReaction": "LIKE",
  "createdAt": "2026-07-20T09:15:00",
  "updatedAt": "2026-07-20T09:15:00",
  "timeAgo": "1 hour ago",
  "formattedDate": "20 Jul 2026"
}
```

| Field | Type | Notes |
|---|---|---|
| `parentAnswerId` | UUID\|null | Root answer id for reanswers; `null` for top-level answers. |
| `replyToAnswerId` / `replyToUserId` | UUID\|null | The answer/author the reply was *actually* aimed at, captured **before** depth-1 hoisting. `null` on top-level answers. |
| `replyCount` | long | Denormalized reanswer count (top-level answers only; always 0 on reanswers). |
| `mediaUrl` / `mediaType` / `mediaThumbnailUrl` | string | Single inline media. `mediaType` is `"IMAGE"` or `"VIDEO"`. |
| `voiceUrl` / `voiceDurationSeconds` | — | Optional voice note. |
| `links` | string | **Comma-separated URL string**, not an array — split on `,` to render. |
| `attachments` | `AnswerAttachmentResponse[]` | See [Engagement](./engagement.md#attachments). |
| `sources` | `AnswerSourceResponse[]` | See [Engagement](./engagement.md#sources--references). |
| `accepted` | boolean | Set by the question author via the accept flow. |
| `reactionCount` | long | LIKE count. |
| `myReaction` | enum\|null | `"LIKE"` if the current viewer reacted; `null` if not reacted or anonymous. Batch-resolved per page. |
| `deleted` / `deletedAt` | — | Soft-delete tombstone (relevant in `ANSWER_DELETED` SSE payloads). |

---

## List top-level answers

```
GET /api/v1/questions/{questionId}/answers
```

**Auth:** Optional.

Paged listing of non-deleted **top-level** answers, oldest first (`createdAt ASC`).
Single SQL with an entity-graph fetch on author/parent plus **one batched
`myReaction` lookup for the whole page** — no N+1 round trips.

Visibility rules applied in the query:

- Soft-deleted answers are excluded.
- Answers from users with a block edge to the viewer are excluded.
- Answers whose authors the **question author has restricted** are hidden from everyone
  *except* the question author and the answer author themselves.

### Query parameters

| Param | Type | Default |
|---|---|---|
| `page` | int | 0 |
| `size` | int | 20 |

### Response — `200 OK`: `Page<QuestionAnswerResponse>`.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 404 | `QUESTION_NOT_FOUND` | Unknown/deleted question. |

---

## Post a top-level answer

```
POST /api/v1/questions/{questionId}/answers
```

**Auth:** Required — `SCHOLAR`, `RESEARCHER`, or `ADMIN`.

Creates a top-level answer and — for the **first and every** top-level answer — moves the
question to `status: ANSWERED` and bumps `answerCount` atomically.

> **Fixed today:** the `ANSWERED` status transition could previously be **silently lost** —
> the code set the status, saved, then called `entityManager.refresh(question)`, which
> overwrote the entity from the DB and discarded the un-flushed transition. The order is now
> *increment → refresh → set status → save*, so the transition always persists.

Guards, in order:

1. Rate limit: 10 answers / 30 s per user (`429 RATE_LIMITED`).
2. Role gate (`403`).
3. **Dedup window** — a double-click/retry with the same body inside the dedup window
   returns the recently created matching answer instead of writing a duplicate.
4. Status: `CLOSED`/`ARCHIVED` questions reject answers (`400 QUESTION_CLOSED`).
5. Lock: `answersLocked` → `400 ANSWERS_LOCKED`.
6. Block guard vs. the question author (`403 ANSWER_BLOCKED_RELATIONSHIP`).
7. **Answer limit:** if `maxAnswers` is set and `answerCount` has reached it →
   `400 ANSWER_LIMIT_REACHED`. Only top-level answers count.

### Request body (`CreateAnswerRequest`)

| Field | Type | Required | Notes |
|---|---|---|---|
| `body` | string | yes | Max 10 000 chars. |
| `parentAnswerId` | UUID | no | Leave unset here — this endpoint is for top-level answers. (Setting it makes this a reanswer.) |
| `mediaUrl` / `mediaType` / `mediaThumbnailUrl` | string | no | Pre-uploaded media. `mediaType`: `IMAGE` or `VIDEO`. |
| `voiceUrl` / `voiceDurationSeconds` | string / int | no | Pre-uploaded voice note. |
| `links` | string | no | Comma-separated URLs. |
| `sources` | `CreateAnswerSourceRequest[]` | no | Inline sources, saved in order (see [Engagement — Sources](./engagement.md#sources--references) for the shape). |

```json
{
  "body": "The hadith narrated in Sunan al-Darimi describes...",
  "links": "https://sunnah.com/darimi:3417",
  "sources": [
    { "sourceType": "ISBN", "title": "Tafsir Ibn Kathir, vol. 6", "isbn": "978-9960-892-77-7" }
  ]
}
```

### Response — `201 Created`: `QuestionAnswerResponse`.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Blank/oversized `body`. |
| 400 | `QUESTION_CLOSED` | Question is `CLOSED` or `ARCHIVED`. |
| 400 | `ANSWERS_LOCKED` | `answersLocked = true`. |
| 400 | `ANSWER_LIMIT_REACHED` | `maxAnswers` cap hit (top-level answers only). |
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `ACCESS_FORBIDDEN` | Role is plain `USER` ("Only scholars and researchers can answer questions"). |
| 403 | `ANSWER_BLOCKED_RELATIONSHIP` | Block edge with the question author. |
| 404 | `QUESTION_NOT_FOUND` | Unknown question. |
| 429 | `RATE_LIMITED` | > 10 answers per 30 s (details carry `retryAfterSeconds`). |

### Side effects

- `answerCount` +1 (atomic), question → `ANSWERED`, counter cache write-through.
- SSE `ANSWER_CREATED` with the full answer DTO and fresh `questionAnswerCount` (see [Realtime](./realtime.md)).
- RabbitMQ `QUESTION_ANSWERED` event → notification to the question author.
- `@mention` scan of the body (no `@followers` fan-out from answers).
- Activity log entry for the answer author.

---

## Post an answer with media (multipart)

```
POST /api/v1/questions/{questionId}/answers/upload
Content-Type: multipart/form-data
```

**Auth:** Required — same role gate and guards as the JSON endpoint.

One-shot composer upload — mirrors `POST /api/v1/posts/{id}/comments/upload` so the
front-end can reuse its comment composer. Files are uploaded to object storage under
`qna/{questionId}/answers/...`, the resulting public URLs are stamped onto the request,
and the normal answer pipeline runs unchanged.

### Multipart parts

| Part | Type | Required | Notes |
|---|---|---|---|
| `data` | JSON (`CreateAnswerRequest`) | yes | Same shape as the JSON endpoint. |
| `media` | file | no | Image or video. `mediaType` is derived from the content type (`video*` → `VIDEO`, else `IMAGE`). |
| `voice` | file | no | Voice note → `voiceUrl`. |

**Response — `201 Created`:** `QuestionAnswerResponse`. Errors: same as the JSON endpoint,
plus standard multipart failures (`413`/`MaxUploadSize`).

---

## List reanswers (replies)

```
GET /api/v1/questions/{questionId}/answers/{answerId}/reanswers
GET /api/v1/questions/{questionId}/answers/{answerId}/replies      (alias)
```

**Auth:** Optional.

Paged list of the direct children of a top-level answer, oldest first. Same visibility
rules as the top-level listing (soft-delete, block, restriction) and the same batched
`myReaction` lookup. `replyCount` on reanswers is always `0` (flat threads).

Query parameters: `page` (default 0), `size` (default 20).
**Response — `200 OK`:** `Page<QuestionAnswerResponse>` (each row has `parentAnswerId` set).

| Status | `errorCode` | When |
|---|---|---|
| 404 | `QUESTION_NOT_FOUND` | Unknown question. |
| 404 | `ANSWER_NOT_FOUND` | Unknown/deleted parent answer. |

---

## Post a reanswer (reply)

```
POST /api/v1/questions/{questionId}/answers/{answerId}/reanswers
POST /api/v1/questions/{questionId}/answers/{answerId}/replies     (alias)
```

**Auth:** Required — `SCHOLAR`, `RESEARCHER`, or `ADMIN`.

Body: same `CreateAnswerRequest` as a top-level answer — the path `{answerId}` is set as
`parentAnswerId` server-side (any `parentAnswerId` in the body is overwritten).

**Flat at depth 1:** if `{answerId}` refers to a reanswer, the new reply is attached to
that reanswer's **top-level parent as a sibling** — never a deeper child. The actual
target is preserved in `replyToAnswerId`/`replyToUserId` on the response.

Differences from top-level answers:

- Reanswers do **not** count toward `answerCount` or the `maxAnswers` cap, and do not
  change question `status`.
- The parent's denormalized `replyCount` is bumped (+1).
- Additional block guard against the parent-answer author (`403 REANSWER_BLOCKED_RELATIONSHIP`).
- SSE event is `REANSWER_CREATED` (carries `parentAnswerId` and fresh `answerReplyCount`).
- Dedup window is scoped per parent answer.

**Response — `201 Created`:** `QuestionAnswerResponse` with `parentAnswerId` set.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `QUESTION_CLOSED` / `ANSWERS_LOCKED` | Same lifecycle guards as top-level answers. |
| 403 | `ANSWER_BLOCKED_RELATIONSHIP` / `REANSWER_BLOCKED_RELATIONSHIP` | Block edge with the question author / parent-answer author. |
| 404 | `QUESTION_NOT_FOUND` / `PARENT_ANSWER_NOT_FOUND` | Unknown question / unknown parent answer. |
| 429 | `RATE_LIMITED` | Shared 10-per-30 s answer limiter. |

### Multipart variant

```
POST /api/v1/questions/{questionId}/answers/{answerId}/reanswers/upload
POST /api/v1/questions/{questionId}/answers/{answerId}/replies/upload   (alias)
```

Same parts as the top-level `answers/upload` (`data`, `media`, `voice`); sets
`parentAnswerId = {answerId}`.

---

## Edit answer

```
PATCH /api/v1/questions/{questionId}/answers/{answerId}
```

**Auth:** Required — answer author, **question author**, or `ADMIN` (`canManageAnswer`).

Works for both top-level answers and reanswers — pass the reanswer's id as `{answerId}`;
there is no separate reply-edit route.

### Request body (`EditAnswerRequest`)

| Field | Type | Required | Notes |
|---|---|---|---|
| `body` | string | yes | Max **5 000** chars on edit (create allows 10 000). JSON aliases: `text`, `content`, `description`, `answer`, `answerBody`. |

**Response — `200 OK`:** updated `QuestionAnswerResponse` (`edited: true`, `editedAt` set).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `EMPTY_ANSWER` | Blank body. |
| 400 | `VALIDATION_FAILED` | Body > 5 000 chars. |
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `ACCESS_FORBIDDEN` | Not answer author / question author / admin. |
| 404 | `QUESTION_NOT_FOUND` / `ANSWER_NOT_FOUND` | Unknown ids. |

**Side effects:** SSE `ANSWER_EDITED` (full answer DTO + new body); mention **delta** scan
notifies only newly added `@handles`.

---

## Delete answer

```
DELETE /api/v1/questions/{questionId}/answers/{answerId}
```

**Auth:** Required — answer author, question author, or `ADMIN`.

Soft-deletes the answer (sets `deletedAt`). Also handles reanswers — no separate
reply-delete route. Cleanup performed in the same transaction:

- All reactions on the answer are purged and `reactionCount` reset to 0.
- If the answer was **accepted**, it is un-accepted and the question's
  `acceptedAnswerCount` is decremented.
- **Top-level answer:** `answerCount` −1 (clamped at 0); if it was the last top-level
  answer and the question was `ANSWERED`, status reverts to `OPEN`.
- **Reanswer:** parent's `replyCount` −1.
- Counter cache write-through; RabbitMQ `ANSWER_DELETED` event; SSE `ANSWER_DELETED`
  carrying the tombstone DTO plus fresh `questionAnswerCount` / `answerReplyCount`.

**Response — `204 No Content`.**

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `ACCESS_FORBIDDEN` | Not answer author / question author / admin. |
| 404 | `QUESTION_NOT_FOUND` / `ANSWER_NOT_FOUND` | Unknown ids. |

---

## Accept an answer

```
POST /api/v1/questions/{questionId}/answers/{answerId}/accept
```

**Auth:** Required — **question author** (or `ADMIN`) only.

Marks a top-level answer as accepted. Multiple answers may be accepted on one question;
each accept bumps the question's `acceptedAnswerCount` (which drives `hasAcceptedAnswer`
on feed cards). Idempotent — re-accepting an already-accepted answer does not double-count.

Reanswers cannot be accepted.

**Response — `200 OK`:** `QuestionAnswerResponse` with `accepted: true`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `REANSWER_NOT_ACCEPTABLE` | Target is a reanswer. |
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `ACCESS_FORBIDDEN` | Caller is not the question author or an admin ("Only the question author can accept answers"). |
| 404 | `QUESTION_NOT_FOUND` / `ANSWER_NOT_FOUND` | Unknown ids. |

**Side effects:**

- `acceptedAnswerCount` +1 (first accept of this answer only).
- RabbitMQ `ANSWER_ACCEPTED` event → **`ANSWER_ACCEPTED` notification** to the answer author.
- SSE `ANSWER_ACCEPTED` on the question stream.

> This is the **only** endorsement mechanism. There is no rating, feedback, or
> scholar-vote endpoint — do not look for one.

---

## Unaccept an answer

```
DELETE /api/v1/questions/{questionId}/answers/{answerId}/accept
```

**Auth:** Required — question author (or `ADMIN`) only.

Reverses an accept: `accepted: false`, `acceptedAnswerCount` −1 (only if it was accepted —
idempotent). Broadcasts SSE `ANSWER_UNACCEPTED`. No notification is sent on unaccept.

**Response — `200 OK`:** `QuestionAnswerResponse` with `accepted: false`.
Errors: same table as *Accept* (minus `REANSWER_NOT_ACCEPTABLE`).
