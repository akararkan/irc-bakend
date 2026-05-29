# Feed — Complete API Documentation

Full reference for every feed endpoint and the internal mechanics that power
them: home timeline (fanout-on-write), profile feed, global reels discover
feed, friend suggestions, and the realtime new-post push.

---

## Table of contents

1. [Overview & architecture](#1-overview--architecture)
2. [Auth & common headers](#2-auth--common-headers)
3. [Response shapes](#3-response-shapes)
4. [Home feed](#4-home-feed)
5. [Profile feed (by author)](#5-profile-feed-by-author)
6. [Reels discover feed](#6-reels-discover-feed)
7. [Friend suggestions](#7-friend-suggestions)
8. [Cursor pagination guide](#8-cursor-pagination-guide)
9. [Fanout-on-write internals](#9-fanout-on-write-internals)
10. [Feed hydration (bulk strategy)](#10-feed-hydration-bulk-strategy)
11. [Redis cache layer](#11-redis-cache-layer)
12. [Realtime new-post push (SSE)](#12-realtime-new-post-push-sse)
13. [Cassandra tables index](#13-cassandra-tables-index)
14. [Side-effect map](#14-side-effect-map)

---

## 1. Overview & architecture

There are **three distinct feed types**, each backed by a separate Cassandra table:

| Feed type | Endpoint | Cassandra table | Read pattern |
|---|---|---|---|
| **Home timeline** | `GET /api/v1/posts/feed` | `feed_by_user` | Single partition by `user_id` |
| **Profile feed** | `GET /api/v1/posts/by-author/{id}` | `posts_by_author` | Single partition by `author_id` |
| **Reels discover** | `GET /api/v1/posts/reels` | `reels_by_day` | Single partition by `day_bucket` |

**Write path (fanout-on-write):**
When a post is created, an async Spring `@Async` job walks the author's
followers in keyset-paginated batches (500 per page, up to 50 000 followers)
and writes one `feed_by_user` row per follower. The author always gets a
self-fanout row so their own feed is populated.

**Read path (home feed, 3-tier):**
1. **Redis sorted set** — `feed:timeline:{userId}` holds the last 100 post IDs
   scored by timestamp; a cache hit returns without touching Cassandra.
2. **Cassandra** — `feed_by_user` partition scan if Redis misses.
3. **Backfill** — on cache miss the slice is written back to Redis for the
   next page.

All three feeds are **cursor-paginated** — no OFFSET, constant-time per page.

---

## 2. Auth & common headers

| Header | Value | Required for |
|---|---|---|
| `Authorization` | `Bearer <accessToken>` | Home feed (must know viewer for `likedByMe`/`savedByMe`) |
| — | — | Profile feed and reels are **public** — no JWT needed |

The home feed falls back to `?userId=` for legacy clients if no JWT is present,
but the JWT path is preferred and authoritative.

---

## 3. Response shapes

### 3.1 FeedItemResponse — lightweight list item

Used by all three feed endpoints.

```json
{
  "id":           "5e5c69f7-eb97-4582-8b14-1ec328171fbd",
  "authorId":     "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id":        "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "username":  "akar",
    "fullName":  "Akar Arkan",
    "avatarUrl": "https://cdn.example.com/avatars/akar.jpg"
  },
  "postType":    "POST",
  "textPreview": "First 280 chars of the post caption...",
  "mediaUrl":    "https://cdn.example.com/posts/media/img-001.jpg",
  "reactionCount": 42,
  "commentCount":  7,
  "viewCount":    310,
  "saveCount":     18,
  "shareCount":     5,
  "likedByMe":   false,
  "savedByMe":   false,
  "createdAt":   "2026-05-26T09:00:00Z"
}
```

| Field | Source |
|---|---|
| `id` | `post_id` from the feed/author/reels row |
| `author` | Postgres `users` + `user_profiles` (bulk-loaded per page) |
| `postType` | `TEXT`, `EMBEDDED`, `VOICE_POST`, `REEL`, `REPOST`, `STORY` |
| `textPreview` | First 280 chars of `textContent` — **read live** from `posts_by_id` via one bulk IN-clause per page. Reflects the latest PATCH edits. (Falls back to the create-time denorm snapshot only when the canonical row is missing, e.g. a deleted post that hasn't yet aged out of the follower's `feed_by_user` TTL.) |
| `mediaUrl` | First (cover) media URL — read live from the same bulk load as `textPreview`. |
| `*Count` | Cassandra `post_counters` table — bulk IN-clause load per page |
| `likedByMe` | `reactions_by_post` — bulk IN + userId filter per page |
| `savedByMe` | `saves_by_post_user` — bulk IN + userId filter per page |

> **No N+1 needed.** The frontend used to issue parallel `GET /posts/{id}`
> per row to compensate for stale denorm snapshots. As of the
> read-time-hydration pass, every `/feed` / `/profile-feed` page already
> contains the freshest `textPreview` and `mediaUrl` straight from
> `posts_by_id`. Treat the response as authoritative for the preview fields;
> only hydrate `/posts/{id}` when the user opens a single post for full
> detail (location, full media list, reaction-type breakdown, etc.).

### 3.2 CursorPage wrapper (internal)

Feed endpoints return `List<FeedItemResponse>` directly (not wrapped). The
client drives cursor pagination by reading the `createdAt` of the last item
and passing it as `?cursor=` on the next call.

```
null cursor  → first page  (latest N items)
?cursor=<ts> → next page   (items older than <ts>)
empty list   → end of feed
```

---

## 4. Home feed

The authenticated user's personalised timeline — posts from every account
they follow, plus their own posts. Fanout-on-write: new posts arrive in
near-realtime via Redis pub/sub + SSE before the user even refreshes.

Two path aliases — both accept the same parameters:

```
GET /api/v1/posts/feed
GET /api/v1/posts/feed/cursor    ← legacy alias
```

**Auth:** JWT preferred; `?userId=` accepted as fallback for legacy clients.

**Query parameters:**

| Param | Type | Default | Description |
|---|---|---|---|
| `cursor` | ISO-8601 Instant | none | Exclusive upper bound on `created_at`. Omit for first page. |
| `limit` | int | 20 | Number of items to return (takes precedence over `pageSize`) |
| `pageSize` | int | 20 | Legacy alias for `limit` |
| `userId` | UUID | none | Legacy fallback when no JWT is present |

**Effective size** = `limit > 0 ? limit : pageSize`.

**Viewer resolution:**

```
JWT principal  →  use user from token           (preferred)
?userId=       →  use that UUID                 (legacy, no auth check)
neither        →  return empty list []
```

---

### First page

```http
GET /api/v1/posts/feed?limit=20
Authorization: Bearer <token>
```

**Response:** `200 OK` — `List<FeedItemResponse>` (newest first, up to 20 items)

**Cassandra query:**
```cql
SELECT * FROM feed_by_user WHERE user_id = ? LIMIT ?
```

---

### Next page (cursor)

Take the `createdAt` of the **last item** from the previous response and
pass it as `cursor`:

```http
GET /api/v1/posts/feed?limit=20&cursor=2026-05-26T09:00:00Z
Authorization: Bearer <token>
```

**Cassandra query:**
```cql
SELECT * FROM feed_by_user WHERE user_id = ? AND created_at < ? LIMIT ?
```

An empty list `[]` means the feed has been fully consumed.

---

### Full worked example

```
Page 1:  GET /feed?limit=20
         → items[0].createdAt = "2026-05-26T12:00:00Z"
           ...
           items[19].createdAt = "2026-05-26T09:00:00Z"   ← last item

Page 2:  GET /feed?limit=20&cursor=2026-05-26T09:00:00Z
         → items[0].createdAt = "2026-05-26T08:55:00Z"
           ...

Page N:  GET /feed?limit=20&cursor=...
         → []   (end of feed)
```

---

### Side effects on first-page read

If the Redis sorted set `feed:timeline:{userId}` is **empty** (cold cache or
eviction), the Cassandra slice is read and the post IDs are written back into
Redis for the next read. The cache holds the last **100** post IDs scored by
timestamp.

---

## 5. Profile feed (by author)

All posts by a single author, newest first. Mixed post types (`TEXT`, `REEL`,
`STORY`, etc.) — filter on the client if you want only a specific type.

```
GET /api/v1/posts/by-author/{authorId}
```

**Auth:** none (public)

**Path param:** `authorId` — UUID of the author

**Query parameters:**

| Param | Type | Default | Description |
|---|---|---|---|
| `pageSize` | int | 20 | Items per page |
| `cursor` | ISO-8601 Instant | none | Exclusive upper bound. Omit for first page. |

---

### First page

```http
GET /api/v1/posts/by-author/41ee2a6b-2cd9-417b-861c-d1293c623690?pageSize=20
```

**Response:** `200 OK` — `List<FeedItemResponse>` (newest first)

**Cassandra query:**
```cql
SELECT * FROM posts_by_author WHERE author_id = ? LIMIT ?
```

---

### Next page (cursor)

```http
GET /api/v1/posts/by-author/{authorId}?pageSize=20&cursor=2026-05-26T09:00:00Z
```

**Cassandra query:**
```cql
SELECT * FROM posts_by_author WHERE author_id = ? AND created_at < ? LIMIT ?
```

---

### What's in each item

The `posts_by_author` row stores a **denormalised subset** of the post:

| Column | Notes |
|---|---|
| `postType` | `TEXT`, `REEL`, `EMBEDDED`, etc. |
| `visibility` | `PUBLIC`, `FOLLOWERS`, `PRIVATE`, `CLOSE_FRIENDS` |
| `textPreview` | First 280 chars |
| `mediaUrl` | Cover / first media URL |

The full post (all media, location, audio, etc.) is available at
`GET /api/v1/posts/{id}`.

---

## 6. Reels discover feed

Global reels feed bucketed by UTC day. Newer reels appear at the top of each
day bucket. To paginate backwards through time, the client decrements the
`day` parameter.

Two path aliases — both accept the same parameters:

```
GET /api/v1/posts/reels
GET /api/v1/posts/feed/reels    ← legacy alias
```

**Auth:** none (public)

**Query parameters:**

| Param | Type | Default | Description |
|---|---|---|---|
| `day` | `YYYY-MM-DD` string | today UTC | Day bucket to fetch. Omit for today's reels. |
| `pageSize` | int | 20 | Items per page (canonical name) |
| `size` | int | 20 | Legacy alias for `pageSize` |
| `page` | int | 0 | Legacy field — kept for client compatibility, ignored by Cassandra backend |

**Effective size** = `size > 0 ? size : pageSize`.

---

### Today's reels (first page)

```http
GET /api/v1/posts/reels?pageSize=20
```

**Response:** `200 OK` — `List<FeedItemResponse>` with `postType = "REEL"`

**Cassandra query:**
```cql
SELECT * FROM reels_by_day WHERE day_bucket = '2026-05-26' LIMIT 20
```

---

### Yesterday's reels

```http
GET /api/v1/posts/reels?day=2026-05-25&pageSize=20
```

---

### Cross-day pagination pattern

There is no server-side cursor for cross-day scrolling. The client iterates
day strings backwards:

```
GET /reels?day=2026-05-26     → today's page
GET /reels?day=2026-05-25     → yesterday
GET /reels?day=2026-05-24     → two days ago
...
```

Stop when the response is `[]` — no reels were published that day.

---

### How a reel enters the discover feed

On `POST /api/v1/posts` with `postType = REEL`, `CassandraPostService` writes
a `reels_by_day` row **synchronously** before the HTTP response is returned:

```
day_bucket  = LocalDate.now(UTC).toString()   → "2026-05-26"
created_at  = Instant.now()                   → clustering key DESC
post_id     = new UUID                        → tie-breaker
author_id   = JWT principal
text_preview = first 280 chars
media_url    = first media URL
```

---

## 7. Friend suggestions

Sorted list of recommended accounts to follow, ordered by mutual-follower
count descending (pre-computed at the table level).

```
GET /api/v1/posts/suggestions?userId={uuid}&limit={n}
```

**Auth:** none (public)

**Query parameters:**

| Param | Type | Required | Description |
|---|---|---|---|
| `userId` | UUID | **Yes** | The user to generate suggestions for |
| `limit` | int | No (default 20) | Max number of suggestions to return |

**Response:** `200 OK` — `List<FriendSuggestionEntity>`

```json
[
  {
    "userId":        "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "suggestedId":   "uuid-of-suggested-user",
    "mutualCount":   12,
    "computedAt":    "2026-05-26T00:00:00Z"
  }
]
```

---

### Trigger a suggestions recompute

Forces an immediate recomputation for a user — typically called from a
follow/unfollow webhook.

```
POST /api/v1/posts/suggestions/recompute?userId={uuid}
```

**Auth:** none (no JWT guard — internal/webhook use)

**Response:** `202 Accepted` (empty body)

---

## 8. Cursor pagination guide

All three feed types use **cursor-based** (keyset) pagination, not
offset-based. This means:

- Each page is **O(1)** in Cassandra regardless of how deep you are.
- The cursor is the **`createdAt` Instant of the last item** on the
  current page.
- An empty response means you have reached the end of the feed.

### Pagination flow

```
Client                              Server
  |                                    |
  | GET /feed?limit=20                 |
  |─────────────────────────────────→  |
  |  ← [ item1(ts=T1) ... item20(ts=T20) ]
  |                                    |
  | GET /feed?limit=20&cursor=T20      |
  |─────────────────────────────────→  |
  |  ← [ item21(ts=T21) ... item40(ts=T40) ]
  |                                    |
  | GET /feed?limit=20&cursor=T40      |
  |─────────────────────────────────→  |
  |  ← []                              | ← end of feed
```

### JavaScript example

```js
let cursor = null;
let hasMore = true;

async function nextPage() {
  const url = cursor
    ? `/api/v1/posts/feed?limit=20&cursor=${cursor}`
    : `/api/v1/posts/feed?limit=20`;

  const items = await fetch(url, { headers: { Authorization: `Bearer ${token}` } })
    .then(r => r.json());

  if (items.length === 0) {
    hasMore = false;
    return items;
  }

  cursor = items[items.length - 1].createdAt;  // Instant string from last item
  return items;
}
```

---

## 9. Fanout-on-write internals

Understanding the write path helps with debugging delayed feed delivery or
missing posts.

### On post create

```
POST /api/v1/posts  (any postType, any visibility)
     │
     ▼
CassandraPostService.createPost()
     │
     ├─ 1. INSERT posts_by_id              ← canonical row (sync)
     ├─ 2. INSERT posts_by_author          ← profile feed (sync)
     ├─ 3. INSERT reels_by_day             ← only if postType = REEL (sync)
     │
     └─ 4. feedTimelineService.fanoutAsync(postId, authorId, ...)
              │                            ← returns immediately; HTTP response sent
              │     (Spring @Async thread)
              │
              ├─ Self-fanout: INSERT feed_by_user(userId=authorId)
              ├─ Redis push:  ZADD feed:timeline:{authorId}
              ├─ SSE push:    publishToUser(authorId, FEED_NEW_POST)
              │
              └─ Keyset-paginated follower scan (500 per batch, max 50 000):
                   for each followerBatch (parallelStream):
                     ├─ INSERT feed_by_user(userId=followerId)
                     ├─ ZADD   feed:timeline:{followerId}
                     ├─ SSE push: publishToUser(followerId, FEED_NEW_POST)
                     └─ Deliver POST_NEW notification to follower
```

### Visibility rules in fanout

| `visibility` | Fanout behaviour |
|---|---|
| `PUBLIC` | Self-fanout + all followers |
| `FOLLOWERS` | Self-fanout + all followers |
| `PRIVATE` / `ONLY_ME` | Self-fanout **only** — no follower writes |
| `CLOSE_FRIENDS` | Self-fanout + followers (close-friends filter applied at read time in the post layer) |

### Edits don't fan out — read-time hydration covers them

`PATCH /api/v1/posts/{id}` updates `posts_by_id` and best-effort mirrors
`posts_by_author`, but **does not** rewrite every follower's `feed_by_user`
row. Doing so would cost O(followers) writes per edit and is unworkable for
high-follower authors.

Instead, the read path (`hydrateHomeFeed` / `hydrateProfileFeed`) bulk-loads
`posts_by_id` for the IDs on the current page and serves the live
`textPreview` / `mediaUrl` from there. The `feed_by_user` row's snapshot
fields are now only a fallback for the case where the canonical row is gone
(deleted post within the 30-day TTL window). Net effect: edits show up on
every feed read on the next page-load — no client N+1, no fanout-on-edit cost.

### On new follow (backfill)

When user A follows user B, `FeedTimelineService.backfillFollowerFeed(A, B)`
is called asynchronously to populate A's home feed with up to **50** of B's
most recent posts. Without backfill, the new follower's feed would be empty
until B posts something new.

```
backfillFollowerFeed(newFollowerId=A, authorId=B)
  │
  └─ postByAuthorRepo.firstPage(B, 50)
       → for each post:
           INSERT feed_by_user(userId=A, ...)
```

### Fanout limits

| Constant | Value | Reason |
|---|---|---|
| `MAX_FANOUT_FOLLOWERS` | 50 000 | Caps async thread time per post |
| `FANOUT_BATCH` | 500 | Keyset page size; parallelStream within each batch |
| `REDIS_FEED_SIZE` | 100 | Sorted-set cap; oldest entries evicted automatically |

Accounts with > 50 000 followers: followers beyond the cap don't get a
proactive fanout row. They'll see the post when the Cassandra read path
falls through from Redis (`feed_by_user` query with no Redis hit).

---

## 10. Feed hydration (bulk strategy)

`PostHydrator` resolves all the data the frontend needs to render a feed
page in **exactly 3 Cassandra + 1 Postgres bulk reads** per page — regardless
of page size.

### Per feed-page hydration

```
Input: List<FeedByUserEntity> rows (e.g. 20 items)

Step 1 — bulk author load (Postgres):
  userRepo.findAllById(Set<authorId>)
  → Map<authorId, AuthorSummary>  (name, username, avatarUrl)

Step 2 — bulk counter load (Cassandra):
  postCounterRepo.findAllByPostIdIn(Set<postId>)
  → Map<postId, PostCounterEntity>  (reaction/comment/view/save/share counts)

Step 3 — bulk liked check (Cassandra, viewer only):
  reactionRepo.findAllByPostIdInAndUserId(Set<postId>, viewerId)
  → Set<postId> likedSet

Step 4 — bulk saved check (Cassandra, viewer only):
  saveRepo.findAllByPostIdInAndUserId(Set<postId>, viewerId)
  → Set<postId> savedSet

Step 5 — assemble FeedItemResponse per row (no I/O):
  for each row:
    author   = authorMap.get(row.authorId)
    counters = counterMap.get(row.postId)
    likedByMe = likedSet.contains(row.postId)
    savedByMe = savedSet.contains(row.postId)
```

**Result:** 20 fully-hydrated feed items from 4 bulk queries.
No per-row point reads. No N+1.

Steps 3 and 4 are skipped when `viewerId == null` (anonymous request).

---

## 11. Redis cache layer

### Feed timeline sorted set

| Key | `feed:timeline:{userId}` |
|---|---|
| Type | Sorted set (ZSET) |
| Member | `postId` (UUID string) |
| Score | `createdAt.toEpochMilli()` |
| Max size | 100 entries (older ones evicted via `ZREMRANGE`) |
| Written by | `FeedTimelineService.writeFanoutRow()` on every fanout write |
| Read by | `FeedTimelineService.homeFeed()` (checked before Cassandra) |
| Evicted by | Follow/unfollow/block via `FollowingIdsCache` eviction |

### Following IDs cache

| Key | Spring Cache `user-following-ids`, key = `userId` |
|---|---|
| TTL | 1 minute |
| Content | `List<UUID>` — following IDs minus blocked users |
| Evicted by | `UserSocialServiceImpl` on follow, unfollow, block, unblock |

This cache is used by feed read paths that need to filter content by social
graph (e.g. "show only posts from accounts I follow").

---

## 12. Realtime new-post push (SSE)

When a post is fanned out to a follower, the system publishes a Redis message
on the per-user feed channel so any open SSE connection on any app server
instance can deliver the new post to the browser/mobile client instantly —
without waiting for the user to scroll.

### Channel format

```
irc:feed:{userId}
```

### Event payload

```json
{
  "event":    "FEED_NEW_POST",
  "postId":   "5e5c69f7-eb97-4582-8b14-1ec328171fbd",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690"
}
```

The client receives this on the notification SSE stream and should trigger a
refresh of the home feed (or prepend the post if it already has the full
`PostResponse` from a separate fetch).

### SSE delivery path

```
FeedTimelineService.fanoutAsync()
  │
  └─ pushRealtime(followerId, postId, authorId)
       │
       └─ FeedRealtimePublisher.publishToUser(followerId, payload)
            │
            └─ redis.convertAndSend("irc:feed:{followerId}", json)
                 │
                 └─ SSE subscriber on any app instance delivers to browser
```

---

## 13. Cassandra tables index

| Table | Role | Partition key | Clustering keys |
|---|---|---|---|
| `feed_by_user` | Home timeline rows — one per viewer per post | `user_id` | `created_at DESC`, `post_id` |
| `posts_by_author` | Profile feed — one per author per post | `author_id` | `created_at DESC`, `post_id` |
| `reels_by_day` | Global reels discover — one per reel per day | `day_bucket` | `created_at DESC`, `post_id` |
| `posts_by_id` | Canonical post row — full data | `post_id` | — |
| `post_counters` | Live counter columns per post | `post_id` | — |
| `reactions_by_post` | Per-post per-user like | `post_id` | `user_id` |
| `saves_by_post_user` | Per-post per-user save | `post_id` | `user_id` |

### feed_by_user TTL

Rows in `feed_by_user` have a **30-day TTL** at the Cassandra table level.
Posts older than 30 days disappear from the home feed automatically without
any application-level cleanup. The profile feed (`posts_by_author`) and reels
feed (`reels_by_day`) have no TTL — they are permanent until the post is
deleted.

---

## 14. Side-effect map

### On post create (any type)

| Action | Table written | Cache updated | Realtime event |
|---|---|---|---|
| Write canonical row | `posts_by_id` | — | — |
| Write profile feed | `posts_by_author` | — | — |
| Write reels feed | `reels_by_day` *(REEL only)* | — | — |
| Self-fanout | `feed_by_user` | `ZADD feed:timeline:{authorId}` | `FEED_NEW_POST` → author's SSE |
| Follower fanout | `feed_by_user` (×N) | `ZADD feed:timeline:{followerId}` (×N) | `FEED_NEW_POST` → each follower's SSE |
| Follower notification | `notifications` | — | `POST_NEW` SSE to each follower's inbox |

### On post delete

| Action | Result |
|---|---|
| `posts_by_id` | Hard-deleted immediately |
| `posts_by_author` | Hard-deleted immediately |
| `feed_by_user` | **Not swept** — rows expire via 30-day TTL |
| `reels_by_day` | **Not swept** — expires via TTL |
| Elasticsearch | Deleted async (best-effort) |

### On follow

| Action | Result |
|---|---|
| Backfill new follower's feed | Up to 50 of the followed user's recent posts inserted into `feed_by_user` |
| `FollowingIdsCache` | Evicted — next read rebuilds the "accounts I can see" set |
| `user-following-ids` Spring cache | Evicted |

### On unfollow / block

| Action | Result |
|---|---|
| `FollowingIdsCache` | Evicted |
| Existing feed rows | **Not removed** — blocked/unfollowed user's posts stay in `feed_by_user` until TTL expires. The post layer applies a social guard on render so blocked content is not shown. |
