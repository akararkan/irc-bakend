# IRC Platform — API Documentation

> Base URL: `http://localhost:8080`
>
> All authenticated endpoints require the header:
> ```
> Authorization: Bearer <access_token>
> ```

---

## Table of Contents

1. [Authentication](#1-authentication)
2. [Research — CRUD & Lifecycle](#2-research--crud--lifecycle)
3. [Research — Media & Files](#3-research--media--files)
4. [Research — Social (Reactions, Comments, Saves)](#4-research--social)
5. [Research — Views & Downloads](#5-research--views--downloads)
6. [Research — Search & Discovery](#6-research--search--discovery)
7. [Posts — CRUD](#7-posts--crud)
8. [Posts — Comments](#8-posts--comments)
9. [User Saved Researches](#9-user-saved-researches)
10. [Enums Reference](#10-enums-reference)

---

## 1. Authentication

### Register
```
POST /api/v1/auth/register
```
**Auth**: None

### Login
```
POST /api/v1/auth/login
```
**Auth**: None

### Refresh Token
```
POST /api/v1/auth/refresh
```
**Auth**: None

### Logout
```
POST /api/v1/auth/logout
```
**Auth**: Required

---

## 2. Research — CRUD & Lifecycle

### Create Research (DRAFT)

```
POST /api/v1/researches
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`

**Body**:
```json
{
  "title": "Impact of AI on Islamic Jurisprudence",
  "description": "A comprehensive study exploring how artificial intelligence can assist Islamic scholars in deriving rulings from classical texts...",
  "abstractText": "This paper investigates the intersection of modern AI tools and traditional Islamic legal methodology (usul al-fiqh). We evaluate natural language processing models trained on classical Arabic texts...",
  "keywords": "artificial intelligence, islamic jurisprudence, fiqh, NLP, arabic",
  "citation": "Al-Khatib, A. (2026). Impact of AI on Islamic Jurisprudence. IRC Publications.",
  "doi": null,
  "visibility": "PUBLIC",
  "scheduledPublishAt": null,
  "commentsEnabled": true,
  "downloadsEnabled": true,
  "tags": ["artificial-intelligence", "islamic-jurisprudence", "fiqh", "nlp"],
  "sources": [
    {
      "sourceType": "DOI",
      "title": "NLP for Classical Arabic — A Survey",
      "citationText": "Smith, J. (2024). NLP for Classical Arabic. Journal of AI Research, 42(3), 115-132.",
      "url": null,
      "doi": "10.1234/jar.2024.42.3.115",
      "isbn": null,
      "displayOrder": 0
    },
    {
      "sourceType": "URL",
      "title": "Usul al-Fiqh Methodology",
      "citationText": null,
      "url": "https://example.com/usul-al-fiqh-guide",
      "doi": null,
      "isbn": null,
      "displayOrder": 1
    },
    {
      "sourceType": "ISBN",
      "title": "Al-Mustasfa min Ilm al-Usul",
      "citationText": "Al-Ghazali. Al-Mustasfa min Ilm al-Usul. Dar al-Kutub al-Ilmiyya.",
      "url": null,
      "doi": null,
      "isbn": "978-2-7451-1234-5",
      "displayOrder": 2
    }
  ]
}
```

**Response**: `201 Created` — Full `ResearchResponse` with generated `ircId`, `slug`, `shareToken`.

---

### Update Research

```
PATCH /api/v1/researches/{id}
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN` (owner only)

**Body** (all fields optional — only send what you want to change):
```json
{
  "title": "Updated: Impact of AI on Islamic Jurisprudence",
  "description": "Updated description...",
  "abstractText": "Updated abstract...",
  "keywords": "AI, islamic law, fiqh, NLP, machine learning",
  "citation": "Updated citation text",
  "doi": "10.12345/irc.2026.000001",
  "visibility": "PUBLIC",
  "scheduledPublishAt": "2026-05-01T10:00:00",
  "commentsEnabled": true,
  "downloadsEnabled": false,
  "tags": ["ai", "islamic-law", "machine-learning"],
  "sources": [
    {
      "sourceType": "MANUAL",
      "title": "Manual citation",
      "citationText": "Free-text citation here",
      "url": null,
      "doi": null,
      "isbn": null,
      "displayOrder": 0
    }
  ]
}
```

**Response**: `200 OK` — Updated `ResearchResponse`.

---

### Publish Research

```
POST /api/v1/researches/{id}/publish
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN` (owner only)

**Body**: None

**Response**: `200 OK` — Research status becomes `PUBLISHED`, DOI auto-generated if not set.

---

### Unpublish (Revert to Draft)

```
POST /api/v1/researches/{id}/unpublish
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN` (owner only)

**Response**: `200 OK`

---

### Archive Research

```
POST /api/v1/researches/{id}/archive
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN` (owner only)

**Response**: `200 OK`

---

### Retract Research

```
POST /api/v1/researches/{id}/retract
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN` (owner only)

**Response**: `200 OK` — Only works on published research.

---

### Delete Research (Soft Delete)

```
DELETE /api/v1/researches/{id}
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN` (owner only)

**Response**: `204 No Content`

---

### Get Research by ID

```
GET /api/v1/researches/{id}
```
**Auth**: Optional (anonymous users see public data; authenticated users see their reaction/save state)

**Response**: `200 OK` — Full `ResearchResponse`.

---

### Get Research by Slug

```
GET /api/v1/researches/slug/{slug}
```
**Auth**: Optional

**Response**: `200 OK` — Full `ResearchResponse`.

---

### Get Research by Share Token

```
GET /api/v1/researches/share/{shareToken}
```
**Auth**: Optional

**Response**: `200 OK` — Full `ResearchResponse`.

---

### Get Research Feed (Published)

```
GET /api/v1/researches/feed?page=0&size=20
```
**Auth**: Optional

**Response**: `200 OK` — Paged `ResearchSummaryResponse`.

---

### Get Researcher's Published Works

```
GET /api/v1/researches/researcher/{researcherId}?page=0&size=20
```
**Auth**: Optional

**Response**: `200 OK` — Paged `ResearchSummaryResponse`.

---

### Get My Drafts

```
GET /api/v1/researches/me/drafts?page=0&size=20
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`

**Response**: `200 OK` — Paged `ResearchSummaryResponse`.

---

### Get All My Researches

```
GET /api/v1/researches/me/all?page=0&size=20
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`

**Response**: `200 OK` — Paged `ResearchSummaryResponse`.

---

## 3. Research — Media & Files

### Upload Video Promo

```
POST /api/v1/researches/{id}/video-promo
Content-Type: multipart/form-data
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN` (owner only)

**Form field**: `video` — mp4, webm, or quicktime file

**Response**: `200 OK` — Updated `ResearchResponse`.

---

### Remove Video Promo

```
DELETE /api/v1/researches/{id}/video-promo
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`

**Response**: `200 OK`

---

### Upload Cover Image

```
POST /api/v1/researches/{id}/cover-image
Content-Type: multipart/form-data
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`

**Form field**: `image` — jpeg, png, webp, or gif file

**Response**: `200 OK`

---

### Remove Cover Image

```
DELETE /api/v1/researches/{id}/cover-image
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`

**Response**: `200 OK`

---

### Add Media File

```
POST /api/v1/researches/{id}/media
Content-Type: multipart/form-data
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`

**Form fields**:
| Field          | Type     | Required |
|----------------|----------|----------|
| `file`         | File     | Yes      |
| `caption`      | String   | No       |
| `altText`      | String   | No       |
| `displayOrder` | Integer  | No       |

**Response**: `201 Created` — `MediaResponse`.

---

### Update Media Metadata

```
PATCH /api/v1/researches/{id}/media/{mediaId}
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`

**Body**:
```json
{
  "caption": "Figure 1 — Model Architecture",
  "altText": "Diagram showing the NLP pipeline architecture",
  "displayOrder": 1,
  "durationSeconds": 120,
  "widthPx": 1920,
  "heightPx": 1080
}
```

**Response**: `200 OK` — `MediaResponse`.

---

### Remove Media File

```
DELETE /api/v1/researches/{id}/media/{mediaId}
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`

**Response**: `204 No Content`

---

### Upload Source File

```
POST /api/v1/researches/{id}/sources/{sourceId}/file
Content-Type: multipart/form-data
```
**Auth**: Required — `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`

**Form field**: `file` — pdf, doc, docx, or txt file

**Response**: `200 OK` — `SourceResponse`.

---

## 4. Research — Social

### React to Research

```
POST /api/v1/researches/{researchId}/reactions
```
**Auth**: Required

**Body**:
```json
{
  "reactionType": "INSIGHTFUL"
}
```

Allowed values: `LIKE`, `LOVE`, `INSIGHTFUL`, `CELEBRATE`, `CURIOUS`, `SUPPORT`

**Response**: `201 Created`

---

### Remove Reaction

```
DELETE /api/v1/researches/{researchId}/reactions
```
**Auth**: Required

**Response**: `204 No Content`

---

### Get Reaction Breakdown

```
GET /api/v1/researches/{researchId}/reactions/breakdown
```
**Auth**: None

**Response**: `200 OK`
```json
{
  "LIKE": 42,
  "LOVE": 15,
  "INSIGHTFUL": 87,
  "CELEBRATE": 5,
  "CURIOUS": 12,
  "SUPPORT": 8
}
```

---

### Get Comments

```
GET /api/v1/researches/{researchId}/comments?page=0&size=20
```
**Auth**: None

**Response**: `200 OK` — Paged `CommentResponse`.

---

### Add Comment

```
POST /api/v1/researches/{researchId}/comments
```
**Auth**: Required

**Body** (top-level comment):
```json
{
  "content": "Excellent research! The methodology for training on classical Arabic is very novel.",
  "parentId": null
}
```

**Body** (reply to comment):
```json
{
  "content": "Thank you! We plan to expand the training corpus in the next phase.",
  "parentId": "c1a2b3c4-d5e6-f7a8-b9c0-d1e2f3a4b5c6"
}
```

**Response**: `201 Created` — `CommentResponse`.

---

### Edit Comment

```
PATCH /api/v1/researches/{researchId}/comments/{commentId}
```
**Auth**: Required (comment author only)

**Body**:
```json
{
  "content": "Updated: Excellent research! I especially appreciate the comparative analysis."
}
```

**Response**: `200 OK` — `CommentResponse` with `edited: true`.

---

### Delete Comment

```
DELETE /api/v1/researches/{researchId}/comments/{commentId}
```
**Auth**: Required (comment author only)

**Response**: `204 No Content`

---

### Like a Comment

```
POST /api/v1/researches/{researchId}/comments/{commentId}/like
```
**Auth**: Required

**Response**: `201 Created`

---

### Unlike a Comment

```
DELETE /api/v1/researches/{researchId}/comments/{commentId}/like
```
**Auth**: Required

**Response**: `204 No Content`

---

### Save / Bookmark Research

```
POST /api/v1/researches/{researchId}/save?collection=AI%20Research
```
**Auth**: Required

| Query Param   | Type   | Required | Default   |
|---------------|--------|----------|-----------|
| `collection`  | String | No       | "Default" |

**Response**: `201 Created`

---

### Unsave / Unbookmark Research

```
DELETE /api/v1/researches/{researchId}/save
```
**Auth**: Required

**Response**: `204 No Content`

---

## 5. Research — Views & Downloads

### Record View

```
POST /api/v1/researches/{researchId}/view
```
**Auth**: Optional (anonymous views tracked by IP)

**Response**: `200 OK` — De-duplicated per user/IP within 24 hours. View event sent to RabbitMQ for async persistence.

---

### Record Download

```
POST /api/v1/researches/{researchId}/download?mediaId={mediaId}
```
**Auth**: Optional

| Query Param | Type | Required | Description                        |
|-------------|------|----------|------------------------------------|
| `mediaId`   | UUID | No       | Specific media file to download    |

**Response**: `200 OK` — Returns a pre-signed download URL (string) for the media file, or `null` if no specific media requested.

---

### Get Share Link

```
POST /api/v1/researches/{id}/share
```
**Auth**: None

**Response**: `200 OK` — Returns the shareable URL string. Increments share count.

---

### Record Citation

```
POST /api/v1/researches/{id}/cite
```
**Auth**: None

**Response**: `200 OK` — Increments citation count.

---

## 6. Research — Search & Discovery

### LIKE Search (title, keywords, abstract)

```
GET /api/v1/researches/search?q=artificial+intelligence&page=0&size=20
```
**Auth**: Optional

**Response**: `200 OK` — Paged `ResearchSummaryResponse`.

---

### Full-Text Search (PostgreSQL GIN/tsvector)

```
GET /api/v1/researches/search/fts?q=islamic+jurisprudence+AI&page=0&size=20
```
**Auth**: Optional

**Response**: `200 OK` — Paged `ResearchSummaryResponse` ranked by relevance.

---

### Search by Tags

```
GET /api/v1/researches/search/tags?tags=ai&tags=fiqh&page=0&size=20
```
**Auth**: Optional

**Response**: `200 OK` — Paged `ResearchSummaryResponse`.

---

### Trending Tags

```
GET /api/v1/researches/tags/trending?limit=20
```
**Auth**: None

| Query Param | Type | Default | Max |
|-------------|------|---------|-----|
| `limit`     | int  | 20      | 100 |

**Response**: `200 OK`
```json
["artificial-intelligence", "islamic-jurisprudence", "hadith", "quran-studies", "fiqh"]
```

---

## 7. Posts — CRUD

### Create Post

```
POST /api/v1/posts
```
**Auth**: Required

**Body** (text post):
```json
{
  "postType": "TEXT",
  "textContent": "Just finished reviewing an amazing paper on AI-assisted hadith classification! 🔬",
  "visibility": "PUBLIC"
}
```

**Body** (voice post):
```json
{
  "postType": "VOICE_POST",
  "textContent": "Voice note about today's seminar",
  "voiceUrl": "https://cdn.example.com/voices/note-123.mp3",
  "voiceDurationSeconds": 45,
  "voiceTranscript": "Today I attended the most insightful seminar on...",
  "waveformData": "0.1,0.3,0.5,0.8,0.6,0.4,0.2",
  "visibility": "PUBLIC"
}
```

**Body** (embedded / media post):
```json
{
  "postType": "EMBEDDED",
  "textContent": "Check out these diagrams from our latest research!",
  "visibility": "PUBLIC",
  "mediaList": [
    {
      "mediaType": "IMAGE",
      "url": "https://cdn.example.com/images/diagram1.png",
      "thumbnailUrl": "https://cdn.example.com/thumbs/diagram1.png",
      "altText": "Research methodology diagram",
      "sortOrder": 0
    }
  ],
  "locationName": "IRC Research Center, Istanbul",
  "locationLat": 41.0082,
  "locationLng": 28.9784
}
```

**Body** (reel):
```json
{
  "postType": "REEL",
  "textContent": "60-second summary of our new paper 📄",
  "visibility": "PUBLIC",
  "mediaList": [
    {
      "mediaType": "VIDEO",
      "url": "https://cdn.example.com/videos/reel-001.mp4",
      "thumbnailUrl": "https://cdn.example.com/thumbs/reel-001.jpg",
      "durationSeconds": 60,
      "sortOrder": 0
    }
  ],
  "audioTrackUrl": "https://cdn.example.com/audio/nasheed.mp3",
  "audioTrackName": "Inspirational Nasheed"
}
```

**Body** (story — auto-expires in 24h):
```json
{
  "postType": "STORY",
  "textContent": "Behind the scenes at the research lab! 🧪",
  "visibility": "PUBLIC",
  "mediaList": [
    {
      "mediaType": "IMAGE",
      "url": "https://cdn.example.com/images/lab-photo.jpg",
      "sortOrder": 0
    }
  ]
}
```

**Body** (re-share another post):
```json
{
  "postType": "TEXT",
  "textContent": "Everyone needs to read this! 👇",
  "visibility": "PUBLIC",
  "sharedPostId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "shareCaption": "Brilliant analysis"
}
```

**Response**: `201 Created` — `PostResponse`.

---

### Get Post

```
GET /api/v1/posts/{postId}
```
**Auth**: Optional (if authenticated, returns the user's reaction)

**Response**: `200 OK` — `PostResponse`.

---

### Get Public Feed

```
GET /api/v1/posts/feed?page=0&size=20
```
**Auth**: None

**Response**: `200 OK` — Paged `PostResponse` (published, public, newest first).

---

### Get Reel Feed

```
GET /api/v1/posts/feed/reels?page=0&size=10
```
**Auth**: None

**Response**: `200 OK` — Paged `PostResponse` (reels only).

---

### Get User's Posts

```
GET /api/v1/posts/user/{authorId}?page=0&size=20
```
**Auth**: None

**Response**: `200 OK` — Paged `PostResponse`.

---

### Search Posts

```
GET /api/v1/posts/search?q=AI+research&page=0&size=20
```
**Auth**: None

**Response**: `200 OK` — Paged `PostResponse`.

---

### React to Post

```
POST /api/v1/posts/{postId}/react
```
**Auth**: Required

**Body**:
```json
{
  "reactionType": "INSIGHTFUL"
}
```

Allowed values: `LIKE`, `LOVE`, `HAHA`, `WOW`, `SAD`, `ANGRY`, `CARE`, `INSIGHTFUL`

**Response**: `200 OK` — Updated `PostResponse`.

---

### Remove Post Reaction

```
DELETE /api/v1/posts/{postId}/react
```
**Auth**: Required

**Response**: `204 No Content`

---

### Share Post

```
POST /api/v1/posts/{postId}/share?caption=Must+read
```
**Auth**: Required

| Query Param | Type   | Required |
|-------------|--------|----------|
| `caption`   | String | No       |

**Response**: `200 OK` — `PostResponse`.

---

### Delete Post (Soft Delete)

```
DELETE /api/v1/posts/{postId}
```
**Auth**: Required (author only)

**Response**: `204 No Content`

---

## 8. Posts — Comments

### Add Comment

```
POST /api/v1/posts/{postId}/comments
```
**Auth**: Required

**Body** (text comment):
```json
{
  "textContent": "Great insight! I agree with your perspective."
}
```

**Body** (reply with voice):
```json
{
  "parentId": "c1a2b3c4-d5e6-f7a8-b9c0-d1e2f3a4b5c6",
  "textContent": null,
  "voiceUrl": "https://cdn.example.com/voices/reply-456.mp3",
  "voiceDurationSeconds": 15,
  "voiceTranscript": "I wanted to add that...",
  "waveformData": "0.2,0.4,0.7,0.9,0.5,0.3"
}
```

**Response**: `201 Created` — `CommentResponse`.

---

### Get Top-Level Comments

```
GET /api/v1/posts/{postId}/comments?page=0&size=20
```
**Auth**: Optional (if authenticated, returns the user's reaction on each comment)

**Response**: `200 OK` — Paged `CommentResponse`.

---

### Get Replies to a Comment

```
GET /api/v1/posts/{postId}/comments/{commentId}/replies?page=0&size=10
```
**Auth**: Optional

**Response**: `200 OK` — Paged `CommentResponse`.

---

### React to Comment

```
POST /api/v1/posts/{postId}/comments/{commentId}/react
```
**Auth**: Required

**Body**:
```json
{
  "reactionType": "LIKE"
}
```

**Response**: `200 OK` — `CommentResponse`.

---

### Delete Comment

```
DELETE /api/v1/posts/{postId}/comments/{commentId}
```
**Auth**: Required (comment author only)

**Response**: `204 No Content`

---

## 9. User Saved Researches

### Get All Saved Researches

```
GET /api/v1/me/saved-researches?page=0&size=20
```
**Auth**: Required

**Response**: `200 OK` — Paged `ResearchSummaryResponse`.

---

### Get Collections

```
GET /api/v1/me/saved-researches/collections
```
**Auth**: Required

**Response**: `200 OK`
```json
["Default", "AI Research", "Hadith Studies", "Fiqh"]
```

---

### Get Saved Researches by Collection

```
GET /api/v1/me/saved-researches/collections/{collectionName}?page=0&size=20
```
**Auth**: Required

**Response**: `200 OK` — Paged `ResearchSummaryResponse`.

---

## 10. Enums Reference

### ResearchStatus
| Value       | Description                       |
|-------------|-----------------------------------|
| `DRAFT`     | Initial state, not yet published  |
| `PUBLISHED` | Live, visible in feed & search    |
| `ARCHIVED`  | Hidden by the researcher          |
| `RETRACTED` | Formally retracted after publish  |

### ResearchVisibility
| Value            | Description                      |
|------------------|----------------------------------|
| `PUBLIC`         | Visible to everyone              |
| `FOLLOWERS_ONLY` | Only visible to followers        |
| `PRIVATE`        | Only visible to the researcher   |

### ReactionType (Research)
`LIKE`, `LOVE`, `INSIGHTFUL`, `CELEBRATE`, `CURIOUS`, `SUPPORT`

### SourceType
| Value        | Description                       |
|--------------|-----------------------------------|
| `URL`        | External web link                 |
| `DOI`        | Digital Object Identifier         |
| `ISBN`       | Book reference                    |
| `MEDIA_FILE` | Uploaded file stored in S3        |
| `MANUAL`     | Free-text citation                |

### PostType
| Value        | Description                          |
|--------------|--------------------------------------|
| `TEXT`       | Text-only post                       |
| `EMBEDDED`   | Text with media attachments          |
| `VOICE_POST` | Primary content is voice/audio       |
| `STORY`      | 24-hour ephemeral post               |
| `REEL`       | Short-form video                     |

### PostVisibility
`PUBLIC`, `FOLLOWERS_ONLY`, `ONLY_ME`

### PostReactionType
`LIKE`, `LOVE`, `HAHA`, `WOW`, `SAD`, `ANGRY`, `CARE`, `INSIGHTFUL`

---

## Pagination

All paginated endpoints accept these query parameters:

| Param  | Type | Default | Description              |
|--------|------|---------|--------------------------|
| `page` | int  | 0       | Zero-based page number   |
| `size` | int  | 20      | Page size                |
| `sort` | str  | varies  | e.g. `createdAt,desc`   |

**Paginated response envelope** (Spring Data `Page`):
```json
{
  "content": [ ... ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": { "sorted": true, "unsorted": false, "empty": false }
  },
  "totalElements": 142,
  "totalPages": 8,
  "last": false,
  "first": true,
  "size": 20,
  "number": 0,
  "numberOfElements": 20,
  "empty": false
}
```

---

## Error Response Format

All errors follow this structure:
```json
{
  "timestamp": "2026-04-02T12:00:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Comment content cannot be empty",
  "code": "EMPTY_COMMENT",
  "path": "/api/v1/researches/abc-123/comments"
}
```

Common HTTP status codes:
| Code | Meaning                          |
|------|----------------------------------|
| 200  | Success                          |
| 201  | Created                          |
| 204  | No Content (successful delete)   |
| 400  | Bad Request / Validation Error   |
| 401  | Unauthorized (not logged in)     |
| 403  | Forbidden (wrong role / not owner) |
| 404  | Resource Not Found               |
| 409  | Conflict (optimistic lock / dup) |
| 500  | Internal Server Error            |

---

## RabbitMQ Events (Internal Reference)

Events are published to `irc.topic.exchange` and consumed by notification & analytics services.

| Routing Key                      | Event Class               | Trigger                    |
|----------------------------------|---------------------------|----------------------------|
| `research.lifecycle.published`   | ResearchPublishedEvent    | Research is published      |
| `research.social.reacted`        | ResearchReactedEvent      | User reacts to research    |
| `research.social.commented`      | ResearchCommentedEvent    | User comments on research  |
| `research.analytics.viewed`      | ResearchViewedEvent       | Research page viewed       |
| `research.analytics.downloaded`  | ResearchDownloadedEvent   | Research file downloaded   |
| `post.lifecycle.created`         | PostCreatedEvent          | New post created           |
| `post.social.reacted`            | PostReactedEvent          | User reacts to post        |
| `post.social.commented`          | PostCommentedEvent        | User comments on post      |
| `post.social.shared`             | PostSharedEvent           | User shares a post         |
| `post.social.comment.reacted`    | PostCommentReactedEvent   | User reacts to comment     |

