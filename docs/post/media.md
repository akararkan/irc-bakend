# Post Media (Carousel) API

Manage a post's ordered media carousel after publication — add, list, remove and
reorder items. For four or fewer items the create endpoints usually inline media on
`posts_by_id.mediaUrls`; these endpoints exist for larger albums and post-publish
edits. Rows live in `media_by_post`, clustered by `sort_order ASC` so the read order
matches the render order.

- **Base path:** `/api/v1/posts/{postId}/media`
- **Auth:** `Authorization: Bearer <JWT>`. **Listing is public; every mutation
  requires authentication *and* post authorship** (see below).
- **Errors:** unified envelope — see [Error handling](../errors/error-handling.md).

> **Behavior note — author-guarded mutations.** `POST`, `DELETE` and `PUT` verify the
> caller against the post's `author_id` in `posts_by_id` before touching anything
> (media mutations don't pass through the post-service author guard, so the check
> lives here):
>
> - anonymous caller → **`401 AUTH_UNAUTHORIZED`**
> - post does not exist → **`404 POST_NOT_FOUND`**
> - authenticated but not the post's author → **`403 FORBIDDEN`** (service-level
>   `SecurityException` — "Not the post author")

Related: [Posts CRUD](./posts.md) · [Engagement](./engagement.md) ·
[Realtime SSE](./realtime.md)

**`MediaByPostEntity`** — the row shape used by every endpoint here:

| Field | Type | Description |
|-------|------|-------------|
| `postId` | UUID | Partition key |
| `sortOrder` | int | Clustering key — carousel position, ASC |
| `mediaId` | UUID | Clustering key — row id (server-generated when absent) |
| `mediaType` | string | `IMAGE` / `VIDEO` / `AUDIO` … |
| `url` | string | Public R2 URL |
| `thumbnailUrl` | string | Optional preview image (videos) |
| `s3Key` | string | Storage key in the R2 bucket |
| `durationSeconds` | int | Optional — playable media |
| `fileSizeBytes` | long | Optional |
| `mimeType` | string | e.g. `image/jpeg` |
| `altText` | string | Accessibility label |

---

## 1. `GET /api/v1/posts/{postId}/media` — list carousel items

```
GET /api/v1/posts/{postId}/media
```

**Auth:** none — **public**.

Returns the post's carousel rows in display order (`sortOrder ASC`). Use it when the
post-detail page needs more than what `PostResponse.mediaUrls` carries (thumbnails,
durations, alt text).

**Path parameters**

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | The post whose carousel to read |

**Response `200`:**

```json
[
  {
    "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b",
    "sortOrder": 0,
    "mediaId": "m0a1b2c3-d4e5-f678-9012-3456789abcde",
    "mediaType": "IMAGE",
    "url": "https://cdn.example.com/posts/img1.jpg",
    "thumbnailUrl": null,
    "s3Key": "posts/media/img1.jpg",
    "durationSeconds": null,
    "fileSizeBytes": 482301,
    "mimeType": "image/jpeg",
    "altText": "Cover photo"
  },
  {
    "postId": "f66aebce-d659-45b8-8479-75195f5d6d4b",
    "sortOrder": 1,
    "mediaId": "m1b2c3d4-e5f6-a789-0123-456789abcdef",
    "mediaType": "VIDEO",
    "url": "https://cdn.example.com/posts/clip.mp4",
    "thumbnailUrl": "https://cdn.example.com/posts/clip-thumb.jpg",
    "s3Key": "posts/media/clip.mp4",
    "durationSeconds": 42,
    "fileSizeBytes": 8429301,
    "mimeType": "video/mp4",
    "altText": "Lecture clip"
  }
]
```

Empty carousel → `200` + `[]`.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `TYPE_MISMATCH` | `postId` malformed |

---

## 2. `POST /api/v1/posts/{postId}/media` — add one item

```
POST /api/v1/posts/{postId}/media
Content-Type: application/json
```

**Auth:** required + **author-only** (verified against `posts_by_id.author_id`).

Appends a single media row to the carousel. The file itself should already be in R2 —
pass its public URL and storage key here. For brand-new posts prefer the
[multipart create](./posts.md#2-post-apiv1posts--create-multipart), which uploads and
persists atomically.

**Path parameters**

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | The post to append to |

**Request body (`AddMediaRequest`):**

```json
{
  "sortOrder":       2,
  "mediaType":       "IMAGE",
  "url":             "https://cdn.example.com/posts/big.jpg",
  "thumbnailUrl":    null,
  "s3Key":           "posts/media/abc.jpg",
  "durationSeconds": null,
  "fileSizeBytes":   482301,
  "mimeType":        "image/jpeg",
  "altText":         "Picture of the library entrance"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sortOrder` | int | yes | Carousel position |
| `mediaType` | string | yes | `IMAGE` / `VIDEO` / `AUDIO` |
| `url` | string | yes | R2 public URL |
| `thumbnailUrl` | string | no | Preview image |
| `s3Key` | string | no | Storage key (enables later cleanup) |
| `durationSeconds` / `fileSizeBytes` / `mimeType` / `altText` | mixed | no | Metadata |

**Response `200`:** the created `MediaByPostEntity` row (server generates `mediaId`).

**Side effects:** one `media_by_post` insert. `posts_by_id.mediaUrls` is **not**
rewritten — the carousel table is the authority for albums.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | Anonymous caller |
| 404 | `POST_NOT_FOUND` | Post does not exist |
| 403 | `FORBIDDEN` | Caller is not the post's author |
| 400 | `MALFORMED_JSON` | Body unparseable |

---

## 3. `DELETE /api/v1/posts/{postId}/media/{mediaId}` — remove one item

```
DELETE /api/v1/posts/{postId}/media/{mediaId}?sortOrder=1
```

**Auth:** required + **author-only**.

Removes a single carousel row by its full `(postId, sortOrder, mediaId)` primary key.
`sortOrder` **must** be passed — it is part of the Cassandra clustering key, so the
row can't be addressed without it. Idempotent: an unknown tuple is a silent no-op.

**Path parameters**

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | The post |
| `mediaId` | UUID | The media row to remove |

**Query parameters**

| Param | Type | Description |
|-------|------|-------------|
| `sortOrder` | int | **Required** — the row's carousel position (clustering key) |

**Response:** `204 No Content`.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | Anonymous caller |
| 404 | `POST_NOT_FOUND` | Post does not exist |
| 403 | `FORBIDDEN` | Caller is not the post's author |
| 400 | `MISSING_PARAMETER` | `sortOrder` not supplied |

---

## 4. `PUT /api/v1/posts/{postId}/media` — replace all (reorder)

```
PUT /api/v1/posts/{postId}/media
Content-Type: application/json
```

**Auth:** required + **author-only**.

Replaces the entire carousel with a new ordered list — the drag-and-drop reorder
path. Because `sortOrder` is part of the clustering key it can't be `UPDATE`d in
place; the server bulk-deletes the existing rows and re-inserts the new list, with
`sortOrder` reassigned **from the array index** (0, 1, 2, …) regardless of what the
body says. Rows without a `mediaId` get a fresh one. Acceptable cost — carousels
rarely exceed 20 items.

**Path parameters**

| Param | Type | Description |
|-------|------|-------------|
| `postId` | UUID | The post whose carousel to replace |

**Request body:** the full new ordered list of `MediaByPostEntity` rows:

```json
[
  {
    "mediaId": "m1b2c3d4-e5f6-a789-0123-456789abcdef",
    "mediaType": "IMAGE",
    "url": "https://cdn.example.com/posts/img2.jpg",
    "s3Key": "posts/media/img2.jpg",
    "mimeType": "image/jpeg"
  },
  {
    "mediaId": "m0a1b2c3-d4e5-f678-9012-3456789abcde",
    "mediaType": "IMAGE",
    "url": "https://cdn.example.com/posts/img1.jpg",
    "s3Key": "posts/media/img1.jpg",
    "mimeType": "image/jpeg"
  }
]
```

**Response `200`:** the freshly-read ordered list (same shape as the
[GET](#1-get-apiv1postspostidmedia--list-carousel-items)).

**Side effects:** `media_by_post` partition delete + N re-inserts.

> An empty array clears the carousel entirely — send the full desired state, not a
> diff.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | Anonymous caller |
| 404 | `POST_NOT_FOUND` | Post does not exist |
| 403 | `FORBIDDEN` | Caller is not the post's author |
| 400 | `MALFORMED_JSON` | Body unparseable |
