# Research API — Lifecycle

Create, edit, publish, and read scholarly research publications.

**Base path:** `/api/v1/researches`

Research is the scholarly-publication half of the platform. A research paper is owned by one
*corresponding researcher* and carries contributors, media files, sources, tags, an optional
video promo and cover image, plus the full social surface (reactions, comments, saves,
downloads, citations).

Sibling documents:

- [Media, sources & contributors](./media-sources-contributors.md)
- [Social interactions](./social.md) — reactions, comments, saves, views, downloads
- [Feeds & discovery](./feeds-discovery.md) — feeds, tags, saved lists, share & cite
- [Realtime (SSE)](./realtime.md) — per-research event stream

## Authentication

All endpoints accept a Bearer JWT (`Authorization: Bearer <access-token>`) or the
`access_token` cookie. Errors use the unified envelope described in
[error handling](../errors/error-handling.md) — every error body carries `status`, `error`,
`message`, `path`, `errorCode`, and optionally `details` / `fieldErrors` / `traceId`.

Authoring endpoints are role-gated: the caller must hold `SCHOLAR`, `RESEARCHER`, `ADMIN`, or
`SUPER_ADMIN` **and** be the paper's corresponding researcher (owner). A plain `USER` cannot
author research.

## Research statuses

`ResearchStatus` (verified in `research/enums/ResearchStatus.java`):

| Status | Meaning |
|---|---|
| `DRAFT` | Default after create. Invisible in public feeds; social actions (react/comment/save/view/download) are rejected with `400 NOT_PUBLISHED`. |
| `PUBLISHED` | Live. Visible in feeds, indexed in Elasticsearch, tags counted toward trending. |
| `ARCHIVED` | Hidden from feeds and removed from search/trending, but still readable by id / slug / share token. |
| `RETRACTED` | Like archived, but the UI should render a retraction banner. Only a `PUBLISHED` paper can be retracted. |

`ResearchVisibility` is orthogonal: `PUBLIC` (default) \| `FOLLOWERS_ONLY` \| `PRIVATE`.

Research counts toward trending **only while `PUBLISHED`** — publish indexes the tags into the
shared Cassandra tag subsystem, and unpublish / archive / retract / delete removes them.

---

## Create a draft

```
POST /api/v1/researches
```

**Auth:** Bearer JWT — role `SCHOLAR` / `RESEARCHER` / `ADMIN` / `SUPER_ADMIN`.

Creates a new research in `DRAFT` status with metadata, tags, sources, contributors, and
(optionally) every media file in a single `multipart/form-data` call. The IRC identifier
(`ircId`, e.g. `IRC-2026-000042`), the URL `slug`, and the `shareToken` are all minted here at
creation time.

### Multipart parts

| Part | Required | Type | Notes |
|---|---|---|---|
| `data` | yes | `application/json` | `CreateResearchRequest` (below) |
| `files[]` | no | binary, repeatable | Media files. Matched to `data.mediaFiles[i]` **by index position**. For robustness the server also accepts the part names `files`, `file`, `media`, `media[]`, `images`, `image`, `videos`, `video` — and falls back to any other non-`data` file part. |

### `data` body — `CreateResearchRequest`

```json
{
  "title":        "The effects of X on Y",
  "description":  "## Background\nLong **markdown** body …",
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
      "sourceType":   "ISBN",
      "title":        "Tafsir Ibn Kathir, vol. 6",
      "citationText": "Ibn Kathir …",
      "isbn":         "978-9960-892-77-7",
      "displayOrder": 0
    }
  ],
  "mediaFiles": [
    { "caption": "Figure 1", "altText": "Histogram", "displayOrder": 0 }
  ],
  "contributors": [
    { "userId": "U-uuid", "role": "CO_AUTHOR", "displayOrder": 1, "contributionNote": "Wrote section 3" }
  ]
}
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| `title` | string | yes | ≤ 500 chars |
| `description` | string | yes | ≤ 50 000 chars — the paper body (source text) |
| `abstractText` | string | yes | ≤ 5 000 chars |
| `bodyFormat` | enum | no | `PLAIN` \| `MARKDOWN` \| `HTML`. Omitted → auto-detected (`HTML` when the source contains tag patterns, otherwise `MARKDOWN`). Output is always OWASP-sanitised. |
| `keywords` | string | no | ≤ 2 000 chars, free text (search boost — not tags) |
| `citation` | string | no | ≤ 5 000 chars |
| `visibility` | enum | no | Defaults to `PUBLIC` |
| `scheduledPublishAt` | ISO datetime | no | See [Scheduled publishing](#scheduled-publishing) |
| `commentsEnabled` | boolean | no | Defaults to `false` if omitted (JSON default) — send explicitly |
| `downloadsEnabled` | boolean | no | Same as above |
| `tags` | string[] | yes | 1–30 tags, each ≤ 100 chars; normalised trim + lowercase |
| `sources` | `SourceRequest[]` | no | Inline bibliographic sources — see [sources](./media-sources-contributors.md#sources) |
| `mediaFiles` | `MediaUploadMetadata[]` | no | Per-file `caption` (≤ 500), `altText` (≤ 300), `displayOrder`; matched to `files[]` by index — extra entries ignored, missing entries default |
| `contributors` | `ContributorRequest[]` | no | Each user must exist and hold role `RESEARCHER` / `SCHOLAR` (or `ADMIN`) |

### Example

```bash
curl -X POST https://api.irc.example.com/api/v1/researches \
  -H "Authorization: Bearer <token>" \
  -F 'data={"title":"…","description":"…","abstractText":"…","tags":["x"]};type=application/json' \
  -F 'files[]=@paper.pdf;type=application/pdf' \
  -F 'files[]=@figure1.png;type=image/png'
```

### Response — `201 Created` — `ResearchResponse`

```json
{
  "id":   "R-uuid",
  "slug": "the-effects-of-x-on-y",
  "ircId": "IRC-2026-000042",

  "researcherId": "U-uuid",
  "researcherFullName": "Yusuf al-Qaradawi",
  "researcherUsername": "yusuf",
  "researcherProfileImage": "https://cdn…/yusuf.jpg",

  "title":           "The effects of X on Y",
  "description":     "## Background\nLong **markdown** body …",
  "descriptionHtml": "<h2>Background</h2><p>Long <strong>markdown</strong> body …</p>",
  "abstractText":    "Three-paragraph abstract …",
  "abstractHtml":    "<p>Three-paragraph abstract …</p>",
  "bodyFormat":      "MARKDOWN",
  "keywords":        "X, Y, methodology",
  "citation":        "Al-Qaradawi, Y. (2026)…",

  "videoPromoUrl": null,
  "videoPromoDurationSeconds": null,
  "videoPromoThumbnailUrl": null,
  "coverImageUrl": null,

  "status":     "DRAFT",
  "visibility": "PUBLIC",
  "scheduledPublishAt": null,
  "publishedAt": null,

  "viewCount": 0, "downloadCount": 0, "reactionCount": 0, "commentCount": 0,
  "saveCount": 0, "shareCount": 0, "citationCount": 0,

  "commentsEnabled": true,
  "downloadsEnabled": true,

  "shareToken": "9f2c1a7be4d803ab",
  "shareUrl":   "https://irc.example.com/r/9f2c1a7be4d803ab",

  "tags": ["x", "methodology", "review"],
  "mediaFiles":   [ { "id": "M-uuid", "fileUrl": "…", "mediaType": "DOCUMENT", "…": "…" } ],
  "sources":      [ { "id": "S-uuid", "sourceType": "ISBN", "…": "…" } ],
  "contributors": [ { "id": "CT-uuid", "userId": "U-uuid", "role": "CO_AUTHOR", "…": "…" } ],

  "currentUserReacted": false,
  "currentUserReactionType": null,
  "currentUserSaved": false,

  "createdAt": "2026-05-15T09:00:00",
  "updatedAt": "2026-05-15T09:00:00",
  "timeAgo": "just now",
  "formattedDate": "15 May 2026"
}
```

Key `ResearchResponse` fields:

| Field | Type | Notes |
|---|---|---|
| `ircId` | string | Official IRC paper identifier — minted at creation, immutable |
| `description` / `abstractText` | string | Author-supplied **source** (use in the editor) |
| `descriptionHtml` / `abstractHtml` | string | Pre-rendered, OWASP-sanitised HTML — safe for direct DOM injection |
| `bodyFormat` | enum | `PLAIN` \| `MARKDOWN` \| `HTML`; null on legacy rows (treat as `PLAIN`) |
| `status`, `visibility`, `scheduledPublishAt`, `publishedAt` | — | Lifecycle state |
| `viewCount` … `citationCount` | long | Denormalised counters |
| `shareToken`, `shareUrl` | string | Public share short-link |
| `currentUserReacted` / `currentUserReactionType` / `currentUserSaved` | — | Viewer context (false/null for anonymous) |

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean validation failed (missing title/description/abstract/tags, size limits) — `fieldErrors` populated |
| 400 | `NULL_REQUEST_BODY` | Missing `data` part |
| 400 | `EMPTY_FILE` / `MISSING_FILENAME` / `INVALID_FILENAME` | A file part is empty or has an unsafe name (`..`, `/`, `\`) |
| 400 | `FILE_TOO_LARGE` | Upload exceeds the configured multipart limit |
| 400 | `CONTRIBUTOR_IS_OWNER` | Owner listed as a contributor |
| 400 | `DUPLICATE_CONTRIBUTOR` | Same user twice in `contributors` |
| 400 | `CONTRIBUTOR_NOT_ELIGIBLE` / `CONTRIBUTOR_DELETED` | Contributor not `RESEARCHER`/`SCHOLAR`, or deactivated |
| 403 | `FORBIDDEN` | Caller lacks the authoring role |
| 409 | — (duplicate resource) | Title/slug collision |
| 503 | `MEDIA_UPLOAD_FAILED` | Object storage rejected a file — already-uploaded S3 objects are rolled back |
| 500 | `DB_ERROR` / `UNEXPECTED_ERROR` | Storage rolled back, transaction aborted |

### Side effects

- Row created as `DRAFT` — **no** search indexing, no trending, no feed fan-out yet.
- `RESEARCH_CONTRIBUTOR_ADDED` notification dispatched to each listed contributor.
- `research-feed` cache evicted. Trending-tags cache is deliberately **not** evicted
  (10-minute TTL is the freshness contract — see [feeds & discovery](./feeds-discovery.md#trending-tags)).

---

## Update

```
PATCH /api/v1/researches/{id}
```

**Auth:** Bearer JWT — authoring role, owner only.

Partial update (`UpdateResearchRequest`). Only non-null fields are applied. Works on any
status; editing a `PUBLISHED` paper re-indexes search and (when `tags` change) rebuilds the
Cassandra trending/tag rows.

### Request body — `UpdateResearchRequest`

```json
{
  "title": "The effects of X on Y (revised)",
  "description": "New body …",
  "abstractText": "New abstract …",
  "bodyFormat": "MARKDOWN",
  "keywords": "X, Y",
  "citation": "…",
  "visibility": "PUBLIC",
  "scheduledPublishAt": "2026-08-01T09:00:00",
  "commentsEnabled": true,
  "downloadsEnabled": true,
  "tags": ["x", "review"],
  "sources": [ { "sourceType": "URL", "title": "…", "url": "https://…" } ],
  "contributors": [ { "userId": "U-uuid", "role": "ADVISOR" } ]
}
```

| Field | Notes |
|---|---|
| `title` | Alias accepted: `name`. Changing the title regenerates the slug (if the new slug is free) and refreshes the denormalised title on the Cassandra tag-feed rows. |
| `description` | Aliases accepted: `body`, `text`, `content`. Re-renders `descriptionHtml`. |
| `abstractText` | Aliases accepted: `abstract`, `summary`. Re-renders `abstractHtml`. |
| `bodyFormat` | When sent, **both** body and abstract are re-rendered under the new format. When omitted, the row's stored format is kept (auto-detect on legacy rows). |
| `scheduledPublishAt` | Must be in the future → else `400 INVALID_SCHEDULE` |
| `tags` | If non-null, replaces the tag set via **diff-merge** (only actual additions/removals are written). |
| `sources` | If non-null, **replaces** the full source list. Empty list clears. |
| `contributors` | If non-null, **replaces** the full contributor list (pass the desired list, not a delta). Empty list clears. Only *newly added* users are notified. |

### Response — `200 OK` — updated `ResearchResponse`.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_INPUT` | Missing id or body |
| 400 | `INVALID_SCHEDULE` | `scheduledPublishAt` in the past |
| 400 | `CONSTRAINT_VIOLATION` | Data-integrity failure other than slug |
| 400 | `CONTRIBUTOR_IS_OWNER` / `DUPLICATE_CONTRIBUTOR` / `CONTRIBUTOR_NOT_ELIGIBLE` | Contributor replacement problems |
| 403 | `FORBIDDEN` | Not the owner / lacks role |
| 404 | `RESOURCE_NOT_FOUND` | Research not found |
| 409 | — | Optimistic-lock conflict ("modified by another user") or slug duplicate |

### Side effects

- Elasticsearch re-index (async).
- Cassandra tag rows rebuilt **only when `tags` changed and the paper is `PUBLISHED`**.
- Mention **delta** scan — only newly introduced `@handles` in title/abstract/body get a mention notification.
- `RESEARCH_UPDATED` SSE event (carries fresh `status`).
- Caches evicted: `research-by-id`, `research-by-slug`, `research-feed`.

---

## Publish

```
POST /api/v1/researches/{id}/publish
```

**Auth:** Bearer JWT — authoring role, owner only.

Transitions `DRAFT → PUBLISHED`, stamps `publishedAt`, and runs the full publication fan-out.
The `ircId` already exists (minted at create) and is stable across unpublish/re-publish.

The fan-out contract, in order:

1. **Inline (publish rolls back if these fail):** the mention scan over title + abstract + body
   (the `@followers` token is honoured here) and the Cassandra tag indexing that puts the
   paper into trending/tag feeds.
2. **Best-effort (logged, never roll back a clean publish):** the `RESEARCH_PUBLISHED`
   RabbitMQ event (follower notifications), the user-activity record, the Elasticsearch
   index, and the `RESEARCH_PUBLISHED` SSE lifecycle broadcast.

### Response — `200 OK` — `ResearchResponse` with `status: "PUBLISHED"` and `publishedAt` set.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `ALREADY_PUBLISHED` | Paper is already `PUBLISHED` |
| 400 | `MISSING_TITLE` / `MISSING_ABSTRACT` | Draft is incomplete |
| 403 | `FORBIDDEN` | Not the owner |
| 404 | `RESOURCE_NOT_FOUND` | Research not found |

### Side effects

- Search index + trending tags + follower/mention notifications as above.
- `RESEARCH_PUBLISHED` SSE event with `status`.
- Caches evicted: `research-by-id`, `research-by-slug`, `research-feed`.

---

## Unpublish

```
POST /api/v1/researches/{id}/unpublish
```

**Auth:** Bearer JWT — authoring role, owner only.

Transitions `PUBLISHED → DRAFT` and clears `publishedAt`. The `ircId` and `shareToken` are
preserved — re-publishing keeps the same identifier so citations don't break.

**Response — `200 OK`** — `ResearchResponse` with `status: "DRAFT"`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `NOT_PUBLISHED` | Paper is not currently published |
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / existence |

**Side effects:** removed from Elasticsearch and from Cassandra trending/tag feeds;
`RESEARCH_UPDATED` SSE event (with `status: "DRAFT"`); caches evicted.

---

## Archive

```
POST /api/v1/researches/{id}/archive
```

**Auth:** Bearer JWT — authoring role, owner only.

Transitions to `ARCHIVED`. Hidden from feeds, search, and trending — but still readable by
id / slug / share token. Use when a paper is superseded but should remain citable.

**Response — `200 OK`** — `ResearchResponse` with `status: "ARCHIVED"`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `ALREADY_ARCHIVED` | Already archived |
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / existence |

**Side effects:** ES delete, Cassandra untag, `RESEARCH_UPDATED` SSE event, caches evicted.

---

## Retract

```
POST /api/v1/researches/{id}/retract
```

**Auth:** Bearer JWT — authoring role, owner only.

Transitions `PUBLISHED → RETRACTED`. Same visibility rules as archived, but the UI should
render a "RETRACTED" banner — the paper stays readable for citation-integrity reasons.

**Response — `200 OK`** — `ResearchResponse` with `status: "RETRACTED"`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `NOT_PUBLISHED` | Only a `PUBLISHED` paper can be retracted |
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / existence |

**Side effects:** ES delete, Cassandra untag, `RESEARCH_UPDATED` SSE event, caches evicted.

---

## Delete (hard)

```
DELETE /api/v1/researches/{id}
```

**Auth:** Bearer JWT — authoring role, owner only.

Hard-deletes the research and everything attached to it, in one transaction:

1. S3/R2 binaries (media, source files, video promo, cover image),
2. Postgres children (downloads, reactions, saves, comments + comment reactions, contributors,
   sources, media, tags), then the research row,
3. best-effort external cleanup — Elasticsearch document, Cassandra tag rows, notifications
   referencing the paper, and the Cassandra engagement partitions (reactions, save lookups,
   views, downloads, per-user save lists).

Use sparingly (compliance / take-downs) — **retract** is the user-facing destructive action.

**Response — `204 No Content`.**

| Status | `errorCode` | When |
|---|---|---|
| 403 | `FORBIDDEN` | Not the owner |
| 404 | `RESOURCE_NOT_FOUND` | Research not found |

**Side effects:** `RESEARCH_DELETED` SSE event; caches evicted.

---

## Get by id

```
GET /api/v1/researches/{id}
```

**Auth:** none (public). Optional JWT populates viewer fields.

Full `ResearchResponse` for the detail page. `currentUserReacted` / `currentUserSaved`
reflect the authenticated caller (resolved by O(1) point reads, not collection scans).
**Block-aware:** if the viewer and the researcher are in a block relationship (either
direction) the paper is hidden as a `404`.

Anonymous responses are cached server-side for 5 minutes (`research-by-id`).

**Response — `200 OK`** — `ResearchResponse` (see [Create](#create-a-draft) for the shape).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_RESEARCH_ID` | Malformed id |
| 404 | `RESOURCE_NOT_FOUND` | Not found, soft-deleted, or hidden by a block edge |

---

## Get by slug

```
GET /api/v1/researches/slug/{slug}
```

**Auth:** none (public). Optional JWT populates viewer fields.

Same as get-by-id, resolved by the SEO slug (e.g. `the-effects-of-x-on-y`). Block-aware.
Anonymous responses cached 5 minutes (`research-by-slug`).

**Response — `200 OK`** — `ResearchResponse`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_SLUG` | Blank slug |
| 404 | `RESOURCE_NOT_FOUND` | Not found or hidden by a block edge |

---

## Get by share token

```
GET /api/v1/researches/share/{shareToken}
```

**Auth:** none (public). Optional JWT populates viewer fields.

Resolves a share-token short link (from
[`GET /{id}/share-link`](./feeds-discovery.md#share)) to the full `ResearchResponse` —
used by the public deep-link landing page.

**Response — `200 OK`** — `ResearchResponse`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_TOKEN` | Blank token |
| 404 | `RESOURCE_NOT_FOUND` | Unknown token |

---

## Scheduled publishing

Set `scheduledPublishAt` (must be in the future) on a draft via create or `PATCH`. A
background job (`ScheduledPublishJob`) scans for due drafts every **~60 seconds**
(`app.research.scheduled-publish-ms`, default `60000`; initial delay
`app.research.scheduled-publish-initial-ms`, default `30000`) and auto-publishes each one.

Contract details:

- The job calls **the same full `publish()` path** as the manual endpoint, acting as the
  paper's own owner — so every auto-published paper gets the complete fan-out: Elasticsearch
  index, Cassandra trending tags, `@mentions`, the `RESEARCH_PUBLISHED` notification event,
  and the `RESEARCH_PUBLISHED` SSE broadcast.
- **Fixed:** previously a second, competing scheduler could win the race and publish due
  drafts through an incomplete path that skipped search indexing and trending — those papers
  were permanently missing from search/trending. That scheduler has been removed;
  auto-published papers now always reach Elasticsearch and trending. (See the guard comment
  in `ResearchServiceImpl` — do not re-add a second `@Scheduled` publisher.)
- Each due draft publishes in its own transaction and is isolated in try/catch — one
  incomplete draft (e.g. `MISSING_ABSTRACT`) is skipped and retried on the next scan without
  blocking the rest. Once published, the row leaves the `status = DRAFT` filter and is never
  re-published.
- Manual `POST /{id}/publish` still works at any time, including before the scheduled moment.
