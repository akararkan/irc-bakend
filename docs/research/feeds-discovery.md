# Research API — Feeds & Discovery

Public and personal feeds, tag search and trending, saved-paper lists, and share / cite.

**Base path:** `/api/v1/researches`

Sibling documents:

- [Lifecycle](./research.md) — create / update / publish / read
- [Media, sources & contributors](./media-sources-contributors.md)
- [Social interactions](./social.md) — save/unsave toggles live there
- [Realtime (SSE)](./realtime.md)

## Authentication & shared behavior

Bearer JWT (`Authorization: Bearer <token>`) or `access_token` cookie. Errors use the
[unified envelope](../errors/error-handling.md).

All list endpoints use Spring offset pagination — `?page=0&size=20&sort=field,desc` —
and return a `Page<ResearchSummaryResponse>`:

```json
{
  "content": [
    {
      "id":   "R-uuid",
      "slug": "the-effects-of-x-on-y",
      "ircId": "IRC-2026-000042",
      "title": "The effects of X on Y",
      "abstractText": "Three-paragraph abstract …",
      "abstractHtml": "<p>Three-paragraph abstract …</p>",
      "coverImageUrl": "https://cdn…/covers/r-uuid.jpg",
      "videoPromoThumbnailUrl": null,

      "researcherId": "U-uuid",
      "researcherFullName": "Yusuf al-Qaradawi",
      "researcherUsername": "yusuf",
      "researcherProfileImage": "https://cdn…/yusuf.jpg",

      "status": "PUBLISHED",
      "publishedAt": "2026-05-21T08:42:00",

      "viewCount": 1247, "reactionCount": 213, "commentCount": 31,
      "downloadCount": 89, "saveCount": 58, "shareCount": 7, "citationCount": 4,

      "tags": ["x", "methodology", "review"],
      "shareUrl": "https://irc.example.com/r/9f2c1a7be4d803ab",

      "currentUserReacted": true,
      "currentUserSaved": false,
      "savedAt": null
    }
  ],
  "totalElements": 512, "totalPages": 26, "number": 0, "size": 20
}
```

| Field | Notes |
|---|---|
| `viewCount` … `citationCount` | **Served from a Redis counter cache** — all 7 counters for the whole page are batch-loaded in one pipelined round-trip (`CounterCache.getMany`), not read per card from Postgres. Writes keep the cache fresh via write-through. |
| `currentUserReacted` / `currentUserSaved` | Batched per page in two `IN`-clause queries (no N+1); always `false` for anonymous callers |
| `savedAt` | When the *viewer* bookmarked the paper — populated **only** by the saved-list endpoints below; `null` everywhere else |

Full-text relevance search lives on the unified endpoint
`GET /api/v1/search?q=…&types=RESEARCH` — not on this controller. Tag filtering stays here
because it is a structured Postgres query, not a relevance search.

---

## Feeds

### Public feed

```
GET /api/v1/researches/feed
```

**Auth:** none (public). Optional JWT adds block filtering + viewer flags.

Offset-paginated feed of **`PUBLISHED`** papers, ordered by `publishedAt` descending
(default `size=20`, `sort=publishedAt`). For an authenticated viewer, papers from users in a
block relationship (either direction) are excluded. Backed by the `research-feed` cache
(2-minute TTL, evicted on publish/update/delete).

**Response — `200 OK`** — `Page<ResearchSummaryResponse>`.

### Following feed

```
GET /api/v1/researches/feed/following
```

**Auth:** Bearer JWT required.

`PUBLISHED` papers from researchers the caller follows, newest first, **plus the caller's
own papers** (so a researcher who follows no one still sees their own publications).

**Performance note (new):** the feed resolves the follow graph through a **cached,
block-filtered following-ids set** (`user-following-ids` cache, ~1-minute TTL, evicted on
follow/unfollow/block) — **one cached lookup** per request instead of one block-check query
per followed account. No behavior change: blocked users are still excluded in both
directions.

**Response — `200 OK`** — `Page<ResearchSummaryResponse>`.

| Status | `errorCode` | When |
|---|---|---|
| 401 | — | Missing / invalid JWT |

### Papers by researcher

```
GET /api/v1/researches/researcher/{researcherId}
```

**Auth:** none (public).

All **`PUBLISHED`** papers by one researcher (public profile page). Default `size=20`.

**Response — `200 OK`** — `Page<ResearchSummaryResponse>`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_RESEARCHER_ID` | Malformed id |

---

## Researcher dashboard

### My drafts

```
GET /api/v1/researches/me/drafts
```

**Auth:** Bearer JWT — role `SCHOLAR` / `RESEARCHER` / `ADMIN` / `SUPER_ADMIN`.

Page of the caller's `DRAFT` papers (dashboard → "Drafts" tab).

**Response — `200 OK`** — `Page<ResearchSummaryResponse>` (all `status: "DRAFT"`).

### All my papers

```
GET /api/v1/researches/me/all
```

**Auth:** Bearer JWT — role `SCHOLAR` / `RESEARCHER` / `ADMIN` / `SUPER_ADMIN`.

Page of *every* paper the caller owns, regardless of status — drafts, published, archived,
retracted.

**Response — `200 OK`** — `Page<ResearchSummaryResponse>`.

---

## Tags

### Search by tags

```
GET /api/v1/researches/search/tags
```

**Auth:** none (public).

Published papers matching **any** of the supplied tags (OR semantics). Tags are normalised
server-side (trim + lowercase, duplicates dropped).

| Param | In | Required | Notes |
|---|---|---|---|
| `tags` | query, repeatable | yes | `?tags=fiqh&tags=hadith` |
| `page` / `size` / `sort` | query | no | Default `size=20` |

**Response — `200 OK`** — `Page<ResearchSummaryResponse>`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_TAGS` / `INVALID_TAGS` | No usable tags supplied |

### Trending tags

```
GET /api/v1/researches/tags/trending
```

**Auth:** none (public).

Most-used tags across all **published** papers, ordered by usage count.

**Caching (new):** the result is **cached server-side for 10 minutes** per `limit`
(`trending-tags` cache, `sync=true` so concurrent cold misses collapse into a single
aggregation run). It is **no longer recomputed on every content write** — creating or
editing a paper does not evict this cache; the 10-minute TTL is the freshness contract.

| Param | In | Default | Notes |
|---|---|---|---|
| `limit` | query | `20` | Values `≤ 0` or `> 100` fall back to `10` |

**Response — `200 OK`:**

```json
["tafsir", "hadith", "fiqh", "methodology"]
```

> This is the research-only trending list. For the cross-content trending strip (research +
> Q&A, pre-ranked in Cassandra) use `GET /api/v1/tags/trending?scope=RESEARCH` — research
> participates there **only while `PUBLISHED`** (tags are indexed on publish and removed on
> unpublish/archive/retract/delete).

---

## Saved papers

Save / unsave toggles are on the social controller —
[`POST` / `DELETE /{researchId}/save`](./social.md#save--bookmark). These endpoints read the
result.

### My saved papers

```
GET /api/v1/researches/me/saved
```

**Auth:** Bearer JWT.

Page of the caller's saved papers, newest-saved first. Each row carries **`savedAt`** — the
bookmark timestamp (distinct from the paper's own `publishedAt`).

**Response — `200 OK`** — `Page<ResearchSummaryResponse>` with `savedAt` populated and
`currentUserSaved: true`.

### Saved papers in one collection

```
GET /api/v1/researches/me/saved/collection
```

**Auth:** Bearer JWT.

| Param | In | Required | Notes |
|---|---|---|---|
| `name` | query | yes | Collection name (papers saved without one live in `"Default"`) |

**Response — `200 OK`** — `Page<ResearchSummaryResponse>` (with `savedAt`).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_COLLECTION_NAME` | Blank `name` |

### My collection names

```
GET /api/v1/researches/me/saved/collections
```

**Auth:** Bearer JWT.

Distinct collection names the caller has saved papers into.

**Response — `200 OK`:**

```json
["Default", "Fiqh reading list", "To cite"]
```

### Rename a collection

```
PATCH /api/v1/researches/me/saved/collections
```

**Auth:** Bearer JWT.

Renames a collection across every save row owned by the caller.

| Param | In | Required |
|---|---|---|
| `oldName` | query | yes |
| `newName` | query | yes |

**Response — `200 OK`** (empty body).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_OLD_NAME` / `MISSING_NEW_NAME` | Blank names |

---

## Share

Both endpoints return the unified `ShareLinkInfo` payload (same shape as posts and Q&A):

```json
{
  "shortUrl":     "https://api.irc.example.com/r/9f2c1a7be4d803ab",
  "canonicalUrl": "https://app.irc.example.com/research/R-uuid",
  "token":        "9f2c1a7be4d803ab",
  "shareCount":   8
}
```

| Field | Notes |
|---|---|
| `shortUrl` | Public OG-tagged short link hosted by the backend — safe to paste in chat/social; resolves via [`GET /share/{shareToken}`](./research.md#get-by-share-token) |
| `canonicalUrl` | Frontend URL for in-app navigation |
| `token` | The bare 16-char research share token |
| `shareCount` | Fresh counter after the call |

### Preview share link

```
GET /api/v1/researches/{id}/share-link
```

**Auth:** none (public).

Returns `ShareLinkInfo` **without** bumping `shareCount` — for the inline share UI before
the user actually copies the link.

**Response — `200 OK`** — `ShareLinkInfo`.

| Status | `errorCode` | When |
|---|---|---|
| 400 | `NOT_PUBLISHED` | Paper isn't published |
| 404 | `RESOURCE_NOT_FOUND` | Research not found |

### Record a share

```
POST /api/v1/researches/{id}/share
```

**Auth:** none (public); optional JWT attributes the actor on the SSE event.

Atomically bumps `shareCount` and returns `ShareLinkInfo`. Call when the user actually
copies / sends the link.

**Response — `200 OK`** — `ShareLinkInfo` (with the post-increment `shareCount`).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `NOT_PUBLISHED` | Paper isn't published |
| 404 | `RESOURCE_NOT_FOUND` | Research not found |

**Side effects:** `shareCount` +1; Redis counter write-through; `SHARE_COUNT_UPDATED` SSE
event with fresh counters.

---

## Citations

### Record a citation

```
POST /api/v1/researches/{id}/cite
```

**Auth:** Bearer JWT required.

Increments `citationCount` — "I cited this paper in my work." **Deduplicated** per
`(research, citer)` within a **30-day window**, so the public counter cannot be inflated by
one caller; a repeat cite inside the window is a silent no-op (`200`).

**Response — `200 OK`** (empty body).

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_RESEARCH_ID` / `NOT_PUBLISHED` | Bad input / paper not published |
| 401 | — | Missing / invalid JWT |
| 404 | `RESOURCE_NOT_FOUND` | Research not found |

**Side effects (non-deduped calls):** `citationCount` +1; Redis counter write-through;
`CITATION_COUNT_UPDATED` SSE event with fresh counters.
