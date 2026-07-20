# Search API

Base path: **`/api/v1/search`** (public) · **`/api/v1/admin/search`** (admin operations)

This is the **only** full-text search entry point in the API. One Elasticsearch query
hits the `irc-posts`, `irc-qna`, and `irc-research` indices in parallel and returns a
single score-ordered merge — the older per-entity `/api/v1/posts/search`,
`/api/v1/questions/search`, and `/api/v1/researches/search` endpoints were removed in
its favour.

**Division of labour with the tag subsystem:** relevance search ("what matches these
words?") is Elasticsearch's job — BM25 ranking layered with typo tolerance, phrase
boost, typeahead prefix matching, recency decay, and per-domain engagement boosts.
Trending and tag feeds ("most-used tags right now", "everything tagged `#hajj`") are
**usage-based** and served from Cassandra counters — see [tags.md](./tags.md). Each
store does the job it is fastest at; neither replaces the other.

The write side stays per-entity: each domain's indexer keeps its own index in sync
with its canonical store (Cassandra for posts/reels, Postgres for Q&A and research).
Search is eventually consistent — typically under a second behind a write.

Auth is Bearer JWT platform-wide; `GET /search` itself is public. Errors use the
standard envelope — see [../errors/error-handling.md](../errors/error-handling.md).

Siblings: [tags.md](./tags.md) · [mentions.md](./mentions.md) ·
[media-proxy.md](./media-proxy.md) · [activity.md](./activity.md) · [audit.md](./audit.md)

---

## 1. Unified search

```
GET /api/v1/search
```

**Auth:** Public — no token required (anonymous-safe).

Cross-entity full-text search over posts, reels, questions, and research.

### Query parameters

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `q` | string | yes | — | Free-text query. Present-but-blank → `200` with `results: []` (never an error). Missing entirely → `400`. |
| `types` | CSV | no | all | Any subset of **`POST`, `REEL`, `QUESTION`, `RESEARCH`** (the only recognized type names — verified against `GlobalSearchService.EntityType`). Case-insensitive. `all` or omitted = all four. Unknown tokens are silently skipped; if nothing valid remains, the search runs against **all** types (it does not return empty). |
| `cursor` | string | no | — | Opaque token from the previous response's `nextCursor`. When supplied, `page` is ignored and pagination runs via ES `search_after`. **Preferred over `page`** — see "Cursor vs. offset" below. |
| `page` | int | no | `0` | 0-indexed offset page (legacy mode; ignored when `cursor` is set). |
| `size` | int | no | `20` | Results per page across **all** indices combined (not per index). Clamped to `[1, 100]`. |
| `expand` | boolean | no | `true` | When `true`, each hit is enriched with `titlePreview` / `authorUsername` / `authorName` / `createdAt` straight from the ES source — zero extra round-trips, eliminates the typeahead N+1. Pass `expand=false` for the bare `(contentType, contentId, score)` shape. |

### Cursor vs. offset pagination

Cursor mode uses ES `search_after` sorted on `(score DESC, _doc ASC)`:

- **Stable across inserts** — a document indexed mid-scroll cannot shift later pages,
  so no row is ever duplicated or skipped. Offset (`page`) mode drifts as new content
  lands, which is visible on live typeahead and infinite scroll.
- **Cheaper deep pagination** — offset mode makes ES collect and discard `page × size`
  hits; `search_after` seeks directly.

Use cursor mode for typeahead and infinite scroll; offset mode remains fine for
shallow, static result pages. The cursor token is base64 of the last hit's sort
values — treat it as opaque and pass it back verbatim.

### Response `200`

```json
{
  "query":    "tafsir methodology",
  "types":    ["POST", "REEL", "QUESTION", "RESEARCH"],
  "page":     0,
  "size":     20,
  "results": [
    {
      "contentType":    "RESEARCH",
      "contentId":      "1b2c5a90-0000-4000-8000-000000000001",
      "type":           "RESEARCH",
      "id":             "1b2c5a90-0000-4000-8000-000000000001",
      "score":          13.42,
      "titlePreview":   "A maqasid reading of contemporary hajj logistics",
      "authorUsername": "yusuf",
      "authorName":     "Yusuf al-Din",
      "createdAt":      "2026-05-26T12:00:00Z"
    },
    {
      "contentType": "QUESTION",
      "contentId":   "a91f0000-0000-4000-8000-000000000003",
      "type":        "QUESTION",
      "id":          "a91f0000-0000-4000-8000-000000000003",
      "score":       11.07,
      "titlePreview": "Is it permitted to delay hajj for debt repayment?"
    }
  ],
  "degraded": false
}
```

Envelope fields:

| Field | Type | Meaning |
|---|---|---|
| `query` | string | Echo of `q`. |
| `types` | array | The effective type filter (all four when none/invalid supplied). |
| `page` | int | Echo of `page` in offset mode; always `0` in cursor mode. |
| `size` | int | The clamped page size actually used. |
| `results` | array | Score-ordered hits (see below). Render in returned order — do not re-sort. |
| `degraded` | boolean | `true` when the ES call failed and `results` is therefore empty. Mirrored by the **`X-Search-Degraded: true`** response header for callers that don't parse the body. A missing index on a fresh cluster is a legitimately empty result, **not** degraded. |
| `nextCursor` | string | **Cursor mode only.** Token for the next page; empty string when no further page exists. Absent entirely in offset mode. |

Hit fields (`GlobalSearchHit`; `null` fields are omitted from the JSON):

| Field | Type | Meaning |
|---|---|---|
| `contentType` | string | `POST` / `REEL` / `QUESTION` / `RESEARCH`. Canonical — matches the tag-feed row shape in [tags.md](./tags.md). Posts and reels share the `irc-posts` index and are split post-hoc by the `postType` source field. |
| `contentId` | UUID | Canonical entity id. Hydrate by type: `POST`/`REEL` → `GET /api/v1/posts/{id}`, `QUESTION` → `GET /api/v1/questions/{id}`, `RESEARCH` → `GET /api/v1/researches/{id}`. |
| `type`, `id` | string, UUID | **Deprecated aliases** of `contentType` / `contentId` (same values), kept for one release window — migrate. |
| `score` | double | Relevance score (BM25 × recency/engagement function score). Ordering only; not meaningful in isolation. |
| `titlePreview` | string | `expand=true` only. First ≤280 chars of the primary text (`title` for Q&A/research, `textContent` for posts). |
| `authorUsername` | string | `expand=true` only. Author/researcher handle. |
| `authorName` | string | `expand=true` only. Display name. |
| `createdAt` | instant | `expand=true` only. Entity creation time. |

No viewer-specific fields (`savedByMe`, `reactedByMe`, …) are ever inlined — those
require the entity's hydration endpoint, which also owns live counters and moderation
state.

### Ranking (always on, no query knobs)

The query layers, in one `bool` wrapped in a `function_score`:

1. **Weighted multi-field recall** — `title^4`, `textContent^3`, `abstractText^2`,
   `keywords^2`, `body^2`, `tags^2`, `hashtags^2`, plus author/researcher names and
   `locationName`; `BestFields`, `tieBreaker=0.3`.
2. **Typo tolerance** — `fuzziness=AUTO` (1 edit at 3–5 chars, 2 at 6+).
3. **Phrase boost** (×2.0) — exact phrases outrank scattered tokens.
4. **Typeahead** — `match_phrase_prefix` (×1.5) catches the in-flight last word.
5. **Required overlap** — `minimum_should_match=75%` keeps single-common-token noise out.
6. **Lifecycle filter** — `DELETED` / `DRAFT` / `ARCHIVED` / `RETRACTED` /
   `REMOVED_BY_MODERATOR` never surface, even if leaked into an index.
7. **Recency decay** — Gaussian on `createdAt` (no decay first day, ×0.5 at 30 days).
8. **Engagement boosts** — `log1p` of `reactionCount` (posts), `answerCount` (Q&A, ×2),
   `citationCount` (research, ×1.5), plus `_index`-scoped tiebreakers: `viewCount`
   (Q&A), `commentCount` (posts), `downloadCount` (research), each ×0.5.

Full derivation with calibration numbers: [`../_legacy/SEARCH_API.md`](../_legacy/SEARCH_API.md).

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_PARAMETER` | `q` not supplied at all. |
| 200 | — (`degraded: true` + `X-Search-Degraded: true`) | Elasticsearch unreachable/failed. Deliberate: a degraded search box beats a broken page. Show a "search is degraded" banner, not an error toast. |

---

## 2. Admin — reindex research

```
POST /api/v1/admin/search/research/reindex
```

**Auth:** Bearer JWT with the **`ADMIN`** role (`@PreAuthorize("hasRole('ADMIN')")`,
plus the chain-level `ADMIN` rule on `/api/v1/admin/**`).

Re-emits every **PUBLISHED** research record from Postgres into the `irc-research`
Elasticsearch index. Use after a mapping change (a new `@Field` on
`ResearchSearchDocument`) so existing documents pick up the new field, or to refresh
drifted score counters. Runs **synchronously** — the response carries the final counts
so the caller knows it completed. Writes go through the same retry wrapper as the
per-row indexer, so a transient connection failure self-heals mid-run.

### Query parameters

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `drop` | boolean | no | `true` | `true`: delete the index first so it is recreated from the current entity mapping (the clean way to land new fields). `false`: keep the mapping, just refresh document values. |

### Response `200`

```json
{
  "indexDropped":     true,
  "documentsIndexed": 1842,
  "pages":            19,
  "durationMs":       5210,
  "note":             null
}
```

| Field | Type | Meaning |
|---|---|---|
| `indexDropped` | boolean | Whether the index was deleted and recreated. |
| `documentsIndexed` | long | Published research records written to ES. |
| `pages` | int | Postgres pages walked. |
| `durationMs` | long | Wall-clock duration of the run. |
| `note` | string | Optional operator note (e.g. partial-failure detail); usually `null`. |

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_REQUIRED` | No / invalid Bearer token. |
| 403 | `ACCESS_DENIED` | Authenticated but not `ADMIN`. |
