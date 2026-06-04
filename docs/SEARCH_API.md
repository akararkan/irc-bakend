# Search API — full reference

Base path: **`/api/v1/search`**

This document covers the **unified, cross-entity** search endpoint
served by `GlobalSearchController`. It is the single full-text search
entry point exposed by the backend — the older per-entity
`/api/v1/posts/search`, `/api/v1/researches/search` and
`/api/v1/questions/search` endpoints were removed in favour of it.

Related documents:

- [`POST_API.md`](./POST_API.md) — post / reel / comment APIs
- [`RESEARCH_API.md`](./RESEARCH_API.md) — research APIs
- [`QNA_API.md`](./QNA_API.md) — Q&A APIs

---

## 1. Why a single endpoint

The platform has three searchable entity families, each with its own
Elasticsearch index and field schema:

| Index           | Owns        | Search fields                                                                                                |
|-----------------|-------------|--------------------------------------------------------------------------------------------------------------|
| `irc-posts`     | posts, reels | `textContent`, `hashtags`, `authorName`, `authorUsername`                                                    |
| `irc-qna`       | questions   | `title`, `body`, `tags`, `keywords`, `authorName`, `authorUsername`                                          |
| `irc-research`  | research    | `title`, `abstractText`, `description`, `keywords`, `tags`, `researcherName`, `researcherUsername`           |

A top-bar search box on the client needs to query **all three** at
once and rank everything together. Doing that on the client side
(three round-trips, merge locally) wastes a request and breaks
relevance — BM25 scores are only comparable when computed in the same
query against the same `multi_match`.

The unified endpoint solves both problems: one Elasticsearch request
hits all three indices in parallel using a cross-index `multi_match`,
and Elasticsearch returns the global BM25-ranked merge. The response
stamps each hit with its `type` (`POST` / `REEL` / `QUESTION` /
`RESEARCH`) so the client knows which hydration endpoint to call for
each ID.

Posts and reels share the `irc-posts` index — they are distinguished
post-hoc by the `postType` source field. When the caller asks only for
`type=REEL`, an additional `term` filter (`postType=REEL`) narrows the
posts index.

---

## 2. `GET /api/v1/search`

**Auth:** 🟢 Public (anonymous-safe).

### Query parameters

| Name     | Type    | Required | Default | Notes                                                                                                                                                                                                       |
|----------|---------|----------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `q`      | string  | yes      | —       | Free-text search query. Blank / missing → `200` with `results: []`.                                                                                                                                         |
| `types`  | CSV     | no       | all     | Subset of `POST`, `REEL`, `QUESTION`, `RESEARCH`. Pass `all` or omit to search every entity. Unknown tokens are silently ignored.                                                                            |
| `cursor` | opaque  | no       | —       | Opaque cursor from a previous response's `nextCursor`. **Preferred over `page`** for typeahead / live feeds — uses ES `search_after` so inserts during a scroll don't shift later pages.                       |
| `page`   | int     | no       | `0`     | 0-indexed (legacy offset mode; **ignored when `cursor` is set**). Offset paging drifts/dupes as new content lands; use `cursor` if you can.                                                                  |
| `size`   | int     | no       | `20`    | Per-page result count across **all** indices combined (not per index). Clamped to [1, 100].                                                                                                                  |
| `expand` | boolean | no       | `true`  | When `true`, each hit is enriched with `titlePreview` / `authorUsername` / `authorName` / `createdAt` from the ES source — eliminates the typeahead N+1. Pass `expand=false` to fall back to the bare shape. |

### Response `200`

```json
{
  "query":      "tafsir methodology",
  "types":      ["POST", "REEL", "QUESTION", "RESEARCH"],
  "page":       0,
  "size":       20,
  "degraded":   false,
  "results": [
    {
      "contentType":    "RESEARCH",
      "contentId":      "1b2c…",
      "type":           "RESEARCH",
      "id":             "1b2c…",
      "score":          13.42,
      "titlePreview":   "A maqāṣid reading of contemporary hajj logistics",
      "authorUsername": "yusuf",
      "authorName":     "Yusuf al-Qaradawi",
      "createdAt":      "2026-05-26T12:00:00Z"
    },
    { "contentType": "QUESTION", "contentId": "a91f…", "type": "QUESTION", "id": "a91f…", "score": 11.07, "titlePreview": "Is it permitted to delay hajj for debt repayment?" }
  ]
}
```

When called with `?cursor=…`, the response also carries `nextCursor` (empty
string when no more pages exist).

Field-level notes:

- **`contentType`** — `POST` / `REEL` / `QUESTION` / `RESEARCH`. **Canonical
  field; matches `TagController.TaggedContent`** so the same client code
  renders search hits and tag-feed rows.
- **`contentId`** — canonical UUID of the underlying entity.
- **`type` / `id`** — **deprecated aliases** carrying the same values. Kept
  for one release window so existing frontends keep working — migrate to
  `contentType` / `contentId`.
- **`score`** — raw BM25 score (higher = better match). Use for ordering
  only; the value is not meaningful in isolation.
- **`titlePreview` / `authorUsername` / `authorName` / `createdAt`** — present
  only when `expand=true` (the default). Pulled from the ES document, so no
  extra round-trips. **No viewer-specific fields** (no `savedByMe`,
  `reactedByMe`, etc.) — those still require the hydration call (§3).
- **`degraded`** — `true` when ES failed and `results` is empty; lets the
  frontend distinguish a real empty result from a service hiccup. Mirrored on
  the response header `X-Search-Degraded: true`.

Hydration dispatch by `contentType`:

- `POST` → `GET /api/v1/posts/{id}`
- `REEL` → `GET /api/v1/posts/{id}` (same endpoint, reels live in the same table)
- `QUESTION` → `GET /api/v1/questions/{id}`
- `RESEARCH` → `GET /api/v1/researches/{id}`

If `q` is blank or every supplied `types` token is invalid, the
endpoint returns `200` with an empty `results` array — never an error.

### Ranking powers

The unified query is **not** a plain `multi_match`. It's a layered
Elasticsearch query that combines six independent ranking signals,
all assembled inside a single `bool` and then wrapped in a
`function_score` so BM25 mixes with recency + engagement. None of
these are exposed as query parameters — they apply automatically to
every search.

#### Power 1 — Per-field weighting (BM25)

Different fields carry different signal. The primary `multi_match`
runs against this weighted set; the boost number is the field's
weight in the final BM25 score:

```
title^4              textContent^3
abstractText^2       keywords^2
body^2               tags^2
hashtags^2           description
authorName           authorUsername
researcherName       researcherUsername
locationName
```

Elasticsearch silently ignores fields that don't exist on a given
index, so the same query is safe across all three. Adding
`locationName` to the field set means geo-tagged posts surface for
place-name searches ("Erbil", "Cairo") even when the post text
doesn't mention the place.

#### Power 2 — Typo tolerance (`fuzziness=AUTO`)

The primary `multi_match` runs with `fuzziness=AUTO`, which is
shorthand for Damerau–Levenshtein edit distance scaled by query
length:

| Token length | Allowed edits |
|---|---|
| 1–2 chars | 0 (exact) |
| 3–5 chars | 1 |
| 6+ chars | 2 |

Real effect — these all return the same canonical doc:

```
ramadan   →  ramadan ✓
ramadhan  →  ramadan ✓ (1 edit)
ramadann  →  ramadan ✓ (1 edit)
rmd       →  no match (too short for fuzz)
```

You don't pass anything to enable this — it's always on. Side effect:
single-character queries fall back to exact only (the analyzer's
behavior, not a bug), so the typeahead in [Power 4](#power-4--typeahead-prefix-match) below is what catches "in-flight"
1–2-char queries.

#### Power 3 — Phrase relevance boost

A parallel `match_phrase` runs in a `should` clause with **boost
2.0**. Exact phrases beat scattered tokens — so a doc containing
"ramadan zakat" outranks a doc that has "ramadan" in one paragraph
and "zakat" in another.

```
query:  "ramadan zakat"

doc A — "...ramadan zakat rules..."           → score boosted ×2
doc B — "...ramadan... ...later, zakat..."    → BM25 only
```

#### Power 4 — Typeahead (`match_phrase_prefix`)

A second `should` clause uses `match_phrase_prefix` (boost 1.5). It
matches the last word as a prefix:

```
query:  "ramad"

→ matches docs containing "ramadan", "ramadhan", "ramada", "ramadi"
```

This is what makes the search-as-you-type dropdown feel snappy
without needing dedicated edge-ngram fields on every index.

#### Power 5 — Required overlap (`minimum_should_match=75%`)

Multi-word queries require at least **75%** of the tokens to match,
rounded down. Without this, a single common token (e.g. "the") would
match every document and dilute the result list.

| Token count | Tokens required |
|---|---|
| 1 | 1 |
| 2 | 1 |
| 3 | 2 |
| 4 | 3 |
| 5 | 3 |
| 8 | 6 |

`BestFields` + `tieBreaker=0.3` is used so the dominant field decides
rank, but secondary field hits still contribute 30% of their score
— a query that hits both `title` and `body` ranks higher than one
that hits `title` alone.

#### Power 6 — Lifecycle filter (no dead content)

Five statuses are blocked at query time via `mustNot`, defending
against indexer regressions that might leak unpublished content:

```
DELETED · DRAFT · ARCHIVED · RETRACTED · REMOVED_BY_MODERATOR
```

`mustNot term` on a missing field is a silent no-op, so the same
filter applies safely across all three indices. Research drafts,
soft-deleted posts, and moderator-removed Q&A never appear in
results even if a bug leaks them into the index.

#### Power 7 — Recency decay (Gaussian)

The entire `bool` is wrapped in a `function_score`. The first scoring
function is a **gauss decay** on `createdAt`:

```
origin = now
offset = 1d         (no decay within the last day)
scale  = 30d        (score × 0.5 at 30 days old)
decay  = 0.5
```

Visualized:

```
relevance multiplier
  1.0 ┤■■■■■■■■■■■■■■■
  0.8 ┤              ╲
  0.6 ┤               ╲
  0.4 ┤                 ╲___
  0.2 ┤                     ╲____
  0.0 ┤_______________________________
       0d   30d   90d   180d   365d
                age of doc
```

Equal-relevance hits prefer the newer document — important for a
social timeline where 2-year-old posts shouldn't outrank fresh ones
just because the old one has more BM25 token matches.

#### Power 8 — Per-index engagement boost (`field_value_factor`)

Six parallel scoring functions, mapping each domain's strongest
engagement signals onto the score. Each function uses `missing=0`
so a doc only earns the boost for the metric it actually carries —
Q&A docs aren't penalized for lacking `reactionCount`, posts aren't
penalized for lacking `citationCount`. The three **secondary**
functions (Q&A `viewCount`, posts `commentCount`, research
`downloadCount`) are **scoped via `_index` filters** so each fires
only on its own domain — keeping noisier signals out of cross-domain
ranking.

| # | Field | Factor | Modifier | Applies to | Role | Why |
|---|---|---|---|---|---|---|
| 1 | `reactionCount` | 1.0 | `log1p` | posts, research | **primary** (posts) / secondary (research) | Social signal — likes/reactions |
| 2 | `answerCount`   | 2.0 | `log1p` | Q&A | **primary** | "More answers = better question." Smaller absolute values (typically 0–20), so larger factor brings the contribution in line with the others |
| 3 | `citationCount` | 1.5 | `log1p` | research | **primary** | Academic gold standard — a much-cited paper ranks above an obscure one of equal text relevance |
| 4 | `viewCount`     | 0.5 | `log1p` | **Q&A only** (filtered by `_index=irc-qna`) | secondary | Tiebreaker between two answer-equivalent questions. Excluded from posts / research because views are noisier than reactions/citations as a quality signal |
| 5 | `commentCount`  | 0.5 | `log1p` | **posts only** (filtered by `_index=irc-posts`) | secondary | Tiebreaker between two like-equivalent posts — a heavily-discussed post sits above a same-reaction-count post with no conversation. Excluded from research / Q&A because they already carry stronger primary signals |
| 6 | `downloadCount` | 0.5 | `log1p` | **research only** (filtered by `_index=irc-research`) | secondary | Tiebreaker between two similarly-cited papers — a paper that's been downloaded for study (PDF / dataset) ranks above one of equal citation count with no downloads. Excluded from posts / Q&A because raw download counts have no analogue there |

Calibration logic — typical max value, then `log1p`, then × factor:

```
posts:    reactionCount ~1000 → log1p(1000) ≈ 6.9 → × 1.0 = 6.9    (primary)
          commentCount  ~500  → log1p(500)  ≈ 6.2 → × 0.5 = 3.1    (secondary)
qna:      answerCount   ~20   → log1p(20)   ≈ 3.0 → × 2.0 = 6.0    (primary)
          viewCount     ~1000 → log1p(1000) ≈ 6.9 → × 0.5 = 3.5    (secondary)
research: citationCount ~50   → log1p(50)   ≈ 3.9 → × 1.5 = 5.9    (primary)
          downloadCount ~500  → log1p(500)  ≈ 6.2 → × 0.5 = 3.1    (secondary)
                        plus reactionCount tap-in (smaller)
```

Domain-by-domain tie-breaking behavior:

- **Posts** — primary signal is reactions, secondary is comments.
  A post with 10 likes and 50 comments still ranks below one with
  100 likes and no comments at equal text relevance; the secondary
  only matters when the primaries are close.
- **Q&A** — primary is answers, secondary is views. A
  heavily-viewed-but-unanswered question sits below a much-answered
  question of equal relevance; the more-viewed wins only when the
  answer counts tie.
- **Research** — primary is citations (with a smaller
  `reactionCount` tap-in), secondary is downloads. Two papers with
  the same citation count are tie-broken by download activity —
  rewarding papers that are being actively read / used, not just
  cited. Citations always dominate downloads, so a heavily-downloaded
  but uncited paper sits below a much-cited one of equal relevance.

So a viral post, a much-answered question, and a much-cited paper
all reach comparable boost magnitudes — none of the three domains
gets crushed by the others when results are mixed in a single
`/api/v1/search` response.

`log1p` (`log(1+x)`) is what keeps any single domain from running
away with the ranking: a 100 000-reaction post gets `log1p ≈ 11.5`,
not 100 000 — so a 100-reaction post (`log1p ≈ 4.6`) with a much
better text match isn't drowned out.

The seven scoring functions (recency + six engagement ones) combine
with `score_mode=sum` (they add), and the sum multiplies the BM25
query score (`boost_mode=multiply`). Net effect for any single doc:

```
final = bm25 × (gauss(createdAt)
              + log1p(reactionCount)×1.0    -- if field present
              + log1p(answerCount)×2.0      -- if field present
              + log1p(citationCount)×1.5    -- if field present
              + log1p(viewCount)×0.5        -- if doc is irc-qna
              + log1p(commentCount)×0.5     -- if doc is irc-posts
              + log1p(downloadCount)×0.5    -- if doc is irc-research)
```

A document only earns contributions from the metrics its index
actually populates — every other term collapses to zero through the
`missing=0` clause, and the per-function `_index` filters (functions
4, 5, 6) collapse for any doc outside their target index.

#### What this means for the client

You don't pass anything to opt into any of these powers — they're
always on. The only knob the client controls is the `types` filter
and the cursor/page. Predictable consequences:

- Typos are forgiven on tokens of 3+ chars.
- Multi-word queries respect token overlap — fewer noisy single-token hits.
- Exact phrases rank above scattered matches.
- Partial last-word matches catch in-flight typeahead queries.
- Fresh content surfaces above old content of equal text relevance.
- Popular content surfaces above ignored content of equal text relevance.
- Deleted / drafts / retracted content never appears.

### Index-side fields that feed the powers

Each domain owns its own index. Below is the set of fields each
indexer populates — every search power above runs against
whichever of these exist on a given index.

| Index | Text fields | Filter fields | Score fields |
|---|---|---|---|
| `irc-posts` | `textContent`, `hashtags`, `authorName`, `authorUsername`, `locationName` | `postType`, `visibility`, `status` | `reactionCount`, `commentCount`, `viewCount`, `createdAt` |
| `irc-qna` | `title`, `body`, `tags`, `keywords`, `authorName`, `authorUsername` | `status` | `answerCount`, `viewCount`, `saveCount`, `createdAt` |
| `irc-research` | `title`, `abstractText`, `description`, `keywords`, `tags`, `researcherName`, `researcherUsername` | `status` | `reactionCount`, `viewCount`, `citationCount`, `downloadCount`, `publishedAt`, `createdAt` |

### Examples

Search everything:

```
GET /api/v1/search?q=zakat
```

Search posts + reels only:

```
GET /api/v1/search?q=zakat&types=POST,REEL
```

Search research only, page 2 of 10-per-page:

```
GET /api/v1/search?q=tafsir%20methodology&types=RESEARCH&page=2&size=10
```

---

## 3. Hydration pattern

The endpoint returns IDs + types only — clients must hydrate each hit
through its canonical detail endpoint. This is intentional:

- the canonical store (Cassandra for posts/reels, Postgres for Q&A and
  research) holds the live counters, the viewer-specific fields
  (`likedByMe`, `savedByMe`, …) and the moderation state — none of
  which are kept in Elasticsearch;
- the ES indices are eventually-consistent — using them as the source
  of truth would surface stale data to the user.

Recommended client flow for an infinite-scroll search results page:

1. Call `/api/v1/search` with `page=0`.
2. For each hit, dispatch the matching detail endpoint by `type`.
3. Render results in BM25 order (as returned).
4. On scroll, increment `page` and repeat.

Group hits by `type` if the client batches its hydration calls (e.g.
parallel `Promise.all` per entity family).

---

## 4. Indexing

The query side is unified, but the **write side** is still per-entity:
each domain owns its own indexer and keeps its own index in sync with
its canonical store. Search is eventually consistent — typically <1 s
behind the write.

| Indexer service          | Index          | Reacts to                                                                                          |
|--------------------------|----------------|----------------------------------------------------------------------------------------------------|
| `PostSearchService`      | `irc-posts`    | Cassandra post create / edit / delete (called from `CassandraPostService`)                         |
| `ResearchSearchService`  | `irc-research` | Postgres research lifecycle (publish, unpublish, archive, retract, delete)                         |
| `QnaSearchService`       | `irc-qna`      | Postgres question create / edit / delete                                                           |

Each indexer exposes only `indexAsync(entity)` and `deleteAsync(id)`
— they no longer expose a query method. Query-time logic lives
exclusively in `GlobalSearchService`.

`SearchInfrastructureInitializer` ensures all three indices exist on
boot.

---

## 5. Error model

| Condition                       | HTTP  | Body                                                                                                                                                              |
|---------------------------------|-------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Missing `q` parameter           | `400` | Spring default `MISSING_PARAMETER`                                                                                                                                |
| Elasticsearch unreachable       | `200` | `{ "results": [], "degraded": true, ... }` + response header `X-Search-Degraded: true`. The frontend can use either signal to render a "Search degraded" banner. |

The "ES-down ⇒ empty results" behaviour is deliberate: a degraded
search box is much less disruptive than a search box that breaks the
page. The `degraded` flag (added because empty-vs-broken was previously
indistinguishable on the client) lets the UI tell the user something
happened. The warning is also logged at the service level
(`GlobalSearchService.search` / `GlobalSearchService.searchCursor`).

---

## 6. Migration notes

The following per-entity endpoints were **removed** in favour of this
unified one:

| Removed                                             | Replacement                                |
|-----------------------------------------------------|--------------------------------------------|
| `GET /api/v1/posts/search?q=…`                      | `GET /api/v1/search?q=…&types=POST,REEL`   |
| `GET /api/v1/questions/search?q=…`                  | `GET /api/v1/search?q=…&types=QUESTION`    |
| `GET /api/v1/researches/search?q=…`                 | `GET /api/v1/search?q=…&types=RESEARCH`    |

The response shape changed: removed endpoints returned bare UUIDs
under `results: [...]`. The unified endpoint returns
`{contentType, contentId, score, ...}` objects instead — clients need
to read `.contentId` (and optionally branch on `.contentType` for
cross-entity result pages).

> **Shape note (May 2026).** `contentType` / `contentId` replaced the
> earlier `type` / `id` field names so the search hit shape matches
> `TagController.TaggedContent`. The old `type` / `id` fields are still
> emitted alongside the new ones for one release window — migrate, then
> they go away.

Tag-filter endpoints (`GET /api/v1/researches/search/tags`) are
unrelated to relevance search and remain on their domain controllers.
The newer **cross-content** tag + trending surfaces live under
`/api/v1/tags/*` — see §7.

---

## 7. Tags, keywords & trending (Cassandra)

Relevance search (§2) answers *"what matches these words?"*. Two other
discovery surfaces — **trending tags** and **tag feeds** — are powered by a
dedicated **Cassandra** tag subsystem, not Elasticsearch.

### 7.1 Why two stores

| Need | Store | Why |
|---|---|---|
| Type a few letters, get quality-ranked results | **Elasticsearch** | BM25 relevance ranking. Cassandra can look up rows by key but cannot rank them by textual relevance. |
| "Most-used tags right now" (`hajj`, `ramadan`) | **Cassandra** | A native `COUNTER` per tag is an O(1) increment on every tag use; the trending leaderboard is a tiny pre-ranked partition. |
| "Everything tagged `#hajj`, newest first" | **Cassandra** | One partition per tag, clustered by time → a single sequential, cursor-paged read. |

This is the "fastest path" for each job: ES for fuzzy relevance, Cassandra for
exact-tag counters and feeds.

### 7.2 What carries tags, and when it counts

| Content | Tags supplied | Counted toward trending |
|---|---|---|
| **Q&A question** | author `tags` on create / edit | immediately (questions are live on create); removed on delete |
| **Research** | author `tags` | only while **PUBLISHED** — added on publish, removed on unpublish / archive / retract / delete; re-synced on edit |
| **Posts** | `#hashtags` extracted from text | immediately, on create / edit. Cleared on delete. |
| **Reels** | `#hashtags` extracted from caption | same as posts; the {@code REEL} scope splits them out for trending. |

> **Posts ↔ tag feed (May 2026).** Posts and reels now fan out into the unified
> {@code content_by_tag} table on create/edit/delete, so a {@code #hajj} on a
> post shows up in {@code GET /tags/hajj/content} alongside questions and
> research. The legacy {@code POST_ACTIONS_API.md} hashtag endpoints
> ({@code /api/v1/hashtags/{tag}/posts}) still work and remain the source of
> truth for the posts-only view, but the unified {@code /tags/...} surface is
> now the recommended one. Historical posts (predating this change) need a
> one-off `POST /api/v1/admin/tags/backfill-posts` (admin-gated) to surface in
> the unified feed; new posts are indexed automatically.

**Tag normalization** (applied everywhere): lowercased, trimmed, a leading `#`
stripped, de-duplicated, capped at **30 tags** per item and **100 chars** each.
Case folding uses `Locale.ROOT` so it's language-independent — Turkish or
Greek-locale servers don't fold `I` differently.

**Unicode is preserved.** Tags are stored in whatever script the user typed
(`رمضان` and `ramadan` are two distinct tags, not the same one). This is
intentional — the platform doesn't auto-transliterate. Frontends should not
either; if you want a "see also" hint, surface it client-side without
collapsing the tags into one.

**`keywords` vs `tags`** — different things:
- **`tags`** are short, normalized topic labels that drive trending + tag feeds (Cassandra) *and* are indexed in Elasticsearch.
- **`keywords`** are a free-text string the author adds purely for search discoverability. Indexed in Elasticsearch and boosted (`keywords^2`) in relevance search — **not** part of trending or tag feeds.

### 7.3 `GET /api/v1/tags/trending`

**Auth:** 🟢 Public.

Pre-ranked most-used tags. Reads a single pre-sorted Cassandra partition — no
per-request sorting.

| Param | Type | Default | Notes |
|---|---|---|---|
| `scope` | enum | `ALL` | `ALL` (questions + research), `QUESTION`, or `RESEARCH`. Unknown values fall back to `ALL`. |
| `limit` | int | `20` | Max tags (clamped to 1–100). |

**Response `200`**

```json
[
  { "tag": "hajj",    "usageCount": 1284, "rank": 0 },
  { "tag": "ramadan", "usageCount": 1102, "rank": 1 },
  { "tag": "zakat",   "usageCount":  734, "rank": 2 }
]
```

| Field | Meaning |
|---|---|
| `tag` | Normalized tag label |
| `usageCount` | How many content items in this scope carry the tag (snapshot value) |
| `rank` | 0-based position (already sorted; render in order) |

> **Freshness:** the leaderboard is recomputed on a short cycle (default every
> 10 minutes). The underlying counters are always exact; only the ranked
> snapshot is periodic. So a brand-new tag may take a few minutes to surface in
> trending even though its feed (§7.4) is live immediately.

### 7.4 `GET /api/v1/tags/{tag}/content`

**Auth:** 🟢 Public.

All content (posts, reels, questions and research) carrying a tag,
**newest first**, cursor-paged.

| Param      | Type   | Default | Notes                                                                                                                                                                                                                                                          |
|------------|--------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `pageSize` | int    | `20`    | Rows per page (clamped to 1–100).                                                                                                                                                                                                                              |
| `cursor`   | opaque | —       | Opaque base64 cursor from the previous response's `nextCursor`. Includes both `createdAt` and `contentId` so two rows landing in the same millisecond don't get skipped or duplicated. Bare-`Instant` legacy cursors (no tiebreaker) are still accepted.       |

**Response `200`**

```json
{
  "tag": "hajj",
  "pageSize": 20,
  "items": [
    {
      "contentId":    "1b2c…",
      "contentType":  "RESEARCH",
      "authorId":     "9a2c…",
      "titlePreview": "A maqāṣid reading of contemporary hajj logistics",
      "createdAt":    "2026-05-26T12:00:00Z"
    },
    {
      "contentId":    "a91f…",
      "contentType":  "QUESTION",
      "authorId":     "41ee…",
      "titlePreview": "Is it permitted to delay hajj for debt repayment?",
      "createdAt":    "2026-05-25T22:14:00Z"
    }
  ],
  "nextCursor": "MjAyNi0wNS0yNVQyMjoxNDowMFp8YTkxZjAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAw"
}
```

`contentType` tells you which detail endpoint to hydrate:
- `POST` / `REEL` → `GET /api/v1/posts/{id}`
- `QUESTION` → `GET /api/v1/questions/{id}`
- `RESEARCH` → `GET /api/v1/researches/{id}`

`titlePreview` is a denormalized snippet so the feed renders without an extra
fetch. When the underlying entity's title is edited later, the preview is
refreshed in place — no stale snippets.

Pagination is **exact** (no skips / no dupes) because the cursor packs
`createdAt` + `contentId`. Treat `nextCursor` as opaque — pass it back
verbatim, never decode client-side. Stop paging when `items` is shorter than
`pageSize` or `nextCursor` is empty.

### 7.5 `GET /api/v1/tags/{tag}/usage`

**Auth:** 🟢 Public. Returns one tag's usage count.

Per-scope mode:

```
GET /api/v1/tags/hajj/usage?scope=RESEARCH
→ { "tag": "hajj", "scope": "RESEARCH", "usageCount": 312 }
```

**Breakdown mode** (`scope=*`) — single round-trip for the tag-page header
chip. Returns counts for every scope (including zero for empty ones):

```
GET /api/v1/tags/hajj/usage?scope=*
→ {
    "tag":    "hajj",
    "scopes": {
      "ALL":      496,
      "QUESTION": 184,
      "RESEARCH": 312,
      "POST":       0,
      "REEL":       0
    }
  }
```

Use breakdown mode when rendering "312 research · 184 questions · 496 total"
on the tag page header — saves three calls.

### 7.6 `GET /api/v1/tags/search` — autocomplete

**Auth:** 🟢 Public. Prefix-matches the tag catalogue inside a scope.
Powers chip-input autocomplete on create/edit forms so users converge on
existing tags (`rama…` → `ramadan`, `ramadan-2026`) instead of inventing
near-duplicate variants.

| Param    | Type | Default | Notes                                                                                                          |
|----------|------|---------|----------------------------------------------------------------------------------------------------------------|
| `prefix` | str  | —       | Required. Normalised before matching (lowercase, leading `#` stripped).                                        |
| `scope`  | enum | `ALL`   | `ALL` / `QUESTION` / `RESEARCH` / `POST` / `REEL`. Constrain to the type the user is tagging if relevant.       |
| `limit`  | int  | `10`    | Max suggestions (clamped to 1–50).                                                                              |

**Response `200`**

```json
[
  { "tag": "ramadan",      "usageCount": 1102 },
  { "tag": "ramadan-2026", "usageCount":   84 },
  { "tag": "ramadan-iftar","usageCount":   12 }
]
```

Backed by a clustering-key range scan on `tag_counters` — no secondary index,
no extra disk. Ordered by tag name (ascending); the client can re-sort by
`usageCount` if a "popular first" UI is preferred.

### 7.7 How it's stored (Cassandra)

| Table | Key | Role |
|---|---|---|
| `content_by_tag` | part. `tag`, clust. `created_at DESC, content_id` | The tag feed (§7.4). One row per (tag, content). |
| `tags_by_content` | part. `content_id`, clust. `tag` | Reverse index → clean delete/retag without re-parsing text. |
| `tag_counters` | part. `scope`, clust. `tag`, `usage_count COUNTER` | Source of truth for usage. Each tag use bumps the `ALL` scope and the type scope. |
| `trending_tags` | part. `scope`, clust. `tag_rank ASC` | Pre-ranked snapshot (§7.3), rebuilt by `TrendingTagJob`. |

Write fan-out per tagged item is best-effort and isolated — a tag-index failure
never fails the content create/update.

---

## 8. Frontend integration guide

Everything a frontend needs to wire the **search box**, the **trending strip**,
**tag feeds**, and **tag input** on forms.

### 8.1 Golden rules

1. Search is **public** — no token needed for `/search` or any `/tags/*` read.
2. With `expand=true` (the default), a search hit already carries `titlePreview` / `authorUsername` / `authorName` / `createdAt` — enough to render a dropdown or card without a follow-up hydration call. For full detail (counters, viewer-specific flags like `savedByMe`), hydrate via the entity's detail endpoint (§3).
3. Render results in the returned order (BM25). Don't re-sort by `score`.
4. Search is **degrade-safe**: if ES is down the endpoint returns `200` with `results: []` **and** `degraded: true` (also as the `X-Search-Degraded` header). Show "Search is degraded — try again shortly", not a generic error.
5. Field names: prefer **`contentType` / `contentId`** (the canonical names that match `/tags/{tag}/content`). The deprecated aliases `type` / `id` still appear on the response for one release window — migrate then ignore them.

### 8.2 The search box

Dropdown / typeahead (expanded hits, cursor paging):

```ts
async function searchTypeahead(q, types, cursor) {
  if (!q.trim()) return { results: [], degraded: false, nextCursor: "" };
  const p = new URLSearchParams({ q, size: "10" });           // expand=true is default
  if (types?.length) p.set("types", types.join(","));
  if (cursor)        p.set("cursor", cursor);
  return fetch(`/api/v1/search?${p}`).then(r => r.json());
  // results: [{ contentType, contentId, score, titlePreview, authorUsername, createdAt, ... }]
}
```

Full results page (offset paging is also fine here — users rarely deep-scroll search):

```ts
async function searchPage(q, types, page = 0) {
  if (!q.trim()) return { results: [], degraded: false };
  const p = new URLSearchParams({ q, page: String(page), size: "20" });
  if (types?.length) p.set("types", types.join(","));
  return fetch(`/api/v1/search?${p}`).then(r => r.json());
}
```

👉 **What to do**

- **Debounce** input ~250–300 ms; cancel the in-flight request on a new keystroke.
- **Use cursor mode for typeahead** (`cursor=…` from the previous response's `nextCursor`). Stable across inserts that land during the scroll.
- **Render dropdown rows directly** from the expanded hit fields. Only hydrate on tap (full detail page) or when you need counters / viewer flags.
- **Hydrate** by `contentType` for full pages:
  - `QUESTION` → `GET /api/v1/questions/{id}`
  - `RESEARCH` → `GET /api/v1/researches/{id}`
  - `POST` / `REEL` → `GET /api/v1/posts/{id}`
- **Scoped tabs** ("Questions" / "Research" / "All") map directly to `types`.
- **Watch `degraded`** — render the banner when `true`. (Or check the `X-Search-Degraded` response header.)

### 8.3 Trending tag strip

```ts
const trending = await fetch("/api/v1/tags/trending?scope=ALL&limit=12")
  .then(r => r.json());   // [{tag, usageCount, rank}, ...] already ranked
```

👉 **What to do**

- Render chips **in array order** (it's pre-ranked; `rank` is informational).
- Optionally show `usageCount` as a subtle count badge.
- Tapping a chip opens that tag's feed (§8.4) — route to `/tags/{tag}`.
- Use `scope=QUESTION` / `scope=RESEARCH` for section-specific trending strips (e.g. a "Trending in Research" rail).
- Cache for a couple of minutes client-side — trending only refreshes every ~10 min server-side, so polling faster is wasted.

### 8.4 Tag feed page (`/tags/{tag}`)

```ts
async function tagFeed(tag, cursor) {
  const p = new URLSearchParams({ pageSize: "20" });
  if (cursor) p.set("cursor", cursor);              // opaque token from previous nextCursor
  const r = await fetch(`/api/v1/tags/${encodeURIComponent(tag)}/content?${p}`).then(r => r.json());
  return { items: r.items, nextCursor: r.nextCursor };
}

// Header chip — one call gets all four scope counts.
async function tagBreakdown(tag) {
  const r = await fetch(`/api/v1/tags/${encodeURIComponent(tag)}/usage?scope=*`).then(r => r.json());
  return r.scopes;        // { ALL, QUESTION, RESEARCH, POST, REEL }
}
```

👉 **What to do**

- Render each row from `titlePreview` + a type badge (`contentType`). Posts/reels, questions, and research all show up here — your renderer needs four type branches.
- **Paginate by cursor:** pass the opaque `nextCursor` straight back as `cursor`. Never decode client-side; the format is base64 and may change. Stop when `nextCursor` is empty or the page is short.
- **Header chip:** `usage?scope=*` returns all four breakdown counts in one round-trip — render "312 research · 184 questions · 184 posts · 0 reels · 496 total" without three separate calls.
- The feed is **live** (updates the moment content is tagged), unlike the trending strip which lags by the refresh cycle.

### 8.5 Tag + keyword input on create/edit forms (Q&A & Research)

Both `POST/PATCH /api/v1/questions` and the research create/update endpoints
accept:

- **`tags`** — `string[]`, up to 30. A chip/token input. You don't need to
  lowercase or de-dupe — the server normalizes — but doing it client-side keeps
  the UI tidy. Strip a leading `#` if your input adds one.
- **`keywords`** — a single free-text string (up to 2000 chars) for extra search
  terms. A plain text field.

```ts
// Autocomplete the chip input as the user types.
async function suggestTags(prefix, scope = "ALL") {
  if (!prefix) return [];
  const p = new URLSearchParams({ prefix, scope, limit: "10" });
  return fetch(`/api/v1/tags/search?${p}`).then(r => r.json());
  // [{ tag, usageCount }, ...]
}
```

👉 **What to do**

- **Autocomplete with `/tags/search`** as the user types in the chip input. Filtering trending client-side misses any tag not currently in the top-N; the prefix search covers the whole catalogue. Re-sort suggestions by `usageCount` if you want "popular first".
- On **Q&A**: tags take effect immediately (the question is live on create).
- On **Research**: tags only start trending once the paper is **published**; a
  draft's tags are stored but invisible to trending until publish. Reflect this
  in the UI ("tags will appear in trending after you publish").
- On **edit**, send the full replacement `tags` array (the server rebuilds the
  index). Sending `tags: []` clears them; omitting `tags` leaves them unchanged.
- **Don't transliterate.** `رمضان` and `ramadan` are intentionally distinct tags. If you want a "you might mean…" UX, offer it client-side without folding them into one.

### 8.6 Checklist

- [ ] Search box debounced; blank query skips the call.
- [ ] Search hits read **`contentType` / `contentId`** (not `type` / `id`).
- [ ] Typeahead uses **cursor mode** (`cursor=` from `nextCursor`); offset paging is OK for full results pages.
- [ ] Dropdown rows render from the **inlined** `titlePreview` / `authorUsername` / `createdAt` — hydration only on tap or for full detail pages.
- [ ] `degraded: true` (or `X-Search-Degraded`) renders a "Search is degraded" banner, not a generic error toast.
- [ ] Results kept in BM25 order; infinite scroll by `cursor` (or `page` for offset pages).
- [ ] Trending strip rendered in array order; cached ~minutes; chips deep-link to tag feeds.
- [ ] Tag feed paginated by the **opaque** `nextCursor` (passed back verbatim); rows badged by `contentType`. Posts and reels appear here alongside Q&A / research.
- [ ] Tag header uses `usage?scope=*` for the per-scope breakdown — one call, not three.
- [ ] Tag input uses `/tags/search?prefix=` for autocomplete (full catalogue), not `/tags/trending` (top-N only).
- [ ] Tag input capped at 30, `#` stripped; keywords a separate free-text field.
- [ ] Frontend does not transliterate Arabic ↔ Latin tags (kept distinct on purpose).
- [ ] Research UI tells authors tags trend only after publish.

---

## 9. Operations

### One-off: backfill historical posts into the unified tag feed

Posts created **before** the unified-tag fan-out shipped won't appear in
`GET /tags/{tag}/content` until they're indexed. Run this once after deploy
to migrate them forward:

```
POST /api/v1/admin/tags/backfill-posts          (🔴 ADMIN role required)
→ {
    "postsScanned":      12345,
    "postsWithHashtags":  4321,
    "tagRowsWritten":     7890,
    "startedAt":         "2026-05-29T13:00:00Z"
  }
```

Idempotent for the feed rows (UPSERTs by primary key), but the trending
counter increments are **not** idempotent — don't re-run unless you know
the counters need a refresh.
