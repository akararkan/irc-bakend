# Admin Dashboard — Section 3: Research & Q&A

The academic heart of the platform: the research pipeline (drafts → scheduled/manual publish → archive/retract), the IRC identifier system, download/citation analytics, scholar Q&A oversight, and the unified tag/trending subsystem. Underlying mechanics: [../research/research.md](../research/research.md), [../qna/questions.md](../qna/questions.md), [../qna/answers.md](../qna/answers.md), [../search/indexing-and-reindex.md](../search/indexing-and-reindex.md). Tag legend and ground rules: [README.md](README.md).

## 1. Purpose & scope

| In scope | Out of scope (see) |
|---|---|
| Research lifecycle oversight, scheduled-publisher monitoring, takedown/unpublish, integrity flags | Post/reel/story/sound moderation → [content-moderation.md](content-moderation.md) |
| IRC identifier (internal "DOI") registry, sources, contributors, research media | Report triage & strikes → [safety-reports.md](safety-reports.md) |
| Download analytics (PG + Cassandra), top-downloaded / top-cited | Media pipeline internals → [media-storage.md](media-storage.md) |
| Q&A moderation, author-accept health, scholar activity | ES architecture & other 5 indices → [search-feed-trending.md](search-feed-trending.md) |
| Tags/trending admin (backfill, merge/block), research+qna+answers reindex | Cross-platform KPI tree → [analytics-kpis.md](analytics-kpis.md) |

**Design constraint carried over from the product**: Q&A has **no answer rating/feedback system — by design** ("academic not entertainment"). The only quality signal is author-accept (`acceptAnswer`/`unacceptAnswer`). This dashboard measures accept behavior; it must **not** propose re-adding ratings, votes, or scholar best-answer voting.

### Reality snapshot

| Capability | Status |
|---|---|
| Research lifecycle endpoints (publish/unpublish/archive/retract/delete) | **[EXISTS]** — but **owner-only**, no admin override (`ResearchServiceImpl.findResearchOwnedByOrThrow` is a strict `equals` on researcher id) |
| Scheduled publisher | **[EXISTS]** `research/job/ScheduledPublishJob` — the *single* publisher (a second one was removed to kill a publish race; never re-add) |
| IRC identifier system | **[EXISTS]** `IRC-{YEAR}-{6-digit-seq}` — internal, **not a real DOI** |
| Download logging | **[EXISTS]** dual-store: PG `research_downloads` + Cassandra `research_downloads_by_research` |
| Top-downloaded / top-cited views | **[PLANNED]** (data columns exist; no query/endpoint) |
| Plagiarism / quality flags | **[PLANNED]** (nothing exists) |
| Q&A admin moderation | **[PARTIAL]** — `Role.ADMIN` bypass inside `canManageQuestion`/`canManageAnswer` lets admins edit/delete/lock via the *normal* endpoints; no admin browse/queue |
| Question CLOSED / ARCHIVED | **[PARTIAL]** — enum values enforced as answer-blocking (`QuestionServiceImpl` L465) but **no code ever sets them** |
| Tag admin | **[PARTIAL]** — only `POST /api/v1/admin/tags/backfill-posts`; no merge/block/rename |
| ES reindex for research/qna/answers | **[EXISTS]** `SearchAdminController` |

## 2. Dashboard views & widgets

### 2.1 Research pipeline board
| Widget | On screen | Status |
|---|---|---|
| Status funnel | Count cards DRAFT / PUBLISHED / ARCHIVED / RETRACTED (+ soft-deleted), % week-over-week | **[PLANNED]** (trivial SQL over `research.status`) |
| Scheduled queue | Table of DRAFT rows with `scheduled_publish_at` set: title, owner, due-in, overdue highlight | **[PLANNED]** — same filter the job uses (`ResearchRepository.findDueForScheduledPublish`) **[EXISTS]** |
| Publisher job monitor | Last sweep time, drafts published, per-item failures (from `[SCHED-PUBLISH]` log lines), config values `app.research.scheduled-publish-ms` (60 s default) / `-initial-ms` (30 s) | **[PLANNED]** widget; job **[EXISTS]** |
| Research browser | Filterable list (status, visibility PUBLIC/FOLLOWERS_ONLY/PRIVATE, researcher, date, ircId search) → detail drawer | **[PLANNED]** |
| Research detail drawer | Abstract, counters row (7 counters), IRC identifier + HMAC verification state, sources (URL/ISBN/MEDIA_FILE/MANUAL), contributors (CO_AUTHOR/ADVISOR/REVIEWER/TRANSLATOR/EDITOR/CONTRIBUTOR), media (promo video, cover, files), takedown button | **[PLANNED]** drawer; all underlying data **[EXISTS]** |
| Integrity flags panel | Plagiarism-suspect / low-quality flag queue with reviewer notes | **[PLANNED]** end-to-end |

### 2.2 Download & citation analytics
| Widget | On screen | Status |
|---|---|---|
| Downloads/day chart | Area chart, 30/90-day windows, split registered vs anonymous | **[PLANNED]** query over PG `research_downloads.created_at` **[EXISTS]** |
| Recent downloads (per research) | Who / which media file / IP / when — last N | Data + reader **[EXISTS]** (`CassandraResearchEngagementService.recentDownloads`), endpoint **[PLANNED]** |
| Top-downloaded table | Rank, title, owner, `download_count`, 7-day delta | **[PLANNED]** (`ORDER BY download_count DESC` — column **[EXISTS]**) |
| Top-cited table | Same over `citation_count` (fed by `POST /api/v1/researches/{id}/cite` with 30-day per-citer dedup **[EXISTS]**) | **[PLANNED]** |
| Dedup-window note (fixed caption) | Numbers are shaped by dedupe: 90 d/user/media, 1 h/IP anonymous, **fail-open** if Redis is down (`ResearchDownloadTracker`) | doc note |

### 2.3 Q&A oversight
| Widget | On screen | Status |
|---|---|---|
| Question funnel | Cards OPEN / ANSWERED / CLOSED / ARCHIVED (CLOSED/ARCHIVED will read 0 until §4 adds setters) | **[PLANNED]** |
| Unanswered backlog | Questions with `answer_count = 0` older than N days, sorted oldest-first | **[PLANNED]** |
| Accept health | Accepted-answer rate gauge + trend; locked-questions list (`answers_locked = true`) | **[PLANNED]** (columns `accepted_answer_count`, `answers_locked` **[EXISTS]**) |
| Moderation console | Question/answer detail with admin edit/delete/lock actions — these route to the **existing** endpoints via the ADMIN bypass | actions **[EXISTS]**, console **[PLANNED]** |
| Scholar activity board | Active scholars/week, top question authors, top answerers, accepts given/received | **[PLANNED]** (SQL over `questions`/`question_answers` author ids) |

### 2.4 Tags & trending
| Widget | On screen | Status |
|---|---|---|
| Trending viewer | Top-100 per scope tabs ALL / QUESTION / RESEARCH / POST / REEL (reads the same snapshot as `GET /api/v1/tags/trending?scope=` **[EXISTS]**) | **[PLANNED]** admin view |
| Tag inspector | Per-tag usage by scope (`GET /api/v1/tags/{tag}/usage` **[EXISTS]**) + tagged-content sample (`GET /api/v1/tags/{tag}/content` **[EXISTS]**) | **[PLANNED]** view |
| Trending job monitor | Last rebuild per scope, refresh interval (`app.tags.trending-refresh-ms`, 10 min default), staleness alert | **[PLANNED]**; `TrendingTagJob` **[EXISTS]** |
| Backfill runner | One-shot post-hashtag backfill with big red non-idempotency warning + last-run result `{postsScanned, postsWithHashtags, tagRowsWritten, startedAt}` | **[EXISTS]** endpoint, **[PLANNED]** UI |
| Merge / block manager | Merge misspelled tags, block tags from trending | **[PLANNED]** end-to-end (nothing exists) |

### 2.5 Index operations (research/qna slice)
Reindex buttons for `irc-research`, `irc-qna`, `irc-answers` with `drop` toggle and last-run counts — endpoints **[EXISTS]** (§4); full index-health board lives in [search-feed-trending.md](search-feed-trending.md).

## 3. Data sources

| Widget | Source | Status |
|---|---|---|
| Status funnel, browser, scheduled queue | PG `research` (`status`, `visibility`, `scheduled_publish_at`, `published_at`, `deleted_at`; indexes incl. `idx_research_status`, `idx_research_irc_id`) — `research/entity/Research.java` | **[EXISTS]** table, **[PLANNED]** admin queries |
| Publisher monitor | `ScheduledPublishJob.publishDueResearch` + `[SCHED-PUBLISH]` log lines; delegates to canonical `ResearchService.publish` (full fan-out: ES, @mentions, trending tags, `RESEARCH_PUBLISHED` broadcast), one tx per item | **[EXISTS]** |
| IRC identifier | `irc_sequence_number` (PG sequence `research_irc_seq`), `irc_id` = `IRC-{YEAR}-{6-digit-seq}`, HMAC-SHA256 `irc_verification_hash` keyed by `IRC_VERIFICATION_SECRET`, verification URL — `research/migration/V2__add_irc_identifier_system.sql` | **[EXISTS]** — internal scheme; Crossref/DataCite real-DOI integration **[PLANNED]** |
| Counters row | PG `research` denormalized columns `view_count, download_count, reaction_count, comment_count, save_count, share_count, citation_count` (source of truth), mirrored to Redis `CounterCache` kind RESEARCH (`c:r:{id}`), whole-value broadcast on research SSE | **[EXISTS]** |
| Sources / contributors / media | PG `ResearchSource` (SourceType URL/ISBN/MEDIA_FILE/MANUAL), `ResearchContributor` (6 roles; eligibility RESEARCHER/SCHOLAR/ADMIN), `ResearchMedia` + promo-video/cover endpoints on `ResearchController` | **[EXISTS]** |
| Download log (relational) | PG `research_downloads` (`research_id`, `media_id` nullable = whole bundle, `user_id` nullable = anonymous, `ip_address`, `created_at` via `BaseAuditEntity`) written by `rabbitmq/consumer/ResearchAnalyticsConsumer` off queue `irc.queue.analytics` (routing `research.analytics.downloaded`) alongside `incrementDownloadCount` | **[EXISTS]** |
| Download log (recent-N) | Cassandra `research_downloads_by_research` (`research_id` P, `created_at` DESC, `download_id`, `user_id`, `media_id`, `ip_address`) via `CassandraResearchEngagementService.mirrorDownload` / `.recentDownloads` | **[EXISTS]** |
| Download dedupe | Redis `irc:rdownload:dedupe:u:{researchId}:{mediaId}:{userId}` 90 d, `…:a:{…}:{ip}` 1 h — `research/realtime/ResearchDownloadTracker`, fail-open | **[EXISTS]** |
| Citations | `ResearchServiceImpl.incrementCitationCount` + `DedupGuard` 30 d per (research, citer); **counter only — no per-event citation log** | **[EXISTS]** counter, **[PLANNED]** event log |
| Q&A funnel / backlog / accept health | PG `questions` (`status`, `answer_count`, `view_count`, `accepted_answer_count`, `answers_locked`, `max_answers`, `deleted_at`, `idx_question_status`), `question_answers` (`is_accepted`, `reaction_count`, `reply_count`, `deleted_at` — **no `accepted_at` column**) | **[EXISTS]** tables, **[PLANNED]** admin queries |
| Scholar activity | Author ids on `questions` / `question_answers` joined to `users.role` (roles: question authors = SCHOLAR/ADMIN only; answers = SCHOLAR/RESEARCHER/ADMIN — `findScholarOrThrow` / `findAnswerAuthorOrThrow`) | **[EXISTS]** data |
| Trending viewer / inspector | Cassandra `tag_counters` (scope partition, `usage_count` counter) + `trending_tags` top-100 snapshot rebuilt by `TrendingTagJob`; research counts **only when published**, retract/unpublish/archive `untag()` | **[EXISTS]** |
| Reindex counts | Synchronous `ReindexResult`/`ReindexSummary` response bodies of `SearchAdminController` | **[EXISTS]** (response-only; not stored) |

## 4. Admin actions

Existing actions first, then proposals. All proposed routes live under `/api/v1/admin/**` (double-gated). Danger: L=low M=medium H=high C=critical. Step-up = re-auth via [../settings/README.md](../settings/README.md) step-up flow.

| Action | Endpoint | Params | Danger | Step-up | Audit action | Status |
|---|---|---|---|---|---|---|
| Reindex research index | `POST /api/v1/admin/search/research/reindex` | `drop` (default true — deletes index first; drop=false refreshes score counters only; PUBLISHED rows only) | M (search gap during sync run) | No | auto (interceptor, §5) | **[EXISTS]** |
| Reindex questions index | `POST /api/v1/admin/search/questions/reindex` | `drop` | M | No | auto | **[EXISTS]** |
| Reindex answers index | `POST /api/v1/admin/search/answers/reindex` | `drop` (reanswers included) | M | No | auto | **[EXISTS]** |
| Post-hashtag backfill | `POST /api/v1/admin/tags/backfill-posts` | none; full token-range scan; **trending counter increments NOT idempotent — do not re-run casually** | H | **Yes (proposed)** | auto | **[EXISTS]** |
| Admin edit/delete/lock question | `PATCH|DELETE /api/v1/questions/{id}`, `POST|DELETE /api/v1/questions/{id}/lock-answers`, `PATCH …/answer-limit` | normal params; ADMIN passes `canManageQuestion` | M–H | No today | auto | **[EXISTS]** (bypass, not an admin route) |
| Admin delete/edit answer | `PATCH|DELETE /api/v1/questions/{qId}/answers/{aId}` | ADMIN passes `canManageAnswer` | M | No today | auto | **[EXISTS]** (bypass) |
| Browse research | `GET /api/v1/admin/research` | `status,visibility,researcherId,q,ircId,page` | L | No | auto | **[PLANNED]** |
| Research detail (admin) | `GET /api/v1/admin/research/{id}` | — (incl. PRIVATE/FOLLOWERS_ONLY rows) | L | No | auto | **[PLANNED]** |
| Recent downloads (admin) | `GET /api/v1/admin/research/{id}/downloads` | `limit` (Cassandra recent-N reader exists) | L | No | auto | **[PLANNED]** endpoint over **[EXISTS]** reader |
| Top-downloaded / top-cited | `GET /api/v1/admin/research/top` | `by=downloads\|citations&window` | L | No | auto | **[PLANNED]** |
| Admin unpublish (takedown to draft) | `POST /api/v1/admin/research/{id}/unpublish` | `reason` (required) | H | **Yes** | `RESEARCH_ADMIN_UNPUBLISH` | **[PLANNED]** — mirror owner path: status→DRAFT, `publishedAt=null`, ES `deleteAsync`, `untag()`, notify author |
| Admin retract | `POST /api/v1/admin/research/{id}/retract` | `reason` | H | **Yes** | `RESEARCH_ADMIN_RETRACT` | **[PLANNED]** — RETRACTED keeps the tombstone public-record style, unlike unpublish |
| Admin hard-delete research | `DELETE /api/v1/admin/research/{id}` | `reason` | C | **Yes** | `RESEARCH_ADMIN_DELETE` | **[PLANNED]** — cascades incl. `ResearchDownloadRepository.deleteAllByResearchId` **[EXISTS]** |
| Set integrity flag | `POST /api/v1/admin/research/{id}/flags` | `type=PLAGIARISM\|QUALITY, note` | M | No | `RESEARCH_FLAGGED` | **[PLANNED]** (new entity + queue) |
| Close / archive question | `POST /api/v1/admin/questions/{id}/close` / `/archive` (+ reopen) | `reason` | M | No | `QUESTION_CLOSED` etc. | **[PLANNED]** — enum values CLOSED/ARCHIVED already enforced as answer-blocking, just unreachable |
| Browse questions (admin) | `GET /api/v1/admin/questions` | `status,authorId,unanswered,lockedOnly,q` | L | No | auto | **[PLANNED]** |
| Merge tags | `POST /api/v1/admin/tags/merge` | `from,to,scope?` — rewrites `content_by_tag` rows + counter transfer (counter tables can't be atomically moved; document drift) | H | **Yes** | `TAG_MERGED` | **[PLANNED]** |
| Block tag from trending | `POST /api/v1/admin/tags/{tag}/block` + `DELETE` | scope? — blocklist consulted by `TrendingTagJob` rebuild | M | No | `TAG_BLOCKED` | **[PLANNED]** |

**Note on ownership asymmetry (verified):** research lifecycle has **no** admin override — `findResearchOwnedByOrThrow` throws for anyone but the owner, so the admin unpublish/retract/delete rows above are genuinely new server code, not just new routes. Q&A is the opposite: the override already exists in the service layer and only lacks an admin-facing surface.

## 5. Logs surfaced in this section

| Log | Store | What this section shows | Status |
|---|---|---|---|
| Request audit (`audit_log_by_resource`) | Cassandra | "What happened to this Research/Question/Answer" — `AuditLoggingInterceptor.parseResource` already maps `research→Research`, `questions→Question`, `answers/reanswers/replies→QuestionAnswer`; **no read endpoint exists** — surfacing it here depends on the by-resource reader proposed in [logs-audit.md](logs-audit.md) | **[PARTIAL]** (written, unreadable) |
| Request audit (`audit_log_by_user`) | Cassandra | Per-admin action history via `GET /api/v1/admin/audit?userId=` + SSE tail `GET /api/v1/admin/audit/stream` | **[EXISTS]** |
| `[SCHED-PUBLISH]` lines | app log (console) | Publisher sweep results + per-draft failures ("could not auto-publish {id}") — the only failure record; no file appender configured | **[EXISTS]** (log-only) |
| `research_downloads` / `research_downloads_by_research` | PG + Cassandra | Download analytics widgets (§2.2) | **[EXISTS]** |
| `[RABBIT-DLQ]` drain lines | app log | Dead-lettered `research.analytics.downloaded` events = silently lost download counts; see [operations.md](operations.md) | **[EXISTS]** (log-only) |
| `activity_by_user` QnA/research event types | Cassandra | **Not surfaced** — per-user private history, partitioned by user; unusable for admin aggregation by design ([logs-audit.md](logs-audit.md)) | boundary note |
| Business-event audit (`AuditLogService.record`) | Cassandra | Proposed writer for `RESEARCH_ADMIN_*`, `QUESTION_CLOSED`, `TAG_MERGED` rows with reasons — helper **[EXISTS]** but has zero callers today; this section's mutations should be its first | **[PLANNED]** usage |

## 6. Analytics & KPIs

| Metric | Definition | Source | Chart | Status |
|---|---|---|---|---|
| Research published/week | count of `status=PUBLISHED` grouped by week(`published_at`) | PG `research` | line | **[PLANNED]** query, **[EXISTS]** data |
| Scheduled-publish share | % of publishes performed by `ScheduledPublishJob` vs manual | `[SCHED-PUBLISH]` log or new marker column | bar | **[PLANNED]** (needs marker — actor is the owner either way) |
| Downloads/day | rows/day in `research_downloads`, split user vs anonymous (`user_id IS NULL`) | PG | area | **[PLANNED]** query, **[EXISTS]** data |
| Unique downloaders (30 d) | distinct `user_id` in window | PG | stat tile | **[PLANNED]** |
| Top-10 downloaded / cited | `ORDER BY download_count / citation_count DESC` | PG `research` | table | **[PLANNED]** |
| Citation velocity | citations/week — **needs a per-event citation log**; today only lifetime `citation_count` + Redis 30 d dedupe exists | new PG table | line | **[PLANNED]** |
| Answer rate | questions with `answer_count > 0` / total (window by `created_at`) | PG `questions` | gauge + line | **[PLANNED]** |
| Accepted-answer rate | questions with `accepted_answer_count > 0` / questions with ≥1 answer | PG `questions` | gauge | **[PLANNED]** |
| Time-to-accept | median(accept time − question `created_at`) — **blocked: `question_answers` has `is_accepted` but no `accepted_at`**; add column or derive from audit-by-resource accept rows | PG (+schema add) | box/median line | **[PLANNED]** |
| Time-to-first-answer | median(first answer `created_at` − question `created_at`) | PG `question_answers` | line | **[PLANNED]** (computable today) |
| Unanswered backlog age | p90 age of `answer_count=0` questions | PG | stat tile | **[PLANNED]** |
| Scholar engagement | distinct scholars authoring a question/answer/accept per week | PG join `users.role` | line | **[PLANNED]** |
| Trending scope health | top usage_count per scope; QUESTION+RESEARCH feed the daily `TRENDING_DIGEST` (09:00 UTC, floor-gated) | Cassandra `tag_counters`/`trending_tags` | table | **[EXISTS]** data, **[PLANNED]** panel |
| Reindex outcome | docs indexed/failed per run | `ReindexResult` response | last-run tile | **[EXISTS]** (ephemeral — propose persisting run history) |

Honest-numbers caveats: download counts are dedupe-shaped (90 d/user, 1 h/anon-IP) and **fail-open** (Redis outage ⇒ overcount); download events ride RabbitMQ (DLQ loss ⇒ undercount); `citation_count` dedupes 30 d per citer.

## 7. Alerts & thresholds

| Alert | Condition | Severity | Source | Status |
|---|---|---|---|---|
| Scheduled publisher failing | any `could not auto-publish` in last sweep, or same draft failing > 3 sweeps | warn / page at > 10 | `[SCHED-PUBLISH]` log | **[PLANNED]** |
| Publisher stalled | oldest due draft overdue > 10 min (scheduler pool is 4 threads, shared with 9 SSE heartbeats — see [operations.md](operations.md)) | page | PG query | **[PLANNED]** |
| Download spike | per-research downloads > N×(30 d avg) in 1 h — scraping/abuse signal | warn | PG rollup | **[PLANNED]** |
| Analytics DLQ | any `research.analytics.downloaded` dead-lettered | warn | `[RABBIT-DLQ]` | **[PLANNED]** wiring |
| Empty reindex | reindex returns 0 docs after `drop=true` (index now empty = search outage for that domain) | page | `ReindexResult` | **[PLANNED]** |
| Trending staleness | `trending_tags` scope partition not rewritten within 2× refresh interval | warn | job timestamp | **[PLANNED]** |
| Accept-rate drop | accepted-answer rate 7 d < 0.7 × 90 d baseline | info | KPI store | **[PLANNED]** |
| Unanswered backlog | > K questions unanswered > 7 d | info | PG | **[PLANNED]** |

## 8. Permissions & safety notes

- All existing endpoints in this section are `hasRole('ADMIN')` **and** under `/api/v1/admin/**`, so they get the filter-chain double gate — keep every proposed route under that prefix (lesson from the two annotation-only stragglers documented in [architecture.md](architecture.md)).
- **Q&A admin bypass is silent today**: an admin deleting a scholar's answer via `DELETE /api/v1/questions/{qId}/answers/{aId}` is indistinguishable from an author delete except in the audit log. Proposed: require a `reason` param when actor ≠ author, record a business-event audit row, and notify the author.
- **Do not re-add ratings/feedback/best-answer voting** to Q&A — removed deliberately; accept-only is the product decision this dashboard measures, not a gap it fixes.
- Takedowns must replicate the owner path's full fan-out (ES delete, trending `untag`, realtime broadcast) or ghosts remain in search/trending; wire `Resolution.CONTENT_REMOVED` from [safety-reports.md](safety-reports.md) to these actions so report resolutions and takedowns are one flow.
- `drop=true` reindexes are synchronous and leave the index empty mid-run — run off-peak; `drop=false` for counter-freshness only.
- Tag backfill's trending-counter increments are **not idempotent** — gate behind step-up + confirmation, persist last-run so a second click warns.
- Download rows carry **IP addresses** (PG + Cassandra) — the analytics widgets should show aggregates by default and gate row-level IP display behind the same PII policy as [logs-audit.md](logs-audit.md).
- The IRC identifier is not a DOI: never render it as "DOI" in admin UI; verification = recompute HMAC with `IRC_VERIFICATION_SECRET` (rotating that secret invalidates all published verification URLs — flag in [operations.md](operations.md) env registry).

## 9. Build order / dependencies

| Phase | Deliverable | Depends on |
|---|---|---|
| 1 | Read-only browsers: `GET /admin/research`, `/admin/research/{id}`, `/admin/questions` + status funnels | nothing (new queries over existing PG tables) |
| 2 | Download analytics: `/admin/research/{id}/downloads` (wrap existing Cassandra reader), downloads/day SQL, top-downloaded/top-cited | Phase 1 UI shell |
| 3 | Q&A close/archive/reopen endpoints + `reason`-required admin bypass hardening | first caller of `AuditLogService.record` |
| 4 | Research admin takedown (unpublish/retract/delete) with full fan-out + author notification + step-up | Phase 3 audit pattern; step-up service **[EXISTS]** |
| 5 | Tag merge/block + trending-blocklist hook in `TrendingTagJob`; backfill UI with run-history guard | Phase 1 |
| 6 | KPI collectors: `accepted_at` column on `question_answers` (plain timestamp add — `ddl-auto=update` handles it), citation event log, weekly rollup job feeding [analytics-kpis.md](analytics-kpis.md) | Phases 1–2 |
| 7 | Integrity flags (plagiarism/quality): new entity, flag queue widget, reviewer workflow | Phase 4 (shares takedown actions) |

Cross-links: [admin-api-blueprint.md](admin-api-blueprint.md) sequences these against the other sections; [content-moderation.md](content-moderation.md) owns the generic content-takedown patterns this section reuses.
