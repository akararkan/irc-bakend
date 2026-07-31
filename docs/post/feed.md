# Feeds & Friend Suggestions API

> **⚠️ The home feed is now a ranked recommendation system — the canonical
> feed documentation moved to [`docs/feed/`](../feed/README.md)**
> (algorithm spec, full API analysis incl. channels-in-feed + the live
> rail, frontend guide). **Friend suggestions were likewise rebuilt as a
> multi-signal engine — canonical docs at
> [`docs/suggestions/`](../suggestions/README.md).** This file remains
> accurate for the fanout write path and the profile feed; the home-feed
> *read* sections below describe the `?ranked=false` chronological
> fallback, and the suggestion sections describe only the legacy raw-row
> read shape.

The profile feed, the home timeline (fanout-on-write) and the friends-of-friends
suggestion engine. Feed reads are single Cassandra partition slices hydrated in bulk
(counters, viewer likes/saves and author profiles are each loaded in one IN-clause
round-trip per page — never per row).

- **Base path:** `/api/v1/posts`
- **Auth:** `Authorization: Bearer <JWT>`. The profile feed is public; the home feed
  and both suggestion endpoints are viewer-bound (JWT-derived).
- **Errors:** unified envelope — see [Error handling](../errors/error-handling.md).
- **Page-size clamp:** `pageSize` / `limit` are clamped into **1..100**; values above
  100 are silently reduced to 100.

Related: [Posts CRUD](./posts.md) · [Reels](./reels.md) ·
[Engagement](./engagement.md) · [Realtime SSE](./realtime.md)

---

## 1. `GET /api/v1/posts/by-author/{authorId}` — profile feed

```
GET /api/v1/posts/by-author/{authorId}?pageSize=20&cursor=2026-07-20T14:30:00Z
```

**Auth:** none (public). `likedByMe` / `savedByMe` reflect the JWT viewer when
present, otherwise `false`.

Every post written by one author, newest first, cursor-paginated. Backed by
`posts_by_author` — a single partition scan, fast even for an author with thousands
of posts. Rows are hydrated against the live canonical post (`posts_by_id`), so an
edited post shows fresh text and a hard-deleted post is silently dropped from the
page.

**Path parameters**

| Param | Type | Description |
|-------|------|-------------|
| `authorId` | UUID | The author whose posts to list |

**Query parameters**

| Param | Type | Description |
|-------|------|-------------|
| `pageSize` | int | Default `20`, clamped to 1..100 |
| `cursor` | ISO-8601 Instant | Omit for the first page; pass the `createdAt` of the last item for the next page |

**Response `200`:** `List<FeedItemResponse>`, newest first. Empty array = end of feed.

```json
[
  {
    "id": "f66aebce-d659-45b8-8479-75195f5d6d4b",
    "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "author": {
      "id": "41ee2a6b-2cd9-417b-861c-d1293c623690",
      "username": "akar.arkanf19",
      "fullName": "akar arkan",
      "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
    },
    "entityType": "POST",
    "postType": "EMBEDDED",
    "textPreview": "Reading at the library today 📚 #fiqh @ahmed",
    "mediaUrl": "https://cdn.example.com/posts/2f.jpg",
    "videoUrl": null,
    "reactionCount": 12,
    "commentCount": 3,
    "viewCount": 345,
    "saveCount": 7,
    "shareCount": 1,
    "likedByMe": true,
    "savedByMe": false,
    "createdAt": "2026-07-20T14:30:00Z"
  }
]
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Post id (use for React keys and `/posts/{id}` navigation) |
| `author` | object | Inlined `AuthorSummary` — no second round-trip for name/avatar |
| `entityType` | string | `POST` \| `RESEARCH` \| `QUESTION` — dispatch discriminator (always `POST` on this endpoint) |
| `postType` | string | `TEXT` / `EMBEDDED` / `VOICE_POST` / `REEL` / `REPOST` sub-flavour |
| `textPreview` | string | Live text truncated to 280 chars |
| `mediaUrl` | string | Cover media (first media URL), or `null` |
| `videoUrl` | string | REEL only — first `VIDEO`-typed URL; `null` for every other type |
| counters | long | Live values from `post_counters` |
| `likedByMe` / `savedByMe` | boolean | Viewer-relative flags |
| `createdAt` | Instant | Also serves as the pagination cursor |

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `TYPE_MISMATCH` | `authorId` or `cursor` malformed |

---

## 2. `GET /api/v1/posts/feed` — home timeline

```
GET /api/v1/posts/feed?pageSize=20&cursor=2026-07-20T14:30:00Z
```

Also available as **`GET /api/v1/posts/feed/cursor`** — a legacy alias preserved from
the pre-Cassandra controller; both accept the same parameters and return the same
response.

**Auth:** viewer is taken from the JWT principal. A legacy `?userId=` fallback is
still honored for old clients when no JWT is present; anonymous callers with no
`userId` get `200` + `[]` (not a 401).

The viewer's home timeline: posts by every followed author, plus the viewer's own
posts, newest first. Since the feed table is shared with other modules, the page can
also contain `RESEARCH` and `QUESTION` cards (see `entityType` below).

**Query parameters**

| Param | Type | Description |
|-------|------|-------------|
| `userId` | UUID | Legacy fallback only — the JWT principal always wins when present |
| `pageSize` | int | Default `20`, clamped to 1..100 |
| `limit` | int | Legacy alias for `pageSize`; when `> 0` it takes precedence |
| `cursor` | ISO-8601 Instant | `createdAt` of the last item from the previous page |

**Response `200`:** `List<FeedItemResponse>` (same shape as §1), newest first.
Mixed-entity example:

```json
[
  {
    "id": "aa11bb22-3344-5566-7788-99aabbccddee",
    "authorId": "9c1f1a2b-3344-5566-7788-99aabbccddee",
    "author": { "id": "9c1f1a2b-3344-5566-7788-99aabbccddee", "username": "ahmad", "fullName": "Ahmad Rahman", "profileImage": "https://cdn.example.com/avatars/9c1f.jpg" },
    "entityType": "POST",
    "postType": "REEL",
    "textPreview": "60-second recap from yesterday's lecture",
    "mediaUrl": "https://cdn.example.com/reels/thumb-9c1f.jpg",
    "videoUrl": "https://cdn.example.com/reels/clip-9c1f.mp4",
    "reactionCount": 124, "commentCount": 18, "viewCount": 8492,
    "saveCount": 35, "shareCount": 7,
    "likedByMe": false, "savedByMe": true,
    "createdAt": "2026-07-20T13:15:00Z"
  },
  {
    "id": "cc33dd44-5566-7788-99aa-bbccddeeff00",
    "authorId": "7d2e3f4a-1122-3344-5566-778899aabbcc",
    "author": { "id": "7d2e3f4a-1122-3344-5566-778899aabbcc", "username": "fatima", "fullName": "Fatima Yusuf", "profileImage": null },
    "entityType": "RESEARCH",
    "postType": "PUBLICATION",
    "textPreview": "Water rights in classical fiqh — a comparative survey",
    "mediaUrl": "https://cdn.example.com/research/cover-7d2e.jpg",
    "videoUrl": null,
    "reactionCount": 0, "commentCount": 0, "viewCount": 0,
    "saveCount": 0, "shareCount": 0,
    "likedByMe": false, "savedByMe": false,
    "createdAt": "2026-07-20T11:00:00Z"
  }
]
```

- **`entityType` first.** `POST` rows navigate to `/api/v1/posts/{id}`; `RESEARCH` →
  `/api/v1/researches/{id}`; `QUESTION` → `/api/v1/questions/{id}`. For non-POST rows
  `postType` is only a UI badge label, and all five counters are `0` — real counters
  live on the entity's own detail endpoint (no cross-domain bulk hydration at feed
  read time).
- **Deleted posts never render.** POST rows are re-joined against `posts_by_id` at
  read time; rows whose canonical post is gone are dropped from the page.
- **Own posts guaranteed.** The read path merges the viewer's own `posts_by_author`
  partition into the page (deduped by post id), so the author always sees their own
  posts even for legacy rows that predate self-fanout.

### Fanout-on-write internals

How this list gets built (useful for understanding freshness):

- On post create, an async job walks the author's followers with **keyset pagination**
  (`findFollowerIdsAfter`, batches of 500 — constant cost per page regardless of
  scan depth) and writes one `feed_by_user` row per follower, plus a self-row for
  the author. `ONLY_ME` posts fan out to the author's own partition only.
- Per-follower writes run in parallel on a **bounded executor**
  (core 8 / max 32 / queue 10 000, `CallerRunsPolicy` for natural backpressure) —
  not the JVM-wide ForkJoin common pool.
- Hard cap `MAX_FANOUT_FOLLOWERS = 50 000` per post; a circuit breaker opens after
  20 consecutive Cassandra write failures and drops fanout writes for 30 s (readers
  fall back to pull-on-read).
- Each delivered row also updates the follower's Redis ZSET cache
  (`feed:timeline:{userId}`, last ~100 ids), publishes a `FEED_NEW_POST` realtime
  event, and queues a `POST_NEW` in-app notification (email-ineligible — 50 k
  followers never means 50 k emails).
- `feed_by_user` rows expire on a 30-day TTL; on a new follow, the author's ~50 most
  recent posts are backfilled into the follower's partition.

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 400 | `TYPE_MISMATCH` | `cursor` / `userId` malformed |

---

## 3. `GET /api/v1/posts/suggestions` — who to follow

```
GET /api/v1/posts/suggestions?limit=20
```

**Auth:** **required** — `401 AUTH_UNAUTHORIZED` for anonymous callers.

> **Behavior note.** This endpoint is now strictly viewer-bound: the partition being
> read is bound to the **JWT principal**, never a query parameter. The old
> `?userId=` parameter is **gone** — one user can no longer browse another user's
> suggestion graph.

Returns the pre-computed "who to follow" list — pure friends-of-friends collaborative
filtering. Score = number of mutual follows; candidates need at least 2 mutuals; the
top 50 are stored per user, already sorted `score DESC` at the table level so the
read is a single partition scan.

**Query parameters**

| Param | Type | Description |
|-------|------|-------------|
| `limit` | int | Default `20`, clamped to 1..100 |

**Response `200`:**

```json
[
  {
    "userId":      "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "score":       6,
    "candidateId": "9c1f1a2b-3344-5566-7788-99aabbccddee",
    "reason":      "6 mutual follows",
    "computedAt":  "2026-07-20T03:15:00Z"
  },
  {
    "userId":      "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "score":       4,
    "candidateId": "7d2e3f4a-1122-3344-5566-778899aabbcc",
    "reason":      "4 mutual follows",
    "computedAt":  "2026-07-20T03:15:00Z"
  }
]
```

| Field | Type | Description |
|-------|------|-------------|
| `userId` | UUID | The caller (partition key — always the JWT user) |
| `score` | int | Mutual-follow count (sort key, DESC) |
| `candidateId` | UUID | The suggested user to follow |
| `reason` | string | Human-readable label, e.g. `"6 mutual follows"` |
| `computedAt` | Instant | When the suggestion batch was computed |

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |

---

## 4. `POST /api/v1/posts/suggestions/recompute` — trigger recompute

```
POST /api/v1/posts/suggestions/recompute
```

**Auth:** **required** — `401 AUTH_UNAUTHORIZED` for anonymous callers.

> **Behavior note.** Like the read endpoint, recompute now derives the target user
> from the **JWT** — the old `?userId=` parameter is gone. You can only recompute
> your own suggestions.

Fires an asynchronous recompute of the caller's suggestion partition: walk the direct
follow set, tally second-degree mutual counts, keep the top 50 with ≥ 2 mutuals, and
rewrite `friend_suggestions_by_user`. Useful after a follow burst (e.g. onboarding
contact import).

**Request body:** none.

**Response:** `202 Accepted`, empty body — the recompute runs on the async pool;
poll `GET /suggestions` afterwards for the refreshed list.

**Side effects**

- `friend_suggestions_by_user` partition cleared and repopulated (async).

**Errors**

| Status | `errorCode` | When |
|--------|-------------|------|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid JWT |
