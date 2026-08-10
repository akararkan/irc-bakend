# Sound-Library Search

The reels/stories **sound picker** search — typo-tolerant title/artist
matching over the `irc-sounds` Elasticsearch index, popularity-boosted by
how many posts use each sound. **APPROVED sounds only**: pending, rejected
and archived entries never surface (their statuses are in the global
dead-status filter, and the dedicated endpoint filters `status=APPROVED`
inside the query).

| Surface | Purpose |
|---|---|
| `GET /api/v1/sounds/search` | The sound picker — full `SoundEntity[]` rows, ready to play |
| `GET /api/v1/search?types=SOUND` | The unified search bar |
| `GET /api/v1/sounds/by-category/{cat}` | Category *browsing* (Cassandra, cursor-paged) — not search |

---

## `GET /api/v1/sounds/search`

```
GET /api/v1/sounds/search?q={text}&category={cat}&limit={n}
```

**Auth:** Bearer JWT.

| Param | Default | Notes |
|---|---|---|
| `q` | — (required) | Search text; blank → `[]` (use the category browser for listing) |
| `category` | — | Optional exact category filter (e.g. `nasheed`) |
| `limit` | `20` | Clamped `[1, 100]` |

**Mechanism:** ES BM25 over `title^3` / `artistName^2` with
`fuzziness=AUTO` (a search for `desret dawn` still finds *Desert Dawn*) and
a phrase-prefix typeahead layer, wrapped in
`score = BM25 × (1 + log1p(useCount))` — the platform's most-used sounds
win ties, `log1p` keeps a mega-viral sound from drowning exact title
matches. `O(t · log n)`.

ES returns ranked ids only (`_source` trimmed to the id, totals never
counted); rows hydrate from Cassandra `sounds_by_id` in **one IN-clause
batch read** — not a point read per id — **preserving rank order** and
re-checking `APPROVED` so a stale index row can't surface an un-approved
sound.

**Response `200`:** `SoundEntity[]` — `id`, `title`, `artistName`,
`audioUrl`, `coverArtUrl`, `durationSeconds`, `category`, `status`,
`uploaderId`, timestamps. Relevance-ordered; render in order.

If ES is unavailable the endpoint returns `[]` (never 5xx) — the picker
falls back to category browsing.

## Global search — `types=SOUND`

Contract in [global-search.md](global-search.md). Sound-specific mapping:
`titlePreview` = title, `authorName` = artist name. Hydrate with
`GET /api/v1/sounds/{id}`.

## Index lifecycle

`irc-sounds` docs: `title`, `artistName`, `category` (keyword), `status`
(keyword), `useCount`, `createdAt`.

- **Upload** → indexed immediately (as `PENDING_REVIEW`, invisible to both
  search surfaces until approved).
- **Approve** → re-indexed as `APPROVED`; becomes searchable within ~1 s.
- **Every adoption** (a post/reel created with the sound) → async
  `useCount` refresh, so popularity ranking tracks real usage. The refresh
  re-reads sound + counter off the request path — the post-create path pays
  nothing.
- Admin rebuild: `POST /api/v1/admin/search/sounds/reindex` — full
  Cassandra library walk; fine because the library is a bounded curated
  catalog ([indexing-and-reindex.md](indexing-and-reindex.md)).
