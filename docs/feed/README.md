# Home Feed — Ranked Recommendation System

The home feed is a **multi-stage recommendation pipeline**, modeled on the
architecture used by modern platforms (Facebook / Instagram / TikTok):

```
Content Sources → Candidate Generation → Safety Filtering
              → Scoring (engagement + affinity + freshness)
              → Diversity Re-ranking → Final Feed Delivery
```

It replaces the previous purely-chronological read of `feed_by_user` while
keeping every operational property of that design: fanout-on-write, stateless
cursor pagination, bounded per-request cost, circuit-breaker resilience.

**This directory is the canonical feed documentation.**

| Doc | Contents |
|---|---|
| [algorithm.md](./algorithm.md) | The full ranking specification — candidate sources, score formula, weights, half-lives, boosts, diversity rules, pagination proof |
| [api-reference.md](./api-reference.md) | Every feed-surface API analyzed — home feed (v1 + v2), live rail, reels, profile feed, suggestions, and the engagement endpoints that produce ranking signals |
| [frontend-guide.md](./frontend-guide.md) | How a client renders the ranked feed: live rail, channel cards, "Suggested for you" labels, pagination contract |

Older, pre-ranking docs live in [`docs/post/feed.md`](../post/feed.md)
(fanout internals — still accurate for the write path) and `docs/_legacy/`.

---

## What's in the feed now

One ranked stream, four candidate sources:

1. **Social graph (`FOLLOWING` / `SELF`)** — posts, research and questions
   fanned out from followed accounts into `feed_by_user` (unchanged write
   path), plus the viewer's own posts merged at read time.
2. **Channels (`CHANNEL`)** — fresh posts (last 48 h) from the broadcast
   channels the viewer subscribes to, fetched read-time (no fanout writes),
   scored with their views/forwards/comments, capped at 8 per first page.
3. **Exploration (`EXPLORE`)** — ~5–10 % trending reels from authors the
   viewer does *not* follow (interest-graph discovery). Expands to a full
   page for cold-start users with an empty timeline.
4. **Live streams** — not feed items but a dedicated `liveNow` rail
   (followed hosts first, topped up with the most-watched public streams),
   returned beside the items on the first page.

## Key components

| Component | File | Role |
|---|---|---|
| `HomeFeedService` | `app/post/cassandra/service/HomeFeedService.java` | Pipeline orchestrator (candidates → filter → score → diversify) |
| `FeedRankingService` | `app/post/cassandra/service/FeedRankingService.java` | Score formula + diversity re-ranker |
| `AuthorAffinityService` | `app/post/cassandra/service/AuthorAffinityService.java` | Interest-graph accumulator (`user_author_affinity` counter table) |
| `ChannelFeedCandidateService` | `app/chat/service/ChannelFeedCandidateService.java` | Channel-digest candidate generation |
| `FeedTimelineService` | `app/post/cassandra/service/FeedTimelineService.java` | Fanout-on-write + chronological window reads (unchanged) |
| `PostHydrator` | `app/post/cassandra/service/PostHydrator.java` | Bulk hydration (authors, counters, liked/saved flags) |
| `HomeFeedSources` | `app/post/cassandra/service/HomeFeedSources.java` | `FOLLOWING · SELF · CHANNEL · EXPLORE` provenance labels |

## Storage

| Table | Store | Purpose |
|---|---|---|
| `feed_by_user` | Cassandra | Fanned-out timeline (30-day TTL) — unchanged |
| `post_counters` | Cassandra | Engagement signal source (likes/comments/shares/saves/views) |
| `user_author_affinity` | Cassandra (counter) | **New** — weighted (viewer → author) interest counter |
| `message_counters` + Redis HLL | Cassandra/Redis | Channel-post views/forwards/comments |
| `live_streams` | Postgres | Live rail source (`status = LIVE`) |

## Safety fixes shipped with the ranker

- **Block filtering at read time** — previously the home feed served raw
  fanout rows; rows written *before* a block (and global explore candidates)
  could leak content from blocked users. Every ranked page now drops items
  whose author has any block relationship with the viewer, and the live rail
  drops blocked hosts.
- Deleted posts were already dropped at hydration; that behavior is kept.

## Compatibility

- `GET /api/v1/posts/feed` keeps its **bare-array** response shape; ranking
  fields (`source`, `rankScore`, `channel`, `channelPostId`) are additive
  and nullable. `?ranked=false` restores the exact pre-ranking behavior.
- Legacy cursor derivation (last array element's `createdAt`) keeps working
  — see the tail-pin rule in [algorithm.md](./algorithm.md#pagination).
- New clients should use `GET /api/v1/posts/feed/home` (items + `liveNow` +
  explicit `nextCursor`).
