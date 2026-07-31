# Home Feed Ranking Algorithm — Specification

The feed is computed per request in four stages. Everything is deterministic
counter math — no ML infrastructure required — but the shape (candidate
generation → ranking → safety → diversity) is exactly the multi-stage
architecture the large platforms run, so a learned ranker can replace the
formula later without touching the pipeline.

---

## Stage 1 — Candidate generation

All sources are fetched **in parallel** on the bounded `taskExecutor`
(core 8 / max 32 / queue 10k / CallerRunsPolicy). Any source failing
degrades to an empty list — the feed never 500s because one source is down.

### 1a. Social-graph timeline (every page)

The viewer's `feed_by_user` partition, chronological keyset window of
`pageSize` rows (`created_at < cursor`), merged with the viewer's own
`posts_by_author` rows and deduped — identical to the pre-ranking read.
Contains POST / RESEARCH / QUESTION rows (discriminated by `entity_type`).

### 1b. Channel digest (first page only)

`ChannelFeedCandidateService.recentChannelPosts(viewer)`:

1. Subscribed channels from Postgres (`findMySubscribedChannels`), pruned to
   those whose `last_message_at` falls inside the **48 h lookback**, sorted
   by last activity, capped at **15 channels**.
2. Per surviving channel (parallel): one single-partition slice of
   `messages_by_conversation` in the current `ChatBuckets` bucket (stepping
   back at most one bucket when the window spans a boundary). Deleted /
   system rows skipped; up to **5 posts per channel**.
3. One bulk `message_counters` read for all candidates
   (views / forwards / comments).

After scoring, only the **top 8** channel posts survive — a chatty channel
cannot out-volume the social graph.

### 1c. Exploration slice (first page only)

Engagement-ranked recent reels (`ReelFeedService.forYouReels` pool) filtered
to authors the viewer does **not** follow, minus already-shown posts:

- normal quota: `clamp(pageSize / 10, 1, 3)` items (~5–10 %, the classic
  exploitation/exploration split);
- **cold start** (empty timeline, no cursor): quota expands to the full
  page — a brand-new user gets trending content instead of an empty screen.

### 1d. Live rail (first page only)

Not item candidates — a separate `liveNow` array:

1. `LiveStreamService.listFollowingLive(viewer)` — followed hosts,
   most-recently-started first;
2. topped up to **10** with `listLive()` (most-watched public streams);
3. blocked hosts removed.

---

## Stage 2 — Safety filtering

- **Blocks**: one `findBlockedAmong(viewer, pageAuthorIds)` round-trip; any
  item whose author has a block relationship with the viewer (either
  direction) is dropped. This also closes the pre-existing gap where fanout
  rows written before a block kept surfacing.
- **Deletions**: hydration drops POST rows whose canonical `posts_by_id` row
  is gone (unchanged).
- Moderation states, mute lists and per-author feed hiding are future hooks;
  they belong in this stage.

---

## Stage 3 — Scoring

Per candidate:

```
E  = 3·ln(1+likes) + 4·ln(1+comments) + 5·ln(1+shares) + 4·ln(1+saves) + 0.5·ln(1+views)
A  = 2·ln(1 + affinity(viewer, author))
F  = e^(−ageHours / halfLife)
B  = boost multiplier (below)

score = (1 + E + A) · F · B
```

### Engagement `E` — weighted, log-damped counters

| Signal | Weight | Why |
|---|---|---|
| comments | 4 | strong intent (typing effort) |
| shares / forwards | 5 | strongest distribution signal |
| saves | 4 | "worth returning to" |
| likes | 3 | cheap positive signal |
| views | 0.5 | weak, high-volume signal |

`ln(1+x)` damping means engagement grows the score **sub-linearly** — a
100k-like viral post beats a 100-like post, but not by 1000×, so fresh and
relevant content still competes. Channel posts reuse the same formula with
their own counters mapped in (views→viewCount, forwards→shareCount,
comments→commentCount). RESEARCH / QUESTION rows currently carry zero
counters at feed-read time (cross-domain hydration is intentionally not
done) and therefore rank on freshness + affinity + relationship — a known,
accepted approximation.

### Affinity `A` — the interest graph

`user_author_affinity` is a Cassandra counter table: one weighted counter
per (viewer, author) pair, incremented asynchronously on every positive
engagement:

| Action | Increment |
|---|---|
| view (deduped) | +1 |
| like | +3 |
| save | +4 |
| comment / reply | +5 |
| share | +5 |

Written best-effort off the request thread by `AuthorAffinityService` from
the reaction/save/comment/share/view services. Never decremented (unlike ≠
un-interest; affinity models long-term demonstrated interest).
Self-engagement is skipped. Read cost: one single-partition slice with a
clustering `IN` per feed page.

This is what turns the feed from a pure social graph into a **hybrid
social + interest graph**: two accounts you follow equally often are no
longer tied — the one whose content you actually engage with wins.

### Freshness `F` — per-type exponential decay

| Content type | Half-life |
|---|---|
| POST | 24 h |
| REEL | 48 h |
| CHANNEL_POST | 18 h |
| QUESTION | 48 h |
| RESEARCH | 72 h |

Old content decays smoothly toward zero but never re-orders *across* pages
(ranking is within the chronological window — see Pagination).

### Boosts `B`

| Condition | Multiplier |
|---|---|
| mutual follow (author follows viewer AND viewer follows author) | ×1.25 |
| viewer's own post | ×1.05 |
| `CHANNEL` source | ×0.90 |
| `EXPLORE` source | ×0.85 |

Injected discovery content is deliberately damped: it must *earn* a slot
with engagement, never displace the social graph by default.

---

## Stage 4 — Diversity re-ranking

Greedy pass over the score-sorted list: a candidate is deferred while its
author (channel posts group by channel id) already occupies the last **2**
emitted slots. If every remaining candidate violates the cap (single-author
feed), the best one is emitted anyway — the rule shapes, it never drops.

---

## Pagination

**Invariant: rank order never leaks into pagination.**

- Ranking happens strictly **within** the chronological window fetched for
  the page; `nextCursor` = the raw window's oldest `created_at` (computed
  *before* hydration drops), so every timeline row is delivered exactly
  once — no loss, no loops, fully stateless.
- Channel / explore / live injections happen on the **first page only** and
  never advance the cursor, so they can't cause skips.
- **Legacy tail-pin** (`/feed` array shape only): pre-ranking clients derive
  the cursor from the last array element's `createdAt`. The ranked page
  therefore moves the chronologically-oldest timeline item to the array
  tail. Cost: one item out of rank order at the bottom of the page.
  `/feed/home` returns an explicit `nextCursor` instead and applies no pin.

---

## Cost per request (first page, defaults)

| Work | Round-trips |
|---|---|
| timeline window | 2 Cassandra (feed + own posts) |
| hydration | ~5 bulk (authors PG, posts, counters, liked, saved) |
| channel digest | 1 PG + ≤15 parallel Cassandra slices + 1 bulk counters |
| explore | reuse of reels day-buckets + 1 bulk counters + hydration |
| live rail | 1–2 PG |
| block filter | 1 PG |
| affinity | 1 Cassandra (single partition) |
| mutual follows | 1 PG |

Cursor pages skip the channel/explore/live work entirely.

---

## Future work (explicitly out of scope now)

- Per-user negative feedback ("show less like this", hide author) as a
  Stage-2 input.
- Restrict-relationship downranking.
- Watch-time / dwell-time capture (would replace the view weight).
- Counter hydration for RESEARCH / QUESTION feed rows.
- Affinity decay (e.g. periodic halving job) if long-lived accounts show
  stale-interest lock-in.
- Fanout-on-write for channel posts (entity_type `CHANNEL_POST` in
  `feed_by_user`) if the read-time digest becomes hot.
- Replacing the formula with a learned ranker fed by the same signals.
