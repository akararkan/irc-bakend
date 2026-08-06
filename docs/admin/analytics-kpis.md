# 10 — Analytics & KPIs

The platform measurement plan: the KPI tree, per-module metric tables with
**honest sourcing** (what an existing counter/table computes *today* vs what
needs new collection), the unified event-pipeline proposal that closes the gap,
and the Analytics section of the admin dashboard itself.

Tags per [README.md](README.md): **[EXISTS]** real today (class/endpoint cited) ·
**[PARTIAL]** raw data exists, the aggregate query/endpoint must be written ·
**[PLANNED]** requires new collection (the event pipeline of §6).

Related: [logs-audit.md](logs-audit.md) (log catalog), [operations.md](operations.md)
(infra health metrics — *not* product analytics), [admin-api-blueprint.md](admin-api-blueprint.md)
(endpoint registry), [users-roles.md](users-roles.md) / [safety-reports.md](safety-reports.md)
(module drilldowns), [../feed/](../feed/) & [../search/](../search/) (underlying mechanics).

---

## 1. Purpose & scope

| In scope | Out of scope |
|----------|--------------|
| Product analytics: activity, growth, engagement, retention, funnels, per-module KPIs | Infra health (CPU, queue depth, SSE fan-out) → [operations.md](operations.md) |
| The Overview page + per-module drilldowns of the dashboard's Analytics section | Moderation queues/decision metrics → [safety-reports.md](safety-reports.md), [content-moderation.md](content-moderation.md) |
| The `analytics_events` collection pipeline, rollup jobs, and query API **[EXISTS (built 2026-08)]** | Per-creator "insights" for end users (future product feature; this doc's pipeline is its prerequisite) |
| Anomaly alerting on daily metric series **[EXISTS (built 2026-08)]** | Billing/monetization (gifts are symbolic — no wallet exists) |

**The single most important honest statement in this doc** (pre-2026-08
baseline): the platform had rich *lifetime, per-entity* counters and
*per-user, private* activity logs, but **no date-bucketed, platform-wide
metric store**. **Since the 2026-08 build the §6 pipeline exists**
(`analytics_events`, `analytics_metric_daily`/`analytics_dau_by_day`/
`analytics_metric_rollup`, rollup jobs) — DAU/MAU, retention, funnels, and
daily series accrue **from deployment forward**; history before then remains
uncomputable. Postgres-backed domains (research, QnA, chat metadata, live,
media, users) are additionally aggregable with plain SQL.

## 2. Measurement reality today

| Class of question | Answerable today? | Why |
|-------------------|-------------------|-----|
| "How many likes does post X have?" | **Yes [EXISTS]** | `post_counters` point read via `PostCounterRepository` / `CounterCache` |
| "Total platform likes / views / posts?" | **No** | Cassandra counter tables are point-readable per id; full-table scans are forbidden by schema design (recon: "no scan index") |
| "Signups per day?" | **Query away [PARTIAL]** | `users.created_at` exists (`BaseAuditEntity`); no endpoint aggregates it |
| "DAU yesterday?" | **Yes [EXISTS (built 2026-08)]** — from deployment forward | `analytics_dau_by_day` (PK-dedup upsert via `MetricDailyService.markActive`); `login_events` writer is wired from `AuthServiceImpl` (see [logs-audit.md](logs-audit.md)) |
| "Research downloads this week?" | **Query away [PARTIAL]** | PG `research_downloads` rows carry `created_at` (written by `ResearchAnalyticsConsumer`) |
| "Story views last month?" | **Never (data is gone)** | `story_views_by_story` rows carry the story's 24h TTL — historical story analytics evaporate by design |
| "Top search queries?" | **No [PLANNED]** | Queries logged only in per-user `activity_by_user` (`GLOBAL_SEARCH` with `query` + `hit_count`); no global index, and scanning per-user partitions platform-wide is impractical |
| "Total reel watch-time?" | **No [PLANNED]** | `watched_seconds` exists only in per-user `reel_views_by_user`; never aggregated per-reel or platform-wide |

## 3. Existing signal inventory (the raw material)

### 3.1 Redis hot counter cache — `common/cache/CounterCache` [EXISTS]

One Redis hash per entity, write-through mirror of the DB truth (30-day idle
TTL, pipelined `getMany`, fail-open). **Mirror only — never an analytics source
of record**, but the field codes below are the de-facto metric vocabulary:

| Kind | Key prefix | Fields used |
|------|-----------|-------------|
| POST | `c:p:` | `rx` reactions, `cm` comments, `vw` views, `sh` shares, `sv` saves |
| POST_COMMENT | `c:pc:` | `rx`, `rp` replies |
| RESEARCH | `c:r:` | `rx`, `cm`, `vw`, `sh`, `sv`, `dl` downloads, `ct` citations |
| RESEARCH_COMMENT | `c:rc:` | `rx`, `rp` |
| QUESTION | `c:q:` | `an` answers, `vw`, `rx`, `bv` |
| ANSWER | `c:a:` | `rx` |

### 3.2 Cassandra counter tables (durable per-entity truth) [EXISTS]

All written via `post/cassandra/service/CounterService`; all **point-read only**:

| Table | Key | Counters | Notes |
|-------|-----|----------|-------|
| `post_counters` | post_id | reaction/comment/share/view/save | Bulk-hydrated per feed page (`PostHydrator` IN-clause) |
| `comment_counters` | comment_id | reaction, reply | Hard-delete adjusts by `1+replyCount` |
| `sound_counters` | sound_id | use_count | Surfaced at `GET /api/v1/sounds/{id}/usage`; ES popularity boost. **Uses, not plays** — plays are tracked nowhere |
| `hashtag_counters` | hashtag | post_count | Legacy; superseded by unified tags below |
| `tag_counters` | (scope, tag) | usage_count | Scopes ALL/QUESTION/RESEARCH/POST/REEL; whole scope readable in one partition (`findByScope`) — **the one Cassandra counter family that IS aggregable** |
| `trending_tags` | (scope, rank) | usage snapshot | Top-100/scope, rebuilt every 10 min by `TrendingTagJob`; read at `GET /api/v1/tags/trending` |
| `poll_counters` | poll_id | vote_a, vote_b | No LWT — accepted double-vote skew |
| `notification_unread_counter` | user_id | unread | Badge only |
| `message_counters` | message_id | views, forwards, comments | Channel-post metrics (source of truth under the Redis aggregates) |
| `user_author_affinity` | (user, author) | interactions | Weights view +1 / like +3 / save +4 / comment +5 / share +5 (`AuthorAffinityService`); feeds ranking + PYMK, **no reverse index** |

### 3.3 Event-shaped Cassandra logs [EXISTS]

| Table | Grain | Analytics value | Caveat |
|-------|-------|-----------------|--------|
| `activity_by_user` (+`_and_type`, `activity_lookup`) | ~30 `UserActivityType` events incl. searches with `hit_count`, `PROFILE_VIEW`, `SOUND_USED` | Richest behavioral store in the system | Partitioned **per user**, user-deletable, private — unusable for global analytics without a parallel fan-in writer (that writer is §6) |
| `views_by_post` / research view rows | unique viewer + `first_viewed_at` | Reconciliation source for view counters | No date bucketing across posts |
| `reel_views_by_user` | watch event + `watched_seconds` | Only watch-time signal on the platform | Per-user partitions; never rolled up |
| `research_downloads_by_research` | download + user + media + IP | Per-research download log (`recentDownloads`) | "Recent N" reads only; PG twin is the aggregable copy |
| `shares_by_post` | share events | Backs share counter | No global scan |
| `story_views_by_story` | viewer rows | Author-facing viewer list | **TTL 24h — evaporates** |

### 3.4 Postgres aggregable columns/tables [EXISTS data → PARTIAL aggregates]

Recon-confirmed "computable today with only new queries":

| Source | Aggregates it can answer |
|--------|--------------------------|
| `research.{view,download,reaction,comment,save,share,citation}_count` | Research engagement totals (SQL `SUM`) |
| `questions.{answer_count,view_count,accepted_answer_count}` | QnA totals, accept rate |
| `conversations.{member_count,post_count,type}` | Chat/channel structural totals |
| `user_follows` | Graph size, follows/day (`created_at`) |
| `users`, `user_profiles`, `account_deletion_requests`, `deleted_accounts` | Signups/day, role mix, deletion pipeline |
| `media_assets.stored_bytes` (+ `status`, `media_type`) | Per-user footprint **[EXISTS]** via `StorageUsageService` (`sumStoredBytes`, Redis-cached 1h); platform total **[EXISTS (built 2026-08)]** — `MediaAssetRepository.sumStoredBytesPlatform()` feeds the `storageBytes` overview tile |
| `live_streams.{viewer_count,peak_viewer_count}`, `stream_viewers.{joined_at,left_at}` | Streams/day, peaks, per-session watch-time (left_at null on crash — caveat) |
| `stream_gift_tallies` | Coin totals per stream/user; platform `SUM` [PARTIAL] |
| `call_sessions` / `call_participants` | Call volume, duration, missed rate |
| `notifications` (type, created_at) | Delivery volume by type — **skewed by the 90-day read-purge** (`NotificationCleanupJob`) |
| `reports`, `user_strikes` | Safety intake volume ([safety-reports.md](safety-reports.md)) |

### 3.5 Stat services already computing aggregates [EXISTS]

| Service | What it computes | Access today |
|---------|------------------|--------------|
| `ChannelStatsService` → `GET /api/v1/channels/{id}/stats` | members, onlineNow, joined7d/30d, left30d, muted, postsByType, totalViews/Forwards (Redis `chat:chtotals:`), joinsByDay(30d), joinsBySource, top-10 posts | **Channel owner/admin only — a platform ADMIN who isn't a member is refused**; Redis aggregates silently reset on flush |
| `UserStatsService.statsFor` | Live per-user counts: posts/reels (Cassandra count), published research, questions, followers/following; Redis-cached 30s | Public profile stats (the stale `user_profiles` denormalized counters are dead — always 0) |
| `ChannelPostMetricsService` | HLL-deduped per-post channel views (`chat:viewers:{id}`), running channel totals | Feeds channel stats |
| `StorageUsageService` | Per-user stored bytes by type | Settings surface |
| `AuditLoggingInterceptor` → `duration_ms` on every audited request | Latency signal per request | Queryable only per user via the audit API; never aggregated |

## 4. KPI tree

### 4.1 North Star **[PLANNED]** — WALC: Weekly Active Learners & Contributors

*Definition:* distinct users who, in a trailing 7-day UTC window, performed at
least one **learning action** (research view ≥30s / research download / question
view with answer scroll / search with result click / save of any entity) **or**
one **contribution action** (published post, reel, story, question, answer,
research, or comment). Chosen over raw WAU because IRC is an *academic* network
— lurk-free learning and contribution are the mission, not sessions.

*Source:* only the §6 event pipeline can compute the learning half. The
contribution half is **[PARTIAL]** early: distinct authors/week from PG
(`questions`, `question_answers`, `research`, comments) — Cassandra posts
excluded until events land. Chart: weekly line + WoW delta tile.

### 4.2 Level-1 KPIs

| KPI | Definition | Source | Chart | Status |
|-----|------------|--------|-------|--------|
| DAU | Distinct authenticated users emitting ≥1 event per UTC day | `analytics_dau_by_day` (PK-dedup upsert, `MetricDailyService.markActive` → `dauSeries`); `dauToday` overview tile | Line (90d) + tile | **[EXISTS (built 2026-08)]** |
| WAU / MAU | Distinct actives, trailing 7d / 30d | `MetricDailyService.distinctActiveOver(n)` union over `analytics_dau_by_day`; `mau30d` (30 d distinct) + `onlineNow` tiles in `/overview` | Line | **[EXISTS (built 2026-08)]** |
| Stickiness | DAU ÷ MAU | Derived | Line + tile | **[PLANNED]** |
| New signups | `users` rows created per day | PG `users.created_at` | Bar | **[PARTIAL]** |
| Login success / fail / new-IP | Per-day login outcomes | `login_events` — writer **wired** (`AuthServiceImpl` calls `record(...)` / `recordSuccessAndAlertIfNew`); `login.success` / `login.failed` daily series served by `GET /api/v1/admin/analytics/engagement` | Stacked bar | **[EXISTS (built 2026-08)]** |
| Content created | posts+reels+stories+questions+answers+research per day | PG halves [PARTIAL]; posts/stories need events [PLANNED]; **exception: reels are day-partitioned in `reels_by_day` and countable per day today [PARTIAL]** | Stacked area by type | mixed |
| Engagement actions | reactions+comments+shares+saves per day | **[PLANNED]** — lifetime counters can't be diffed retroactively | Stacked area | **[PLANNED]** |

### 4.3 Activation funnel **[EXISTS (built 2026-08)]** — `FunnelTracker` + `user_first_events` + `GET /api/v1/admin/analytics/funnel`

`signup → profile completed → first follow → first post-or-question`

| Step | Definition | Interim source (SQL, per signup cohort) | Full source |
|------|------------|------------------------------------------|-------------|
| Signup | `users` row created | `users.created_at` [PARTIAL] | `user.signup` event |
| Profile completed | avatar OR bio OR displayName set | `user_profiles` join [PARTIAL] (no *timestamp* of completion — state only) | `profile.completed` event |
| First follow | first `user_follows` row as follower | `MIN(created_at)` per user [PARTIAL] | `follow.create` |
| First post/question | first authored content | questions via PG [PARTIAL]; posts need events | `post.create` / `qna.question_create` |

Rendered as a funnel bar per monthly cohort + median time-between-steps table.
Maintained incrementally in `user_first_events` via `FunnelTracker`
(first-seen / profile-completed / first-follow / first-content hooks) so the
funnel is a read, not a scan.

### 4.4 Retention cohorts **[EXISTS (built 2026-08)]**

Weekly signup cohorts × weeks-since-signup, cell = % of cohort active that week
(active = any event). Triangle heatmap, 12 cohorts × 12 weeks default. Backed
by `cohort_retention_weekly`, maintained by the weekly cohort job (Mon 03:10
UTC) over `analytics_dau_by_day` actor sets, served at
`GET /api/v1/admin/analytics/retention`.

## 5. Per-module KPI tables

Column key — **Source** cites what computes it *today* or what will;
**Chart** follows dashboard conventions (§7.3).

### 5.1 Users

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Signups/day | New `users` rows | PG `users.created_at` | Bar | **[PARTIAL]** |
| Role distribution | Count by USER/RESEARCHER/SCHOLAR/ADMIN | PG `users.role` | Donut/tile row | **[PARTIAL]** |
| Follows/day | New `user_follows` edges | PG `created_at` | Line | **[PARTIAL]** |
| Deletion funnel | requested / cancelled / purged per week | `account_deletion_requests.status` + `deleted_accounts` | Stacked bar | **[PARTIAL]** |
| Profile stats reads | posts/research/questions/followers per user | `UserStatsService.statsFor` **[EXISTS]** (per-user, live) | Drilldown table | **[EXISTS]** |
| D7 / D28 retention | Cohort % active | §4.4 | Heatmap | **[PLANNED]** |

### 5.2 Content (posts / reels / stories / sounds)

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Posts/day | New posts (non-reel) | No day index in Cassandra → `post.create` events | Bar | **[PLANNED]** |
| Reels/day | New reels | `reels_by_day` partition count (`ReelsByDayEntity`) | Bar | **[PARTIAL]** |
| Stories/day + completion | Posted stories; view-through | Rows TTL out; `story.post`/`story.view` events are the only route | Bar + line | **[PLANNED]** |
| Per-post engagement | Lifetime rx/cm/sh/vw/sv for an inspected post | `post_counters` point read **[EXISTS]** | Entity drilldown | **[EXISTS]** |
| Platform engagement/day | Daily reaction/comment/share/save totals | events | Stacked area | **[PLANNED]** |
| Reel watch-time | Total + per-reel `watched_seconds` | today per-user only (`reel_views_by_user`); `reel.watch` events aggregate it | Line | **[PLANNED]** |
| Sound uses | `use_count` per sound; top sounds | `sound_counters` **[EXISTS]** (point read; "top" needs a rollup) | Table | **[PARTIAL]** |
| Sound plays/day | Playbacks (≠ uses) | Tracked nowhere — `sound.play` event | Line | **[PLANNED]** |
| Trending tags | Top-100 per scope | `trending_tags` + `GET /api/v1/tags/trending` **[EXISTS]** | Table | **[EXISTS]** |

### 5.3 Chat (DMs/groups — metadata only, see §12)

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Messages/day | Count only, never content | Cassandra log is bucketed per conversation (no global scan) → `chat.message_send` event (metadata) | Line | **[PLANNED]** |
| Conversations created/day by type | DIRECT/GROUP/CHANNEL | PG `conversations.created_at, type` | Stacked bar | **[PARTIAL]** |
| Message-request funnel | pending→accepted/declined/blocked per week | PG `message_requests.status` | Stacked bar | **[PARTIAL]** |
| Calls/day + avg duration + missed % | From signaling metadata | PG `call_sessions` (`answered_at`/`ended_at`, status MISSED) | Line + tile | **[PARTIAL]** |
| Disappearing-mode adoption | % conversations with `disappearing_seconds > 0` | PG `conversations` | Tile | **[PARTIAL]** |

### 5.4 Channels

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Channel count / verified count | Live CHANNEL conversations | PG `conversations` (`type`, `verified`, `deleted_at is null`) | Tiles | **[PARTIAL]** |
| Per-channel stats | members, online, joins 7/30d, views, top posts | `ChannelStatsService` **[EXISTS]** — but owner/admin-gated; the dashboard needs the platform-admin override proposed in [chat-channels-live.md](chat-channels-live.md) | Drilldown page | **[PARTIAL]** |
| Channel post views/forwards per day | Daily deltas | Redis totals are lifetime + loss-tolerant → `channel.post_view` events | Line | **[PLANNED]** |
| Joins by source (platform-wide) | invite/search/share/… | PG `conversation_members.join_source` | Stacked bar | **[PARTIAL]** |
| Top channels by growth | joins 7d ranked | PG `conversation_members.joined_at` GROUP BY | Table | **[PARTIAL]** |

### 5.5 Live streaming

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Streams/day, live-now | Started streams; currently LIVE | PG `live_streams` [PARTIAL]; live-now via `GET /api/v1/streams/live` **[EXISTS]** | Bar + tile | mixed |
| Peak / avg concurrent viewers | Per stream and platform | `live_streams.peak_viewer_count` [PARTIAL]; platform concurrency curve needs `stream.watch` heartbeat events | Line | mixed |
| Watch-time | Σ viewer session minutes | Sessions from `stream_viewers.joined_at/left_at` [PARTIAL — `left_at` null on crash]; authoritative via heartbeat events | Line | **[PARTIAL]** |
| Gifts: coins/day, top streams | Symbolic coin flow | `stream_gift_tallies` SUM [PARTIAL]; per-stream board `GET /api/v1/streams/{id}/gifts/top` **[EXISTS]** | Line + table | mixed |
| Guest (stage) usage | Streams with ≥1 guest; avg guests | PG `stream_guests` | Bar | **[PARTIAL]** |
| Orphaned LIVE rows | LIVE with no publisher (crash) | No detector — see [operations.md](operations.md) force-stop plan | Alert tile | **[PLANNED]** |

### 5.6 Research

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Publishes/day; scheduled-publish share | PUBLISHED transitions | PG `research` status + timestamps (`ScheduledPublishJob` path) | Bar | **[PARTIAL]** |
| Downloads/day (+unique users) | Download events | PG `research_downloads.created_at` (deduped upstream by `ResearchDownloadTracker` 90d/user, 1h/IP — dedupe *shapes* the number, disclose on chart) | Line | **[PARTIAL]** |
| Engagement totals | Σ view/reaction/comment/save/share/citation | SQL SUM over `research.*_count` | Tiles | **[PARTIAL]** |
| Top research (7d) | By downloads/views delta | Needs daily deltas → events; lifetime top via SUM today | Table | **[PARTIAL]** |
| Retraction/archive rate | Lifecycle exits per month | PG `research.status` | Bar | **[PARTIAL]** |

### 5.7 QnA

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Questions & answers/day | New rows | PG `created_at` | Stacked bar | **[PARTIAL]** |
| Accept rate | Questions with `accepted_answer_count > 0` ÷ questions (age >7d) | PG `questions` | Line | **[PARTIAL]** |
| Time-to-first-answer | Median `MIN(answer.created_at) − question.created_at` | PG join | Line | **[PARTIAL]** |
| Unanswered backlog | OPEN with `answer_count = 0`, by age bucket | PG | Bar | **[PARTIAL]** |
| Question views/day | Daily view volume | Lifetime `view_count` only today → events | Line | **[PLANNED]** |

### 5.8 Notifications & email

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Deliveries/day by type | New `notifications` rows | PG `notifications.created_at,type` — **history >90d is purged for read rows** (`NotificationCleanupJob`); rollups must snapshot daily | Stacked area | **[PARTIAL]** |
| Read rate / time-to-read | read_at vs created | PG `is_read`, `read_at` | Line | **[PARTIAL]** |
| Open→click through | Notification opened in client | Client never reports opens → `notification.open` event | Line | **[PLANNED]** |
| Emails sent / throttled | Outbound email volume | **Recorded nowhere** — `EmailThrottle` leaves only transient Redis keys; add a send-ledger or `email.sent` event | Line | **[PLANNED]** |
| Digest reach | TRENDING_DIGEST rows/day | PG `notifications` groupKey `TRENDING_DIGEST:{date}` | Bar | **[PARTIAL]** |
| Unsubscribe rate | master-off toggles/week | `settings_audit` rows for the email keys | Line | **[PARTIAL]** |

### 5.9 Media & storage

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Uploads/day by type & status | `media_assets` rows | PG `created_at`, `media_type`, `status` | Stacked bar | **[PARTIAL]** |
| Failure rate | FAILED_* ÷ terminal per day | PG `status` (`MediaStatus.isTerminalFailure`) | Line | **[PARTIAL]** |
| Total stored bytes (+growth) | Platform Σ `stored_bytes` | `MediaAssetRepository.sumStoredBytesPlatform()` → `storageBytes` tile in `/overview`; per-user version via `StorageUsageService` | Area | **[EXISTS (built 2026-08)]** |
| Dedup savings | Referrer rows with `stored_bytes = 0` | PG count | Tile | **[PARTIAL]** |
| Top storage consumers | Per-owner SUM ranked | `sumStoredBytes(ownerId)` over top-N | Table | **[PARTIAL]** |

### 5.10 Search

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Searches/day by scope | Query volume | Only per-user `activity_by_user` today → `search.query` events | Line | **[PLANNED]** |
| Zero-result rate | `hit_count = 0` share | `hit_count` already captured per activity row — event prop carries it forward | Line | **[PLANNED]** |
| Top queries (k-anon ≥5 actors) | Frequency-ranked normalized queries | events + rollup with min-actor floor (§12) | Table | **[PLANNED]** |
| Click-through rank | Result click position | Never captured → `search.result_click` | Histogram | **[PLANNED]** |
| Index doc counts / drift | ES `_count` vs source-of-truth count per index | ES APIs exist; no admin endpoint wraps them → [search-feed-trending.md](search-feed-trending.md) | Table | **[PARTIAL]** |

### 5.11 Suggestions (PYMK)

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Impressions/day | Suggestion cards shown | Not tracked → `suggestion.impression` event | Line | **[PLANNED]** |
| Accept rate | Follows attributable to a suggestion ÷ impressions | Needs impression + `follow.create` with `source=pymk` prop | Line | **[PLANNED]** |
| Dismiss rate | Persistent dismissals/day | Cassandra dismissal writes exist (per-user) — daily count needs events or a counter | Line | **[PLANNED]** |
| Source mix of accepted | FoF/contacts/DM/groups/affinity/institution shares | Pipeline knows the source at serve time — carry as event prop | Stacked bar | **[PLANNED]** |

## 6. The gap & the fix: unified event pipeline **[EXISTS (built 2026-08)]**

### 6.1 Why nothing lighter works

- Cassandra counters: lifetime scalars, no history, no scans.
- `activity_by_user`: right events, wrong partitioning (per user, private, user-deletable — must not be repurposed).
- Audit log: request-grained, per-user partitions, SSE/heartbeat paths excluded, metadata-only; an ops/compliance tool, not analytics.
- RabbitMQ: **already carries many of the right events** (`post.social.#`, `qna.#`, `research.analytics.downloaded`, `user.social.#` on `irc.topic.exchange`) but nothing durable subscribes for analytics — a head start, not a store.

### 6.2 Collection — `analytics_events` (Cassandra, bucketed) [EXISTS (built 2026-08) — bootstrapped by `AnalyticsEventService`]

```
CREATE TABLE analytics_events (
  event_day   date,          -- UTC day bucket
  shard       tinyint,       -- 0..15, from event_id hash: bounds partition size
  event_time  timestamp,
  event_id    timeuuid,
  event_type  text,          -- taxonomy below
  actor_id    uuid,          -- null for anonymous
  target_type text,          -- POST/RESEARCH/QUESTION/USER/CHANNEL/STREAM/SOUND/...
  target_id   text,
  props       map<text,text>,-- small, typed-by-convention; NEVER free-text content
  client      text,          -- web/ios/android
  session_id  uuid,
  PRIMARY KEY ((event_day, shard), event_time, event_id)
) WITH CLUSTERING ORDER BY (event_time DESC)
  AND default_time_to_live = 34560000;   -- 400 days; rollups are the long-term record
```

Writers, in priority order:

1. **`AnalyticsEventTapConsumer`** — queue `irc.queue.analytics-events` bound
   with pattern `#` on `irc.topic.exchange`: captures every event the
   platform already publishes, zero touch to domain code. **[EXISTS (built 2026-08)]**.
2. **`AnalyticsEventService.record(...)`** — explicit fail-open one-statement
   writer **[EXISTS (built 2026-08)]**; hooked up so far via `FunnelTracker`
   (first-seen / profile-completed / first-follow / first-content). The wider
   explicit-instrumentation list — views, searches, story views, sound plays,
   reel watches, stream watch heartbeats, notification opens, suggestion
   impressions, session starts — remains **[PLANNED]** per surface.

### 6.3 Event taxonomy (v1)

| Event | Actor | Target | Props | Emitting surface |
|-------|-------|--------|-------|-----------------|
| `session.start` | user | — | client, appVersion | auth filter (also PFADDs interim DAU HLL) |
| `user.signup` / `user.login` | user | — | method, outcome | AuthService (also wires `login_events`) |
| `profile.completed` | user | USER | fieldsSet | profile service |
| `follow.create` / `follow.remove` | user | USER | source (organic/pymk/profile) | follow service (rabbit `user.social.#` exists) |
| `post.create` / `post.delete` | author | POST | postType (POST/REEL), hasSound, tagCount | rabbit `post.lifecycle.#` **[EXISTS transport]** |
| `post.view` | viewer | POST | dedupe7d=true/false | `CassandraViewService.recordView` |
| `post.react` / `comment` / `share` / `save` | user | POST | — | rabbit `post.social.#` **[EXISTS transport]** |
| `reel.watch` | viewer | POST | watchedSeconds | `ReelViewService` |
| `story.post` / `story.view` / `story.poll_vote` | user | STORY | lifetime, choice | `CassandraStoryService` — **only durable story analytics record** (rows TTL out) |
| `sound.play` / `sound.use` | user | SOUND | context (story/reel/post) | player callback + `CounterService.incrementSoundUse` |
| `search.query` | user | — | scope, queryNorm, hitCount | `GlobalSearchService` (mirrors what activity log already captures) |
| `search.result_click` | user | entity | rank, scope | client → thin endpoint |
| `chat.message_send` | sender | CONVERSATION | convType, msgType — **no body, ever** | `MessageService` |
| `channel.join` / `leave` / `post_view` | user | CHANNEL | joinSource | member service + `ChannelPostMetricsService` |
| `stream.start` / `end` | host | STREAM | durationSec, peakViewers | `LiveStreamService` |
| `stream.watch` | viewer | STREAM | heartbeat every 60s watching → watch-time = Σ heartbeats | viewer SSE/join loop |
| `gift.send` | user | STREAM | giftType, coins | `StreamStageService.sendGift` |
| `call.end` | initiator | CONVERSATION | type, durationSec, missed | `CallService` |
| `research.publish` / `view` / `download` / `cite` | user | RESEARCH | scheduled, mediaId | rabbit `research.#` **[EXISTS transport]** |
| `qna.question_create` / `answer_create` / `answer_accept` | user | QUESTION | — | rabbit `qna.#` **[EXISTS transport]** |
| `notification.delivered` / `open` | recipient | NOTIFICATION | type, channel (inapp/email) | NotificationService + client open ping |
| `media.upload_complete` | owner | MEDIA | type, bytes, status | `MediaProcessingService` |
| `suggestion.impression` / `dismiss` | user | USER | source | PYMK serve/dismiss paths |
| `report.submit` | reporter | entity | reason | `ReportService` (volume only; triage lives in [safety-reports.md](safety-reports.md)) |

### 6.4 Rollups (daily jobs) **[EXISTS (built 2026-08)]** — `AnalyticsJobs` + `MetricDailyService`

| Table | Shape | Written by |
|-------|-------|-----------|
| `analytics_metric_daily` (Cassandra) | live per-day metric counters, bumped as events happen (`MetricDailyService.bump`; sources incl. `LoginEventService`, activity hooks) | on-event |
| `analytics_metric_rollup` (Cassandra) | PK `(metric, month)`, clustering `day`; plain `bigint value` (NOT a counter — **idempotent overwrite, safe re-runs**) | `AnalyticsJobs.dailyRollup`, cron `0 40 2 * * *` UTC for D-1 |
| `analytics_dau_by_day` (Cassandra) | `((day), user_id)` — DAU via PK-dedup upsert (`markActive`); `distinctActiveOver(n)` unions days for WAU/MAU | on-event |
| `user_first_events` (PG) | `user_id` PK → first_seen, profile_completed_at, first_follow_at, first_content_at (funnel §4.3) | incremental, on-event via `FunnelTracker` |
| `cohort_retention_weekly` (PG) | `(cohort_week, week_offset)` → active_count | `AnalyticsJobs.weeklyCohorts`, Mondays 03:10 UTC |
| `metric_alerts` (PG) | metric, day, z, value, mean, sd (§11) | `AnalyticsJobs.anomalyScan`, cron `0 55 2 * * *` UTC |

Scheduler note: the shared `@Scheduled` pool is `size: 4` and already carries
16 methods ([operations.md](operations.md)) — bump the pool or give rollups
their own executor.

### 6.5 Query API **[EXISTS (built 2026-08)]** — see §9 for the endpoint table

Thin read layer over the rollup tables (`mergedSeries` = rollup overlaid with
today's live counters) — no ad-hoc event scans from the dashboard, ever. Raw
`analytics_events` is touched only by rollup jobs and the step-up-gated sample
endpoint.

## 7. Dashboard views & widgets

### 7.1 Overview page — 12 headline tiles

Each tile: big number, delta vs compare period, 14-day sparkline, click →
drilldown. Status = what powers it at launch vs end-state.

| # | Tile | Content | Launch source | Status |
|---|------|---------|---------------|--------|
| 1 | **DAU** | yesterday + WoW delta | `analytics_dau_by_day` (`dauToday` in `/overview`) | **[EXISTS (built 2026-08)]** |
| 2 | **MAU / online-now** | 30 d distinct actives + live presence count | `mau30d` (`distinctActiveOver(30)`) + `onlineNow` tiles in `/overview` | **[EXISTS (built 2026-08)]** |
| 3 | **Stickiness** | DAU÷MAU % | derived | **[PLANNED]** |
| 4 | **North Star (WALC)** | weekly, WoW | §4.1 | **[PLANNED]** |
| 5 | **New signups** | today + 7d spark | PG `users.created_at` | **[PARTIAL]** — day-1 buildable |
| 6 | **Content created** | today, stacked mini-bar by type | PG + `reels_by_day`; posts/stories join later | mixed |
| 7 | **Engagement actions** | today (rx+cm+sh+sv) | `metric_daily` | **[PLANNED]** |
| 8 | **Messages sent** | today, count only | `metric_daily` (`chat.message_send`) | **[PLANNED]** |
| 9 | **Live now** | LIVE streams + Σ viewers | `GET /api/v1/streams/live` + `viewer_count` | **[EXISTS]** — day-1 buildable |
| 10 | **Notifications delivered** | today, in-app vs email | PG `notifications` + email ledger | **[PARTIAL]** / email **[PLANNED]** |
| 11 | **Storage footprint** | total GB + 30d growth | `sumStoredBytesPlatform()` → `storageBytes` in `/overview` | **[EXISTS (built 2026-08)]** |
| 12 | **Open reports** | SUBMITTED + APPEALED count | PG `reports.state` → [safety-reports.md](safety-reports.md) | **[PARTIAL]** |

Below the tiles: **Activity chart** (DAU line, 90d, compare overlay), **Content
mix** stacked area, **Module health strip** (one mini-tile per module linking to
its drilldown), **Recent anomalies** list (§11).

### 7.2 Drilldown pages

One per §5 module, uniform template: KPI tile row → time-series charts →
breakdown tables → link to the owning section doc (e.g. live drilldown links
[chat-channels-live.md](chat-channels-live.md)). Entity inspector panes reuse
existing point reads (`post_counters`, `ChannelStatsService`, `UserStatsService`,
gift leaderboard) — these are **[EXISTS]** and ship in phase A.

### 7.3 Conventions

| Convention | Rule |
|------------|------|
| Time zone | **UTC everywhere** (rollup jobs and cron already run UTC); day boundary 00:00Z |
| Date ranges | Presets 7d / 28d (default) / 90d / 365d / custom; current partial day rendered hatched and excluded from deltas |
| Compare period | Preceding window of equal length (28d vs prior 28d); tiles additionally show WoW same-weekday |
| Deltas | Absolute + %, green/red only when direction is unambiguous (reports ↑ = red) |
| Dedupe disclosure | Charts whose source dedupes (post views 7d/user, downloads 90d/user, HLL ±0.8%) carry a ⓘ footnote — the dedupe window shapes the number |
| CSV export | Every chart/table exports the visible series: `GET /api/v1/admin/analytics/export?dataset=&window=` (`text/csv`) **[EXISTS (built 2026-08)]**; UTF-8, header row |

## 8. Data sources per widget (summary map)

| Widget family | Reads | Store |
|---------------|-------|-------|
| Tiles 1–4, 7, 8 + all time-series | `analytics_metric_daily` / `analytics_metric_rollup` / `analytics_dau_by_day` **[EXISTS (built 2026-08)]** | Cassandra (rollups) |
| Tiles 5, 10, 12 + PG breakdowns | aggregate SQL in `AdminAnalyticsController` **[EXISTS (built 2026-08)]** | Postgres |
| Tile 9, live rail | `GET /api/v1/streams/live`, `live_streams` **[EXISTS]** | PG |
| Tile 11, storage tables | `MediaAssetRepository.sumStoredBytes*` **[EXISTS — per-user + `sumStoredBytesPlatform()` (built 2026-08)]** | PG |
| Entity inspectors | `post_counters`/`CounterCache`, `UserStatsService`, `ChannelStatsService`, `GET /api/v1/streams/{id}/gifts/top`, `GET /api/v1/tags/trending` **[EXISTS]** | Cassandra/Redis/PG |
| Funnel & retention | `user_first_events`, `cohort_retention_weekly` **[EXISTS (built 2026-08)]** | PG |
| Anomaly list | `metric_alerts` **[EXISTS (built 2026-08)]** | PG |

## 9. Admin actions

All under `/api/v1/admin/analytics/**` → automatic filter-chain ADMIN
double-gate + `@PreAuthorize` ([architecture.md](architecture.md)); every call
lands in the audit log via `AuditLoggingInterceptor` **[EXISTS]** — rows below
are the *additional* explicit audit actions.

| Action | Endpoint | Params | Danger | Step-up | Audit action |
|--------|----------|--------|--------|---------|--------------|
| Overview snapshot | `GET /api/v1/admin/analytics/overview` **[EXISTS (built 2026-08)]** — incl. `dauToday`, `mau30d`, `onlineNow`, `storageBytes` tiles | — | Low | No | — (interceptor READ) |
| Content / engagement / trending breakdowns | `GET /api/v1/admin/analytics/{content,engagement,trending}` **[EXISTS (built 2026-08)]** | `days?` | Low | No | — |
| Metric series | `GET /api/v1/admin/analytics/series` **[EXISTS (built 2026-08)]** — rollup merged over live counters | `metric, window=30` | Low | No | — |
| Funnel | `GET /api/v1/admin/analytics/funnel` **[EXISTS (built 2026-08)]** | `cohort=YYYY-MM` | Low | No | — |
| Retention grid | `GET /api/v1/admin/analytics/retention` **[EXISTS (built 2026-08)]** | `weeks=12` (≤26) | Low | No | — |
| CSV export | `GET /api/v1/admin/analytics/export` (`text/csv`) **[EXISTS (built 2026-08)]** | `dataset, window` | Medium (bulk egress) | No | `ANALYTICS_EXPORTED` |
| Re-run a rollup day | `POST /api/v1/admin/analytics/rollup/{date}/run` **[EXISTS (built 2026-08)]** | path date; idempotent (plain-column overwrite §6.4) | Medium | No | `ANALYTICS_ROLLUP_RERUN` |
| Backfill from PG sources | `POST /api/v1/admin/analytics/backfill` **[EXISTS (built 2026-08)]** | `source, from, to` | Medium (long-running; PG load) | No | `ANALYTICS_BACKFILL` |
| Sample raw events | `GET /api/v1/admin/analytics/events/sample` **[EXISTS (built 2026-08)]** | `type, day, limit≤100` | **High** (individual behavioral traces) | **Yes** (`@RequiresStepUp`) | `ANALYTICS_RAW_ACCESS` |
| Read / configure alert thresholds | `GET /api/v1/admin/analytics/alerts-config` + `PUT /api/v1/admin/analytics/alerts/{metric}` **[EXISTS (built 2026-08)]** (`analytics_alert_config`) | zWarn, zAlert, minVolume, enabled | Low | No | `ANALYTICS_ALERT_CONFIG` |
| Anomaly feed | `GET /api/v1/admin/analytics/anomalies` **[EXISTS (built 2026-08)]** | `days?` | Low | No | — |

Existing endpoints the section reuses without change: 7 reindexes
(`SearchAdminController`) stay in [search-feed-trending.md](search-feed-trending.md);
`GET /api/v1/channels/{id}/stats` needs the platform-admin override proposed in
[chat-channels-live.md](chat-channels-live.md) before the channel drilldown works
for non-member admins.

## 10. Logs surfaced in this section

Full catalog with schemas/retention: [logs-audit.md](logs-audit.md). Analytics
surfaces these read-only views:

| Log | Use here | Status |
|-----|----------|--------|
| `analytics_events` (sampled, step-up) | Taxonomy debugging, instrumentation verification — `GET …/events/sample`, audited `ANALYTICS_RAW_ACCESS` | **[EXISTS (built 2026-08)]** |
| `research_downloads` (PG) / `research_downloads_by_research` (Cassandra) | Download drilldown per research | **[EXISTS]** (repo reads; no admin endpoint yet → [research-qna.md](research-qna.md)) |
| `audit_log_by_user.duration_ms` | Per-user request latency in the user inspector (not aggregated) | **[EXISTS]** via `GET /api/v1/admin/audit` |
| `login_events` | Login outcome series (writer wired from `AuthServiceImpl`; served via `/engagement`) | **[EXISTS (built 2026-08)]** |
| `activity_by_user` | **Explicitly NOT surfaced** — per-user private history; admins never browse it (§12) | — |

## 11. Alerts & thresholds — anomaly detection **[EXISTS (built 2026-08)]**

`AnalyticsJobs.anomalyScan` runs after the daily rollup (cron `0 55 2 * * *` UTC):

| Rule | Definition |
|------|------------|
| Baseline | Per metric: mean μ and std σ over trailing **28 completed days**, previously-flagged anomalous days excluded from the window |
| Warn | \|z\| = \|x−μ\|/σ ≥ **2.5** |
| Alert | \|z\| ≥ **3.5**, or metric = 0 where μ ≥ 50 (pipeline-dead detector) |
| Noise floor | Skip metrics with μ < 50/day (small numbers make meaningless z) |
| Weekly seasonality | Optional per-metric mode: baseline over trailing 8 same-weekdays instead of 28 days (default ON for signups, content, DAU) |
| Delivery | Row in `metric_alerts` + system notification **and email** to all ADMINs via `NotificationType.ADMIN_ANOMALY` (built 2026-08, incl. its `EmailTemplate.actionVerb` case) + `GET …/anomalies` feed |
| Defaults reviewable | Thresholds per metric stored in `analytics_alert_config`, editable via `PUT .../alerts/{metric}` / read via `GET .../alerts-config` (§9) |

Suggested initial watchlist: DAU, signups, content.created, engagement.actions,
chat.messages, notifications.delivered, media.upload_failures, reports.submitted,
stream.watch_minutes. Infra alerts (DLQ depth, job overruns, SSE fan-out) belong
to [operations.md](operations.md).

## 12. Permissions & safety notes

| Rule | Rationale |
|------|-----------|
| All routes under `/api/v1/admin/analytics/**` | Filter-chain double-gate applies automatically (`SecurityConfig` `/api/v1/admin/**` → `hasRole('ADMIN')`) |
| Aggregates by default; raw events behind step-up | `analytics_events` rows are individual behavioral traces; the sample endpoint is the only raw window, audited as `ANALYTICS_RAW_ACCESS` |
| **No content in props, ever** | `chat.message_send` carries type/conversation-type only; the privacy boundary of [chat-channels-live.md](chat-channels-live.md) holds for analytics too. Never surface `conversations.last_message_preview` in any analytics projection (known metadata leak) |
| Search queries k-anonymized | Top-queries table only shows normalized queries with **≥5 distinct actors** in the window; below the floor they aggregate as "(other)" |
| `activity_by_user` is off-limits | It is the user's *private* history (user-deletable); the pipeline collects its own parallel stream rather than reading it |
| Event collection is fail-open and non-blocking | Analytics must never fail or slow a user request (same discipline as `CounterCache`/affinity writes) |
| Dedupe windows disclosed on charts | Views 7d/user, downloads 90d-user/1h-IP, channel views lifetime-HLL — silent differences would make cross-metric comparisons lie |
| Deleted users | Rollups keep aggregate counts; `analytics_events` rows for purged accounts are anonymized-by-TTL (400d) — document in the deletion policy ([users-roles.md](users-roles.md)) |

## 13. Build order / dependencies

> **Status (built 2026-08):** phases 0–D are substantially shipped —
> `login_events` is wired from `AuthServiceImpl` (phase 0), the platform
> `stored_bytes` SUM exists (`sumStoredBytesPlatform`), the phase-A aggregate
> endpoints live in `AdminAnalyticsController`, collection runs via
> `analytics_events` + `AnalyticsEventTapConsumer` (`#` tap) + `FunnelTracker`
> hooks (phase B; the wider per-surface explicit instrumentation of §6.2
> writer 2 remains open), rollups + query API are live
> (`AnalyticsJobs`, `analytics_metric_rollup`, §9 endpoints — phase C), and
> funnel/retention/anomaly-scan + CSV export shipped (phase D). Still open:
> the WALC North-Star composite (§4.1) and any §5 rows still tagged
> [PLANNED].

| Phase | Work | Depends on | Delivers |
|-------|------|-----------|----------|
| **0 — prerequisites (days)** | Wire `LoginEventService` into `AuthServiceImpl` — **done (built 2026-08)**; DAU via `analytics_dau_by_day` (superseded the interim Redis-HLL idea); platform-total `stored_bytes` SUM — **done** (`sumStoredBytesPlatform`) | — | Login series accruing; DAU from day 1 |
| **A — read-only over existing data (week 1-2)** | Aggregate SQL endpoints for every **[PARTIAL]** row in §5 (signups, research, QnA, chat metadata, live sessions, gifts, media, notifications, reports); `reels_by_day` count; Overview tiles 5, 9, 11, 12 + drilldown skeletons; entity inspectors from existing point reads | Phase 0 | A useful dashboard with zero new collection risk |
| **B — collection (week 3-5)** | `analytics_events` table; `AnalyticsEventConsumer` on `irc.queue.analytics-events` bound `#` to `irc.topic.exchange` (captures all existing post/qna/research/user events immediately); `AnalyticsEventService` + explicit instrumentation for views, searches, story views, sound plays, reel watches, stream heartbeats, notification opens, suggestion impressions | A | Events accruing for every surface incl. the three recon call-outs: sound plays, story views, stream watch-time |
| **C — rollups + query API (week 5-7)** | `DailyRollupJob`, `metric_daily*`, `uniques_daily`, `user_first_events`; query API of §9; Overview tiles 1-4, 6-8, 10 complete; **bump `spring.task.scheduling.pool.size`** (16 methods already share 4 threads) | B (≥1 week of events) | Real DAU/WAU/MAU, stickiness, all daily series |
| **D — advanced (week 7-9)** | Funnel + retention cohorts + North Star; `AnomalyScanJob` + `ADMIN_ANOMALY` notification (+ `EmailTemplate` case); CSV export; PG-history backfill (signups/downloads/QnA back-dated into `metric_daily` so launch charts aren't empty) | C | Full §4 KPI tree + alerting |

**Cross-doc dependencies:** channel drilldown blocks on the platform-admin
stats override ([chat-channels-live.md](chat-channels-live.md)); report tiles
consume the triage model of [safety-reports.md](safety-reports.md); rollup-job
monitoring registers in the jobs inventory of [operations.md](operations.md);
all endpoints enter the registry in [admin-api-blueprint.md](admin-api-blueprint.md).
