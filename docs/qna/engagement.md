# Q&A API — Engagement (Reactions, Attachments, Sources, Saves, Share)

Answer reactions, per-answer attachments and sources/references, question bookmarks
(save collections), and share links.

**Base path:** `/api/v1/questions`

Errors use the shared envelope — see [Error handling](../errors/error-handling.md).
Sibling docs: [Questions](./questions.md) · [Answers](./answers.md) · [Realtime](./realtime.md)

---

## Answer reactions

Single-reaction model: the only `AnswerReactionType` is **`LIKE`** — there are no
LOVE/HAHA/etc. variants anywhere in the module ("academic, not entertainment").
Reactions apply to **both top-level answers and reanswers**.

### React (LIKE)

```
POST /api/v1/questions/{questionId}/answers/{answerId}/react
```

**Auth:** Required.

Request body is optional; if present it is `{ "reactionType": "LIKE" }` (the only value —
the server records `LIKE` regardless).

Idempotent: reacting again is a DB no-op, but the server still re-broadcasts the
authoritative `reactionCount` on the SSE stream so optimistic UIs reconcile. The counter
is bumped with an atomic SQL update (no read-modify-write race).

**Response — `200 OK`:** `QuestionAnswerResponse` with `myReaction: "LIKE"` and fresh
`reactionCount`.

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `ANSWER_REACTION_BLOCKED_RELATIONSHIP` | Block edge with the answer author **or** the question author. |
| 404 | `QUESTION_NOT_FOUND` / `ANSWER_NOT_FOUND` | Unknown ids. |
| 429 | `RATE_LIMITED` | > 30 reactions per 10 s. |

**Side effects:** SSE `ANSWER_REACTION_ADDED` (full answer DTO, `reactionType`, fresh
`answerReactionCount`); RabbitMQ `ANSWER_REACTED` event → notification to the answer
author; counter-cache write-through; activity log entry.

### Remove reaction

```
DELETE /api/v1/questions/{questionId}/answers/{answerId}/react
```

**Auth:** Required.

Idempotent: if no reaction row exists, the server still broadcasts the authoritative count
(so a stale optimistic `-1` reconciles) and returns `200`.

**Response — `200 OK`:** `QuestionAnswerResponse` with `myReaction: null`.
**Side effects:** SSE `ANSWER_REACTION_REMOVED` with fresh `answerReactionCount`; RabbitMQ
`ANSWER_UNREACTED` event. Errors: `401`, `404` as above (no block guard, no rate limit on removal).

---

## Attachments

Per-answer file uploads (PDF, DOCX, images, audio, video, archives...). Stored in object
storage under `qna/{questionId}/answers/{answerId}/attachments`. `mediaType` is derived
from the MIME type: `IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT`, `SPREADSHEET`, `ARCHIVE`, `OTHER`.

### `AnswerAttachmentResponse`

```json
{
  "id": "at-uuid",
  "answerId": "a-uuid",
  "fileUrl": "https://cdn.example.com/qna/.../yasin-tafsir.pdf",
  "originalFileName": "yasin-tafsir.pdf",
  "mimeType": "application/pdf",
  "mediaType": "DOCUMENT",
  "fileSize": 482301,
  "displayOrder": 0,
  "caption": "Excerpt from Tafsir Ibn Kathir, vol. 6",
  "durationSeconds": null,
  "thumbnailUrl": null,
  "createdAt": "2026-07-20T09:18:00"
}
```

### Upload attachment

```
POST /api/v1/questions/{questionId}/answers/{answerId}/attachments
Content-Type: multipart/form-data
```

**Auth:** Required — answer author, question author, or `ADMIN` (`canManageAnswer`).

| Part / param | Type | Required | Notes |
|---|---|---|---|
| `file` | file | yes | The binary. |
| `caption` | string (form param) | no | Display caption. |
| `displayOrder` | int (form param) | no | Default 0. |

**Response — `201 Created`:** `AnswerAttachmentResponse`.

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `ACCESS_FORBIDDEN` | Not allowed to manage this answer. |
| 404 | `QUESTION_NOT_FOUND` / `ANSWER_NOT_FOUND` | Unknown ids. |

### List attachments

```
GET /api/v1/questions/{questionId}/answers/{answerId}/attachments
```

**Auth:** Optional.
**Response — `200 OK`:** `AnswerAttachmentResponse[]` ordered by `displayOrder` ascending.
Errors: `404 QUESTION_NOT_FOUND`.

### Update attachment metadata

```
PATCH /api/v1/questions/{questionId}/answers/{answerId}/attachments/{attachmentId}
```

**Auth:** Required — answer author, question author, or `ADMIN`.

Body (`UpdateAnswerAttachmentRequest`) — both fields optional; blank caption clears it:

| Field | Type | Notes |
|---|---|---|
| `caption` | string | Max 500 chars. |
| `displayOrder` | int | Reorder within the answer. |

**Response — `200 OK`:** updated `AnswerAttachmentResponse`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `ATTACHMENT_MISMATCH` | Attachment exists but belongs to a different answer. |
| 403 | `ACCESS_FORBIDDEN` | Not allowed. |
| 404 | `QUESTION_NOT_FOUND` / `ANSWER_NOT_FOUND` / `ATTACHMENT_NOT_FOUND` | Unknown ids. |

### Delete attachment

```
DELETE /api/v1/questions/{questionId}/answers/{answerId}/attachments/{attachmentId}
```

**Auth:** Required — answer author, question author, or `ADMIN`.
Deletes the S3 object **and** the row. **Response — `204 No Content`.**
Errors: same table as *Update attachment*.

---

## Sources / references

Citations attached to an answer. `SourceType` (shared with Research):

| Value | Meaning |
|---|---|
| `URL` | External web link. |
| `ISBN` | Book reference. |
| `MEDIA_FILE` | An uploaded file is the source (stored in S3). |
| `MANUAL` | Free-text citation. |

### `AnswerSourceResponse`

```json
{
  "id": "s-uuid",
  "answerId": "a-uuid",
  "sourceType": "ISBN",
  "title": "Tafsir Ibn Kathir, vol. 6",
  "citationText": "Ibn Kathir, Tafsir al-Qur'an al-Adheem...",
  "url": null,
  "isbn": "978-9960-892-77-7",
  "fileUrl": null,
  "originalFileName": null,
  "displayOrder": 0,
  "createdAt": "2026-07-20T09:18:00"
}
```

### Add source

```
POST /api/v1/questions/{questionId}/answers/{answerId}/sources
```

**Auth:** Required — answer author, question author, or `ADMIN`.

Body (`CreateAnswerSourceRequest`):

| Field | Type | Required | Notes |
|---|---|---|---|
| `sourceType` | enum | yes | `URL` \| `ISBN` \| `MEDIA_FILE` \| `MANUAL`. |
| `title` | string | yes | Max 500 chars. |
| `citationText` | string | no | Max 5 000 chars. |
| `url` | string | no | For `URL` sources. |
| `isbn` | string | no | For `ISBN` sources. |

`displayOrder` is assigned automatically (appended after existing sources).
Sources can also be created inline with the answer itself — see
[`POST .../answers`](./answers.md#post-a-top-level-answer).

**Response — `201 Created`:** `AnswerSourceResponse`.
Errors: `400 VALIDATION_FAILED`, `401`, `403 ACCESS_FORBIDDEN`,
`404 QUESTION_NOT_FOUND` / `ANSWER_NOT_FOUND`.

### Upload source file

```
POST /api/v1/questions/{questionId}/answers/{answerId}/sources/{sourceId}/file
Content-Type: multipart/form-data
```

**Auth:** Required — answer author, question author, or `ADMIN`.

Attaches (or **replaces**) the binary file for a source; part name `file`. The previous
file, if any, is deleted from storage. The source's `sourceType` is forced to
`MEDIA_FILE`, and `fileUrl`/`originalFileName`/`mimeType`/`fileSize` are stamped from the upload.

**Response — `200 OK`:** updated `AnswerSourceResponse`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_FILE` | Empty/absent `file` part. |
| 400 | `SOURCE_MISMATCH` | Source belongs to a different answer. |
| 403 | `ACCESS_FORBIDDEN` | Not allowed. |
| 404 | `QUESTION_NOT_FOUND` / `ANSWER_NOT_FOUND` / `SOURCE_NOT_FOUND` | Unknown ids. |

### List sources

```
GET /api/v1/questions/{questionId}/answers/{answerId}/sources
```

**Auth:** Optional.
**Response — `200 OK`:** `AnswerSourceResponse[]` ordered by `displayOrder`.
Errors: `404 QUESTION_NOT_FOUND`.

### Update source

```
PATCH /api/v1/questions/{questionId}/answers/{answerId}/sources/{sourceId}
```

**Auth:** Required — answer author, question author, or `ADMIN`.

Body (`UpdateAnswerSourceRequest`) — all optional; blank strings clear nullable fields:

| Field | Type | Notes |
|---|---|---|
| `sourceType` | enum | Change the type. |
| `title` | string | Max 500. Blank → `400 EMPTY_TITLE`. |
| `citationText` | string | Max 5 000. |
| `url` | string | — |
| `isbn` | string | Max 20 chars. |
| `displayOrder` | int | Reorder. |

**Response — `200 OK`:** updated `AnswerSourceResponse`.
Errors: `400 EMPTY_TITLE` / `SOURCE_MISMATCH`, `403`, `404` (`SOURCE_NOT_FOUND` etc.).

### Delete source

```
DELETE /api/v1/questions/{questionId}/answers/{answerId}/sources/{sourceId}
```

**Auth:** Required — answer author, question author, or `ADMIN`.
Deletes the uploaded file from storage (if any) and the row.
**Response — `204 No Content`.** Errors: `400 SOURCE_MISMATCH`, `403`, `404`.

---

## Saves / bookmarks

Mirrors the `/posts` and `/researches` save endpoints. Saves are grouped into named
**collections**; omitting a collection name files the save under `"Default"`.

### Save a question

```
POST /api/v1/questions/{questionId}/save
```

**Auth:** Required.

| Query param | Type | Required | Notes |
|---|---|---|---|
| `collection` | string | no | Collection name; blank/omitted → `"Default"`. |

Idempotent — saving an already-saved question re-broadcasts the authoritative
`saveCount` and returns current state without double-counting.

**Response — `201 Created`:** `QuestionResponse` with `isSaved: true` and fresh `saveCount`.

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No token. |
| 403 | `QNA_SAVE_BLOCKED_RELATIONSHIP` | Block edge with the question author. |
| 404 | `QUESTION_NOT_FOUND` | Unknown question. |
| 429 | `RATE_LIMITED` | > 30 social actions per minute. |

**Side effects:** `saveCount` +1 (atomic) + counter-cache write-through; SSE
`SAVE_COUNT_UPDATED` with the fresh count; activity-log entry (recorded on save only —
unsaves are intentionally not logged).

### Unsave a question

```
DELETE /api/v1/questions/{questionId}/save
```

**Auth:** Required. Idempotent — if there is no save row, the authoritative count is
still broadcast so optimistic UIs reconcile.

**Response — `200 OK`:** `QuestionResponse` with `isSaved: false`.
**Side effects:** `saveCount` −1, SSE `SAVE_COUNT_UPDATED`. Errors: `401`, `404`.

### List saved questions

```
GET /api/v1/questions/me/saved
```

**Auth:** Required. Page of the viewer's saved questions, newest-save first.
Query: `page` (0), `size` (20).

**Response — `200 OK`:** `Page<QuestionResponse>` — each item has `isSaved: true` and
**`savedAt` populated** (the bookmark time, distinct from `createdAt`).

### List saved questions in one collection

```
GET /api/v1/questions/me/saved/collection?name={collectionName}
```

**Auth:** Required.

| Query param | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | Collection to filter by. Blank → `400 MISSING_COLLECTION_NAME`. |
| `page` / `size` | int | no | Defaults 0 / 20. |

**Response — `200 OK`:** `Page<QuestionResponse>` (with `savedAt`).

### List collection names

```
GET /api/v1/questions/me/saved/collections
```

**Auth:** Required.
**Response — `200 OK`:** distinct collection names the viewer has used:

```json
["Default", "Fiqh", "To read"]
```

### Rename a collection

```
PATCH /api/v1/questions/me/saved/collections?oldName={old}&newName={new}
```

**Auth:** Required. Renames the collection across every save row owned by the viewer.

| Query param | Type | Required |
|---|---|---|
| `oldName` | string | yes — blank → `400 MISSING_OLD_NAME`. |
| `newName` | string | yes — blank → `400 MISSING_NEW_NAME`. |

**Response — `204 No Content`.**

---

## Share

Both endpoints return the unified `ShareLinkInfo` payload used by posts, research and Q&A:

```json
{
  "shortUrl": "https://api.example.com/q/3f6f8a3e-9d1c-4a51-b8f1-0f0f4d1c2a10",
  "canonicalUrl": "https://app.example.com/questions/3f6f8a3e-9d1c-4a51-b8f1-0f0f4d1c2a10",
  "token": "3f6f8a3e-9d1c-4a51-b8f1-0f0f4d1c2a10",
  "shareCount": 7
}
```

| Field | Notes |
|---|---|
| `shortUrl` | Public OG-tagged backend URL (`/q/{uuid}`) — safe to paste into chats; redirects to the frontend. |
| `canonicalUrl` | Frontend URL (`/questions/{uuid}`) for in-app navigation. |
| `token` | For Q&A this is simply the question UUID. |
| `shareCount` | Denormalized counter after the call. |

### Preview share link (no counter bump)

```
GET /api/v1/questions/{questionId}/share-link
```

**Auth:** Optional (anonymous-safe). For the inline share UI *before* the user actually
copies the link — does **not** touch `shareCount` and emits no event.

**Response — `200 OK`:** `ShareLinkInfo` with the current `shareCount`.
Errors: `404 QUESTION_NOT_FOUND`.

### Record a share (counter bump)

```
POST /api/v1/questions/{questionId}/share
```

**Auth:** Optional (anonymous shares are counted; `actorId` is null in the event).

Atomically increments `shareCount` and returns the link info. Call when the user actually
copies/sends the link.

**Response — `200 OK`:** `ShareLinkInfo` with the post-increment `shareCount`.
**Side effects:** SSE `SHARE_COUNT_UPDATED` with the fresh count.
Errors: `404 QUESTION_NOT_FOUND`.
