# Reels — Feed API

Short-form video feeds. A reel is a normal post with `postType = "REEL"` that is
additionally indexed into the global `reels_by_day` table (partitioned per UTC date so
partitions stay bounded). Four read surfaces: the global day-bucket discover feed, a
following-only feed, an engagement-ranked "For You" feed, and a per-author reels tab.

- **Base path:** `/api/v1/posts`
- **Auth:** `Authorization: Bearer <JWT>`. Global / for-you / by-author reels are
  public; `/reels/following` needs a viewer (anonymous callers get an empty list).
- **Errors:** unified envelope — see [Error handling](../errors/error-handling.md).
- **Page-size clamp:** `pageSize` / `size` are clamped into **1..100**; values above
  100 are silently reduced to 100.

All four endpoints return `List<FeedItemResponse>` (see [feed.md](./feed.md#1-get-apiv1postsby-authorauthorid--profile-feed)
for the full field table). On reel rows `postType` is always `"REEL"` and `videoUrl`
carries the first `VIDEO`-typed media URL from the live canonical post — the playable
asset, even when `mediaUrl` points at a cover thumbnail. Rows whose canonical post has
been deleted are dropped at hydration time. Creation goes through the normal
[post create endpoints](./posts.md) with `postType=REEL`; likes / comments / views use
the standard [engagement endpoints](./engagement.md).

Sample item (shared by every endpoint below):

```json
{
  "id": "aa11bb22-3344-5566-7788-99aabbccddee",
  "authorId": "9c1f1a2b-3344-5566-7788-99aabbccddee",
  "author": {
    "id": "9c1f1a2b-3344-5566-7788-99aabbccddee",
    "username": "ahmad",
    "fullName": "Ahmad Rahman",
    "profileImage": "https://cdn.example.com/avatars/9c1f.jpg"
  },
  "entityType": "POST",
  "postType": "REEL",
  "textPreview": "60-second recap from yesterday's lecture",
  "mediaUrl": "https://cdn.example.com/reels/clip-9c1f.mp4",
  "videoUrl": "https://cdn.example.com/reels/clip-9c1f.mp4",
  "reactionCount": 124,
  "commentCount": 18,
  "viewCount": 8492,
  "saveCount": 35,
  "shareCount": 7,
  "likedByMe": false,
  "savedByMe": true,
  "createdAt": "2026-07-20T13:15:00Z"
}
```

---

## 1. `GET /api/v1/posts/reels` — global discover feed (day bucket)

```
GET /api/v1/posts/reels?day=2026-07-20&pageSize=20
```

Also available as **`GET /api/v1/posts/feed/reels`** (legacy alias — same params,
same response).

**Auth:** none (public). Viewer flags are `false` for anonymous callers.

The global reels feed for one UTC day, chronological within the bucket (this endpoint
is *not* ranked — see [For You](#3-get-apiv1postsreelsfor-you--engagement-ranked-feed)
for ranking). Defaults to today. A scroll-back-in-time UI walks the bucket chain
`today → today-1 → today-2 → …` and concatenates.

**Query parameters**

| Param | Type | Description |
|-------|------|-------------|
| `day` | string `YYYY-MM-DD` | UTC day bucket; defaults to today (UTC) |
| `pageSize` | int | Default `20`, clamped to 1..100 |
| `size` | int | Legacy alias; when `> 0` it takes precedence over `pageSize` |
| `page` | int | Legacy, currently unused |

**Response `200`:** `List<FeedItemResponse>` — `postType` always `"REEL"`. Empty
bucket → `[]`.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `TYPE_MISMATCH` | Malformed query parameter |

---

## 2. `GET /api/v1/posts/reels/following` — reels from followed accounts

```
GET /api/v1/posts/reels/following?pageSize=20&cursor=2026-07-20T13:15:00Z
```

**Auth:** required in practice — the viewer comes from the JWT. Anonymous callers get
`200` + `[]` (not a 401).

Reels only from accounts the viewer follows, newest first, cursor-paginated.

**How it's built — concurrent per-author fan-in.** The viewer's following list is
read from cache, capped at the **first 500 followed authors**; each author's
`posts_by_author` partition is queried concurrently on the bounded shared executor
(up to **5 reels per author** per page), then the results are merge-sorted by
`createdAt DESC` and the top `pageSize` returned. A failed per-author read is logged
and skipped — one slow author can't sink the page.

**Query parameters**

| Param | Type | Description |
|-------|------|-------------|
| `pageSize` | int | Default `20`, clamped to 1..100 |
| `cursor` | ISO-8601 Instant | `createdAt` of the last item from the previous page |

**Response `200`:** `List<FeedItemResponse>` — reels only, newest first. Viewer
follows nobody → `[]`.

> Because each author contributes at most 5 reels per page, a very prolific author's
> older reels surface on subsequent cursor pages rather than flooding page one.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `TYPE_MISMATCH` | Malformed `cursor` |

---

## 3. `GET /api/v1/posts/reels/for-you` — engagement-ranked feed

```
GET /api/v1/posts/reels/for-you?pageSize=20
```

**Auth:** optional. Anonymous callers get the global ranking **without** the
following boost; authenticated callers get a 1.5× boost on reels from accounts they
follow.

The ranked "For You" surface — pure counter math plus recency decay, no ML:

1. **Collect:** up to 200 candidate reels from the last **3** UTC day buckets of
   `reels_by_day` (~66 per day).
2. **Enrich:** one bulk IN-query to `post_counters` for all candidate ids.
3. **Score** each reel:

   ```
   engagement  = reactions × 3 + comments × 2 + views
   recency     = e^(−ageHours / 48)          # 48-hour half-life decay
   followBoost = 1.5 if the viewer follows the author, else 1.0
   score       = (engagement + 1) × recency × followBoost
   ```

   The `+1` keeps brand-new reels with zero engagement from scoring exactly 0, so
   fresh content still surfaces via the recency term.
4. **Sort** descending, return the top `pageSize`.

**Query parameters**

| Param | Type | Description |
|-------|------|-------------|
| `pageSize` | int | Default `20`, clamped to 1..100 |

**Response `200`:** `List<FeedItemResponse>` sorted by score (not by time). No
cursor — re-request for a refreshed ranking.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `TYPE_MISMATCH` | Malformed query parameter |

---

## 4. `GET /api/v1/posts/reels/by-author/{authorId}` — an author's reels

```
GET /api/v1/posts/reels/by-author/{authorId}?pageSize=20&cursor=2026-07-19T09:42:00Z
```

**Auth:** none (public).

A single author's reels, newest first, cursor-paginated — the Reels tab on a profile
page. The general [`/by-author/{authorId}` profile feed](./feed.md#1-get-apiv1postsby-authorauthorid--profile-feed)
mixes reels in with every other post type; this endpoint is the reel-only,
correctly-paginated slice.

**Path parameters**

| Param | Type | Description |
|-------|------|-------------|
| `authorId` | UUID | The author whose reels to list |

**Query parameters**

| Param | Type | Description |
|-------|------|-------------|
| `pageSize` | int | Default `20`, clamped to 1..100 |
| `cursor` | ISO-8601 Instant | `createdAt` of the last item from the previous page |

**Response `200`:** `List<FeedItemResponse>` — reels only, newest first, `[]` when
the author has no (more) reels.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `TYPE_MISMATCH` | `authorId` or `cursor` malformed |
