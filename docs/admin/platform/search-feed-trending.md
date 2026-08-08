# Admin Dashboard — Section 8: Search, Feed & Trending

Operational console for the three discovery engines: **Elasticsearch search**
(8 indices, reindex console, query analytics), the **unified tag / trending
subsystem** (Cassandra counters + `TrendingTagJob` snapshots, override
controls), the **ranked home feed** (pipeline observability + knob registry +
tuning UI), and the **friend-suggestion engine** (PYMK health). Underlying
mechanics live in [../search/](../../search/README.md),
[../feed/algorithm.md](../../feed/algorithm.md) and
[../suggestions/algorithm.md](../../suggestions/algorithm.md); this doc covers
only the admin view. Tag semantics: **[EXISTS]** / **[PARTIAL]** /
**[PLANNED]** per [README.md](../README.md).

---

## 1. Purpose & scope

| In scope | Out of scope (see) |
|----------|--------------------|
| ES index health, doc counts, the 7 reindex endpoints, reindex safety | Search *relevance* algorithms — [../search/algorithms-and-complexity.md](../../search/algorithms-and-complexity.md) |
| Search analytics: top queries, zero-result queries | Per-user activity history UI — [logs-audit.md](logs-audit.md) |
| Trending scopes, rebuild job, tag backfill, pin/ban overrides | Tag content moderation (hashtag abuse) — [content-moderation.md](../trust-safety/content-moderation.md) |
| Feed pipeline observability, `user_author_affinity`, digest/live-rail stats, knob registry + tuning UI | Feed API contract — [../feed/api-reference.md](../../feed/api-reference.md) |
| PYMK source mix, dismissal rates, contact-sync match rates | Contact-sync consent/privacy — [../settings/README.md](../../settings/README.md), [safety-reports.md](../trust-safety/safety-reports.md) |

---

## 2. Dashboard views / widgets

Four tabs. What the admin sees on screen:

### 2.1 Tab "Search"

| Widget | Layout / content | Status |
|--------|------------------|--------|
| **Index health board** | 8 cards, one per index (`irc-posts`, `irc-research`, `irc-qna`, `irc-answers`, `irc-users`, `irc-channels`, `irc-sounds`, `irc-chat-messages`): green/yellow/red, doc count, store size, canonical-store count beside it (drift %), last-reindex timestamp | **[EXISTS (built 2026-08)] — backend**: `GET /api/v1/admin/search/indices` (existence + doc count) and `GET /api/v1/admin/search/health` (per-index doc counts + canonical drift where the canonical store is PG; Cassandra-canonical indices report drift-unknown). Store size still missing |
| **Reindex console** | 7 rows (one per existing endpoint) with a `drop` toggle (default on), Run button behind a type-to-confirm modal, last run result (`indexed` / `failed` counts), run history | **[PARTIAL]** — the 7 endpoints exist (`SearchAdminController`); the UI, history and confirm flow are the dashboard build |
| **Mapping-gotcha banner** | Static warning card: dynamic-mapping repair semantics of `drop=true` (see §8) | **[PLANNED]** (content is real today, documented in [../search/indexing-and-reindex.md](../../search/indexing-and-reindex.md)) |
| **Top queries (24h/7d)** | Ranked table: query, count, avg hit count, scope mix | **[EXISTS (built 2026-08)] — backend**: collector §6.1 + `GET /api/v1/admin/search/analytics/top-queries` |
| **Zero-result queries** | Ranked table of normalized queries with `hitCount=0`; "content gap" export | **[EXISTS (built 2026-08)] — backend**: `GET /api/v1/admin/search/analytics/zero-results` |
| **Search degradation ticker** | Recent `degraded: true` global-search responses + `EsRetry` recovery log volume | **[PLANNED]** — flag exists per-response (`GlobalSearchService`), nothing aggregates it |

### 2.2 Tab "Trending"

| Widget | Layout / content | Status |
|--------|------------------|--------|
| **Live leaderboards** | 5 columns — scopes `ALL` / `QUESTION` / `RESEARCH` / `POST` / `REEL` — each the current top-100 `trending_tags` snapshot with rank, tag, usage count | **[PARTIAL]** — data readable today via public `GET /api/v1/tags/trending?scope=` (`TagController`); admin framing/diffing is new |
| **Rebuild status strip** | Last `TrendingTagJob` run per scope, duration, tags written, failures ("rebuild scope X failed" warns) | **[PLANNED]** — job logs to console only |
| **Override manager** | Pinned tags (forced into a scope at a chosen rank) and banned tags (never surface), with expiry + reason + who set it | **[EXISTS (built 2026-08)] — backend**: `AdminTrendingController` (`GET/POST /api/v1/admin/trending/overrides`, `DELETE …/overrides/{id}`, `POST …/rebuild`) over `trending_tag_overrides`, consulted by `TrendingTagJob`; `TagAdminController` additionally ships `POST/DELETE /api/v1/admin/tags/{tag}/hide` and `POST /api/v1/admin/tags/merge` |
| **Backfill card** | One-shot `POST /api/v1/admin/tags/backfill-posts` runner with its result summary (`postsScanned`, `postsWithHashtags`, `tagRowsWritten`, `startedAt`) and a loud "counters are NOT idempotent — do not re-run casually" warning | **[PARTIAL]** — endpoint **[EXISTS]**, UI planned |
| **Digest monitor** | `TrendingNotificationJob` daily 09:00 UTC run status, users notified, skipped-below-floor days | **[PLANNED]** — see [notifications-email.md](../communication/notifications-email.md) |

### 2.3 Tab "Feed"

| Widget | Layout / content | Status |
|--------|------------------|--------|
| **Pipeline funnel** | Per-stage counts for a sampled window: candidates in (timeline / channel digest / explore / live rail) → after safety filter → scored → after diversity → delivered | **[PLANNED]** — stages exist in code (`HomeFeedService` → `FeedRankingService`), no counters emitted |
| **Knob registry** | Read-only table of every ranking constant with current value and *where it lives* (code vs config) — §2.3.1 | **[EXISTS (built 2026-08)] — backend**: `GET /api/v1/admin/feed/weights`; UI planned |
| **Score explainer** | Enter a userId (+ optional postId): re-run scoring and show the E/A/F/B breakdown per item, source label (`FOLLOWING`/`SELF`/`CHANNEL`/`EXPLORE`, `HomeFeedSources`) | **[EXISTS (built 2026-08)] — backend**: `GET /api/v1/admin/feed/explain/{userId}` (audited `ADMIN_FEED_EXPLAIN`) |
| **Affinity inspector** | Enter viewer userId: top-N `user_author_affinity` rows (author, interactions counter) | **[EXISTS (built 2026-08)] — backend**: `GET /api/v1/admin/feed/affinity/{userId}` (audited `ADMIN_FEED_AFFINITY_VIEW`) |
| **Injection stats** | Time-series: channel-digest items injected/page, live-rail occupancy (0–10), explore slice size (1–3), first-page share | **[PLANNED]** |
| **Fan-out monitor (write path)** | The home-feed *write* side: per-post fan-out duration bucketed by follower count, followers reached, timeline-cache write failures, keyset-scan batch stats. Mechanism **[EXISTS]** — `findFollowerIdsAfter` keyset scan drained through `parallelStream` batches into the timeline store (`feed:timeline:*` Redis prefix, see [operations.md](operations.md) §6.3) — but it emits **no metrics today**; add duration/failure counters at the fan-out call site **[PLANNED]** | **[PARTIAL]** |
| **Tuning UI** | Editable weights with preview + staged rollout — §6.3 | **[EXISTS (built 2026-08)] — backend** (`GET/PATCH /api/v1/admin/feed/config`, `POST …/preview`); UI planned |

### 2.3.1 Feed knob registry — config-vs-code map **[EXISTS]** (values); runtime override **[EXISTS (built 2026-08)]**

The values below are the **compile-time defaults**. Since 2026-08 they can be
overridden at runtime: a single-row PG `feed_ranking_config` +
`FeedTuningService` (30 s cache, sticky `rolloutPercent` bucketing by userId
hash) is threaded through `HomeFeedService` / `FeedRankingService.Knobs` —
with no row (or 0 % rollout) behavior is identical to the constants.

| Knob | Value | Where it lives |
|------|-------|----------------|
| `W_LIKE / W_COMMENT / W_SHARE / W_SAVE / W_VIEW` | 3.0 / 4.0 / 5.0 / 4.0 / 0.5 | Code — `FeedRankingService` `static final` |
| `W_AFFINITY` | 2.0 (× ln(1+interactions)) | Code — `FeedRankingService` |
| Half-lives `POST/REEL/CHANNEL/RESEARCH/QUESTION` | 24h / 48h / 18h / 72h / 48h | Code — `FeedRankingService` |
| `BOOST_MUTUAL / BOOST_SELF / DAMP_CHANNEL / DAMP_EXPLORE` | 1.25 / 1.05 / 0.90 / 0.85 | Code — `FeedRankingService` |
| `MAX_AUTHOR_RUN` (diversity) | 2 | Code — `FeedRankingService` |
| `LIVE_RAIL_MAX / CHANNEL_ITEMS_MAX / EXPLORE_MIN / EXPLORE_MAX / EXPLORE_POOL_MULT` | 10 / 8 / 1 / 3 / 3 | Code — `HomeFeedService` |
| Affinity write weights (view/like/save/comment/share) | +1 / +3 / +4 / +5 / +5 | Code — `AuthorAffinityService` |
| Trending refresh / initial delay | 600 000 ms / 60 000 ms | Config — `app.tags.trending-refresh-ms`, `app.tags.trending-initial-delay-ms` |
| Trending digest cron | `0 0 9 * * *` UTC | Config — `irc.trending.notifications.cron` |
| Trending `TOP_K` | 100 | Code — `TrendingTagJob` |

### 2.4 Tab "Suggestions (PYMK)"

> This tab is the PYMK **health widget** (source-mix, dismissal rate). The whole
> subsystem — the algorithm knob registry, **contact-sync privacy** (hashing, 5k
> cap, identity-hash backfill, consent), the discoverability flags and **QR-discovery
> tokens**, and the enumeration/scraping abuse surface — is now documented as its own
> section: [discovery-pymk-privacy.md](../users/discovery-privacy.md) (§15). Where the two
> overlap (PYMK health), that doc defers here; for privacy/consent/abuse, defer there.

| Widget | Layout / content | Status |
|--------|------------------|--------|
| **Source-mix donut** | Share of served suggestions by primary source: FoF graph / contacts / DM / shared groups / affinity / institution (the 6 sources, `FriendSuggestionService`) | **[PLANNED]** — `primarySource` is computed per candidate but never aggregated |
| **Dismissal rate** | Dismissals ÷ suggestions served, trend line; top-dismissed candidates | **[PARTIAL]** — dismissals persist (`SuggestionDismissal`, PG); "served" denominator needs an impression counter **[PLANNED]** |
| **Contact-sync funnel** | Users who uploaded hashes → hashes stored (`user_contact_hashes`, `ContactMatchService`) → matched to accounts (`matchedContactIds`) → bidirectional matches | **[PARTIAL]** — all data queryable via SQL today, no endpoint/rollup |
| **Weight sheet** | Read-only scoring table (W_CONTACT 12.0, W_DM 10.0, W_INSTITUTION 4.0, W_MUTUAL 3.0 cap 15, W_GROUP 2.5 cap 4, W_LOCATION 2.5, W_AFFINITY 1.5·ln, W_SPECIALIZATION 1.5 cap 3, W_BADGE 0.75, W_COMPLETE 0.75, W_LANGUAGE 0.5, `+W_CONTACT_BIDIR` 6.0; `MIN_SCORE` 2.0, store cap 50, `DIVERSITY_HEAD` 20) — all code constants, same honesty rule as §2.3.1 | **[PLANNED]** UI; values **[EXISTS]** |
| **Per-user recompute** | Trigger a pipeline recompute for one user, view stored candidates (`FriendSuggestionEntity`, Cassandra) | **[PLANNED]** |

---

## 3. Data sources

| Widget | Source of truth | Access path today |
|--------|-----------------|-------------------|
| Index health / doc counts | ES `_cat/indices` + `_count`; indices declared `@Document(createIndex=false)`, materialized by `ElasticsearchIndexInitializer` | `GET /api/v1/admin/search/indices` + `GET /api/v1/admin/search/health` (`admin/search/AdminSearchOpsController`) **[EXISTS (built 2026-08)]** |
| Canonical-store drift counts | PG (`research`, `users`, `questions`, `conversations`), Cassandra (`posts_by_id`, `sounds_by_id`) | `GET /api/v1/admin/search/health` reports drift for PG-canonical indices **[EXISTS (built 2026-08)]**; Cassandra-canonical indices report drift-unknown (no cheap full count) |
| Reindex console | `SearchAdminController` — 7 endpoints, §4 | **[EXISTS]** |
| Reindex resilience | `EsRetry` (`common/search/EsRetry.java`): retry-once on stale pooled connections, used by every `*SearchService.indexAsync/deleteAsync` + `GlobalSearchService.search/searchCursor`; `isIndexNotFound` treated as benign-empty, not degraded | **[EXISTS]** (library behavior, not a data feed) |
| Search analytics | `SearchQueryLogService` (§6.1) — Redis top-K + Cassandra `search_queries_by_bucket`, hooked into `GlobalSearchService` head requests; **no user ids stored**. (The per-user copy stays in `activity_by_user`, self-readable/deletable only) | **[EXISTS (built 2026-08)]** |
| Trending leaderboards | Cassandra `trending_tags` (scope partition, `tag_rank` ASC, pre-ranked top-100) rebuilt by `TrendingTagJob` from `tag_counters` (scope partition, `usage_count` counter) | `GET /api/v1/tags/trending?scope=`, `GET /api/v1/tags/{tag}/usage` **[EXISTS]** (public, not admin-framed) |
| Trending overrides | PG `trending_tag_overrides` (§6.2), consulted by `TrendingTagJob` | **[EXISTS (built 2026-08)]** — `AdminTrendingController` |
| Backfill | `TagAdminController.backfillPosts` — full token-range scan of `posts_by_id` → `ContentTagService.tag` | **[EXISTS]** |
| Feed funnel / injection stats | `HomeFeedService` (candidate gen: timeline every page; channel digest via `ChannelFeedCandidateService`, explore slice, live rail via `LiveStreamService` — first page only) + `FeedRankingService.Scored(item, source, score)` | **None** — Micrometer counters **[PLANNED]**; note `/actuator/prometheus` registry is on the classpath but not exposed ([operations.md](operations.md)) |
| Affinity inspector | Cassandra `user_author_affinity` (viewer partition, author clustering, `interactions` counter); written async best-effort by `AuthorAffinityService` | `GET /api/v1/admin/feed/affinity/{userId}` **[EXISTS (built 2026-08)]** |
| PYMK source mix / dismissals | `FriendSuggestionService` pipeline; `FriendSuggestionEntity` (Cassandra, ≤50 stored/user); `SuggestionDismissal` (PG, persistent); `user_contact_hashes` (PG, HMAC-peppered — raw phone never stored) | Dismiss + serve endpoints exist user-side; zero admin aggregation **[PLANNED]** |

---

## 4. Admin actions

Existing actions first — all under `/api/v1/admin/**`, so double-gated
(filter-chain `hasRole('ADMIN')` + `@PreAuthorize`), per
[architecture.md](../foundation/architecture.md).

| Action | Endpoint | Params | Danger | Step-up | Audit action |
|--------|----------|--------|--------|---------|--------------|
| **[EXISTS]** Reindex research | `POST /api/v1/admin/search/research/reindex` | `drop` (default `true`) | Medium — index empty during rebuild | Proposed: yes | Interceptor row today; `SEARCH_REINDEX` **[PLANNED]** |
| **[EXISTS]** Reindex posts | `POST /api/v1/admin/search/posts/reindex` | `drop` | Medium (also the dynamic-mapping repair path) | yes | same |
| **[EXISTS]** Reindex questions | `POST /api/v1/admin/search/questions/reindex` | `drop` | Medium | yes | same |
| **[EXISTS]** Reindex users | `POST /api/v1/admin/search/users/reindex` | `drop` | Medium | yes | same |
| **[EXISTS]** Reindex channels | `POST /api/v1/admin/search/channels/reindex` | `drop` | Medium | yes | same |
| **[EXISTS]** Reindex answers | `POST /api/v1/admin/search/answers/reindex` | `drop` | Medium | yes | same |
| **[EXISTS]** Reindex sounds | `POST /api/v1/admin/search/sounds/reindex` | `drop` | Medium | yes | same |
| **[EXISTS]** Backfill post tags | `POST /api/v1/admin/tags/backfill-posts` | — | **High** — trending counter increments are NOT idempotent; re-runs inflate trending | **yes** | `TAG_BACKFILL` **[PLANNED]** |
| **[EXISTS (built 2026-08)]** Index board | `GET /api/v1/admin/search/indices` | — | None (read) | no | read, interceptor only |
| **[EXISTS (built 2026-08)]** Index health + drift | `GET /api/v1/admin/search/health` | — | None (read) | no | read, interceptor only |
| **[EXISTS (built 2026-08)]** Top queries | `GET /api/v1/admin/search/analytics/top-queries` | `scope=ALL`, `days≤7`, `limit` | None | no | read |
| **[EXISTS (built 2026-08)]** Zero-result queries | `GET /api/v1/admin/search/analytics/zero-results` | `scope=ALL`, `days≤7`, `limit` | None | no | read |
| **[EXISTS (built 2026-08)]** Force trending rebuild | `POST /api/v1/admin/trending/rebuild` | — | Low | no | `ADMIN_TRENDING_REBUILD` |
| **[EXISTS (built 2026-08)]** Pin a tag | `POST /api/v1/admin/trending/overrides` | body: `tag`, `scope`, `type=PIN`, `rank?`, `expiresAt?`, `reason` | **High** — editorializes a public surface | **yes** | audited (`AdminAuditor`) |
| **[EXISTS (built 2026-08)]** Ban a tag | same endpoint, `type=BAN` | `tag`, `scope\|ALL`, `expiresAt?`, `reason` | **High** | **yes** | audited |
| **[EXISTS (built 2026-08)]** Remove override | `DELETE /api/v1/admin/trending/overrides/{id}` | — | Medium | yes | audited |
| **[EXISTS (built 2026-08)]** List overrides | `GET /api/v1/admin/trending/overrides` | `scope?`, `active?` | None | no | read |
| **[EXISTS (built 2026-08)]** Hide / unhide tag | `POST` / `DELETE /api/v1/admin/tags/{tag}/hide` | `scope?` | High (public surface) | yes | audited |
| **[EXISTS (built 2026-08)]** Merge tags | `POST /api/v1/admin/tags/merge` | `from`, `to` | High | yes | audited |
| **[EXISTS (built 2026-08)]** Read feed config | `GET /api/v1/admin/feed/config` | — | None | no | read |
| **[EXISTS (built 2026-08)]** Change feed weights | `PATCH /api/v1/admin/feed/config` (step-up) | partial body incl. `rolloutPercent` | **Critical** — reshapes every user's feed platform-wide | **yes** | `FEED_CONFIG_CHANGED` (old→new) |
| **[EXISTS (built 2026-08)]** Score preview | `POST /api/v1/admin/feed/preview` | body: `userId`, `overrides?` — shadow-scores baseline vs proposed with rank deltas | None (read-only) | no | read |
| **[EXISTS (built 2026-08)]** Knob registry / score explainer | `GET /api/v1/admin/feed/weights`, `GET /api/v1/admin/feed/explain/{userId}` | — | None | no | `ADMIN_FEED_EXPLAIN` |
| **[EXISTS (built 2026-08)]** Affinity inspect | `GET /api/v1/admin/feed/affinity/{userId}` | `limit` | Low (behavioral data — access is logged) | no | `ADMIN_FEED_AFFINITY_VIEW` |
| **[PLANNED]** PYMK metrics | `GET /api/v1/admin/suggestions/metrics` | `window` | None | no | read |
| **[PLANNED]** PYMK recompute | `POST /api/v1/admin/suggestions/recompute/{userId}` | — | Low | no | `PYMK_RECOMPUTED` |

---

## 5. Logs surfaced in this section

Full log catalog: [logs-audit.md](logs-audit.md). Shown here:

| Log | Store | What this section shows |
|-----|-------|-------------------------|
| Admin audit rows for reindex/backfill calls **[EXISTS]** | Cassandra `audit_log_by_user` (+ `by_resource`, resourceType `Search`) via the audit interceptor | Run history table in the reindex console (who, when, status, `duration_ms`) |
| `[TRENDING]` job lines **[EXISTS]** | Console only (`TrendingTagJob` — per-scope debug + "rebuild scope X failed" WARN) | Rebuild status strip — needs log-shipping or a job-status row **[PLANNED]** |
| `[BACKFILL]` lines **[EXISTS]** | Console only (`TagAdminController`) | Backfill card detail |
| `EsRetry` recovery DEBUGs **[EXISTS]** | Console only | Degradation ticker (recovered-after-retry volume) |
| Per-user search events **[EXISTS]** | Cassandra `activity_by_user` (`GLOBAL_SEARCH`/`HASHTAG_SEARCH` with `query`, `hit_count`) | **Not surfaced** — per-user private history; the global analytics view uses the new collector instead (§6.1, privacy note §8) |
| Search query log **[EXISTS (built 2026-08)]** | `search_queries_by_bucket` (90 d TTL) + Redis `irc:search:top/zero` (8 d) | Top-queries / zero-result widgets |

---

## 6. Analytics & KPIs

| Metric | Definition | Source | Chart |
|--------|------------|--------|-------|
| Index doc count (×8) | `_count` per index | `GET /admin/search/indices` **[EXISTS (built 2026-08)]** | Stat tiles |
| Index drift % | (canonical count − ES count) / canonical | `GET /admin/search/health` **[EXISTS (built 2026-08)]** (PG-canonical indices; Cassandra-canonical = unknown) | Bar per index |
| Reindex duration / outcome | Wall-clock + indexed/failed per run | `audit_log_by_user.duration_ms` **[EXISTS]** + `ReindexResult`/`ReindexSummary` bodies | Table + line |
| Queries/day, by scope | Count of logged queries | Collector §6.1 **[EXISTS (built 2026-08)]** | Line, stacked by scope |
| Zero-result rate | queries with `hitCount=0` ÷ total | Collector **[EXISTS (built 2026-08)]** | Line + ranked table |
| Degraded-search rate | responses with `degraded:true` ÷ total | `GlobalSearchService` flag, needs counter **[PLANNED]** | Line |
| Trending tags/scope | Rows in `trending_tags` per scope (≤100) | Cassandra **[EXISTS]** | Stat tiles (0 = alert, §7) |
| Trending turnover | % of top-20 changed between rebuilds | Snapshot diffing **[PLANNED]** | Line |
| Feed p95 latency | Ranked-page build time | Micrometer timer **[PLANNED]** | Line |
| Injection rates | digest items, live-rail size, explore size per first-page | Micrometer counters **[PLANNED]** | Stacked area |
| Affinity coverage | % of delivered items with affinity > 0 for viewer | Sampled from `FeedRankingService` **[PLANNED]** | Line |
| PYMK source mix | Served suggestions by `primarySource` (6 sources) | Impression counter **[PLANNED]** | Donut |
| PYMK dismissal rate | dismissals ÷ served | `SuggestionDismissal` (PG) **[EXISTS]** ÷ impressions **[PLANNED]** | Line |
| PYMK follow-through | follows within 7d of being served | `user_follows` join vs impression log **[PLANNED]** | Line |
| Contact-sync match rate | matched hashes ÷ uploaded hashes; % users with ≥1 match | SQL over `user_contact_hashes` (**[EXISTS]** data, **[PLANNED]** query/endpoint) | Funnel |

### 6.1 Query-log collector **[EXISTS (built 2026-08)]** — `SearchQueryLogService`

| Aspect | Implementation |
|--------|--------|
| Hook | `GlobalSearchService` head requests call `SearchQueryLogService.record(rawQuery, types, resultCount, degraded)` — fail-open, best-effort; degraded searches are excluded from zero-result stats |
| Normalization | trim, casefold, collapse whitespace, length-capped |
| Hot store | Redis `ZINCRBY irc:search:top:{scope}:{yyyyMMdd}` + `irc:search:zero:{scope}:{yyyyMMdd}` (when `hitCount=0`, plus an `ALL` roll-in), 8-day expiry — top-K reads are one `ZREVRANGE` |
| Durable store | Cassandra `search_queries_by_bucket` (day-bucketed, `query_norm`, `scope`, `hit_count`) with 90 d table TTL |
| Privacy | **No userId** in the global log (not even hashed) — the per-user copy already exists in `activity_by_user` under the user's own control; global log is content-only |

### 6.2 Trending overrides **[EXISTS (built 2026-08)]**

PG table `trending_tag_overrides` (`TrendingTagOverride` entity) + the
`TrendingTagJob` change: active overrides are loaded per run, banned tags
dropped before the top-K cut, pins spliced at their rank after it. Managed by
`AdminTrendingController` (`GET/POST /api/v1/admin/trending/overrides`,
`DELETE …/{id}`, `POST …/rebuild` — pin/ban are step-up + audited).
`TagAdminController` is no longer backfill-only: it also ships
`POST/DELETE /api/v1/admin/tags/{tag}/hide` and `POST /api/v1/admin/tags/merge`.
Snapshot stays the single read path, so the public endpoint needed
no change and overrides take effect within one refresh interval (≤10 min).

### 6.3 Feed tuning **[EXISTS (built 2026-08) — backend]**

1. The §2.3.1 constants are overridable via a **single-row PG
   `feed_ranking_config`** read through `FeedTuningService` (30 s cache,
   invalidated on admin change) and threaded into
   `HomeFeedService` / `FeedRankingService.Knobs` — defaults identical to the
   constants, so an absent row is a no-op.
2. `GET/PATCH /api/v1/admin/feed/config` (step-up; audited
   `FEED_CONFIG_CHANGED` old→new, the `settings_audit` shape).
3. **Preview before apply**: `POST /api/v1/admin/feed/preview` shadow-scores a
   sample user's page — baseline vs proposed — and shows rank deltas.
4. Staged rollout via `rolloutPercent` (sticky percentage bucket by userId
   hash). Dashboard UI itself is still the frontend build.

---

## 7. Alerts & thresholds

| Alert | Condition | Severity | Status |
|-------|-----------|----------|--------|
| Index drift | ES count deviates >5% from canonical store for 2 checks | Warning | **[PLANNED]** |
| Reindex failed | non-2xx or `failed > 0` in reindex response | Critical (search partially empty if `drop=true` ran) | **[PLANNED]** (result data **[EXISTS]**) |
| Empty trending scope | 0 rows in `trending_tags` for any of the 5 scopes after a rebuild cycle — the exact failure the job's SCOPES comment warns about | Warning | **[PLANNED]** |
| Trending rebuild failing | "[TRENDING] rebuild scope X failed" WARN seen ≥2 consecutive runs | Warning | **[PLANNED]** |
| Zero-result spike | zero-result rate > 25% over 1h | Info (content/mapping gap) | **[PLANNED]** |
| Degraded search | `degraded:true` responses > 1% over 15 min, or `EsRetry` second-attempt failures rising | Critical (ES down/unstable) | **[PLANNED]** |
| Feed latency | ranked first-page p95 > 1.5s over 15 min | Warning | **[PLANNED]** |
| Live-rail starvation | live-rail fetch exceptions (`[HOME-FEED] live rail unavailable`) > threshold | Info | **[PLANNED]** |
| Fan-out lag | per-post fan-out duration p95 > 30s, or timeline-cache write failures > 1% (stale home feeds for followers of viral accounts) | Warning | **[PLANNED]** (needs the §2.3 fan-out counters first) |
| Backfill re-run guard | second `backfill-posts` call within 30d → require typed confirmation citing counter inflation | Guard-rail | **[PLANNED]** |
| PYMK dismissal spike | dismissal rate doubles week-over-week (bad suggestion quality or a scoring regression) | Info | **[PLANNED]** |

---

## 8. Permissions & safety notes

- **Gating**: all existing endpoints here (`/api/v1/admin/search/**`,
  `/api/v1/admin/tags/**`) already sit under `/api/v1/admin/**` and get the
  filter-chain double gate — keep every planned endpoint under the same
  prefix ([architecture.md](../foundation/architecture.md)).
- **Reindex is synchronous and disruptive** **[EXISTS]**: all 7 runs block the
  request thread and return final counts (`SearchAdminController` javadoc —
  "all runs are synchronous"); with `drop=true` the index is **deleted first**,
  so that entity type returns empty/partial results until the rebuild
  finishes. `EsRetry.isIndexNotFound` deliberately treats the missing index as
  benign-empty (no `degraded` flag), so users see silence, not errors.
  **[PLANNED]**: wrap runs in an async job with progress + audit; until then
  the dashboard must show an "index offline during run" warning.
- **Dynamic-mapping gotcha** (why `drop` defaults to `true`): indices created
  by dynamic mapping (the `EntityAsMap` era) mapped lifecycle fields as
  `text`, breaking `visibility`/`status`/`postType` term filters;
  `drop=true` recreates from the current entity `@Field` mapping and is the
  documented repair path ([../search/indexing-and-reindex.md](../../search/indexing-and-reindex.md)).
  `drop=false` only refreshes documents/score counters and cannot fix a bad
  mapping.
- **`irc-chat-messages` has no reindex hook by design** — it self-heals on
  message mutation, and disappearing messages are deliberately never indexed
  (privacy fix). Do not add a chat reindex without revisiting that boundary
  ([chat-channels-live.md](../communication/chat-channels-live.md)). Note the
  `SearchAdminController` class javadoc understates its own surface (lists 5
  indices, ships 7 endpoints) — trust the endpoint list.
- **Backfill is not idempotent for trending**: tag-row upserts are safe;
  `tag_counters` increments are not. Treat as run-once-per-migration.
- **Trending overrides are editorial power**: pin/ban shapes a public surface
  — require step-up, a mandatory reason, an expiry, and surface active
  overrides in the audit log ([logs-audit.md](logs-audit.md)). Banned-tag
  policy belongs with [content-moderation.md](../trust-safety/content-moderation.md).
- **Feed config changes are platform-wide**: a bad weight silently reshapes
  every feed. Step-up + preview + staged rollout + revert are mandatory
  (§6.3), and changes must be recorded old→new.
- **Query-log privacy**: search text can contain personal data. The global
  collector stores normalized query text only — never a userId, IP, or
  session; retention-capped (§6.1). The per-user record stays exclusively in
  `activity_by_user` where the user can delete it.
- **Affinity data is behavioral profiling** — viewer-level `user_author_affinity`
  reads should themselves be audited (`AFFINITY_VIEWED`).
- **Contact-sync numbers are aggregate-only**: `user_contact_hashes` is
  HMAC-peppered (raw phones never stored); the dashboard must only ever show
  counts/rates, never per-user hash rows. Consent evidence lives in
  `consent_events` ([safety-reports.md](../trust-safety/safety-reports.md)).

---

## 9. Build order / dependencies

> **Status (built 2026-08):** phases 1 (health/doc-count endpoints), 4
> (query-log collector + analytics endpoints), 5 (trending overrides +
> force-rebuild + tag hide/merge) and 6 backend (feed config externalization,
> preview, explainer, affinity inspector) are shipped. Still open: reindex
> console UI (phase 2), Micrometer feed-funnel counters (phase 3), and PYMK
> impression instrumentation (phase 7).

| Phase | Deliverable | Depends on |
|-------|-------------|------------|
| 1 | **Read-only surfacing**: index health + doc-count proxy (`GET /admin/search/health`), trending leaderboard viewer over existing `trending_tags`, knob-registry + PYMK weight sheets rendered from constants, contact-sync SQL rollup | Nothing new — reads only |
| 2 | **Console over existing actions**: reindex console UI (7 endpoints) with confirm + audit-row history, backfill card with re-run guard | Phase 1; audit interceptor rows (already written) |
| 3 | **Job visibility**: trending rebuild status (persist a per-scope job-status row or ship logs), force-rebuild endpoint; Micrometer counters for feed funnel/injection + actuator exposure (coordinate with [operations.md](operations.md) — `/actuator/prometheus` is currently not exposed) | Phase 1 |
| 4 | **Query-log collector** (§6.1) → top-queries + zero-result widgets and alerts | New writer + stores; independent of phases 2–3 |
| 5 | **Trending overrides** (§6.2): table + `TrendingTagJob` change + endpoints + UI | Phase 3 (rebuild visibility first, so override effects are observable) |
| 6 | **Feed config externalization + tuning UI** (§6.3), score explainer, affinity inspector | Phase 3 metrics (never tune blind); step-up plumbing ([architecture.md](../foundation/architecture.md)) |
| 7 | **PYMK instrumentation**: impression counters → source mix, dismissal & follow-through rates; per-user recompute tool | Phase 4's collector pattern reused |

Cross-references: [admin-api-blueprint.md](../foundation/api-blueprint.md) (endpoint
master list), [analytics-kpis.md](analytics-kpis.md) (KPI tree),
[research-qna.md](../content/research-qna.md) (tag usage inside Research/QnA — this doc
owns trending *operations*), [logs-audit.md](logs-audit.md) (log catalog).
