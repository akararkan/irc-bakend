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
| `irc-qna`       | questions   | `title`, `body`, `authorName`, `authorUsername`                                                              |
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

| Name    | Type   | Required | Default | Notes                                                                                                                                                      |
|---------|--------|----------|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `q`     | string | yes      | —       | Free-text search query. Blank / missing → `200` with `results: []`.                                                                                        |
| `types` | CSV    | no       | all     | Subset of `POST`, `REEL`, `QUESTION`, `RESEARCH`. Pass `all` or omit to search every entity. Unknown tokens are silently ignored.                          |
| `page`  | int    | no       | `0`     | 0-indexed.                                                                                                                                                 |
| `size`  | int    | no       | `20`    | Per-page result count across **all** indices combined (not per index).                                                                                     |

### Response `200`

```json
{
  "query":   "tafsir methodology",
  "types":   ["POST", "REEL", "QUESTION", "RESEARCH"],
  "page":    0,
  "size":    20,
  "results": [
    { "type": "RESEARCH", "id": "1b2c…", "score": 13.42 },
    { "type": "QUESTION", "id": "a91f…", "score": 11.07 },
    { "type": "POST",     "id": "f66a…", "score":  9.84 },
    { "type": "REEL",     "id": "55ee…", "score":  8.16 }
  ]
}
```

Field-level notes:

- **`type`** — `POST` / `REEL` / `QUESTION` / `RESEARCH`. The frontend
  uses this to dispatch the right hydration call:
  - `POST` → `GET /api/v1/posts/{id}`
  - `REEL` → `GET /api/v1/posts/{id}` (same endpoint, reels live in the same table)
  - `QUESTION` → `GET /api/v1/questions/{id}`
  - `RESEARCH` → `GET /api/v1/researches/{id}`
- **`id`** — canonical UUID of the underlying entity.
- **`score`** — raw BM25 score (higher = better match). Use for
  ordering only; the value is not meaningful in isolation.

If `q` is blank or every supplied `types` token is invalid, the
endpoint returns `200` with an empty `results` array — never an error.

### Field boosts

The unified query uses one `multi_match` with index-spanning field
weights:

```
title^4
textContent^3
abstractText^2
keywords^2
body^2
tags^2
hashtags^2
description
authorName, authorUsername
researcherName, researcherUsername
```

Elasticsearch silently ignores fields that don't exist on a given
index, so the same query is safe across all three.

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

| Condition                       | HTTP  | Body                              |
|---------------------------------|-------|-----------------------------------|
| Missing `q` parameter           | `400` | Spring default `MISSING_PARAMETER` |
| Elasticsearch unreachable       | `200` | `results: []` — failure is logged but never propagated. Search is a non-critical read path; it falls back to empty rather than 5xx-ing the search box. |

The "ES-down ⇒ empty results" behaviour is deliberate: a degraded
search box is much less disruptive than a search box that breaks the
page. The warning is logged at the service level
(`GlobalSearchService.search`).

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
`{type, id, score}` objects instead — clients need to read `.id` (and
optionally branch on `.type` for cross-entity result pages).

Tag-filter endpoints (`GET /api/v1/researches/search/tags`) are
unrelated to relevance search and remain on their domain controllers.
