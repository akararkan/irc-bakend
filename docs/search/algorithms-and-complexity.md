# Search Algorithms & Time Complexity

The theory behind every search surface: what algorithm runs, why it was
chosen, and what it costs. Notation throughout:

- **n** — documents in an index · **t** — query terms
- **k** — page size · **m** — matching documents
- **|w|** — length of one term · **p** — offset page number

---

## 1. Elasticsearch relevance search (the main engine)

### 1.1 The inverted index

Every text field is analyzed (standard analyzer: Unicode word segmentation +
lowercasing) into terms, and ES maintains, per term, a **postings list** of
the documents containing it, stored in immutable segments. The term
dictionary is an **FST (finite-state transducer)** — term lookup is
`O(|w|)`, independent of vocabulary size. Postings lists are delta-encoded
in skip-list blocks, so intersecting/advancing through them is logarithmic
per seek, not linear.

**Query cost:** for t terms, top-k retrieval walks t postings lists with a
k-sized min-heap. With **Block-Max WAND** (the dynamic-pruning algorithm
Lucene applies to top-k scored queries), whole blocks whose *maximum
possible* score can't beat the current k-th score are skipped without
scoring — in practice sublinear in the postings length:

> **O(t · log n)** amortized per shard, **O(k · log k)** to produce the
> final page. Multi-index search (`irc-posts` + `irc-qna` + …) fans out to
> all shards **in parallel**, so wall-clock latency is the slowest shard,
> not the sum — searching 7 indices costs the same as searching one.

### 1.2 BM25 scoring

Each (term, doc) pair contributes:

```
score(q,d) = Σ_t  IDF(t) · TF(t,d)·(k₁+1) / (TF(t,d) + k₁·(1−b+b·|d|/avgdl))
```

with `k₁=1.2`, `b=0.75`. The two properties that matter here:

- **IDF** — rare terms dominate: in `"the zakat"`, `zakat` decides the
  ranking, `the` is noise.
- **TF saturation** — the k₁ denominator means the 10th occurrence of a
  term adds almost nothing; keyword-stuffed documents don't win.
- **Length normalization** (b) — a term match in a short title is worth
  more than in a long body, which is also why fields get explicit boosts
  (`title^4` vs `body^2`) on top.

Per-field weights and the full field list: [global-search.md](global-search.md).
`multi_match` in **BestFields** mode scores each field separately and takes
the best (+ `tieBreaker·0.3` × the rest) — the right semantics when a query
targets *one* of title/body/author, not all at once.

### 1.3 Typo tolerance — Levenshtein automata

`fuzziness=AUTO` (edit distance 1 for 3–5-char terms, 2 for 6+) does **not**
scan the vocabulary. ES compiles the query term into a **Levenshtein
automaton** and intersects it with the term-dictionary FST — enumerating
exactly the dictionary terms within edit distance, in
`O(|w|)`-per-candidate time. The matched variants then join the normal
postings traversal. Cost: a small constant factor over exact match —
**not** `O(vocabulary)`.

### 1.4 Phrase and prefix layers

- **`match_phrase` (boost 2.0)** — consults term *positions* within the
  postings to verify adjacency. Cost `O(occurrences)` for the candidate
  docs; only re-ranks (it's a `should` clause), never gates recall.
- **`match_phrase_prefix` (boost 1.5)** — the last token is expanded to up
  to 50 dictionary terms sharing that prefix (FST prefix walk, `O(|w|)`),
  giving as-you-type behavior with zero extra index structures (no
  edge-ngrams to store). The `usernameTokens`/`handleTokens` companion
  fields (handles pre-split on `._-` at *index* time) solve the one case
  prefix matching can't: matching an interior segment of `ahmad.rashid`.
- **`minimum_should_match=75%`** — on the recall clause, a 4-word query
  must match ≥3 terms: kills single-common-token noise without demanding
  perfection.

### 1.5 function_score — the ranking shape

`final = BM25 × Σ(functions)`, evaluated **only for candidate top-k docs**
(cost `O(k)`-ish, negligible):

- **Gaussian recency decay** `exp(−(age−1d)²/2σ²)` with half-strength at
  30 days — content indices only.
- **Constant 1.0 entity baseline** for users/channels/sounds/answers —
  long-lived entities must not live or die by recency.
- **`log1p(counter)` engagement boosts** — logarithmic so popularity
  breaks ties but can never outvote text relevance (a 1M-follower account
  adds ×~14 … log-scale … vs an exact-match BM25 swing of hundreds).
- **Flat +2.0 for accepted answers** — a step function, not a gradient:
  "the author chose this one" is categorical, not a quantity.

Details and the full function table: [global-search.md](global-search.md).

### 1.6 Pagination — `search_after` vs offset

| | Offset (`page=p`) | Cursor (`search_after`) |
|---|---|---|
| ES must materialize | `(p+1)·k` hits, discard `p·k` | exactly `k` hits |
| Cost per page | O(t·log n + (p+1)·k·log((p+1)·k)) | **O(t·log n + k)** |
| Deep pages | degrade linearly | constant |
| Concurrent inserts | shift/duplicate rows | stable — sort key `(score, _doc)` is a total order; a new doc can't move existing keys |

The cursor is base64 of the last hit's sort values — stateless on the
server (no scroll contexts to expire).

### 1.7 Index-write cost

One document upsert is `O(fields)` analysis + an append to the in-memory
segment buffer; segment merges amortize in the background. All app writes
are async ([indexing-and-reindex.md](indexing-and-reindex.md)), so request
paths pay **O(1)** (a task submission).

### 1.8 Response shaping — pay only for what leaves the node

Two costs scale with the *response*, not the index, and both are trimmed on
every search surface:

- **`track_total_hits: false`** wherever a total is never rendered (global,
  chat, sound search). Counting every match forces a full postings walk even
  when only k hits return — the count phase is exactly the part that defeats
  Block-Max WAND pruning, so skipping it keeps queries in the pruned regime.
- **`_source` filtering** — id-only on id→hydrate surfaces (chat messages,
  sounds), preview-fields-only on global search. A hit's body, keyword
  arrays and counters never cross the wire just to be discarded by the
  mapper.

Hydration after an id-ranked search is always **one batch read per store**
(Cassandra `IN`, Postgres `IN` — with the profile join-fetched for people
rows), never a point read per id: the id list is ≤ k, so hydration is
`O(k)` work at **O(1)** round-trips.

---

## 2. Postgres full-text people search

Surface: `GET /users/search` ([users.md](users.md)).

1. **GIN tsvector index** over `simple`-dictionary tokens of
   username‖fname‖lname‖bio. A `websearch_to_tsquery` lookup walks the GIN
   posting tree: **O(log n + m)**. `ts_rank_cd` (cover-density: rewards
   query terms appearing close together) scores only the m candidates, and
   the query is **hard-capped at the top 200** ranked ids — so worst-case
   latency is bounded no matter how common the terms. `O(m·log 200)` heap.
2. **pg_trgm fuzzy fallback** (FTS found nothing, query ≥3 chars): the
   query's trigram set probes a `gin_trgm_ops` index —
   **O(g · postings)** for g query trigrams — ranked by `similarity()`.
   This is what catches `ahmda` → `ahmad`.
3. **Prefix path** (query <3 chars — too short to form trigrams):
   `LIKE 'q%'` prefix ranking, username-prefix first.

Why `simple` and not a language dictionary: the corpus is trilingual
(EN/AR/CKB); stemming one language corrupts the others. Typo tolerance
comes from trigrams instead.

## 3. Cassandra usage rankings (tags & hashtags)

Not relevance search — exact counters and pre-ranked snapshots
([../platform/tags.md](../platform/tags.md)); listed here for the
complexity picture:

| Surface | Read shape | Cost |
|---|---|---|
| Trending tags | one pre-ranked partition, top-100 | **O(k)** — ranking paid by the 10-min rebuild job, not the read |
| Tag prefix autocomplete | clustering-key range scan `[prefix, prefix+0xFF)` within one partition | **O(log P + k)** via the partition's clustering index |
| Tag/hashtag content feeds | one partition per tag, clustered by time, cursor-paged | **O(log P + k)** per page |

## 4. Fallback scans (bounded by construction)

Every LIKE/scan fallback in the system has an explicit ceiling, so "ES is
down" degrades latency by a bounded constant, never unboundedly:

| Fallback | Bound |
|---|---|
| Channel discover Postgres `LIKE '%q%'` | all public channels scanned, **top-50** returned (O(n_channels) — acceptable: channels are a small corpus) |
| In-conversation chat Cassandra scan | **≤24 time-buckets AND ≤3000 rows AND ≤limit hits**, newest-first |
| Cross-conversation chat search | **no fallback by design** — an all-rooms scan would be unbounded; returns `[]` |
| Topic/madhhab lookup | the whole table *is* the bound (≈dozens of rows → **O(1)**) |

## 5. Why not X?

- **Postgres FTS for everything?** No BM25 (`ts_rank` is TF-proximity
  only, no IDF), no per-field function_score, and every search would load
  the primary OLTP database. Kept only where transactional freshness
  beats ranking quality (people picker).
- **Cassandra SAI / `LIKE`?** Cassandra can *find* rows, not *rank* them —
  no scoring exists. It keeps the jobs where exact keys and counters win.
- **Edge-ngram indexes for typeahead?** 3–5× index size for what
  `match_phrase_prefix` + the pre-split token fields already deliver at
  ~zero storage cost. Revisit only if prefix-expansion latency (max 50
  expansions) ever shows up in profiles.
- **Trigram similarity in ES (`fuzzy` everywhere)?** Fuzziness AUTO already
  covers 1–2 edits at bounded cost; unbounded similarity search belongs in
  pg_trgm where it's a fallback, not the hot path.
