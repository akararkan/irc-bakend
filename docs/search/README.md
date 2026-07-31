# Search — the master reference

Every text-search surface on the platform, in one directory: the unified
global search, every per-entity search API, the write-side indexing
pipeline, and the algorithms + time complexity behind each mechanism.

**Coverage guarantee:** every user-facing content entity either has a
search surface documented here, or its exclusion is deliberate and
documented in [coverage.md](coverage.md).

## Files

| File | Covers |
|---|---|
| [global-search.md](global-search.md) | `GET /api/v1/search` — one query over **8 entity types** (posts, reels, questions, answers, research, users, channels, sounds); ranking model, cursor paging, privacy filters |
| [users.md](users.md) | People search: global `types=USER` + the dedicated Postgres FTS `GET /users/search` + mention autocomplete |
| [channels.md](channels.md) | Public-channel discovery: `GET /channels/discover` (ES-ranked), by-handle lookup, global `types=CHANNEL` |
| [qna.md](qna.md) | Question **and answer** search: `types=QUESTION` / `types=ANSWER`, accepted-answer boost, `parentId` deep-linking |
| [sounds.md](sounds.md) | Sound-library search: `GET /sounds/search` + global `types=SOUND` |
| [knowledge.md](knowledge.md) | Topic / madhhab taxonomy lookup: `GET /topics?q=`, `GET /madhhabs?q=` |
| [indexing-and-reindex.md](indexing-and-reindex.md) | The write side: all 8 ES indices, per-index document fields, async best-effort semantics, the 7 admin reindex endpoints, mapping-repair |
| [algorithms-and-complexity.md](algorithms-and-complexity.md) | **The algorithms**: BM25, function_score, fuzzy/phrase/prefix matching, `search_after`, Postgres FTS/trigram, Cassandra prefix scans — with per-operation time complexity |
| [coverage.md](coverage.md) | The entity-by-entity coverage matrix, incl. deliberate exclusions (stories, notifications, …) |

Surfaces whose canonical reference lives elsewhere (linked, not duplicated):

- **Chat message search** — [../chat/search.md](../chat/search.md)
  (membership-scoped ES over `irc-chat-messages`, bounded Cassandra fallback).
- **Tag autocomplete / trending / tag feeds** — [../platform/tags.md](../platform/tags.md)
  (usage-based Cassandra counters; *not* relevance search).
- **@mention suggestions** — [../platform/mentions.md](../platform/mentions.md)
  (Postgres prefix + trigram typeahead).

## Architecture in one page

Three storage engines, each doing the job it is fastest at:

| Engine | Job | Surfaces |
|---|---|---|
| **Elasticsearch** (8 `irc-*` indices) | Relevance ranking — BM25 with typo tolerance, phrase/prefix boosts, recency decay, engagement signals | Global search, channel discover, sound search, chat search |
| **Postgres FTS** (GIN tsvector + pg_trgm) | Exact-store people search with fuzzy fallbacks; transactional consistency | `/users/search`, mention suggest |
| **Cassandra** (counter + clustering tables) | Usage-based rankings and exact-key feeds at write-heavy scale | Trending tags, tag feeds, tag prefix autocomplete, hashtag feeds |

Rules of thumb encoded in that split:

1. **"What matches these words?" → Elasticsearch.** Nothing else BM25-ranks.
2. **"What's popular right now?" → Cassandra counters.** Exact counts,
   pre-ranked snapshots, no scoring at read time.
3. **"Find this person" → both.** The dedicated people search stays on
   Postgres (zero indexing lag, transactional with the account row); the
   global search bar uses the `irc-users` ES index so people rank against
   content in one score scale.

**Write side:** each domain service re-indexes its own entity on every
mutation — async, best-effort, never blocking the request path
(see [indexing-and-reindex.md](indexing-and-reindex.md)). The canonical
store is always Postgres/Cassandra; Elasticsearch is a rebuildable
projection. Search is eventually consistent — typically under a second
behind a write (ES refreshes its segment readers every 1 s).

**Degraded mode:** if ES is down, `GET /api/v1/search` returns
`200 {results: [], degraded: true}` (+ `X-Search-Degraded: true` header)
instead of 5xx; channel discover falls back to a bounded Postgres scan;
in-conversation chat search falls back to a bounded Cassandra scan. A
broken search box never takes the page down with it.

## Time complexity at a glance

Full derivations in [algorithms-and-complexity.md](algorithms-and-complexity.md).
*n* = documents in the index, *t* = query terms, *k* = page size.

| Operation | Mechanism | Complexity |
|---|---|---|
| Global search (any types) | ES BM25 + function_score, top-k heap | **O(t · log n)** amortized per shard, indices searched in parallel |
| Cursor page (`search_after`) | ES seek past sort key | **O(t · log n + k)** — constant per page, any depth |
| Offset page (`page=p`) | ES collect-and-discard | O(t · log n + (p+1)·k · log((p+1)·k)) — degrades with depth |
| Typo tolerance | Levenshtein automaton ∩ term FST | O(t · \|term\|) — bounded, edit distance ≤ 2 |
| `/users/search` (FTS) | Postgres GIN posting tree, capped top-200 | **O(log n + m)**, m = matches, rank cost capped |
| `/users/search` (fuzzy fallback) | pg_trgm GIN similarity | O(g · postings), g = query trigrams |
| Channel discover (ES path) | BM25 × log1p(subscribers) | **O(t · log n)** |
| Channel discover (fallback) | Postgres `LIKE '%q%'` scan | O(n_channels), bounded to top-50 by subscribers |
| Sound search | ES BM25 × log1p(useCount) | **O(t · log n)** + k Cassandra point-reads (O(1) each) |
| Tag autocomplete | Cassandra clustering range scan | **O(log P + k)** within one partition, P = tags in scope |
| Chat search (ES) | BM25, membership `terms` filter | **O(t · log n)** |
| Chat search (fallback) | Bounded partition scan | O(min(3000 rows, 24 buckets)) — hard-capped |
| Topic/madhhab lookup | In-memory filter over tiny table | O(rows) with rows ≈ dozens → effectively **O(1)** |
| Index write (any entity) | ES doc upsert, async | O(fields) off the request path |
