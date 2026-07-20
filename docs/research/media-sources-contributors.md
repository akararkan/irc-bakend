# Research API — Media, Sources & Contributors

Attachments and authorship management for a research paper: video promo, cover image, media
files, bibliographic sources, and contributors.

**Base path:** `/api/v1/researches`

Sibling documents:

- [Lifecycle](./research.md) — create / update / publish / read
- [Social interactions](./social.md)
- [Feeds & discovery](./feeds-discovery.md)
- [Realtime (SSE)](./realtime.md)

## Authentication

Bearer JWT (`Authorization: Bearer <token>`) or `access_token` cookie. Every **write**
endpoint on this page requires role `SCHOLAR` / `RESEARCHER` / `ADMIN` / `SUPER_ADMIN` and
ownership of the research (`403 FORBIDDEN` otherwise). `GET /{id}/sources` and
`GET /{id}/contributors` are public. Errors use the
[unified envelope](../errors/error-handling.md).

## Enums

`MediaType` (derived server-side from the MIME type):

```
IMAGE | VIDEO | AUDIO | DOCUMENT | SPREADSHEET | DATASET | CODE | ARCHIVE | OTHER
```

`SourceType`:

```
URL | ISBN | MEDIA_FILE | MANUAL
```

`ContributorRole` (defaults to `CO_AUTHOR`):

```
CO_AUTHOR | ADVISOR | REVIEWER | TRANSLATOR | EDITOR | CONTRIBUTOR
```

---

## Video promo

### Upload video promo

```
POST /api/v1/researches/{id}/video-promo
```

**Auth:** Bearer JWT — authoring role, owner only. `multipart/form-data`.

Uploads a short promo video (replacing any existing one, whose files are deleted from
storage). **Duration is extracted server-side** from the uploaded file (MP4/MOV/QuickTime via
mp4parser) — the client does *not* send a duration and there is no `durationSeconds`
parameter. If extraction cannot determine the duration (rare container variant), the field is
stored as `null` and the upload still succeeds; the client `<video>` element can read the
duration at playback time.

The endpoint retries internally on optimistic-lock conflicts (e.g. the frontend firing
`/video-promo` in parallel with publish or a metadata edit), so parallel calls don't surface
spurious 409s.

| Part | Required | Allowed types |
|---|---|---|
| `video` | yes | `video/mp4`, `video/webm`, `video/quicktime` |
| `thumbnail` | no | `image/jpeg`, `image/png`, `image/webp` — omitted → any existing thumbnail is cleared |

**Response — `200 OK`** — updated `ResearchResponse` with `videoPromoUrl`,
`videoPromoDurationSeconds` (nullable), `videoPromoThumbnailUrl` set.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `EMPTY_FILE` / `MISSING_FILENAME` / `INVALID_FILENAME` | Bad file part |
| 400 | `INVALID_FILE_TYPE` | MIME not in the allowed list (`details` carries `receivedType` + `allowedTypes`) |
| 400 | `FILE_TOO_LARGE` | Exceeds multipart limit |
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / existence |
| 503 | `VIDEO_UPLOAD_FAILED` / `THUMBNAIL_UPLOAD_FAILED` | Storage unavailable |
| 500 | `VIDEO_UPLOAD_ERROR` | Unexpected failure |

**Side effects:** old promo/thumbnail objects deleted from storage; `research-by-id` cache
evicted.

### Remove video promo

```
DELETE /api/v1/researches/{id}/video-promo
```

**Auth:** Bearer JWT — authoring role, owner only.

Deletes the promo video and its thumbnail from storage and clears all `videoPromo*` fields.

**Response — `200 OK`** — updated `ResearchResponse`.

| Status | `errorCode` | When |
|---|---|---|
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / existence |

---

## Cover image

### Upload cover image

```
POST /api/v1/researches/{id}/cover-image
```

**Auth:** Bearer JWT — authoring role, owner only. `multipart/form-data`.

Uploads the cover image shown on feed cards, replacing any existing one. Retries internally
on optimistic-lock conflicts (cover upload racing publish is a known frontend pattern).

| Part | Required | Allowed types |
|---|---|---|
| `image` | yes | `image/jpeg`, `image/png`, `image/webp`, `image/gif` |

**Response — `200 OK`** — updated `ResearchResponse` with `coverImageUrl` set.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_FILE_TYPE` / `EMPTY_FILE` / `FILE_TOO_LARGE` | Bad file |
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / existence |
| 503 | `COVER_UPLOAD_FAILED` | Storage unavailable |
| 500 | `COVER_UPLOAD_ERROR` | Unexpected failure |

### Remove cover image

```
DELETE /api/v1/researches/{id}/cover-image
```

**Auth:** Bearer JWT — authoring role, owner only.

**Response — `200 OK`** — updated `ResearchResponse` with `coverImageUrl: null`.

---

## Media files

Media added *after* creation. (At create time, prefer batching everything through the
multipart `POST /api/v1/researches` — see [lifecycle](./research.md#create-a-draft).)

### Add a media file

```
POST /api/v1/researches/{id}/media
```

**Auth:** Bearer JWT — authoring role, owner only. `multipart/form-data`.

Adds a single media file. The server detects the MIME type and derives `mediaType`.

| Part / param | In | Required | Notes |
|---|---|---|---|
| `file` | part | yes | Any type (validated for name safety and non-emptiness) |
| `caption` | query | no | ≤ 500 chars |
| `altText` | query | no | |
| `displayOrder` | query | no | Defaults to `0` |

**Response — `201 Created`** — `MediaResponse`:

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

| Status | `errorCode` | When |
|---|---|---|
| 400 | `EMPTY_FILE` / `MISSING_FILENAME` / `INVALID_FILENAME` / `FILE_TOO_LARGE` | Bad file |
| 400 | `MEDIA_METADATA_ERROR` | Metadata violates a DB constraint |
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / existence |
| 503 | `MEDIA_UPLOAD_FAILED` | Storage unavailable |
| 500 | `MEDIA_ADD_ERROR` | Unexpected failure |

### Update media metadata

```
PATCH /api/v1/researches/{id}/media/{mediaId}
```

**Auth:** Bearer JWT — authoring role, owner only.

Metadata-only update — the underlying file is never replaced (delete + re-add for that).
All fields optional; non-null values overwrite.

```json
{
  "caption": "Figure 1 (revised)",
  "altText": "Histogram, revised",
  "displayOrder": 2,
  "durationSeconds": 47,
  "widthPx": 1920,
  "heightPx": 1080
}
```

| Field | Constraints |
|---|---|
| `caption` | ≤ 500 chars |
| `altText` | ≤ 255 chars |
| `displayOrder`, `durationSeconds`, `widthPx`, `heightPx` | integers |

**Response — `200 OK`** — updated `MediaResponse`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_INPUT` | Missing id/body |
| 403 | `FORBIDDEN` | Not owner, **or** media belongs to a different research |
| 404 | `RESOURCE_NOT_FOUND` | Research or media not found |

### Remove a media file

```
DELETE /api/v1/researches/{id}/media/{mediaId}
```

**Auth:** Bearer JWT — authoring role, owner only.

Removes the media row and deletes its object from storage.

**Response — `204 No Content`.**

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_MEDIA_ID` | Missing id |
| 403 | `FORBIDDEN` | Not owner / media–research mismatch |
| 404 | `RESOURCE_NOT_FOUND` | Research or media not found |
| 500 | `MEDIA_DELETE_ERROR` | Delete failed |

---

## Sources

Bibliographic citations. Inline `sources[]` at create/update time covers most cases — these
endpoints are for targeted edits and for attaching a binary source document.

### Update a source

```
PATCH /api/v1/researches/{id}/sources/{sourceId}
```

**Auth:** Bearer JWT — authoring role, owner only.

All fields optional (`UpdateSourceRequest`); non-null values overwrite.

```json
{
  "sourceType":   "ISBN",
  "title":        "Tafsir Ibn Kathir, vol. 6 (revised ed.)",
  "citationText": "Updated citation text…",
  "url":          null,
  "isbn":         "978-9960-892-77-7",
  "displayOrder": 2
}
```

| Field | Constraints |
|---|---|
| `sourceType` | `URL` \| `ISBN` \| `MEDIA_FILE` \| `MANUAL` |
| `title` | ≤ 500 chars |
| `citationText` | ≤ 10 000 chars |
| `isbn` | ≤ 20 chars |

**Response — `200 OK`** — `SourceResponse`:

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

| Status | `errorCode` | When |
|---|---|---|
| 400 | `SOURCE_MISMATCH` | Source belongs to a different research |
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / research or source not found |

### Attach a source file

```
POST /api/v1/researches/{id}/sources/{sourceId}/file
```

**Auth:** Bearer JWT — authoring role, owner only. `multipart/form-data`.

Attaches a document to an existing source row (e.g. a scanned manuscript or a copy of the
cited paper). Replaces any previous file and forces `sourceType` to `MEDIA_FILE`.

| Part | Required | Allowed types |
|---|---|---|
| `file` | yes | `application/pdf`, `application/msword`, `.docx` (`application/vnd.openxmlformats-officedocument.wordprocessingml.document`), `text/plain` |

**Response — `200 OK`** — updated `SourceResponse` with `fileUrl`, `originalFileName`,
`mimeType`, `fileSize` populated and `sourceType: "MEDIA_FILE"`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_SOURCE_ID` / `INVALID_FILE_TYPE` / `EMPTY_FILE` / `FILE_TOO_LARGE` | Bad input |
| 403 | `FORBIDDEN` | Not owner / source–research mismatch |
| 404 | `RESOURCE_NOT_FOUND` | Research or source not found |
| 503 | `SOURCE_UPLOAD_FAILED` | Storage unavailable |
| 500 | `SOURCE_UPLOAD_ERROR` | Unexpected failure |

### List sources

```
GET /api/v1/researches/{id}/sources
```

**Auth:** none (public). Optional JWT enables the block check.

Every source on the research, ordered by `displayOrder` ascending. Block-aware: a viewer in a
block relationship with the researcher gets a `404`.

**Response — `200 OK`** — `SourceResponse[]`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_RESEARCH_ID` | Malformed id |
| 404 | `RESOURCE_NOT_FOUND` | Not found or hidden by a block edge |

---

## Contributors

The corresponding researcher is implicit (the `researcher*` fields on the response) and is
**never** stored as a contributor. Contributors are the additional named participants —
co-authors, advisors, translators, etc. Every referenced user must exist, be active, and hold
role `RESEARCHER` or `SCHOLAR` (staff `ADMIN` also passes).

### Add a contributor

```
POST /api/v1/researches/{id}/contributors
```

**Auth:** Bearer JWT — authoring role, owner only.

```json
{
  "userId":           "U-uuid",
  "role":             "CO_AUTHOR",
  "displayOrder":     1,
  "contributionNote": "Wrote the methodology section."
}
```

| Field | Required | Notes |
|---|---|---|
| `userId` | yes | Must be an existing `RESEARCHER` / `SCHOLAR` |
| `role` | no | Defaults to `CO_AUTHOR` |
| `displayOrder` | no | Defaults to the current contributor count (appends last); lower = listed first |
| `contributionNote` | no | ≤ 500 chars |

**Response — `201 Created`** — `ContributorResponse`:

```json
{
  "id":           "CT-uuid",
  "userId":       "U-uuid",
  "fullName":     "Omar al-Tunisi",
  "username":     "scholar_omar",
  "profileImage": "https://cdn…/omar.jpg",
  "userRole":     "SCHOLAR",
  "role":         "CO_AUTHOR",
  "displayOrder": 1,
  "contributionNote": "Wrote the methodology section.",
  "addedAt":      "2026-05-15T10:00:00"
}
```

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_INPUT` | Missing `userId` |
| 400 | `CONTRIBUTOR_IS_OWNER` | Target is the corresponding researcher |
| 400 | `CONTRIBUTOR_NOT_ELIGIBLE` | Target isn't a researcher/scholar |
| 400 | `CONTRIBUTOR_DELETED` | Target account is deactivated |
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / research or user not found |
| 409 | — (conflict) | User is already a contributor on this research |

**Side effects:** a `RESEARCH_CONTRIBUTOR_ADDED` notification is dispatched to the added
user (never to the owner themself); `research-by-id` cache evicted.

### Replace the contributor list

```
PUT /api/v1/researches/{id}/contributors
```

**Auth:** Bearer JWT — authoring role, owner only.

Atomically replaces the entire contributor list. Pass an empty array to clear. Use when the
UI edits the full co-author table in one form.

**Request body** — `ContributorRequest[]` (same shape as above).

**Response — `200 OK`** — `ContributorResponse[]` (the new full list).

Errors: same as *Add a contributor* (`DUPLICATE_CONTRIBUTOR` for a repeated `userId` in the
payload).

**Side effects:** only users **newly added** by this replace receive the
`RESEARCH_CONTRIBUTOR_ADDED` notification — re-ordering or note edits don't re-notify.

### Update a contributor

```
PATCH /api/v1/researches/{id}/contributors/{contributorId}
```

**Auth:** Bearer JWT — authoring role, owner only.

Partial update of one contributor row — `role`, `displayOrder`, `contributionNote` (all
optional, non-null wins). The linked user cannot be changed (remove + add instead).

```json
{ "role": "ADVISOR", "displayOrder": 0, "contributionNote": "Senior advisor." }
```

**Response — `200 OK`** — updated `ContributorResponse`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `INVALID_INPUT` | Missing body |
| 400 | `CONTRIBUTOR_RESEARCH_MISMATCH` | Row belongs to a different research |
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / row not found |

### Remove a contributor

```
DELETE /api/v1/researches/{id}/contributors/{contributorId}
```

**Auth:** Bearer JWT — authoring role, owner only.

**Response — `204 No Content`.**

| Status | `errorCode` | When |
|---|---|---|
| 400 | `CONTRIBUTOR_RESEARCH_MISMATCH` | Row belongs to a different research |
| 403 / 404 | `FORBIDDEN` / `RESOURCE_NOT_FOUND` | Ownership / row not found |

### List contributors

```
GET /api/v1/researches/{id}/contributors
```

**Auth:** none (public).

All contributors on a research, ordered by `displayOrder` ascending — powers the public
author / acknowledgements panel.

**Response — `200 OK`** — `ContributorResponse[]`.
