# Tags API

Base path: **`/api/v1/tags`** (public reads) · **`/api/v1/admin/tags`** (admin maintenance)

The unified tag subsystem covers **questions, research, posts, and reels** in a single
Cassandra-backed index. It powers four surfaces:

| Surface | Endpoint | Backing store |
|---|---|---|
| Trending tag strip | `GET /tags/trending` | `trending_tags` — pre-ranked snapshot partition |
| Tag feed ("everything tagged `#hajj`") | `GET /tags/{tag}/content` | `content_by_tag` — one partition per tag, clustered by time |
| Usage counts / breakdown | `GET /tags/{tag}/usage` | `tag_counters` — native Cassandra counters |
| Tag autocomplete | `GET /tags/search` | `tag_counters` — clustering-key prefix scan |

Trending is **usage-based** (exact counters, periodic leaderboard), not relevance-based.
Free-text relevance search is Elasticsearch's job — see [search.md](./search.md).

**Tag normalization** (applied on every write and read): lowercased (`Locale.ROOT`),
trimmed, leading `#` stripped, de-duplicated, max **30 tags** per content item, max
**100 chars** per tag. Unicode is preserved — `رمضان` and `ramadan` are intentionally
distinct tags; the platform never transliterates.

**Scopes.** Every tag write increments two counters: the type scope (`QUESTION`,
`RESEARCH`, `POST`, or `REEL`) and the cross-type `ALL` scope. Posts and reels share
one storage entity but count as separate scopes (`postType=REEL` → `REEL` scope).

> **Changed:** the `POST` and `REEL` trending scopes are now actually populated. The
> trending rebuild job previously only rebuilt `ALL` / `QUESTION` / `RESEARCH`, so
> `GET /tags/trending?scope=POST` and `?scope=REEL` always returned an empty list even
> though the counters were being incremented. All five scopes are rebuilt now.

Auth is Bearer JWT platform-wide (`Authorization: Bearer <accessToken>`); all `/tags/*`
reads here are public. Errors use the standard envelope — see
[../errors/error-handling.md](../errors/error-handling.md).

Siblings: [search.md](./search.md) · [mentions.md](./mentions.md) ·
[media-proxy.md](./media-proxy.md) · [activity.md](./activity.md) · [audit.md](./audit.md)

---

## 1. Trending tags

```
GET /api/v1/tags/trending
```

**Auth:** Public — no token required.

Most-used tags, pre-ranked. Serves a single ordered Cassandra partition read — no
per-request sorting. The snapshot is rebuilt from the exact usage counters by
`TrendingTagJob` every **10 minutes** by default (`app.tags.trending-refresh-ms`,
default `600000`, first run 60 s after startup) and keeps the **top 100** tags per
scope. Counters are always exact; only the leaderboard is periodic — a brand-new tag
can take up to one refresh cycle to appear here even though its feed (§2) is live
immediately.

### Query parameters

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `scope` | enum | no | `ALL` | `ALL`, `QUESTION`, `RESEARCH`, `POST`, or `REEL`. Case-insensitive. Unknown values silently fall back to `ALL`. |
| `limit` | int | no | `20` | Max tags returned. Clamped to `[1, 100]`. |

### Response `200`

```json
[
  { "tag": "hajj",    "usageCount": 1284, "rank": 0 },
  { "tag": "ramadan", "usageCount": 1102, "rank": 1 },
  { "tag": "zakat",   "usageCount":  734, "rank": 2 }
]
```

| Field | Type | Meaning |
|---|---|---|
| `tag` | string | Normalized tag label. |
| `usageCount` | long | Content items in this scope carrying the tag (snapshot value; `0` if the counter was null). |
| `rank` | int | 0-based position. The array is already sorted — render in order. |

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 503 | `DATASTORE_UNAVAILABLE` | Cassandra temporarily unreachable. Retryable. |

---

## 2. Tag content feed

```
GET /api/v1/tags/{tag}/content
```

**Auth:** Public — no token required.

All content (posts, reels, questions, research) tagged `{tag}`, **newest first**,
cursor-paged. The path tag is normalized server-side (lowercased, trimmed), so
`/tags/Hajj/content` and `/tags/hajj/content` hit the same partition.

### Path & query parameters

| Name | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `tag` | path | string | yes | — | Tag label; normalized before lookup. URL-encode non-ASCII tags. |
| `pageSize` | query | int | no | `20` | Rows per page. Clamped to `[1, 100]`. |
| `cursor` | query | string | no | — | Opaque base64url token from the previous response's `nextCursor`. Never decode client-side. Encodes `createdAt` **and** `contentId`, so two rows landing in the same millisecond are neither skipped nor duplicated. A legacy bare-ISO-instant cursor (no tiebreaker) is still accepted; a malformed cursor is treated as absent (page restarts from the head). |

### Response `200`

```json
{
  "tag": "hajj",
  "pageSize": 20,
  "items": [
    {
      "contentId":    "1b2c5a90-0000-4000-8000-000000000001",
      "contentType":  "RESEARCH",
      "authorId":     "9a2c0000-0000-4000-8000-000000000002",
      "titlePreview": "A maqasid reading of contemporary hajj logistics",
      "createdAt":    "2026-05-26T12:00:00Z"
    },
    {
      "contentId":    "a91f0000-0000-4000-8000-000000000003",
      "contentType":  "QUESTION",
      "authorId":     "41ee0000-0000-4000-8000-000000000004",
      "titlePreview": "Is it permitted to delay hajj for debt repayment?",
      "createdAt":    "2026-05-25T22:14:00Z"
    }
  ],
  "nextCursor": "MjAyNi0wNS0yNVQyMjoxNDowMFp8YTkxZjAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAz"
}
```

| Field | Type | Meaning |
|---|---|---|
| `tag` | string | Normalized tag that was queried. |
| `pageSize` | int | The clamped page size actually used. |
| `items[].contentId` | UUID | Entity id. Field names match `GlobalSearchHit` (see [search.md](./search.md)) so one renderer covers both. |
| `items[].contentType` | string | `POST` / `REEL` / `QUESTION` / `RESEARCH` — dispatch hydration on this: `POST`/`REEL` → `GET /api/v1/posts/{id}`, `QUESTION` → `GET /api/v1/questions/{id}`, `RESEARCH` → `GET /api/v1/researches/{id}`. |
| `items[].authorId` | UUID | Author's user id. |
| `items[].titlePreview` | string | Denormalized snippet (first ≤280 chars). Refreshed in place when the source title/text is edited. May be `null`. |
| `items[].createdAt` | instant | Content creation time (feed sort key, descending). |
| `nextCursor` | string | Cursor for the next page. Empty string when `items` is empty. Emitted for every non-empty page — stop paging when `items` is shorter than `pageSize` or comes back empty. |

An unknown tag is not an error — it returns `200` with `items: []`.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 503 | `DATASTORE_UNAVAILABLE` | Cassandra temporarily unreachable. Retryable. |

---

## 3. Tag usage count

```
GET /api/v1/tags/{tag}/usage
```

**Auth:** Public — no token required.

Usage count for a single tag — either one scope, or every scope in one round-trip.

### Path & query parameters

| Name | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `tag` | path | string | yes | — | Tag label; normalized before lookup. |
| `scope` | query | string | no | `ALL` | A single scope (`ALL` / `QUESTION` / `RESEARCH` / `POST` / `REEL`; unknown → `ALL`), **or** `*` (alias `ALL_SCOPES`) for the full breakdown. |

### Response `200` — single-scope mode

```
GET /api/v1/tags/hajj/usage?scope=RESEARCH
```

```json
{ "tag": "hajj", "scope": "RESEARCH", "usageCount": 312 }
```

### Response `200` — breakdown mode (`scope=*`)

```
GET /api/v1/tags/hajj/usage?scope=*
```

```json
{
  "tag": "hajj",
  "scopes": {
    "ALL":      496,
    "QUESTION": 184,
    "RESEARCH": 312,
    "POST":       0,
    "REEL":       0
  }
}
```

| Field | Type | Meaning |
|---|---|---|
| `tag` | string | Normalized tag. |
| `scope` | string | Single-scope mode only — the scope queried. |
| `usageCount` | long | Single-scope mode only — count in that scope (`0` for an unknown tag). |
| `scopes` | object | Breakdown mode only — every scope's count. Empty scopes are included as `0` so zero-state chips render consistently. |

Use breakdown mode for the tag-page header ("312 research · 184 questions · 496
total") — one call instead of five.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 503 | `DATASTORE_UNAVAILABLE` | Cassandra temporarily unreachable. Retryable. |

---

## 4. Tag autocomplete

```
GET /api/v1/tags/search
```

**Auth:** Public — no token required.

Prefix autocomplete for tag chip inputs. Returns tags whose normalized name starts
with `prefix`, ordered by tag name **ascending** within the scope. This converges
users onto existing tags (`rama…` → `ramadan`, `ramadan-2026`) instead of minting
near-duplicate variants. Backed by a clustering-key range scan on `tag_counters` —
no secondary index. Works for non-Latin scripts (Arabic prefixes included).

### Query parameters

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `prefix` | string | yes | — | Normalized before matching (lowercased, leading `#` stripped). Blank after normalization → `200 []`. |
| `scope` | enum | no | `ALL` | `ALL` / `QUESTION` / `RESEARCH` / `POST` / `REEL`; unknown → `ALL`. Constrain to the type being tagged if relevant. |
| `limit` | int | no | `10` | Max suggestions. Clamped to `[1, 50]`. |

### Response `200`

```json
[
  { "tag": "ramadan",       "usageCount": 1102 },
  { "tag": "ramadan-2026",  "usageCount":   84 },
  { "tag": "ramadan-iftar", "usageCount":   12 }
]
```

| Field | Type | Meaning |
|---|---|---|
| `tag` | string | Normalized tag label. |
| `usageCount` | long | Exact usage count in the requested scope. Re-sort client-side if you want "popular first". |

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_PARAMETER` | `prefix` not supplied. |
| 503 | `DATASTORE_UNAVAILABLE` | Cassandra temporarily unreachable. Retryable. |

---

## 5. Admin — backfill historical posts

```
POST /api/v1/admin/tags/backfill-posts
```

**Auth:** Bearer JWT with the **`ADMIN`** role.

> **Changed:** this endpoint is now actually admin-gated — `@PreAuthorize("hasRole('ADMIN')")`
> on the handler, plus a belt-and-braces chain rule that requires `ADMIN` for all of
> `/api/v1/admin/**`. It was previously reachable by any caller.

One-shot migration: re-indexes every post's `#hashtags` into the unified
`content_by_tag` feed. Posts only began fanning out into the unified tag feed when the
subsystem shipped — anything created before that is invisible to
`GET /tags/{tag}/content` (it shows only in the legacy posts-only
`/api/v1/hashtags/{tag}/posts` feed) until this runs. Reels are classified into the
`REEL` scope by their `postType`.

Runs synchronously and scans the whole `posts_by_id` table (full token-range read) —
fine up to low-millions of posts. Per-post failures are logged and skipped, never
aborting the run.

**Idempotency warning:** the feed-row writes are idempotent UPSERTs, but the trending
**counter increments are not** — re-running inflates `usageCount` for backfilled tags.
Run once per deployment of the feature, not on a schedule.

### Request

No body, no parameters.

### Response `200`

```json
{
  "postsScanned":      12345,
  "postsWithHashtags":  4321,
  "tagRowsWritten":     7890,
  "startedAt":         "2026-07-20T13:00:00Z"
}
```

| Field | Type | Meaning |
|---|---|---|
| `postsScanned` | long | Total posts read from `posts_by_id`. |
| `postsWithHashtags` | long | Posts that carried at least one `#hashtag`. |
| `tagRowsWritten` | long | `content_by_tag` rows written (one per post × tag). |
| `startedAt` | offset date-time | Timestamp taken when the summary was assembled (UTC). |

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_REQUIRED` | No / invalid Bearer token. |
| 403 | `ACCESS_DENIED` | Authenticated but not `ADMIN`. |
| 503 | `DATASTORE_UNAVAILABLE` | Cassandra temporarily unreachable mid-scan. |
