# Research API — full reference

Base path: **`/api/v1/researches`**

This document is the single source of truth for the frontend on the
research-publication endpoints — what they accept, what they return,
what roles can call them, and the realtime events they broadcast.

---

## Table of contents

1.  [Overview](#1-overview)
2.  [Authentication & role markers](#2-authentication--role-markers)
3.  [Unified error response](#3-unified-error-response)
4.  [Enums](#4-enums)
5.  [Core DTOs](#5-core-dtos)
6.  [Create / update / lifecycle](#6-create--update--lifecycle)
7.  [Video promo](#7-video-promo)
8.  [Cover image](#8-cover-image)
9.  [Media files (post-creation)](#9-media-files)
10. [Sources / references](#10-sources--references)
11. [Contributors](#11-contributors)
12. [Read endpoints (item / feeds)](#12-read-endpoints)
13. [Researcher dashboard](#13-researcher-dashboard)
14. [Search & tags](#14-search--tags)
15. [Save / bookmark](#15-save--bookmark)
16. [Reactions](#16-reactions)
17. [Comments & replies](#17-comments--replies)
18. [Views & downloads](#18-views--downloads)
19. [Share & citations](#19-share--citations)
20. [Per-research SSE stream](#20-per-research-sse-stream)
21. [Realtime event types](#21-realtime-event-types)
22. [Cassandra denormalized tables](#22-cassandra-denormalized-tables)
23. [Cross-cutting rules](#23-cross-cutting-rules)

---

## 1. Overview

Research is the **scholarly publication** half of the platform.
Compared to posts, every research has:

```
Research (owned by one "corresponding researcher")
  ├── Contributors    (co-authors, advisors, translators, …)
  ├── MediaFiles      (figures, datasets, supplementary files)
  ├── Sources         (URL / ISBN / MEDIA_FILE / MANUAL)
  ├── VideoPromo      (optional short promo + thumbnail)
  ├── CoverImage      (optional, shown on cards)
  ├── Tags            (1 – 30 strings)
  ├── Reactions       (single LIKE — "academic not entertainment")
  ├── Comments + replies (depth-1 cap)
  ├── Saves           (per-collection bookmarks)
  ├── Downloads       (tracked for stats)
  └── Citations       (external citation events)
```

Lifecycle: **DRAFT → PUBLISHED → ARCHIVED / RETRACTED**. On publish,
the server mints an `ircId` (e.g. `IRC-2026-000042`) — the official
paper identifier used everywhere the paper is cited or shared.

Real-time deltas fan out via SSE on
`/api/v1/researches/{researchId}/stream`.

---

## 2. Authentication & role markers

| Marker | Meaning |
|--------|---------|
| 🟢 Public | No auth required. |
| 🔵 Authenticated | Caller must be logged in. |
| 🟡 Author-only | Caller must be the corresponding researcher (or the row's owner). |
| 🔴 **Researcher / Scholar / Admin** | Caller must hold a role in `{SCHOLAR, RESEARCHER, ADMIN, SUPER_ADMIN}`. Most write endpoints are gated this way at the controller level via `@PreAuthorize`. |

**Auth headers accepted** (priority order):

1. `Authorization: Bearer <jwt>`
2. Cookie `access_token=<jwt>`
3. `?token=<jwt>` query param (for `EventSource`)

### Endpoints requiring elevated role (🔴)

Every write operation that authors / mutates a research publication
is locked to scholars / researchers / admins:

| Endpoint | Operation |
|---|---|
| `POST /api/v1/researches` | Create |
| `PATCH /api/v1/researches/{id}` | Update |
| `POST /api/v1/researches/{id}/publish` | Publish |
| `POST /api/v1/researches/{id}/unpublish` | Unpublish |
| `POST /api/v1/researches/{id}/archive` | Archive |
| `POST /api/v1/researches/{id}/retract` | Retract |
| `DELETE /api/v1/researches/{id}` | Delete |
| `POST /api/v1/researches/{id}/video-promo` | Upload video promo |
| `DELETE /api/v1/researches/{id}/video-promo` | Remove video promo |
| `POST /api/v1/researches/{id}/cover-image` | Upload cover image |
| `DELETE /api/v1/researches/{id}/cover-image` | Remove cover image |
| `POST /api/v1/researches/{id}/media` | Add media file |
| `PATCH /api/v1/researches/{id}/media/{mediaId}` | Update media metadata |
| `DELETE /api/v1/researches/{id}/media/{mediaId}` | Remove media |
| `PATCH /api/v1/researches/{id}/sources/{sourceId}` | Update source |
| `POST /api/v1/researches/{id}/sources/{sourceId}/file` | Upload source file |
| `POST /api/v1/researches/{id}/contributors` | Add contributor |
| `PUT /api/v1/researches/{id}/contributors` | Replace contributors |
| `PATCH /api/v1/researches/{id}/contributors/{contributorId}` | Update contributor |
| `DELETE /api/v1/researches/{id}/contributors/{contributorId}` | Remove contributor |
| `GET /api/v1/researches/me/drafts` | List my drafts |
| `GET /api/v1/researches/me/all` | List all my researches (any status) |

> **Note.** Roles `SCHOLAR` and `RESEARCHER` are awarded through the
> verification workflow (see `project_user_profile_enhancement.md`).
> A regular `USER` cannot author a research publication.

---

## 3. Unified error response

Same envelope as Posts / QnA:

```json
{
  "timestamp": "2026-05-21T13:42:11.512Z",
  "status":    404,
  "error":     "RESEARCH_NOT_FOUND",
  "message":   "Research 5e07… not found",
  "path":      "/api/v1/researches/5e07.../media"
}
```

Common codes:

| HTTP | `error` | Meaning |
|------|---------|---------|
| 400 | `VALIDATION_ERROR` | Bean-validation failed |
| 401 | `UNAUTHORIZED` | Missing / expired JWT |
| 403 | `FORBIDDEN` | Caller is not the corresponding researcher / lacks role |
| 404 | `RESEARCH_NOT_FOUND` / `MEDIA_NOT_FOUND` / `SOURCE_NOT_FOUND` / `CONTRIBUTOR_NOT_FOUND` / `COMMENT_NOT_FOUND` | … |
| 409 | `CONTRIBUTOR_DUPLICATE` | Adding a user already in the contributor list |
| 409 | `COMMENTS_DISABLED` | Research has `commentsEnabled: false` |
| 409 | `DOWNLOADS_DISABLED` | Research has `downloadsEnabled: false` |
| 409 | `INVALID_STATE_TRANSITION` | E.g. trying to publish a `RETRACTED` paper |
| 422 | `INVALID_CONTRIBUTOR_ROLE` | Adding a user whose role is not `RESEARCHER` / `SCHOLAR` |

---

## 4. Enums

### `ResearchStatus`

```
DRAFT | PUBLISHED | ARCHIVED | RETRACTED
```

| Status | Meaning |
|---|---|
| `DRAFT` | Default after create. Not visible in public feeds. |
| `PUBLISHED` | Visible in public feeds. `publishedAt` and `ircId` are populated. |
| `ARCHIVED` | Hidden from public feeds but still readable by URL. |
| `RETRACTED` | Marked as retracted. Stays readable with a retraction banner. |

### `ResearchVisibility`

```
PUBLIC | FOLLOWERS_ONLY | PRIVATE
```

Orthogonal to status. Even `PUBLISHED` researches can be limited to
followers only.

### `ContributorRole`

```
CO_AUTHOR | ADVISOR | REVIEWER | TRANSLATOR | EDITOR | CONTRIBUTOR
```

Defaults to `CO_AUTHOR`.

### `ReactionType`

```
LIKE
```

Single reaction — same constraint as Posts and QnA.

### `SourceType` (shared with QnA)

```
URL | ISBN | MEDIA_FILE | MANUAL
```

These are the **only** valid values (`ak.dev.irc.app.research.enums.SourceType`).
`MEDIA_FILE` = an uploaded file is the source (attach it via §10); `MANUAL` =
a free-text citation with no link. There is no `BOOK` / `JOURNAL` / `WEBSITE` /
`FILE` / `HADITH` / `QURAN` value.

### `MediaType`

```
IMAGE | VIDEO | AUDIO | DOCUMENT | OTHER
```

### `BodyFormat`

How the server should interpret `description` and `abstractText` on write.
Stored on the row; echoed back on every read.

```
PLAIN     -- text only: angle brackets are HTML-escaped, newlines → <br/>
MARKDOWN  -- CommonMark + GFM (tables, strikethrough, autolinks) → safe HTML
HTML      -- caller-supplied HTML passed through the OWASP sanitiser
```

**On write.** Send `bodyFormat` next to `description`/`abstractText`. If
omitted, the server **auto-detects**: sources containing tag patterns
(`<p>`, `<h1>`, `<table>`, …) → `HTML`, everything else → `MARKDOWN`
(a safe superset of plain text).

**Sanitisation is non-negotiable.** Whether the source is Markdown or
HTML, the rendered output passes through an OWASP whitelist allowing
formatting (bold, italic, lists, blockquote, code/pre), headings (h1–h6),
links (rewritten with `rel="nofollow noopener"`), images (http/https
only), and tables. Stripped: `<script>`, inline event handlers
(`onclick=`, `onerror=`, …), `style="…"` attributes, `<iframe>`,
`<object>`, `<embed>`, and any URL with a non-`http(s)` scheme
(`javascript:`, `data:`, etc.). What you get back in `descriptionHtml` /
`abstractHtml` is **safe to embed directly** (`v-html`,
`dangerouslySetInnerHTML`, etc.).

**On read.** Every response exposes both:

- `description` / `abstractText` — the **source** as the author typed it. Use this in the editor so they can round-trip.
- `descriptionHtml` / `abstractHtml` — the **rendered, sanitised HTML**. Use this in read views (paper detail, cards).
- `bodyFormat` — which format was used.

Legacy rows (created before rich-text support) carry `bodyFormat: "PLAIN"`
and their HTML fields are filled in on the fly with HTML-escape +
newline-to-`<br/>` so the frontend never has to special-case them.

---

## 5. Core DTOs

### `ResearchResponse` (full detail)

```json
{
  "id":   "R-uuid",
  "slug": "the-effects-of-x-on-y",
  "ircId":"IRC-2026-000042",

  "researcherId":       "U-uuid",
  "researcherFullName": "Yusuf al-Qaradawi",
  "researcherUsername": "yusuf",
  "researcherProfileImage": "https://cdn…/yusuf.jpg",

  "title":        "The effects of X on Y",
  "description":  "Long markdown body …",
  "abstractText": "Three-paragraph abstract …",
  "keywords":     "X, Y, methodology",
  "citation":     "Al-Qaradawi, Y. (2026). The effects of X on Y…",

  "videoPromoUrl":      "https://cdn…/promos/r-uuid.mp4",
  "videoPromoDurationSeconds": 47,
  "videoPromoThumbnailUrl":    "https://cdn…/promos/r-uuid-thumb.jpg",
  "coverImageUrl":      "https://cdn…/covers/r-uuid.jpg",

  "status":     "PUBLISHED",
  "visibility": "PUBLIC",
  "scheduledPublishAt": null,
  "publishedAt":        "2026-05-21T08:42:00",

  "viewCount":     1247,
  "downloadCount": 89,
  "reactionCount": 213,
  "commentCount":  31,
  "saveCount":     58,
  "shareCount":    7,
  "citationCount": 4,

  "commentsEnabled":  true,
  "downloadsEnabled": true,

  "shareToken": "2u1a3hk9zq",
  "shareUrl":   "https://irc.example.com/r/2u1a3hk9zq",

  "tags": ["x", "methodology", "review"],

  "mediaFiles":   [ /* MediaResponse[] */ ],
  "sources":      [ /* SourceResponse[] */ ],
  "contributors": [ /* ContributorResponse[] */ ],

  "currentUserReacted":      true,
  "currentUserReactionType": "LIKE",
  "currentUserSaved":        false,

  "createdAt": "2026-05-15T09:00:00",
  "updatedAt": "2026-05-21T08:42:00",
  "timeAgo":      "just now",
  "formattedDate":"21 May 2026"
}
```

### `ResearchSummaryResponse` (feed / search card)

```json
{
  "id":            "R-uuid",
  "slug":          "the-effects-of-x-on-y",
  "ircId":         "IRC-2026-000042",
  "title":         "The effects of X on Y",
  "abstractText":  "Three-paragraph abstract …",
  "abstractHtml":  "<p>Three-paragraph abstract …</p>",
  "coverImageUrl": "https://cdn…/covers/r-uuid.jpg",
  "videoPromoThumbnailUrl": "https://cdn…/promos/r-uuid-thumb.jpg",

  "researcherId":            "U-uuid",
  "researcherFullName":      "Yusuf al-Qaradawi",
  "researcherUsername":      "yusuf",
  "researcherProfileImage":  "https://cdn…/yusuf.jpg",

  "status":      "PUBLISHED",
  "publishedAt": "2026-05-21T08:42:00",

  "viewCount":     1247,
  "reactionCount": 213,
  "commentCount":  31,
  "downloadCount": 89,
  "saveCount":     58,
  "shareCount":    7,
  "citationCount": 4,

  "tags":     ["x", "methodology", "review"],
  "shareUrl": "https://irc.example.com/r/2u1a3hk9zq",

  "currentUserReacted": true,
  "currentUserSaved":   false,

  "savedAt": null
}
```

> `savedAt` is populated only by `/me/saved` and
> `/me/saved/collection`. Null everywhere else.

### `MediaResponse`

```json
{
  "id":               "M-uuid",
  "fileUrl":          "https://cdn…/media/figure-1.png",
  "originalFileName": "figure-1.png",
  "mimeType":         "image/png",
  "mediaType":        "IMAGE",
  "fileSize":         482301,
  "displayOrder":     0,
  "caption":          "Figure 1 — distribution of subjects",
  "altText":          "Histogram showing age distribution",
  "durationSeconds":  null,
  "thumbnailUrl":     null,
  "widthPx":          1280,
  "heightPx":         720
}
```

### `SourceResponse`

```json
{
  "id":               "S-uuid",
  "sourceType":       "ISBN",
  "title":            "Tafsir Ibn Kathir, vol. 6",
  "citationText":     "Ibn Kathir, Tafsir al-Qur'an al-Adheem…",
  "url":              null,
  "isbn":             "978-9960-892-77-7",
  "fileUrl":          null,
  "originalFileName": null,
  "mimeType":         null,
  "fileSize":         null,
  "displayOrder":     0
}
```

### `ContributorResponse`

```json
{
  "id":           "CT-uuid",
  "userId":       "U-uuid",
  "fullName":     "Omar al-Tunisi",
  "username":     "scholar_omar",
  "profileImage": "https://cdn…/omar.jpg",
  "userRole":     "SCHOLAR",
  "accountType":  "VERIFIED_SCHOLAR",
  "role":         "CO_AUTHOR",
  "displayOrder": 1,
  "contributionNote": "Wrote section 3 — methodology",
  "addedAt":      "2026-05-15T10:00:00"
}
```

### `CommentResponse`

```json
{
  "id":         "C-uuid",
  "researchId": "R-uuid",
  "userId":     "U-uuid",
  "userFullName":     "Ali Saleem",
  "userUsername":     "ali",
  "userProfileImage": "https://cdn…/ali.jpg",
  "content":    "Beautiful methodology — well sourced.",
  "mediaUrl":          null,
  "mediaType":         null,
  "mediaThumbnailUrl": null,
  "voiceUrl":             null,
  "voiceDurationSeconds": null,
  "likeCount":  12,
  "replyCount": 2,
  "myReaction": "LIKE",
  "isEdited":   false,
  "editedAt":   null,
  "isHidden":   false,
  "hiddenAt":   null,
  "parentId":   null,
  "replies": [],
  "createdAt":     "2026-05-21T10:15:00",
  "timeAgo":       "5 minutes ago",
  "formattedDate": "21 May 2026"
}
```

---

## 6. Create / update / lifecycle

### 6.1 `POST /api/v1/researches` — create

**Auth:** 🔴 Scholar / Researcher / Admin.

**Content type:** `multipart/form-data`.

**What it does.** Creates a new research **in `DRAFT` status** with
all the metadata, sources, contributors, and (optionally) every media
file in a single multipart call. After this call returns, the researcher
can iterate via §6.2 (`PATCH`) and then call §6.3 to publish.

**When the frontend uses this.** Final "Create" button at the end of
the multi-step researcher composer.

**Multipart parts:**
- `data` (required) — JSON `CreateResearchRequest`
- `files[]` (optional, repeatable) — binary media files. Each entry
  is matched to `mediaFiles[i]` metadata in `data` by index position.

**`data` body** (`CreateResearchRequest`):

```json
{
  "title":        "The effects of X on Y",
  "description":  "## Background\nLong **markdown** body with [a link](https://…) and a table…",
  "abstractText": "Three-paragraph abstract …",
  "bodyFormat":   "MARKDOWN",
  "keywords":     "X, Y, methodology",
  "citation":     "Al-Qaradawi, Y. (2026)…",
  "visibility":   "PUBLIC",
  "scheduledPublishAt": null,
  "commentsEnabled":   true,
  "downloadsEnabled":  true,
  "tags": ["x", "methodology", "review"],
  "sources": [
    {
      "sourceType": "ISBN",
      "title":      "Tafsir Ibn Kathir, vol. 6",
      "citationText": "Ibn Kathir …",
      "isbn":       "978-9960-892-77-7",
      "displayOrder": 0
    }
  ],
  "mediaFiles": [
    { "caption": "Figure 1", "altText": "Histogram", "displayOrder": 0 },
    { "caption": "Figure 2", "altText": null,        "displayOrder": 1 }
  ],
  "contributors": [
    {
      "userId":       "U-uuid",
      "role":         "CO_AUTHOR",
      "displayOrder": 1,
      "contributionNote": "Wrote section 3"
    }
  ]
}
```

**Example curl:**

```bash
curl -X POST https://api.irc.example.com/api/v1/researches \
  -H "Authorization: Bearer <token>" \
  -F 'data={"title":"…","tags":["x"],…};type=application/json' \
  -F 'files[]=@paper.pdf;type=application/pdf' \
  -F 'files[]=@figure1.png;type=image/png'
```

**Response `201`** — `ResearchResponse`.

---

### 6.2 `PATCH /api/v1/researches/{id}` — update

**Auth:** 🔴 Scholar / Researcher / Admin, plus 🟡 author-only.

**What it does.** Partial update of any subset of fields in
`UpdateResearchRequest` (title, description, abstract, `bodyFormat`,
keywords, citation, visibility, comments/downloads toggles, tags,
sources, contributors). Omitted fields are left untouched.

> Updating `description` and/or `abstractText` re-renders the stored
> sanitised HTML under the row's current `bodyFormat`. Sending an explicit
> `bodyFormat` re-renders both fields under the new format — useful when
> migrating an old plain-text paper to Markdown.

**When the frontend uses this.** Edit research → save (works on
DRAFT and PUBLISHED).

**Response `200`** — updated `ResearchResponse`.

---

### 6.3 `POST /api/v1/researches/{id}/publish` — publish

**Auth:** 🔴 Scholar / Researcher / Admin, 🟡 author-only.

**What it does.** Transitions `DRAFT → PUBLISHED`, mints the `ircId`
(if not already set), stamps `publishedAt`, and indexes the research in
Elasticsearch. Following feeds for the researcher's followers fan out
from this moment.

**When the frontend uses this.** "Publish" button on the draft view.

**Response `200`** — updated `ResearchResponse` (now with
`status: "PUBLISHED"`).

---

### 6.4 `POST /api/v1/researches/{id}/unpublish` — back to draft

**Auth:** 🔴 / 🟡.

**What it does.** Transitions `PUBLISHED → DRAFT`. Hides from public
feeds. `ircId` is preserved (re-publishing keeps the same identifier).

**Response `200`** — updated `ResearchResponse`.

---

### 6.5 `POST /api/v1/researches/{id}/archive`

**Auth:** 🔴 / 🟡.

**What it does.** Transitions to `ARCHIVED` — hidden from public feeds
but still readable by URL / slug. Use this when the paper is
superseded but should remain citable.

**Response `200`** — updated `ResearchResponse`.

---

### 6.6 `POST /api/v1/researches/{id}/retract`

**Auth:** 🔴 / 🟡.

**What it does.** Transitions to `RETRACTED`. Same visibility as
ARCHIVED but the UI should render a "RETRACTED" banner — the paper
is being kept publicly visible for citation-integrity reasons.

**Response `200`** — updated `ResearchResponse`.

---

### 6.7 `DELETE /api/v1/researches/{id}` — hard delete

**Auth:** 🔴 / 🟡.

**What it does.** Hard-deletes the research and cascades to
media files, sources, contributors, comments, reactions, downloads,
saves, and Cassandra rows.

**When the frontend uses this.** Use sparingly — for compliance /
admin take-downs. `Retract` is the user-facing destructive action.

**Response:** `204 No Content`.

---

## 7. Video promo

### 7.1 `POST /api/v1/researches/{id}/video-promo`

**Auth:** 🔴 / 🟡.

**Content type:** `multipart/form-data`.

**What it does.** Uploads a short promo video for the research card.
Duration is extracted server-side from the MP4/MOV/QuickTime metadata
(via mp4parser). **The client does NOT send a duration.** If
extraction fails the field is stored as `null` and the client `<video>`
element can read duration at playback time.

**Multipart parts:**
- `video` (required) — `video/mp4`, `video/webm`, `video/quicktime`
- `thumbnail` (optional) — `image/*`

**Response `200`** — updated `ResearchResponse` with
`videoPromoUrl` / `videoPromoThumbnailUrl` set.

---

### 7.2 `DELETE /api/v1/researches/{id}/video-promo`

**Auth:** 🔴 / 🟡.

**What it does.** Removes the video promo and its thumbnail from R2 / S3.

**Response `200`** — updated `ResearchResponse`.

---

## 8. Cover image

### 8.1 `POST /api/v1/researches/{id}/cover-image`

**Auth:** 🔴 / 🟡.

**Content type:** `multipart/form-data`.

**Part:** `image` (required) — `image/*`.

**What it does.** Uploads the cover image that appears on feed cards.

**Response `200`** — updated `ResearchResponse` with `coverImageUrl`.

---

### 8.2 `DELETE /api/v1/researches/{id}/cover-image`

**Auth:** 🔴 / 🟡.

**Response `200`** — updated `ResearchResponse`.

---

## 9. Media files

For media added *after* creation. (At create time, prefer
batching everything through the multipart `POST /researches` in §6.1.)

### 9.1 `POST /api/v1/researches/{id}/media`

**Auth:** 🔴 / 🟡.

**Content type:** `multipart/form-data`.

**Parts / params:**
- `file` (required) — the binary
- `caption` (optional, query)
- `altText` (optional, query)
- `displayOrder` (optional, query)

**What it does.** Adds a single media file to an existing research.
Server detects MIME, derives `mediaType`, extracts dimensions for
images and duration for video / audio.

**Response `201`** — `MediaResponse`.

---

### 9.2 `PATCH /api/v1/researches/{id}/media/{mediaId}`

**Auth:** 🔴 / 🟡.

**What it does.** Updates `caption`, `altText`, or `displayOrder`. The
underlying file is not replaced — use delete + re-add for that.

**Request body** (`UpdateMediaRequest`):

```json
{ "caption": "Figure 1 (revised)", "altText": "…", "displayOrder": 2 }
```

**Response `200`** — updated `MediaResponse`.

---

### 9.3 `DELETE /api/v1/researches/{id}/media/{mediaId}`

**Auth:** 🔴 / 🟡.

**What it does.** Removes the media row and its R2 / S3 object.

**Response:** `204 No Content`.

---

## 10. Sources / references

A research's bibliographic citations. Inline `sources[]` at create
time covers most cases — these endpoints are for later edits and for
attaching a binary citation file.

### 10.1 `PATCH /api/v1/researches/{id}/sources/{sourceId}` — update source

**Auth:** 🔴 / 🟡.

**Request body** (`UpdateSourceRequest`):

```json
{
  "title":        "Tafsir Ibn Kathir, vol. 6 (revised ed.)",
  "citationText": "Updated citation text…",
  "url":          null,
  "isbn":         "978-9960-892-77-7",
  "displayOrder": 2
}
```

**Response `200`** — updated `SourceResponse`.

---

### 10.2 `POST /api/v1/researches/{id}/sources/{sourceId}/file` — attach source file

**Auth:** 🔴 / 🟡.

**Content type:** `multipart/form-data`.

**Part:** `file` (required) — PDF / DOCX / TXT, the source document.

**What it does.** Attaches a binary file to an existing source row
(e.g. uploading a scanned manuscript or copy of the cited paper).

**Response `200`** — updated `SourceResponse` with
`fileUrl` / `originalFileName` / `mimeType` / `fileSize` populated.

---

## 11. Contributors

The corresponding researcher is implicit and managed via `researcherId`.
Contributors are everyone else (co-authors, advisors, …).

### 11.1 `POST /api/v1/researches/{id}/contributors` — add one

**Auth:** 🔴 / 🟡.

**What it does.** Adds a single contributor. The target user must
already exist and hold role `RESEARCHER` or `SCHOLAR`. Adding a user
already on the list returns `409 CONTRIBUTOR_DUPLICATE`.

> **Pre-filter the picker** so the user only sees valid co-authors and never
> hits the role rejection: use
> `GET /api/v1/users/search?eligibleContributor=true&q=…` (USER_API §9.6),
> which returns only `RESEARCHER` / `SCHOLAR` accounts.

**Request body** (`ContributorRequest`):

```json
{
  "userId":           "U-uuid",
  "role":             "CO_AUTHOR",
  "displayOrder":     1,
  "contributionNote": "Wrote the methodology section."
}
```

**Response `201`** — `ContributorResponse`.

---

### 11.2 `PUT /api/v1/researches/{id}/contributors` — replace all

**Auth:** 🔴 / 🟡.

**What it does.** Atomically replaces the entire contributor list.
Pass an empty list to clear. Use this when the UI lets the researcher
edit the full co-author table in one form and submit as a single PUT.

**Request body** — `List<ContributorRequest>`.

**Response `200`** — `List<ContributorResponse>` (new full list).

---

### 11.3 `PATCH /api/v1/researches/{id}/contributors/{contributorId}` — update one

**Auth:** 🔴 / 🟡.

**Request body** (`UpdateContributorRequest`):

```json
{
  "role":             "ADVISOR",
  "displayOrder":     0,
  "contributionNote": "Senior advisor."
}
```

**Response `200`** — updated `ContributorResponse`.

---

### 11.4 `DELETE /api/v1/researches/{id}/contributors/{contributorId}`

**Auth:** 🔴 / 🟡.

**Response:** `204 No Content`.

---

### 11.5 `GET /api/v1/researches/{id}/contributors`

**Auth:** 🟢 Public.

**What it does.** Lists all contributors on a research — used by the
public author / acknowledgements panel.

**Response `200`** — `List<ContributorResponse>`.

---

## 12. Read endpoints

### 12.1 `GET /api/v1/researches/{id}` — by UUID

**Auth:** 🟢 Public.

**What it does.** Returns the full `ResearchResponse`. The
`currentUserReacted` / `currentUserSaved` fields reflect the caller's
state (or `false` for anonymous).

**When the frontend uses this.** Research detail page.

**Response `200`** — `ResearchResponse`.

---

### 12.2 `GET /api/v1/researches/slug/{slug}` — by URL slug

**Auth:** 🟢 Public.

**What it does.** Same as §12.1 but resolved by the SEO-friendly
slug.

**Response `200`** — `ResearchResponse`.

---

### 12.3 `GET /api/v1/researches/share/{shareToken}` — by share token

**Auth:** 🟢 Public.

**What it does.** Resolves a short share-token (returned by the share
endpoint) to the full `ResearchResponse`. Used by the public deep-link
landing page.

**Response `200`** — `ResearchResponse`.

---

### 12.4 `GET /api/v1/researches/feed` — public feed

**Auth:** 🟢 Public.

**What it does.** Page of published researches ordered by
`publishedAt DESC`. Supports Spring Data pagination
(`?page=0&size=20&sort=publishedAt,desc`).

**When the frontend uses this.** "Discover" tab on the research home.

**Response `200`** — `Page<ResearchSummaryResponse>`.

---

### 12.5 `GET /api/v1/researches/feed/following`

**Auth:** 🔵 Authenticated.

**What it does.** Page of published researches from researchers the
caller follows, newest first. Excludes blocked users in both
directions.

**Response `200`** — `Page<ResearchSummaryResponse>`.

---

### 12.6 `GET /api/v1/researches/researcher/{researcherId}` — by researcher

**Auth:** 🟢 Public.

**What it does.** All published researches by a specific researcher,
newest first. Use this on the researcher's public profile page.

**Response `200`** — `Page<ResearchSummaryResponse>`.

---

## 13. Researcher dashboard

These two endpoints power the researcher's private "My research"
view — they expose drafts and non-public statuses, which the public
feeds in §12 hide.

### 13.1 `GET /api/v1/researches/me/drafts`

**Auth:** 🔴 Scholar / Researcher / Admin.

**What it does.** Page of the caller's `DRAFT` researches.

**When the frontend uses this.** Dashboard → "Drafts" tab.

**Response `200`** — `Page<ResearchSummaryResponse>` (all `status: "DRAFT"`).

---

### 13.2 `GET /api/v1/researches/me/all`

**Auth:** 🔴 Scholar / Researcher / Admin.

**What it does.** Page of *all* researches the caller owns, regardless
of status — drafts, published, archived, retracted.

**When the frontend uses this.** Dashboard → "All my research" tab.

**Response `200`** — `Page<ResearchSummaryResponse>`.

---

## 14. Search & tags

### 14.1 Full-text search — migrated to the unified API

> The research-only `GET /api/v1/researches/search` endpoint has been
> **removed**. Full-text search across title / abstract / keywords /
> tags / researcher name now lives on the cross-index endpoint:
>
> **`GET /api/v1/search?q=…&types=RESEARCH&page=…&size=…`**
>
> The response is a list of entity-stamped hits — clients still call
> §12.1 to hydrate each `RESEARCH` UUID. See [`SEARCH_API.md`](./SEARCH_API.md)
> for the full contract.
>
> Tag filtering (§14.2 below) is unchanged — it's a structured Postgres
> query, not relevance search, and stays on the research controller.
>
> The `irc-research` Elasticsearch index is unchanged; only the
> query-time entry point moved.

---

### 14.2 `GET /api/v1/researches/search/tags?tags=…&tags=…`

**Auth:** 🟢 Public.

**What it does.** Returns published researches that match **any** of
the supplied tags (OR semantics).

**Response `200`** — `Page<ResearchSummaryResponse>`.

---

### 14.3 `GET /api/v1/researches/tags/trending?limit=20`

**Auth:** 🟢 Public.

**What it does.** Most-used tags across all published researches,
ordered by usage count.

**When the frontend uses this.** Trending tag chips on the discover
screen.

**Response `200`** — `List<String>`.

> This is the **research-only** trending list (a Postgres `GROUP BY` over
> `research_tags`). For a **cross-content** trending strip that blends research
> with Q&A — and for tag feeds — prefer the unified Cassandra endpoints in
> §14.4, which are faster (pre-ranked) and scope-filterable.

---

### 14.4 Unified cross-content tags & trending (Cassandra)

Published research participates in the **shared tag subsystem** (research + Q&A)
backed by Cassandra. The fan-out follows the **publish lifecycle**, so trending
reflects live scholarship only:

| Lifecycle event | Effect on Cassandra tags |
|---|---|
| **publish** | research's tags indexed (counters + tag feed) |
| **edit while published** (`tags` changed) | tag index rebuilt |
| **unpublish / archive / retract / delete** | research's tags removed |
| create as draft | **no** trending effect until published |

Endpoints (all 🟢 public — full contract + frontend guide in
[`SEARCH_API.md` §7–§8](./SEARCH_API.md#7-tags-keywords--trending-cassandra)):

| Surface | Endpoint |
|---|---|
| Trending tags | `GET /api/v1/tags/trending?scope=RESEARCH` (or `ALL`) |
| Everything tagged `X`, newest first | `GET /api/v1/tags/{tag}/content` |
| A tag's usage count | `GET /api/v1/tags/{tag}/usage?scope=RESEARCH` |

`keywords` (the free-text field on the research) is indexed in Elasticsearch and
boosted in relevance search (§14.1), but is **not** part of tags/trending.

---

## 15. Save / bookmark

### 15.1 `POST /api/v1/researches/{researchId}/save?collection=<name>`

**Auth:** 🔵 Authenticated. Idempotent.

**What it does.** Bookmarks the research into the given collection
(or default unnamed one if omitted). Emits `SAVE_COUNT_UPDATED` on
the SSE stream.

**Response `201`** — `ResearchResponse` with `currentUserSaved: true`.

---

### 15.2 `DELETE /api/v1/researches/{researchId}/save` — unsave

**Auth:** 🔵 Authenticated.

**Response `200`** — `ResearchResponse` with `currentUserSaved: false`.

---

### 15.3 `GET /api/v1/researches/me/saved`

**Auth:** 🔵 Authenticated.

**What it does.** Page of saved researches, newest-saved first. Each
row carries `savedAt` for "Saved &lt;date&gt;" rendering.

**Response `200`** — `Page<ResearchSummaryResponse>` (with `savedAt`).

---

### 15.4 `GET /api/v1/researches/me/saved/collection?name=<name>`

**Auth:** 🔵 Authenticated.

**What it does.** Same as §15.3 but filtered to a single collection.

**Response `200`** — `Page<ResearchSummaryResponse>`.

---

### 15.5 `GET /api/v1/researches/me/saved/collections`

**Auth:** 🔵 Authenticated.

**What it does.** Lists the distinct collection names the caller has
used.

**Response `200`** — `List<String>`.

---

### 15.6 `PATCH /api/v1/researches/me/saved/collections?oldName=…&newName=…`

**Auth:** 🔵 Authenticated.

**What it does.** Renames a save collection across every row owned by
the caller.

**Response:** `200 OK` (empty body).

---

## 16. Reactions

Single LIKE — mirrors Posts and QnA.

### 16.1 `POST /api/v1/researches/{researchId}/reactions`

**Auth:** 🔵 Authenticated.

**What it does.** Adds a LIKE on the research. Idempotent — re-call
has no effect. Emits `REACTION_ADDED` on the SSE stream.

**Request body** (optional, defaults to `LIKE`):

```json
{ "reactionType": "LIKE" }
```

**Response:** `201 Created` (empty body).

---

### 16.2 `DELETE /api/v1/researches/{researchId}/reactions`

**Auth:** 🔵 Authenticated.

**What it does.** Removes the caller's LIKE.

**Response `200`** — updated `ResearchResponse`.

---

### 16.3 `GET /api/v1/researches/{researchId}/reactions/breakdown`

**Auth:** 🟢 Public.

**What it does.** Returns the per-type reaction tally. Today this is
just `{"LIKE": N}` since LIKE is the only type — exposed as a Map for
forward compatibility with future reaction variants if they ever land.

**Response `200`:**

```json
{ "LIKE": 213 }
```

---

## 17. Comments & replies

Depth-1 cap — a reply to a reply becomes a sibling reply on the same
top-level comment.

### 17.1 `GET /api/v1/researches/{researchId}/comments` — list

**Auth:** 🟢 Public.

**What it does.** Page of top-level comments. Each `CommentResponse`
carries its replies inline in the `replies` array — **complete, not
truncated** (the depth-1 cap keeps a thread bounded, so there is no
"load more replies" endpoint; the whole reply set is always present).
`replyCount` and the `replies` array length therefore agree.

Comments may carry voice (`voiceUrl` / `voiceDurationSeconds`) as well as
`mediaUrl` — render whichever is set.

**Response `200`** — `Page<CommentResponse>`.

---

### 17.2 `POST /api/v1/researches/{researchId}/comments` — add comment

**Auth:** 🔵 Authenticated. **Blocked if** `commentsEnabled: false`
→ `409 COMMENTS_DISABLED`.

**What it does.** Creates a top-level comment (or a reply if
`parentId` is set). Replying to a reply hoists the new comment to a
sibling reply.

**Request body** (`AddCommentRequest`):

```json
{
  "content":  "Beautiful methodology.",
  "parentId": null
}
```

**Response `201`** — `CommentResponse`.

---

### 17.3 `POST /api/v1/researches/{researchId}/comments/upload` — comment with media

**Auth:** 🔵 Authenticated.

**Content type:** `multipart/form-data`.

**Multipart parts:**
- `data` (required) — JSON `AddCommentRequest`
- `media` (optional) — `image/*` or `video/*`
- `voice` (optional) — `audio/*`

**Response `201`** — `CommentResponse` with `mediaUrl` / `voiceUrl`
populated.

---

### 17.4 `PATCH /api/v1/researches/{researchId}/comments/{commentId}`

**Auth:** 🟡 Author-only (comment author).

**What it does.** Updates the comment text. Sets `isEdited: true` and
`editedAt`.

**Request body** (`EditCommentRequest`):

```json
{ "content": "Updated comment body." }
```

**Response `200`** — updated `CommentResponse`.

---

### 17.5 `DELETE /api/v1/researches/{researchId}/comments/{commentId}`

**Auth:** 🟡 Author-only (comment author) **or** the research's
corresponding researcher.

**Response:** `204 No Content`.

---

### 17.6 `POST /api/v1/researches/{researchId}/comments/{commentId}/hide`

**Auth:** 🟡 Research-author-only.

**What it does.** Hides a comment from public view without deleting
it. Used for moderation. Sets `isHidden: true` and `hiddenAt`.

**When the frontend uses this.** "Hide" menu item on a comment shown
only to the research author.

**Response:** `204 No Content`.

---

### 17.7 `POST /api/v1/researches/{researchId}/comments/{commentId}/unhide`

**Auth:** 🟡 Research-author-only.

**What it does.** Reverses §17.6.

**Response:** `204 No Content`.

---

### 17.8 `POST /api/v1/researches/{researchId}/comments/{commentId}/reactions`

**Auth:** 🔵 Authenticated.

**What it does.** Adds a LIKE on a comment. Idempotent.

**Request body** (optional):

```json
{ "reactionType": "LIKE" }
```

**Response:** `201 Created`.

---

### 17.9 `DELETE /api/v1/researches/{researchId}/comments/{commentId}/reactions`

**Auth:** 🔵 Authenticated.

**Response `200`** — updated `CommentResponse`.

---

## 18. Views & downloads

### 18.1 `POST /api/v1/researches/{researchId}/view` — record a unique view

**Auth:** 🟢 Public.

**What it does.** Records a unique view (dedupe key = viewer's UUID,
or `X-Forwarded-For` for anonymous viewers, falling back to
`X-Real-IP` then `RemoteAddr`). Counter increment is async — the SSE
stream gets a `VIEW_COUNT_UPDATED` event a moment later.

**When the frontend uses this.** Fire-and-forget on opening the
research detail page.

**Response:** `200 OK` (empty body).

---

### 18.2 `POST /api/v1/researches/{researchId}/download?mediaId=<uuid>` — record a download

**Auth:** 🟢 Public.

**What it does.** Records a download event and returns a **signed
pre-signed URL** to the file. Pass `mediaId` to record a specific
media-file download; omit it for the "main paper PDF" (the first
DOCUMENT media). Emits `DOWNLOAD_COUNT_UPDATED` on the SSE stream.
Blocked if `downloadsEnabled: false` → `409 DOWNLOADS_DISABLED`.

**Response `200`** — a JSON envelope with the signed URL. `url` is `null` when
no `mediaId` was supplied (the download is recorded but there's no single file
to fetch):

```json
{ "url": "https://r2.example.com/research/r-uuid/main.pdf?X-Amz-Signature=…" }
```

---

## 19. Share & citations

### 19.1 `GET /api/v1/researches/{id}/share-link` — preview

**Auth:** 🟢 Public.

**What it does.** Returns the unified share-link info (`url`,
`shareToken`, `qrUrl`, …) **without** bumping `shareCount`. Used by
the inline share UI before the user actually copies.

**Response `200`** — `ShareLinkInfo`:

```json
{
  "url":        "https://irc.example.com/r/2u1a3hk9zq",
  "shareToken": "2u1a3hk9zq",
  "qrUrl":      "https://api.irc.example.com/qr/2u1a3hk9zq"
}
```

---

### 19.2 `POST /api/v1/researches/{id}/share` — record share

**Auth:** 🟢 Public.

**What it does.** Atomically bumps `shareCount` and returns the same
`ShareLinkInfo`. Call when the user actually copies / sends the
link.

**Response `200`** — `ShareLinkInfo`.

---

### 19.3 `POST /api/v1/researches/{id}/cite` — record a citation

**Auth:** 🔵 Authenticated. **De-duplicated** per `(research, citer)` within a
30-day window, so the public counter can't be looped by a single caller (a
repeat cite in the window is a silent no-op).

**What it does.** Increments `citationCount` on the research (once per citer per
window) and emits `CITATION_COUNT_UPDATED` on the SSE stream.

**When the frontend uses this.** When the signed-in user marks "I cited this in
my paper." (Server-to-server citation integrations must authenticate with a
valid token — the endpoint is no longer anonymous.)

**Response:** `200 OK`.

---

## 20. Per-research SSE stream

### 20.1 `GET /api/v1/researches/{researchId}/stream`

**Auth:** 🟢 Public (anonymous-safe; use `?token=` to authenticate
the subscriber so per-viewer fields are populated).

**Content type:** `text/event-stream`.

**What it does.** Subscribes the client to the research's realtime
event channel. Reactions, comments, replies, view-count,
download-count, share-count, save-count, citation-count, and
lifecycle events all push here. A `connected` handshake fires on
subscribe, and a `heartbeat` every 25 s.

**Event payload schema** — `ResearchRealtimeEvent` (flat; `null` fields omitted from the wire):

```json
{
  "eventType":      "COMMENT_CREATED",
  "researchId":     "R-uuid",
  "actorId":        "U-uuid",
  "actorUsername":  "ali",
  "actorAvatarUrl": "https://cdn…/ali.jpg",

  "commentId":       "C-uuid",
  "parentCommentId": null,
  "comment":         { "…": "full CommentResponse — see §5" },

  "reactionCount": 9, "commentCount": 13, "shareCount": 2, "saveCount": 18,
  "viewCount": 1247, "downloadCount": 44, "citationCount": 3,
  "timestamp":  "2026-05-21T10:15:00.012Z"
}
```

There is **no `data` wrapper** — read the fields directly off the event.

- **Comment events** (`COMMENT_CREATED`, `REPLY_CREATED`, `COMMENT_EDITED`) embed
  the full **`comment`** (`CommentResponse`) + **`parentCommentId`** (root comment
  id for replies) → patch that row in place, **no refetch**. The embedded
  comment's `myReaction` is neutral; resolve it per-viewer. Comment **reaction**
  events (`COMMENT_REACTION_ADDED/REMOVED`) carry `commentId` + the absolute
  `commentReactionCount` — patch the like count on the known row.
- **`*_COUNT_UPDATED` events carry the post-action ABSOLUTE value** in the named
  field (`viewCount`, `saveCount`, `downloadCount`, `shareCount`, `citationCount`,
  `reactionCount`, `commentCount`) — **set** the counter, don't `+1`. So
  `SAVE_COUNT_UPDATED` has no save/unsave ambiguity: read `saveCount`.
- **Lifecycle events** (`RESEARCH_PUBLISHED` / `RESEARCH_UPDATED` / `RESEARCH_DELETED`)
  carry the fresh **`status`** — patch the status pill in place (§21).
- **Actor suppression:** when the stream is authenticated (`?token=`), the server
  does **not** echo your own action back to you (it skips the subscriber whose
  viewer id equals the event's `actorId`), so optimistic UIs don't double-count.
  Per-user, so a second tab of yours won't get the echo either.

See §21 for the full event-type list.

---

## 21. Realtime event types

Defined in
`ak.dev.irc.app.research.realtime.ResearchRealtimeEventType`.

> Comment events embed the full `comment` (`CommentResponse`) + `parentCommentId`,
> and every `*_COUNT_UPDATED` carries the named **absolute** counter value — see
> §20.1 for the payload contract.

### Reaction events

| Event | When |
|---|---|
| `REACTION_ADDED` | A user liked the research. |
| `REACTION_CHANGED` | (forward-compat; single LIKE today) |
| `REACTION_REMOVED` | A user unliked. |

### Comment events

| Event | When |
|---|---|
| `COMMENT_CREATED` | New top-level comment. |
| `COMMENT_EDITED` | Comment author edited. |
| `COMMENT_DELETED` | Comment deleted (author or research owner). |
| `REPLY_CREATED` | Depth-1 reply on a comment. |
| `COMMENT_REACTION_ADDED` | LIKE on a comment. |
| `COMMENT_REACTION_CHANGED` | (forward-compat) |
| `COMMENT_REACTION_REMOVED` | LIKE removed on a comment. |

### Live counters

| Event | When |
|---|---|
| `VIEW_COUNT_UPDATED` | New unique view. |
| `DOWNLOAD_COUNT_UPDATED` | New download. |
| `SHARE_COUNT_UPDATED` | New share. |
| `SAVE_COUNT_UPDATED` | Save toggled on or off. |
| `CITATION_COUNT_UPDATED` | External citation recorded. |
| `REACTION_COUNT_UPDATED` | Aggregate reaction count delta. |
| `COMMENT_COUNT_UPDATED` | Aggregate comment count delta. |

### Lifecycle

| Event | When |
|---|---|
| `RESEARCH_UPDATED` | Any metadata field changed. |
| `RESEARCH_DELETED` | Research deleted. |
| `RESEARCH_PUBLISHED` | DRAFT → PUBLISHED. |

> **Emitted with `status`.** Each lifecycle event carries the fresh **`status`**
> (`"PUBLISHED"`, `"ARCHIVED"`, `"RETRACTED"`, `"DRAFT"` on unpublish) so passive
> viewers patch the status pill in place — no refetch. `RESEARCH_PUBLISHED` fires
> on publish (including scheduled auto-publish); `RESEARCH_UPDATED` on edit **and**
> on archive / retract / unpublish (status transitions); `RESEARCH_DELETED` on
> delete. The actor's own stream is suppressed (§20.1).

---

## 22. Cassandra denormalized tables

Same `@TransactionalEventListener(AFTER_COMMIT)` pattern as Posts /
QnA — Postgres is the source of truth, Cassandra mirrors high-read
state for fast viewer-side reads.

| Table | Purpose | TTL |
|---|---|---|
| `research_reactions_by_research` | "Who reacted to this research?" | — |
| `research_reactions_by_user`     | "What did this user react to?" | — |
| `research_saves_by_user`         | Saved-research list per viewer | — |
| `research_save_lookup`           | Per-(user, research) save-state | — |
| `research_comment_likes`         | Comment-LIKE rows                 | — |
| `research_downloads`             | Download events (stats)          | — |
| `research_views`                 | View-dedupe records              | 24 h |

---

## 23. Cross-cutting rules

- **Depth-1 reply cap.** Reply to a reply becomes a sibling — mirrors
  posts/QnA.
- **Single reaction type.** LIKE only — "academic not entertainment."
- **Self-engagement allowed.** Users may save / share / react to
  their own researches; self-notifications are skipped server-side.
- **Counter accuracy.** SSE `*_COUNT_UPDATED` events carry the post-action
  **absolute** value in the named field (§20.1) — set the counter from it
  rather than applying a local `+1`/`-1`.
- **Actor-event suppression (SSE).** The per-research stream does **not** echo an
  action back to the user who performed it (when the stream is authenticated via
  `?token=`) — optimistic UIs won't double-count. See §20.1.
- **Scheduled publish is active.** `scheduledPublishAt` (set on create/update,
  must be in the future) **auto-publishes** a DRAFT when its time arrives — a job
  runs ~every 60 s and takes the normal publish path (ES index, @mentions,
  trending tags, `RESEARCH_PUBLISHED` broadcast). A draft still missing a
  title/abstract is skipped until valid. Manual `POST /{id}/publish` works any time.
- **IRC ID immutability.** Once minted (on first publish), the `ircId`
  is stable — unpublish + re-publish keeps the same identifier so citations
  don't break.
- **Author check.** Mutating endpoints throw `SecurityException` if the caller
  is not the corresponding researcher; the global handler maps this to a
  `403 FORBIDDEN` (errorCode `FORBIDDEN`) JSON envelope.
- **Role gating.** A user without role `SCHOLAR` / `RESEARCHER` /
  `ADMIN` / `SUPER_ADMIN` cannot author or mutate researches. The
  frontend should hide the "Publish research" entrypoint for plain
  `USER` accounts.

---
