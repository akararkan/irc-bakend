# Posts — CRUD API

Create, read, edit and delete posts — the canonical write path of the Cassandra-backed
social layer. A post is stored once in `posts_by_id` and denormalized into the profile
feed (`posts_by_author`), the reels discover feed (`reels_by_day`, REEL only), every
follower's home timeline (`feed_by_user`, async fanout) and Elasticsearch.

- **Base path:** `/api/v1/posts`
- **Auth:** `Authorization: Bearer <JWT>`. Reads are public; every mutation requires
  authentication, and edit/delete are **author-only**.
- **Errors:** all error responses use the unified envelope
  (`timestamp` / `status` / `error` / `message` / `path` / `errorCode` / `details` /
  `fieldErrors` / `traceId`) — see [Error handling](../errors/error-handling.md).
- **Page-size clamp:** every read endpoint in this module clamps `pageSize` / `size` /
  `limit` into **1..100** — values above 100 are silently reduced to 100.

Related: [Feed & suggestions](./feed.md) · [Reels](./reels.md) ·
[Engagement](./engagement.md) · [Media](./media.md) · [Realtime SSE](./realtime.md)

---

## 1. `POST /api/v1/posts` — create (JSON)

```
POST /api/v1/posts
Content-Type: application/json
```

**Auth:** required (401 without a JWT).

Creates a new post owned by the authenticated user. This is the canonical creation
path when the media is already uploaded (e.g. via a previous direct-to-R2 upload) and
the frontend just needs to persist the post row referencing those URLs. For uploads
bundled with the post, use the [multipart variant](#2-post-apiv1posts--create-multipart).

> **`authorId` is always derived from the JWT.** Any `authorId` supplied in the body
> is ignored — the server rebuilds the create command with the principal's id. One
> user can never publish as another.

**Rate limit:** the `social` bucket — 30 writes per minute per user. Exceeding it
returns `429 RATE_LIMITED` with `details.retryAfterSeconds`.

**Request body (`CreatePostCommand`):**

```json
{
  "postType":      "EMBEDDED",
  "visibility":    "PUBLIC",
  "textContent":   "Reading at the library today 📚 #fiqh @ahmed",
  "audioTrackUrl": null,
  "audioTrackName": null,
  "locationName":  "Erbil Central Library",
  "locationLat":   36.1911,
  "locationLng":   44.0094,
  "sharedPostId":  null,
  "shareLink":     null,
  "mediaUrls":     ["https://cdn.example.com/posts/2f.jpg"],
  "mediaTypes":    ["IMAGE"],
  "soundId":       null
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `postType` | string enum | yes | `TEXT` / `EMBEDDED` / `VOICE_POST` / `REEL` / `REPOST` / `STORY` |
| `visibility` | string enum | yes | `PUBLIC` / `FOLLOWERS_ONLY` / `ONLY_ME` |
| `textContent` | string | no | Post body; hashtags + `@mentions` are extracted from it |
| `audioTrackUrl` | string | no | R2 URL of the audio file (for `VOICE_POST`) |
| `audioTrackName` | string | no | Display label for the audio |
| `locationName` | string | no | Free-text location |
| `locationLat` | double | no | Geo coordinate |
| `locationLng` | double | no | Geo coordinate |
| `sharedPostId` | UUID | for `REPOST` | The post being reposted (self-repost is allowed) |
| `shareLink` | string | no | External URL (link cards) |
| `mediaUrls` | string[] | no | R2 public URLs |
| `mediaTypes` | string[] | no | Parallel to `mediaUrls` — `IMAGE` / `VIDEO` / `AUDIO_TRACK` / `DOCUMENT` |
| `soundId` | UUID | no | Adopts a Sound-library entry; bumps its `use_count` |

For a REEL, the feed cover media is the first `VIDEO`-typed URL (so feed rows always
point at playable media); for other post types it is the first URL.

**Response `200`** — fully-hydrated `PostResponse` (author summary + live counters +
viewer flags), so the frontend can prepend it to the feed without a follow-up fetch:

```json
{
  "id": "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id": "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "username": "akar.arkanf19",
    "fullName": "akar arkan",
    "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
  },
  "postType":    "EMBEDDED",
  "status":      "PUBLISHED",
  "visibility":  "PUBLIC",
  "textContent": "Reading at the library today 📚 #fiqh @ahmed",
  "audioTrackUrl":  null,
  "audioTrackName": null,
  "locationName":   "Erbil Central Library",
  "locationLat":    36.1911,
  "locationLng":    44.0094,
  "sharedPostId":   null,
  "shareLink":      null,
  "mediaUrls":      ["https://cdn.example.com/posts/2f.jpg"],
  "mediaTypes":     ["IMAGE"],
  "reactionCount":  0,
  "commentCount":   0,
  "viewCount":      0,
  "saveCount":      0,
  "shareCount":     0,
  "likedByMe":      false,
  "savedByMe":      false,
  "createdAt":      "2026-07-20T14:30:00Z",
  "updatedAt":      "2026-07-20T14:30:00Z",
  "savedAt":        null,
  "savedCollectionName": null
}
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Post id (canonical `posts_by_id` row) |
| `authorId` / `author` | UUID / object | Author id + inlined `AuthorSummary` (`id`, `username`, `fullName`, `profileImage`) |
| `postType` / `status` / `visibility` | string | Enum values; new posts are always `PUBLISHED` |
| `textContent`, media/location/audio fields | mixed | Echo of the stored content |
| `reactionCount` … `shareCount` | long | Live denormalized counters from `post_counters` |
| `likedByMe` / `savedByMe` | boolean | Viewer-relative flags (always `false` for anonymous viewers) |
| `savedAt` / `savedCollectionName` | Instant / string | Only populated by the saved-list endpoint; `null` here |

**Side effects**

Synchronous (before the response returns):
- `posts_by_id` canonical row + `posts_by_author` profile-feed row written.
- `reels_by_day` row (only when `postType == "REEL"`).
- Sound adoption (`soundId` set): post indexed under the sound, `use_count`++.
- Hashtag + mention extraction from `textContent` (all `@username`s resolved in a
  single batched Postgres query); mentioned users can see the post in their mentions
  inbox immediately.
- Activity row `POST_CREATED` for the author (best-effort).

Asynchronous (never blocks the response):
- Fanout to every follower's `feed_by_user` partition (see [feed.md](./feed.md#fanout-on-write-internals)),
  including a `POST_NEW` in-app notification per follower (email-ineligible) and a
  `FEED_NEW_POST` realtime push.
- Elasticsearch indexing (search is eventually consistent).

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 400 | `MALFORMED_JSON` | Body unparseable |
| 429 | `RATE_LIMITED` | More than 30 social writes in 60 s |
| 500 | `INTERNAL_ERROR` | Cassandra write failure |

---

## 2. `POST /api/v1/posts` — create (multipart)

```
POST /api/v1/posts
Content-Type: multipart/form-data
```

**Auth:** required (401 without a JWT).

Creates a post **and** uploads its binary media in one round-trip. Each file part is
streamed to R2 under `posts/media/` and the resulting public URLs are written into
`mediaUrls` automatically (media type classified from the part's content type:
`IMAGE` / `VIDEO` / `AUDIO` / `OTHER`). This is the path the in-app composer uses for
image posts and reels.

The rate-limit check (`social`, 30/min) runs **before** any upload — a throttled user
doesn't waste bandwidth and storage round-trips just to be rejected at the end.

**Form fields** (all optional except `postType`, which defaults to `"POST"` if
omitted; `visibility` defaults to `"PUBLIC"`):

| Form field | Type | Notes |
|------------|------|-------|
| `postType` | string | e.g. `REEL`, `EMBEDDED`, `TEXT` |
| `visibility` | string | defaults to `PUBLIC` |
| `textContent` | string | optional |
| `audioTrackUrl` / `audioTrackName` | string | optional |
| `locationName` / `locationLat` / `locationLng` | string / double | optional; malformed numbers are silently treated as absent |
| `sharedPostId` / `soundId` | UUID | optional; malformed UUIDs are silently treated as absent |
| `shareLink` | string | optional |
| file parts | file | repeatable — **any** part name works: `files`, `files[]`, `media`, `media[]`, `file`, `video`, `videos`, `image`, `images` |

```bash
curl -X POST https://api.irc.example.com/api/v1/posts \
  -H "Authorization: Bearer <jwt>" \
  -F 'postType=REEL' \
  -F 'visibility=PUBLIC' \
  -F 'textContent=Quick recap from todays lecture' \
  -F 'files[]=@reel.mp4;type=video/mp4' \
  -F 'files[]=@thumb.jpg;type=image/jpeg'
```

**Response `200`:** same fully-hydrated `PostResponse` as the JSON create.

**R2 rollback guarantee.** If anything fails after files reached R2, every
successfully-uploaded key is best-effort deleted so the bucket never accumulates
orphan files that no post references:

- **Upload failure** → previously-uploaded keys deleted, response `502` with a
  **custom body** (not the unified envelope): `{ "error": "upload_failed", "message": "<cause>" }`.
- **Cassandra insert fails after R2 success** → all keys deleted, response `500` with
  a **custom body**: `{ "error": "post_create_failed", "message": "...", "rolledBackFiles": 2 }`.

**Errors**

| Status | `errorCode` / body | When |
|--------|--------------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 413 | `FILE_TOO_LARGE` | File exceeds the configured multipart max size |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Wrong content type |
| 429 | `RATE_LIMITED` | Social-bucket throttle (checked before upload) |
| 502 | custom `{"error":"upload_failed"}` | R2 upload failed; uploaded keys rolled back |
| 500 | custom `{"error":"post_create_failed","rolledBackFiles":n}` | DB insert failed after upload; keys rolled back |

**Side effects:** identical to the JSON create (plus the R2 object writes).

---

## 3. `GET /api/v1/posts/{id}` — single post

```
GET /api/v1/posts/{id}
```

**Auth:** none (public). `likedByMe` / `savedByMe` reflect the authenticated viewer
when a JWT is present; both are `false` for anonymous viewers.

Returns the canonical post joined with the author profile summary, live counters and
viewer-relative flags — the post-detail page in one request.

**Path parameters**

| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Post id |

**Response `200`:** full `PostResponse` (same shape and field table as §1), e.g.:

```json
{
  "id": "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": { "id": "41ee2a6b-2cd9-417b-861c-d1293c623690", "username": "akar.arkanf19", "fullName": "akar arkan", "profileImage": "https://cdn.example.com/avatars/41ee.jpg" },
  "postType": "EMBEDDED",
  "status": "PUBLISHED",
  "visibility": "PUBLIC",
  "textContent": "Reading at the library today 📚 #fiqh @ahmed",
  "mediaUrls": ["https://cdn.example.com/posts/2f.jpg"],
  "mediaTypes": ["IMAGE"],
  "reactionCount": 12,
  "commentCount": 3,
  "viewCount": 345,
  "saveCount": 7,
  "shareCount": 1,
  "likedByMe": true,
  "savedByMe": false,
  "createdAt": "2026-07-20T14:30:00Z",
  "updatedAt": "2026-07-20T14:30:00Z",
  "savedAt": null,
  "savedCollectionName": null
}
```

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `TYPE_MISMATCH` | `{id}` is not a UUID |
| 404 | *(bare body)* | Post does not exist or was hard-deleted |

---

## 4. `PATCH /api/v1/posts/{id}` — partial edit (author-only)

```
PATCH /api/v1/posts/{id}
Content-Type: application/json
```

**Auth:** required; **author-only** — non-authors get `403 FORBIDDEN`.

Partial update: every field on the body is optional and `null` leaves the existing
value untouched. Only changed fields are written; a body with no recognised fields is
a logged no-op that returns the unchanged post.

**Path parameters**

| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Post to edit |

**Request body (`EditPostCommand`)** — all fields nullable:

```json
{
  "textContent": "Edited body — typo fix",
  "visibility":  "FOLLOWERS_ONLY",
  "locationName": "Erbil",
  "locationLat":  36.19,
  "locationLng":  44.00,
  "mediaUrls":   null,
  "mediaTypes":  null
}
```

| Field | Type | Description |
|-------|------|-------------|
| `textContent` | string | Also accepted under the JSON keys `text`, `content` or `body` (`@JsonAlias`) |
| `visibility` | string | `PUBLIC` / `FOLLOWERS_ONLY` / `ONLY_ME` |
| `audioTrackUrl` / `audioTrackName` | string | Audio metadata |
| `locationName` / `locationLat` / `locationLng` | string / double | Location pin |
| `mediaUrls` | string[] | Also accepted under `media` or `images` |
| `mediaTypes` | string[] | Parallel to `mediaUrls` |

**Response `200`:** the updated, fully-hydrated `PostResponse` with a fresh
`updatedAt`.

**Side effects**

- `posts_by_id` updated (`updatedAt = now`).
- Best-effort mirror onto the `posts_by_author` profile-feed row (a failure there is
  logged, not surfaced — the hydrator prefers the live canonical row anyway).
- Async Elasticsearch re-index.
- If `textContent` changed: hashtags re-extracted so a `#foo` → `#bar` edit doesn't
  leave the old tag attached to the post.
- Broadcasts **`POST_UPDATED`** on the post's [SSE channel](./realtime.md) (carries
  the new `textContent`) so open viewers reconcile without a refetch.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 403 | `ACCESS_FORBIDDEN` | Caller is not the post's author |
| 404 | *(bare body)* | Post does not exist |
| 400 | `MALFORMED_JSON` | Body unparseable |

---

## 5. `DELETE /api/v1/posts/{id}` — hard-delete (author-only)

```
DELETE /api/v1/posts/{id}
```

**Auth:** required; **author-only** — the controller loads the post and compares
`author_id` against the JWT principal before deleting.

Permanently removes the post. **This is a hard delete — there is no recovery.**

> **Behavior note — immediate disappearance, async cascade.** The post vanishes for
> every reader **before the `204` returns**: the canonical `posts_by_id` row, the
> `posts_by_author` profile-feed row and the Elasticsearch entry are all removed
> synchronously. The heavy cascade over the post's children — which is
> O(comments + saves) driver round-trips and used to pin the request thread for
> seconds on a viral post — now runs on a background executor after the response.

**Path parameters**

| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Post to delete |

**Request body:** none. **Response:** `204 No Content`.

**Side effects**

Synchronous (post is invisible immediately):
- `posts_by_author` profile-feed row deleted (best-effort).
- `posts_by_id` canonical row deleted.
- Async Elasticsearch de-index queued.

Asynchronous cascade (background executor, each step best-effort and logged on
failure so one misbehaving table can't strand the rest):
1. Per-comment fan-out — for every comment on the post: its reaction rows
   (`comment_reactions_by_comment`), its reply partition (`replies_by_comment`,
   one range tombstone) and its `comment_counters` row.
2. `comments_by_post` partition delete.
3. Per-save fan-out — `saves_by_post_user` is drained to find each saver's
   `(user_id, created_at)` tuple, then the matching `saves_by_user` rows are wiped;
   finally the `saves_by_post_user` partition itself.
4. `reactions_by_post`, `views_by_post`, `shares_by_post`, `media_by_post`
   partition deletes.
5. `post_counters` row delete.
6. Hashtag fan-out cleanup + Postgres notifications referencing the post deleted.

Intentionally **not** swept: `reactions_by_user` (user-partitioned; read paths join
through `posts_by_id` and skip the missing parent) and `feed_by_user` rows (30-day
TTL; feed hydration drops rows whose canonical post is gone, so stale timeline
entries never render).

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
| 404 | *(bare body)* | Post does not exist |
| 403 | `ACCESS_FORBIDDEN` | Caller is not the post's author |
