# Operations & Infrastructure — Admin Dashboard §11

Ops section of the admin dashboard: dependency health, the complete
scheduled-jobs inventory, RabbitMQ queue/DLQ operations, SSE stream
operations, Redis operations, the environment/config registry, backup & DR,
deploy notes, and incident runbooks. Tagging follows the folder convention
([README.md](../README.md)): **[EXISTS]** cites real code, **[PARTIAL]** exists
in part, **[PLANNED]** is proposed here and not yet built.

Companion docs: [architecture.md](../foundation/architecture.md) (access model),
[logs-audit.md](logs-audit.md) (log catalog),
[admin-api-blueprint.md](../foundation/api-blueprint.md) (all endpoints + phasing),
[media-storage.md](../content/media-storage.md) (R2/media pipeline detail),
[../realtime/overview.md](../../realtime/overview.md) (SSE model),
[../settings/auth-sessions.md](../../settings/auth-sessions.md) (step-up auth).

---

## 1. Purpose & scope

| In scope | Out of scope (other sections) |
|---|---|
| Health of the 8 runtime dependencies (PostgreSQL, Cassandra, Redis, RabbitMQ, Elasticsearch, MediaMTX, R2, mail egress) | ES index *content* health & reindex console → [search-feed-trending.md](search-feed-trending.md) |
| All 16 `@Scheduled` methods + the continuous listeners; job controls | Media pipeline queues' *business* view → [media-storage.md](../content/media-storage.md) |
| RabbitMQ topology, depth monitoring, DLQ browse/requeue | Audit log browsing → [logs-audit.md](logs-audit.md) |
| SSE emitter fleet, connection counts, heartbeat sweeps | Notification/email volume → notifications-email.md |
| Redis key-prefix registry, memory, rate-limiter observability | User-facing storage quotas → [media-storage.md](../content/media-storage.md) |
| Env-var/config registry, `EnumCheckConstraintReconciler` | — |
| Backup/DR posture, deploy topology, incident runbooks, capacity watchpoints | — |

**Baseline (updated 2026-08):** the ops admin surface is **built** —
`admin/ops/AdminOpsController` serves `/api/v1/admin/ops/{health, jobs,
jobs/{key}/runs, jobs/{key}/run, jobs/{key}/pause|resume, jobs/paused,
queues, queues/dlq (+requeue/discard), sse, redis (+DELETE /redis/keys),
config, config/reconciler, media-plane, es/chat-messages/backfill,
streams/sweep-orphans}` **[EXISTS (built 2026-08)]**.
`spring-boot-starter-actuator` and `micrometer-registry-prometheus`
are on the classpath **[EXISTS]**, but
`application.yaml` contains **no `management:` block**, so only the Spring
Boot default is web-exposed: `/actuator/health` (status-only, no details).
The pom comment promising `/actuator/prometheus` is aspirational — that
endpoint is **not** exposed today **[PARTIAL]**, and Micrometer gauges remain
**[PLANNED]**.

---

## 2. Dashboard views & widgets

One "Operations" section, seven tabs. What the admin sees on screen:

| Tab | Widgets (top → bottom) |
|---|---|
| **Health** | Traffic-light strip of 8 dependency tiles (green/amber/red + latency ms + last-checked); expandable per-tile detail (endpoint probed, error text); app-instance card (uptime, JDK, git sha [PLANNED via build-info]); "degraded-mode map" showing which features die with each red tile (from §10 runbooks) |
| **Jobs** | Jobs table (16 rows, §4) with schedule, last run, outcome, duration, items processed; per-row **Run now** / **Pause** buttons; run-history drawer (last 50 `job_runs` rows per job); "missed schedule" badges |
| **Queues** | Per-queue depth + consumer-count tiles (5 queues); publish NACK / unroutable-return counters; DLQ parking-lot browser (paged table: original exchange/routing key, typeId, age, payload preview) with **Requeue** / **Discard**; message-rate sparkline per queue |
| **Realtime (SSE)** | Per-service connection gauge (9 services × per-instance count); heartbeat sweep status (last sweep, emitters dropped); Redis pub/sub channel throughput; top-N users by concurrent connections |
| **Redis** | Memory usage + fragmentation gauge; key-count by registered prefix (sampled `SCAN`); rate-limiter panel (top limited actions/actors, rejects/min); cache hit ratios (settings, counters, user-stats); "dangerous prefixes" callout (keys that must never be flushed, §6) |
| **Config** | Read-only env-var registry (§7) — name, purpose, set/unset, **secrets masked to presence-only**; startup reconciler report (`EnumCheckConstraintReconciler` drops applied); config-hygiene warnings list |
| **Backup / DR** | Backup status board (last pg_dump, last Cassandra snapshot, R2 lifecycle status — all red until §8 is implemented); restore-runbook link; capacity watchpoint list with current values |

---

## 3. Dependency health

### 3.1 What exists today

| Capability | Status | Detail |
|---|---|---|
| `/actuator/health` aggregate | **[EXISTS]** | Default exposure; Railway uses it as the traffic gate (DEPLOY.md). Auto-configured contributors cover DataSource (PostgreSQL), Cassandra driver, Redis, RabbitMQ, Elasticsearch, and mail *if* spring-mail is configured — but the response is status-only (`show-details` never set), so per-dependency state is invisible to callers |
| `/actuator/prometheus` | **[PARTIAL]** | Registry dependency present; endpoint **not exposed** (no `management.endpoints.web.exposure.include`). Enabling it is a one-line config change *plus* a security change — see caveat below |
| Actuator security | **[EXISTS]** (as a hazard) | `SecurityConfig` filter chain gates only `/api/v1/admin/**`; everything else — including all actuator endpoints — is `anyRequest().permitAll()`. Whatever actuator exposes is **unauthenticated**. `/health` public is fine (Railway needs it); do not widen exposure without adding an `/actuator/**` rule first |
| MediaMTX control API | **[EXISTS]** (API, not a check) | `:9997`, localhost-bound in compose, consumed by `MediaControlClient` (`/v3/config/paths/*`, `/v3/webrtcsessions/*`). No health probe today |
| R2 health | — | No health indicator exists for the S3 client |
| Mail egress | **[PARTIAL]** | Real dispatch path is `ResendEmailSender` (Resend HTTP API) — no indicator. `MAIL_*` SMTP vars exist in yaml; if a spring-mail contributor auto-registers it probes SMTP, *not* the path email actually takes |

### 3.2 Composite health — `GET /api/v1/admin/ops/health` [EXISTS (built 2026-08) — `OpsHealthService`]

Admin-authenticated (double-gated), returns per-dependency detail the public
`/health` deliberately hides. One probe per dependency (PG `SELECT 1`,
Cassandra `system.local`, Redis PING, RabbitMQ passive declare, ES index op,
R2 headBucket, MediaMTX control ping, mail enabled-flag):

| Dependency | Probe | Degrades what (see §10) |
|---|---|---|
| PostgreSQL | `SELECT 1` via Hikari | Everything relational — app is hard-down |
| Cassandra | `SELECT release_version FROM system.local` (LOCAL_ONE) | Posts/feeds/chat log/stories/audit/counters |
| Redis | `PING` + `INFO memory` | Degrades gracefully (fail-open paths, §6) but kills cross-instance SSE fan-out |
| RabbitMQ | connection + passive declare of the 5 queues | Notifications, analytics, media processing stall silently |
| Elasticsearch | cluster health + existence of the 8 `irc-*` indices | All search surfaces |
| MediaMTX | `GET :9997/v3/paths/list` | Live streaming publish/playback |
| R2 | `HEAD` bucket (S3 `headBucket`) | Media upload/download |
| Mail (Resend) | lightweight API-key validation call, cached 5 min | Email notifications only |

Also **[PLANNED]**: expose `management.endpoints.web.exposure.include=health,prometheus,info`
+ `management.endpoint.health.show-details=when_authorized` **only together
with** a new `SecurityConfig` rule locking `/actuator/**` (health excepted) —
tracked as one build-order item so the two can't ship apart.

---

## 4. Scheduled-jobs inventory

**[EXISTS]** — all `@Scheduled` methods in the tree (16 at recon time; the
2026-08 build added `RetentionSweepJob`, `LogAlertSweepJob` and the three
`AnalyticsJobs` sweeps — ~21 now). They share one
scheduler pool: `spring.task.scheduling.pool.size=4` (deliberately raised from
Spring's default 1). A slow business job can still delay heartbeat sweeps —
watchpoint in §11.

### 4.1 Business jobs (7)

| Job (class.method) | Schedule | Purpose | Failure mode today | Last-run visibility |
|---|---|---|---|---|
| `research.job.ScheduledPublishJob.publishDueResearch` | fixedDelay `${app.research.scheduled-publish-ms:60000}`, initial 30s | Auto-publish DRAFT research whose `scheduledPublishAt` is due, via the full publish path (ES, mentions, trending). The *single* publisher — a duplicate was removed to kill a race | Per-item try/catch; a bad draft is skipped and retried forever each tick (no poison-pill quarantine) | `[SCHED-PUBLISH]` console lines only |
| `settings.data.service.AccountLifecycleService.purgeExpired` | cron `0 30 3 * * *` (03:30 server-local) | Anonymize + purge accounts past the 30-day PENDING_DELETION grace; writes `deleted_accounts` tombstone, flips request to PURGED | Per-item error isolation; failed purge retries next night | `[ACCOUNT-DELETE]` console |
| `user.service.NotificationCleanupJob.purgeOldRead` | cron `0 15 3 * * *` (03:15) | Delete READ notifications older than 90 days | Single bulk delete; failure = table keeps growing silently | `[NOTIF-CLEANUP]` console |
| `common.notification.job.TrendingNotificationJob.fireDailyDigest` | cron `${irc.trending.notifications.cron:0 0 9 * * *}`, zone **UTC** | Daily TRENDING_DIGEST from QUESTION+RESEARCH trending tags; groupKey caps 1/user/day; skips below `MIN_USAGE_FLOOR` or when disabled | Skip-and-log; a failed day is simply lost (groupKey prevents catch-up double-send) | `[TRENDING]` console |
| `common.tag.job.TrendingTagJob.rebuildTrending` | fixedDelay `${app.tags.trending-refresh-ms:600000}` (10 min), initial 60s | Rebuild Cassandra `trending_tags` top-100 per scope (ALL/QUESTION/RESEARCH/POST/REEL): read counters, sort, delete + rewrite partition | Failure leaves the previous snapshot serving (stale but valid). Delete+rewrite is non-atomic: a crash mid-rebuild can leave a scope partially written until next tick | `[TRENDING]` console |
| `chat.service.ScheduledMessageService.fireDue` | fixedDelay 15s | Send due scheduled chat messages, batch-paged, one tx each | Permission/validation errors → FAILED (terminal); transient errors stay PENDING and retry next poll | `[CHAT-SCHEDULER]` console |
| `chat.service.CallService.sweepMissed` | fixedDelay 20s | Flip RINGING `CallSession`s past ring timeout to MISSED (`no_answer`) → drives CALL_MISSED notification | Missed sweep = call stays RINGING one more cycle; harmless | console |

### 4.2 SSE heartbeat sweeps (9)

All are single shared sweeps over that service's emitter map (never a thread
per client); dead emitters are pruned on ping failure. Detail in §5.

| Service | fixedDelay |
|---|---|
| `chat.realtime.ChatSseService` | 15s (also refreshes Redis presence `chat:presence:{userId}`, 30s TTL) |
| `user.realtime.NotificationSseService` | 15s |
| `post.realtime.PostRealtimeService` | 25s |
| `post.realtime.StoryRealtimeService` | 25s |
| `post.realtime.StoryTrayRealtimeService` | 25s |
| `research.realtime.ResearchRealtimeService` | 25s |
| `qna.realtime.QnaRealtimeService` | 25s |
| `activity.realtime.UserActivityRealtimeService` | 25s |
| `audit.realtime.AuditRealtimeService` | 25s |

### 4.3 Not `@Scheduled`, but continuous

| Worker | Trigger | Notes |
|---|---|---|
| `rabbitmq.config.RabbitMQConfig.drainDeadLetter` | `@RabbitListener` on `irc.queue.dead-letter` | Consumes, ERROR-logs, and **parks every dead-lettered message as a `dead_letters` PG row** (built 2026-08 — §5.2) **[EXISTS]** |
| `RedisMessagingConfig` pub/sub retry loop | private `ScheduledExecutorService` | Reconnect/retry for Redis messaging — invisible to the scheduler pool **[EXISTS]** |
| Story expiry | Cassandra row TTL (8/16/24h `USING TTL`) | **There is no StoryExpiryJob** — older docs/memory claiming an hourly job are stale; the datastore owns story lifecycle **[EXISTS]** |

### 4.4 `job_runs` ledger [EXISTS (built 2026-08) — `admin/ops/{JobRun, JobRunRecorder, JobRunRepository}`]

Scheduled/manual runs persist a `job_runs` row via `JobRunRecorder.record`;
backs `GET /ops/jobs` (last run per job) and `GET /ops/jobs/{key}/runs`.
Table shape as designed:

| Column | Notes |
|---|---|
| `id` uuid, `job_name` varchar | `job_name` = registry key (e.g. `notification-cleanup`) |
| `started_at`, `finished_at`, `duration_ms` | — |
| `outcome` (`SUCCESS`/`FAILED`/`PARTIAL`/`SKIPPED`) | PARTIAL = per-item failures occurred |
| `items_processed` int, `items_failed` int | jobs report their own counts |
| `error` varchar(1000), `host` varchar | instance id for multi-instance deploys |

Implementation: `JobRunRecorder.record(jobName, triggeredBy, body)` wraps each
job body. Retention: ledger pruned by the notification-cleanup job. Heartbeat
sweeps are **excluded** — they'd write 4 rows/min of noise; they get gauges
instead (§5.3... see §6).

### 4.5 Pause / trigger-now controls [EXISTS (built 2026-08)]

Endpoints in §9. `POST /ops/jobs/{key}/pause|resume` (+ `GET /ops/jobs/paused`)
set **Redis flags `ops:job-paused:{name}`** via `JobPauseRegistry` —
Redis, not in-memory, so the flag holds across all instances. **10 scheduled
jobs honor the flag** (`retention-sweep`, `log-alert-sweep`,
`analytics-daily-rollup`, `analytics-weekly-cohorts`, `analytics-anomaly-scan`,
`trending-rebuild`, `trending-digest`, `notification-cleanup`, `account-purge`,
`research-scheduled-publish`). Trigger-now (`POST /ops/jobs/{key}/run`,
step-up, whitelist of 7) runs the same body under a Redis lock
(`ops:job-lock:{key}`) so a manual run can't overlap the scheduled one, and
refuses while paused. Cron/fixedDelay values themselves stay config-only
(change = redeploy); the dashboard does not edit schedules.

---

## 5. Queue operations (RabbitMQ)

### 5.1 Topology **[EXISTS]** (`rabbitmq/constants/RabbitMQConstants.java`, `rabbitmq/config/RabbitMQConfig.java`)

| Object | Value |
|---|---|
| Main exchange | `irc.topic.exchange` (topic) |
| DLX | `irc.dlx.exchange` |
| Queues | `irc.queue.notifications` (social/lifecycle → Notification rows), `irc.queue.analytics` (downloads/counters), `irc.queue.media.process` (transcode/renditions), `irc.queue.media.delete` (delete-all-renditions), `irc.queue.dead-letter` (parking lot) |
| Feeder queue args | `x-dead-letter-exchange=irc.dlx.exchange`, `x-message-ttl=86400000` (24h max age → expired messages also dead-letter) |
| Routing keys | `user.social.{followed,unfollowed,blocked,unblocked,mentioned}`; `research.lifecycle.published`; `research.social.{reacted,commented,comment.reacted}`; `research.analytics.downloaded`; `qna.lifecycle.{created,deleted,answer.deleted}`; `qna.social.{answered,answer.reacted,answer.unreacted,accepted}`; `post.lifecycle.{created,deleted}`; `post.social.{reacted,unreacted,commented,comment.deleted,comment.reacted,shared}`; `media.process.requested`; `media.delete.requested` |
| Bindings | `post.lifecycle.#`, `post.social.#`, `qna.lifecycle.#`, `qna.social.#`, `media.process.#`, `media.delete.#` (+ notification/analytics key bindings) |
| Listener policy | retry 3 attempts (1s → ×2 → cap 10s), `default-requeue-rejected=false` (reject → DLX), prefetch 10, concurrency 2–5 |
| Publisher safety | correlated publisher confirms + mandatory returns; broker NACK and unroutable returns logged ERROR (`[RABBIT]` prefix) |

### 5.2 The DLQ parking lot **[EXISTS (built 2026-08)]**

`drainDeadLetter` consumes every message on `irc.queue.dead-letter`, logs the
ERROR line, and **persists it as a `dead_letters` PG row**
(`admin/ops/DeadLetter`: original exchange/routing key, typeId, base64
payload, status `PARKED`/`REQUEUED`/`DISCARDED`, resolved_by/at) instead of
dropping it — a nonzero broker DLQ depth now means the drain consumer itself
is down. The browser (`GET /ops/queues/dlq`, filter by status/routing key),
requeue (`POST …/dlq/{id}/requeue`, step-up — republishes to the original
exchange+routing key with an `x-dlq-replay-of` header; a still-poison message
re-parks as a NEW row) and discard (`DELETE …/dlq/{id}`, step-up — row kept
for the audit trail) all operate on this table; rows are pruned at 90 d by
`RetentionSweepJob`. Payloads may contain user data → browse stays a
sensitive action (§15).

### 5.3 Depth monitoring [PARTIAL — snapshot built 2026-08; rates/gauges still PLANNED]

`GET /api/v1/admin/ops/queues` **[EXISTS (built 2026-08)]** reads per-queue
message + consumer counts for the 5 queues via `RabbitAdmin.getQueueProperties`
(passive declare — no management-API dependency) and reports the `dead_letters`
PARKED count. Still [PLANNED]: publish/deliver *rates* (needs the RabbitMQ
management HTTP API `:15672`) and Micrometer gauges once
`/actuator/prometheus` is exposed.

---

## 6. SSE & Redis operations

### 6.1 SSE service fleet **[EXISTS]**

| Service | Stream(s) | Heartbeat | Fan-out mechanism |
|---|---|---|---|
| `ChatSseService` | one stream/user: messages, receipts, typing(+activity), calls, channels, `stream.*` | 15s | Redis pub/sub `irc:chat:{userId}` |
| `NotificationSseService` | `GET /api/v1/notifications/stream` (`{event,data}` envelope) | 15s | Redis pub/sub `irc:notifications:{userId}` |
| `PostRealtimeService` | per-post delta events (no counter values) | 25s | Redis pub/sub |
| `StoryRealtimeService` | per-story stream | 25s | Redis pub/sub |
| `StoryTrayRealtimeService` | `GET /api/v1/stories/tray/stream` — lowercase event names | 25s | Redis pub/sub |
| `ResearchRealtimeService` | research counters/events | 25s | Redis pub/sub |
| `QnaRealtimeService` | QnA events | 25s | Redis pub/sub |
| `UserActivityRealtimeService` | `GET /api/v1/users/me/activity/stream` | 25s | Redis pub/sub (`UserActivityRealtimeBroadcaster`) |
| `AuditRealtimeService` | `GET /api/v1/admin/audit/stream` (admin tail) | 25s | Redis pub/sub `irc:audit:stream` (global channel) |

SSE auth accepts `?token=` fallback (EventSource can't set headers). All
`*/stream` and `*/heartbeat` paths are excluded from the request audit log by
design ([logs-audit.md](logs-audit.md)).

### 6.2 SSE observability

| Capability | Status | Detail |
|---|---|---|
| Per-service connection counts | **[EXISTS (built 2026-08)]** | `GET /api/v1/admin/ops/sse` exposes `AuditRealtimeService.adminCount()` **plus new size accessors on all 8 other SSE services** (`connectedUserCount` / `topicCount` / `viewerCount`). Counts are per-instance — sum across instances at the dashboard |
| Micrometer SSE gauges | **[PLANNED]** | Per service per instance, once `/actuator/prometheus` is exposed |
| Top-N users by concurrent connections | **[PLANNED]** | From the per-user emitter maps; abuse signal (one account holding hundreds of streams) |

### 6.3 Redis key-prefix registry **[EXISTS]** (the keyspace as actually written)

| Prefix / key | Owner | Purpose | TTL |
|---|---|---|---|
| `rl:{action}:{actorId}:{bucket}` | `RateLimiter` | Fixed-window rate limits (presets: reaction 30/10s, comment 10/30s, social 30/min, otp per-number & per-IP) | 1 window |
| `dedup:{ns}:{scope}:{actor}:{textHash}` | `DedupGuard` | Double-submit guard | 3s default |
| `idem:{actor}:{Idempotency-Key}` | `IdempotencyFilter` | Response replay for mutating requests | 24h |
| `c:p:` `c:pc:` `c:r:` `c:rc:` `c:q:` `c:a:` + `{id}` | `CounterCache` | Hot counter hashes (fields rx/cm/rp/vw/sh/sv/dl/ct/an/bv); mirror only, DB is truth | 30d idle, touched on write |
| `settings:{userId}` | `SettingsCache` | Resolved settings | `${SETTINGS_CACHE_TTL_SECONDS:600}` |
| `storage:usage:{userId}` | `StorageUsageService` | Per-user stored-bytes sum | 1h |
| `stepup:{userId}` | `StepUpService` | Step-up auth window | `${STEP_UP_TTL_SECONDS:300}` |
| `sid:denied:{sid}` | `SessionDenylist` | Revoked-session denylist — **security-bearing, must survive** | session-scoped |
| `otp:{purpose}:{destHash}` | `OtpService` | Live OTP (HMAC-peppered); PG row is the audit copy | `${OTP_TTL_SECONDS:300}` |
| `view:{postId}:{userId}` | `CassandraViewService` | Unique-view dedupe (SETNX) | 7d |
| `feed:timeline:` | `FeedTimelineService` | Home-feed timeline cache | — |
| `notif:email:throttle:` / `irc:email:{userId}:{dedupeKey}` | `CassandraNotificationService` / `EmailThrottle` | Email throttle gates — **fail OPEN** if Redis down | `${MAIL_THROTTLE_MINUTES:60}` |
| `chat:presence:{userId}` | `PresenceService` | Online flag, refreshed by SSE heartbeat | 30s |
| `chat:lastseen:{userId}` | `PresenceService` | Last-seen epoch millis | none |
| `chat:typing:` `chat:unread:` `chat:reactions:` `chat:nonce:` `chat:poll:` | chat services | Ephemeral chat state | short/none |
| `chat:viewers:{messageId}` | `ChannelPostMetricsService` | HyperLogLog unique channel-post viewers (probabilistic, fail-open) | none |
| `chat:chtotals:` `chat:chtop:` `chat:chposttypes:` + `{channelId}` | `ChannelPostMetricsService` | Channel stats aggregates — **loss-tolerant but non-rebuildable**: a flush silently zeroes channel totals/top-posts | none |
| `irc:rdownload:dedupe:u:` / `:a:` | `ResearchDownloadTracker` | Download dedupe (user 90d / anon-IP 1h), fail-open | 90d / 1h |
| `irc:rview:anon:` `irc:qview:anon:` | view services | Anonymous view dedupe | window |
| Pub/sub channels | — | `irc:notifications:{userId}`, `irc:chat:{userId}`, `irc:audit:stream`, activity broadcaster channel | n/a |
| Spring cache (`spring.cache.type=redis`) | — | default TTL 300s; `user-stats` cache 30s | per-cache |

### 6.4 Redis ops panel [EXISTS (built 2026-08) — INFO + allowlisted flush]

`GET /api/v1/admin/ops/redis` **[EXISTS]**: `INFO` summary — version, uptime,
clients, memory (used/peak/maxmemory + policy), ops/sec, keyspace
hits/misses + hit ratio, evictions, per-db key counts.
`DELETE /api/v1/admin/ops/redis/keys?prefix=` **[EXISTS]** (step-up, audited
`ADMIN_REDIS_FLUSH`): cursored `SCAN`-based flush restricted to an
**allowlist** (`irc:search:top:`, `irc:search:zero:`, `user-profile`,
`settings:`, `chat:presence:`, `email-ctx:`, `trending:`); auth/abuse prefixes
(`sid:`, `stepup:`, `otp:`, `rl:`, `ops:job-`) are **never flushable**.
Still [PLANNED]: per-prefix key-count sampling and rate-limiter reject
counters (Micrometer).

---

## 7. Config & environment registry

### 7.1 Env-var inventory **[EXISTS]** (`application.yaml` `${VAR:default}` — single file, no profiles)

**🔒 = secret: dashboard must render presence/absence only, never the value.**

| Group | Variables | Purpose |
|---|---|---|
| Server | `SERVER_PORT` (8080) | HTTP port |
| PostgreSQL | `DB_USERNAME`, 🔒`DB_PASSWORD` (Railway: `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`) | Relational store; Hikari max 20 / min idle 5 |
| Cassandra | `CASSANDRA_KEYSPACE`, `CASSANDRA_CONTACT_POINTS`, `CASSANDRA_DATACENTER`, `CASSANDRA_USERNAME`, 🔒`CASSANDRA_PASSWORD`, `CASSANDRA_ENABLED` | Log/counter store; `schema-action=create_if_not_exists`, LOCAL_ONE, 10s timeout |
| Redis | `REDIS_HOST`, `REDIS_PORT`, 🔒`REDIS_PASSWORD` (Railway: `SPRING_DATA_REDIS_URL`) | Cache/presence/pubsub/rate-limit |
| RabbitMQ | `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, 🔒`RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` | Event bus |
| Auth/JWT | 🔒`APP_JWT_SECRET`, `SECURITY_PERMIT_ALL` (local escape hatch, default false), `STEP_UP_TTL_SECONDS` | Token signing; auth toggles |
| 2FA/OTP | `TWOFA_ISSUER`, 🔒`TWOFA_AES_KEY` (TOTP secret encryption — **must be set in prod**), 🔒`OTP_PEPPER`, `OTP_TTL_SECONDS`, `PHONE_DEFAULT_CALLING_CODE` (964) | Step-up factors |
| Contacts | 🔒`CONTACT_PEPPER` | Phone-hash HMAC for contact sync |
| OAuth | 🔒`GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`, `OAUTH_REDIRECT_URI`, `OAUTH_AUTHORIZED_URIS` | Google login |
| Research identity | 🔒`IRC_VERIFICATION_SECRET` | HMAC for `IRC-{YEAR}-{seq}` verification hashes |
| Mail | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, 🔒`MAIL_PASSWORD`, `MAIL_FROM`, `MAIL_FROM_NAME`, `MAIL_ENABLED`, `MAIL_THROTTLE_MINUTES` | Email egress + branding + throttle |
| R2 / media store | `R2_ENDPOINT`, 🔒`R2_ACCESS_KEY`, 🔒`R2_SECRET_KEY`, `R2_BUCKET_NAME`, `R2_PUBLIC_URL`, `R2_REGION` | Cloudflare R2 via S3 SDK |
| Media pipeline | `MEDIA_FFMPEG_BIN`, `MEDIA_FFPROBE_BIN`, `MEDIA_TRANSCODE_ENABLED` (default **false** → passthrough transcoder) | Worker pool 2/2, queue 64, CallerRunsPolicy |
| Streaming | `STREAM_WEBRTC_BASE`, `STREAM_INGEST_BASE`, `STREAM_PLAYBACK_BASE`, 🔒`STREAM_AUTH_SECRET`, `STREAM_RECORDINGS_DIR` (default `./recordings`), `STREAM_FFMPEG_BIN`, `STREAM_CONTROL_API_BASE` (`:9997`), `STREAM_RECORDING_DEFAULT_ON`, `STREAM_STAGE_MAX_GUESTS` | MediaMTX plane |
| Frontend/links | `IRC_BASE_URL`, `app.frontend-url` (share links, `FrontendUrlResolver`) | Link bases |
| CORS | `CORS_ORIGINS/METHODS/HEADERS/CREDENTIALS/MAX_AGE` | — |
| Client gate | `APP_MIN_VERSION`, `APP_FORCE_UPDATE`, `APP_LATEST_VERSION` | Mobile/web version gate |
| Caching | `SETTINGS_CACHE_TTL_SECONDS` | — |
| Jobs | `app.research.scheduled-publish-ms`, `app.tags.trending-refresh-ms`, `irc.trending.notifications.cron` (+ initial-delay vars) | §4 schedules |

### 7.2 Config-hygiene warnings **[EXISTS]** (read-only findings — surface on the Config tab)

| Finding | Risk |
|---|---|
| `application.yaml` commits live-looking defaults for 🔒`MAIL_PASSWORD` (Gmail app password), 🔒`R2_ACCESS_KEY`/`R2_SECRET_KEY`, 🔒`APP_JWT_SECRET`, 🔒`IRC_VERIFICATION_SECRET` | Secret leak via repo; rotate + strip defaults |
| `mediamtx.yml` hardcodes the dev auth secret in `authHTTPAddress`; must change together with `STREAM_AUTH_SECRET` | Auth-hook bypass if only one side rotates |
| `spring.jpa.show-sql: true` | SQL (with literals in some paths) in prod logs |
| `live_streams.stream_key` / `stream_guests.publish_key` stored plaintext in PG (required for MediaMTX compare) | Never render in any admin projection |
| Actuator unauthenticated at filter level (§3) | Lock before widening exposure |

### 7.3 `EnumCheckConstraintReconciler` **[EXISTS]** (`config/EnumCheckConstraintReconciler.java`)

Runs on `ApplicationReadyEvent`; `DROP CONSTRAINT IF EXISTS` for each entry —
because `ddl-auto=update` (no Flyway/Liquibase) never drops a stale
`*_check` when an `@Enumerated(STRING)` enum widens. Never fails startup.

| # | Table | Constraint | Why |
|---|---|---|---|
| 1 | `live_streams` | `live_streams_recording_status_check` | `recording_status` gained PAUSED + PROCESSING |

**Rule:** every enum widening adds one row here *and* to this table. The
reconciler's startup report (constraints dropped this boot) is served by
`GET /api/v1/admin/ops/config/reconciler` **[EXISTS (built 2026-08)]**;
the masked env registry by `GET /api/v1/admin/ops/config` (step-up).

---

## 8. Backup & disaster recovery

### 8.1 Current reality — honest

**Nothing is automated. There is no backup of any store today.** Specifics:

| Store | Posture today |
|---|---|
| PostgreSQL | No pg_dump/WAL archiving anywhere in repo or deploy docs; Railway plugin snapshots (if any) are outside our control and unverified |
| Cassandra | No snapshot schedule; `schema-action=create_if_not_exists` recreates schema — the audit tables' 180-day TTL is now applied idempotently at startup by `AuditSchemaInitializer` (built 2026-08, see [logs-audit.md](logs-audit.md)) |
| Redis | No persistence config asserted; treated as volatile (mostly correctly — see loss matrix below) |
| Elasticsearch | Rebuildable: 7 admin reindexes **[EXISTS]** cover all indices *except* `irc-chat-messages`, which self-heals on mutation — plus the chat backfill tool `POST /api/v1/admin/ops/es/chat-messages/backfill` **[EXISTS (built 2026-08)]** |
| R2 | No bucket versioning/lifecycle configured (see [media-storage.md](../content/media-storage.md)) |
| Recordings | Local disk `{STREAM_RECORDINGS_DIR:./recordings}` — **ephemeral on Railway**; a redeploy can destroy every live-stream recording. MediaMTX `recordDeleteAfter: 0s` means the app owns retention and no one implements it |
| Export ZIPs | `RetentionSweepJob` (built 2026-08) deletes ZIP + row at `expires_at`; GDPR purge deletes a purged user's exports immediately |

### 8.2 Redis loss matrix (what a flush actually costs)

| Severity | Keys | Effect of loss |
|---|---|---|
| **Security-relevant** | `sid:denied:*` | Revoked sessions become valid again until token expiry — the one genuinely dangerous loss |
| Annoying | `stepup:*`, `otp:*` | Users redo step-up / re-request OTP (PG audit copy unaffected) |
| Data-quality | `view:*`, `irc:rdownload:dedupe:*`, `chat:viewers:*` | Dedupe windows reset → counters overcount |
| Silent stat reset | `chat:chtotals:*`, `chat:chtop:*`, `chat:chposttypes:*` | Channel stats zero and never recover (per-post `message_counters` stay correct) |
| Self-healing | counters `c:*`, `settings:*`, `feed:timeline:*`, presence, caches | Rebuilt on demand from DB |
| Abuse-window | `rl:*`, `idem:*`, `dedup:*`, email throttles | Brief rate-limit/dedupe amnesty; email throttle fails open by design |

### 8.3 Recommended posture [PLANNED] — all external to the app

| Item | Recommendation |
|---|---|
| pg_dump | Nightly `pg_dump -Fc` (03:00, before the 03:15/03:30 jobs), 30 daily + 12 monthly retained, restore-tested monthly |
| Cassandra | Nightly `nodetool snapshot` + off-box copy of `audit_log_*`, `messages_by_conversation` keyspaces; apply the missing 180-day `default_time_to_live` on both audit tables while at it |
| Redis | Enable AOF `everysec` if the host allows, purely to shrink the `sid:denied` window; otherwise accept the matrix above |
| R2 | Bucket versioning + lifecycle (abort incomplete multipart 7d, transition cold originals); documented in [media-storage.md](../content/media-storage.md) |
| Recordings | Move finalized MP4s to R2 post-concat (`RecordingStorageService` is the hook point); until then, persistent volume |
| Restore order (runbook) | 1) PG restore → 2) Cassandra restore → 3) start app (RabbitMQ topology re-declares itself on boot; Redis warm-empty acceptable) → 4) run all 7 reindexes `POST /api/v1/admin/search/*/reindex?drop=true` → 5) verify `/actuator/health` + §3 composite → 6) accept known losses: chat-message search history, channel stat totals, dedupe windows |

---

## 9. Admin actions

Existing ops-adjacent actions: the 7 reindexes (`SearchAdminController`)
**[EXISTS]** and `POST /api/v1/admin/tags/backfill-posts` **[EXISTS]** are
catalogued in [search-feed-trending.md](search-feed-trending.md) /
[architecture.md](../foundation/architecture.md). Everything below is **[EXISTS (built
2026-08)]** in `admin/ops/AdminOpsController` under `/api/v1/admin/ops/**`
(inheriting the filter-chain double gate); mutations write explicit audit
rows through `AdminAuditor` → `AuditLogService.record`.

| Action | Endpoint | Params | Danger | Step-up | Audit action |
|---|---|---|---|---|---|
| Composite health | `GET /api/v1/admin/ops/health` | — | none | no | — (read; excluded like other health paths) |
| List jobs + last runs | `GET /api/v1/admin/ops/jobs` | — | none | no | — |
| Job run history | `GET /api/v1/admin/ops/jobs/{job}/runs` | page | none | no | — |
| Trigger job now | `POST /api/v1/admin/ops/jobs/{job}/run` | whitelist of 7; Redis-locked; refused while paused | medium (side effects = the job's own) | **yes** | `ADMIN_JOB_RUN` |
| Pause / resume job | `POST /api/v1/admin/ops/jobs/{job}/pause` / `/resume` (+ `GET /ops/jobs/paused`) | 10 pausable jobs (§4.5) | **high** (paused purge/retention jobs defer legally-relevant deletion; Redis flag `ops:job-paused:*`) | **yes** | `ADMIN_JOB_PAUSE` / `ADMIN_JOB_RESUME` |
| Queue depths | `GET /api/v1/admin/ops/queues` | — | none | no | — |
| Browse DLQ parking lot | `GET /api/v1/admin/ops/queues/dlq` | page, `status`, `routingKey` | medium (payloads may contain user data) | no | — (interceptor READ) |
| Requeue dead letter | `POST /api/v1/admin/ops/queues/dlq/{id}/requeue` | — | **high** (re-fires side effects; a still-poison message re-parks as a new row) | **yes** | `ADMIN_DLQ_REQUEUE` |
| Discard dead letter | `DELETE /api/v1/admin/ops/queues/dlq/{id}` | — | **high** (row kept, marked DISCARDED) | **yes** | `ADMIN_DLQ_DISCARD` |
| SSE connection summary | `GET /api/v1/admin/ops/sse` | — | none | no | — |
| Redis INFO panel | `GET /api/v1/admin/ops/redis` | — | none | no | — |
| Flush cache namespace | `DELETE /api/v1/admin/ops/redis/keys` | `prefix` (allowlist §6.4 **only** — `sid:`/`stepup:`/`otp:`/`rl:`/`ops:job-` never flushable) | **critical** | **yes** | `ADMIN_REDIS_FLUSH` |
| Config registry (masked) | `GET /api/v1/admin/ops/config` | — | medium (even masked topology is sensitive) | **yes** | `ADMIN_OPS_CONFIG_VIEW` |
| Reconciler report | `GET /api/v1/admin/ops/config/reconciler` | — | none | no | — |
| Media-plane snapshot | `GET /api/v1/admin/ops/media-plane` | — | none | no | — |
| Sweep orphaned LIVE streams | `POST /api/v1/admin/ops/streams/sweep-orphans` | `graceMinutes=30`, `maxAgeHours=12`, `dryRun` | **high** (ends streams) | **yes** | `ADMIN_STREAM_SWEEP` |
| Chat-search backfill | `POST /api/v1/admin/ops/es/chat-messages/backfill` | — (idempotent, only-missing walk, async + ledgered) | medium (heavy scan) | **yes** | `ADMIN_CHAT_BACKFILL_RUN` |

---

## 10. Incident runbooks & logs surfaced here

### 10.1 Runbook skeleton — dependency-down playbooks [PLANNED as docs; behaviors EXISTS]

| Dependency down | Observed behavior (today's code) | First moves |
|---|---|---|
| PostgreSQL | App effectively hard-down (auth, users, settings, chat metadata all PG) | Check Hikari pool exhaustion vs host down; Railway plugin status; restore from §8 |
| Cassandra | Posts/feeds/stories/chat log/audit/counters fail; PG surfaces limp on | `CASSANDRA_ENABLED` exists as a switch; check contact points/DC name; LOCAL_ONE means one node up suffices per token range |
| Redis | Mostly graceful: throttles/dedupe **fail open**, counters fall back to DB, presence blanks — but **cross-instance SSE fan-out dies** (all pub/sub) and rate limiting stops | Accept degraded realtime; watch for overcounting (§8.2); do not restart app instances needlessly |
| RabbitMQ | **Silent stall**: amqp listener/connection logging is `OFF` (retry-storm silencing) — notifications, analytics, media processing just stop; publisher NACK/returns still log ERROR | Check `[RABBIT]` ERROR lines; broker mgmt UI :15672; messages older than 24h on feeders dead-letter — after recovery expect a DLQ burst |
| Elasticsearch | All search surfaces error; content mutation paths that index async log and continue | After recovery run reindexes (`?drop=false` unless mappings changed); remember chat index self-heals only |
| MediaMTX | Live publish/playback dies; **orphaned LIVE rows**: no *scheduled* cleanup — a stream whose publisher vanished stays LIVE until the host ends it or an admin sweeps | Restart container; run `POST /api/v1/admin/ops/streams/sweep-orphans` (step-up, `dryRun=true` first — built 2026-08) to end LIVE rows with no MediaMTX publisher session; check auth hook secret pair (§7.2) |
| R2 | Media upload-intent/download fail; feeds still render (URLs break) | Check R2 status/credentials; MinIO only in local compose |
| Mail (Resend) | Emails silently drop; `EmailThrottle` fails open so recovery won't double-send within throttle windows | Check Resend dashboard; in-app notifications unaffected |

### 10.2 Logs surfaced in this section

| Log | Where it lives | Surfaced as |
|---|---|---|
| Console app log, bracket-tagged: `[SCHED-PUBLISH]` `[ACCOUNT-DELETE]` `[NOTIF-CLEANUP]` `[TRENDING]` `[CHAT-SCHEDULER]` `[SCHEMA]` `[RABBIT]` `[RABBIT-DLQ]` `[AUDIT-*]` `[SETTINGS-AUDIT]` `[LOGIN-ALERT]` | stdout only — **no file appender, no shipping**; `/Users/khi/Desktop/irc/app.log` at repo root is a stale 2026-04 manual capture **[EXISTS]** | Jobs/Queues tabs quote the relevant tags; log shipping itself is a [PLANNED] infra item (out of app scope) |
| `job_runs` ledger | PG **[EXISTS (built 2026-08)]** (§4.4) | Jobs tab run history |
| `dead_letters` parking lot | PG **[EXISTS (built 2026-08)]** (§5.2) | DLQ browser |
| Admin audit rows for §9 actions | Cassandra audit tables via `AdminAuditor` → `AuditLogService.record` **[EXISTS (built 2026-08)]** | [logs-audit.md](logs-audit.md) Log Explorer + `GET /api/v1/admin/audit/stream` **[EXISTS]** |

---

## 11. Analytics & KPIs

| Metric | Definition | Source | Chart |
|---|---|---|---|
| Dependency availability | % of §3 probes green, per dependency, 24h/7d | composite health poller [PLANNED] | status strip + uptime bars |
| Probe latency | p50/p95 per dependency | same | small multiples line |
| Job success rate | SUCCESS / total runs per job, 7d | `job_runs` **[EXISTS (built 2026-08)]** | table + trend sparkline |
| Job duration | p95 `duration_ms` per job | `job_runs` **[EXISTS (built 2026-08)]** | line |
| Job schedule adherence | runs with no `job_runs` row within 2× period | derived [PLANNED] | red badge count |
| Queue depth | messages+consumers per queue | `GET /ops/queues` snapshot **[EXISTS (built 2026-08)]**; rates via mgmt API [PLANNED] | area, 24h |
| DLQ arrivals | dead-letters/day (by original routing key) | `dead_letters` **[EXISTS (built 2026-08)]** | stacked bar |
| Publish failures | broker NACKs + unroutable returns/day | Micrometer counter on callbacks [PLANNED] | bar |
| SSE concurrent connections | per service per instance | `GET /ops/sse` **[EXISTS (built 2026-08)]**; Micrometer gauges [PLANNED] | stacked area |
| Heartbeat prune rate | emitters dropped per sweep | gauges [PLANNED] | line |
| Redis memory | used/peak/policy | `GET /ops/redis` (`INFO`) **[EXISTS (built 2026-08)]** | gauge |
| Rate-limit rejects | rejects/min by action | instrumented `RateLimiter` [PLANNED] | bar |
| Media worker saturation | queue size (cap 64) + CallerRuns events | executor gauges [PLANNED] | line + event marks |
| Audit executor saturation | queue size (cap 50k) + CallerRuns events | executor gauges [PLANNED] | line |
| Hikari utilization | active/max (20) | Micrometer (auto once prometheus exposed) **[PARTIAL]** | gauge |
| Recordings disk usage | bytes under `STREAM_RECORDINGS_DIR` | FS walk [PLANNED] | gauge |

---

## 12. Alerts & thresholds [PLANNED]

| Alert | Condition | Severity |
|---|---|---|
| Dependency red | composite probe fails 3 consecutive polls | page |
| Job missed | no `job_runs` row within 2× schedule period (business jobs only) | page for purge/publish jobs; warn otherwise |
| Job failing | 3 consecutive FAILED outcomes | warn |
| DLQ burst | > 10 dead-letters / 10 min | page (usually a poison message or consumer bug) |
| Queue backlog | depth > 1 000 for 5 min, or consumers = 0 on any feeder queue | page |
| Publish NACK / unroutable | any occurrence | warn (should be zero) |
| Redis memory | > 80% of maxmemory, or evictions > 0 on non-cache policy | warn / page |
| SSE flood | one user > 20 concurrent streams, or instance total > threshold | warn |
| Executor saturation | media queue > 48/64, audit queue > 25k/50k, any CallerRuns event | warn |
| Orphaned LIVE streams | LIVE row with zero MediaMTX publisher session > 15 min | warn (manual fix exists: `POST /ops/streams/sweep-orphans`, built 2026-08; no *scheduled* auto-sweep) |
| Recordings disk | > 80% of volume | warn |
| Unbounded-table growth | `activity_by_user` and other stores without retention (audit tables and `export_jobs` are now bounded — TTL + `RetentionSweepJob`, built 2026-08) | weekly report |

---

## 13. Permissions & safety notes

- All new surfaces under `/api/v1/admin/ops/**` → inherit the
  `requestMatchers("/api/v1/admin/**").hasRole("ADMIN")` filter-chain double
  gate ([architecture.md](../foundation/architecture.md)). Do **not** repeat the
  `PUT /channels/{id}/verified` mistake of annotation-only gating.
- Mutating actions (trigger/pause, requeue/discard, flush, config view)
  require **step-up** ([../settings/auth-sessions.md](../../settings/auth-sessions.md))
  and write audit rows via `AdminAuditor` → `AuditLogService.record`
  **[EXISTS (built 2026-08)]**.
- **Never render secrets**: env values (presence-only), `live_streams.stream_key`,
  `stream_guests.publish_key`, Redis key *values* (keys/counts only — values
  of `otp:*`, `stepup:*`, `idem:*` are sensitive).
- DLQ payloads are user data (notification/media events reference user ids
  and content); the parking-lot browser shows envelope + truncated preview,
  full body behind an explicit audited click.
- Actuator: `/health` stays public (Railway gate); any wider exposure ships
  in the same change as a `SecurityConfig` `/actuator/**` lock (§3.2).
- Multi-instance correctness: pause flags and job locks in Redis, not memory;
  SSE counts summed across instances; `SECURITY_PERMIT_ALL=true` must never
  reach a deployed environment (it also disarms the admin double gate).

---

## 14. Deploy notes (reference)

| Item | Detail **[EXISTS]** |
|---|---|
| Target | Railway via Nixpacks; `nixpacks.toml` pins `NIXPACKS_JDK_VERSION=21`; no Dockerfile, no Spring profiles — single `application.yaml` + env overrides with localhost fallbacks; health gate `/actuator/health`; Railway plugins wire PG/Redis/RabbitMQ via `SPRING_DATASOURCE_*`, `SPRING_DATA_REDIS_URL`, `SPRING_RABBITMQ_*` |
| docker-compose (local) | `db` postgres:15 :5432 · `rabbitmq` 3-management :5672/:15672 · `minio` :9000/:9001 (local R2 stand-in only) · `redis` 7 :6379 · `cassandra` 4.1 :9042 (cqlsh healthcheck) · `elasticsearch` 8.13.0 single-node no-xpack :9200 · `mediamtx` (`mediamtx.yml` ro-mounted, `./recordings` bind, `host.docker.internal` extra host) |
| MediaMTX surface | RTMP ingest :1935 (OBS only — browsers publish WHIP) · WHIP/WHEP :8889 · ICE UDP mux :8189 · HLS :8888 (fmp4) · control API :9997 (localhost-bound) · `authMethod: http` → `POST /internal/media/auth/{secret}` (constant-time compare vs `STREAM_AUTH_SECRET`; `authHTTPExclude` api/metrics/pprof) · `record: no` default, per-path enable via `MediaControlClient`, `recordDeleteAfter: 0s` |
| Schema management | JPA `ddl-auto=update`, **no Flyway/Liquibase** → `EnumCheckConstraintReconciler` (§7.3) is load-bearing; Cassandra `create_if_not_exists` → manual `ALTER TABLE` debt (audit TTLs) |
| Sizing knobs | Hikari 20/5 · scheduler pool 4 · audit executor 4/16 q50k · media workers 2/2 q64 · Rabbit prefetch 10, concurrency 2–5 · multipart cap 500MB |

**Capacity watchpoints** (recurring review list): scheduler pool 4 shared by
16 tasks (one slow job delays every heartbeat sweep); media worker
CallerRunsPolicy blocks request threads when its queue-of-64 fills; audit
executor CallerRuns at 50k does the same on hot paths; per-instance SSE
emitter maps are unbounded; unbounded stores — `activity_by_user` and the
recordings dir (audit tables, `export_jobs` + ZIPs and `otp_challenges` are
now bounded by the 180 d TTL / `RetentionSweepJob`, built 2026-08);
notifications table bounded only for READ rows.

---

## 15. Build order / dependencies

> **Status (built 2026-08):** phases 1, 3 and 4 shipped (`job_runs` ledger,
> composite health, read endpoints, `dead_letters` parking lot +
> browser/requeue/discard, pause/resume with Redis flags, masked config +
> reconciler report, allowlisted Redis flush, chat-message ES backfill, plus
> media-plane snapshot and the orphan-stream sweep). Phase 2
> (actuator/Micrometer exposure + gauges) and the external "ext" backup items
> remain open — except the Cassandra audit TTL, now automated by
> `AuditSchemaInitializer`.

| Phase | Item | Depends on |
|---|---|---|
| 1 | `job_runs` ledger + recorder wrapper on the 7 business jobs | nothing — pure add |
| 1 | Composite health endpoint + poller (read-only) | nothing |
| 1 | `GET /ops/jobs`, `/ops/jobs/{job}/runs`, `/ops/sse`, `/ops/redis` (read-only) | ledger; SSE `size()` accessors |
| 2 | Actuator exposure (`prometheus`, `health` details) **in the same PR as** the `/actuator/**` security rule | SecurityConfig change |
| 2 | Micrometer gauges: SSE, executors, rate-limiter rejects, publish NACKs | actuator exposure |
| 2 | Queue-depth poller via RabbitMQ mgmt API + `GET /ops/queues` | mgmt API creds env var |
| 3 | `dead_letters` parking lot (replaces log-and-drop drain) + DLQ browser/requeue/discard | step-up + `AuditLogService.record` wiring |
| 3 | Job trigger-now / pause-resume (Redis flags + locks) | ledger; step-up |
| 3 | Config registry (masked) + reconciler report | step-up |
| 4 | Redis namespace flush (allowlisted) | everything above; last — highest blast radius |
| 4 | Chat-message ES backfill tool | none, but heavy — schedule off-peak |
| ext | Backups (pg_dump, Cassandra snapshots, R2 lifecycle), Cassandra audit `ALTER TABLE` TTL, recordings→R2 | infra work outside the app; start immediately, independent of dashboard phases |

Phase 1 is entirely read-only + one new table — zero behavioral risk, and it
answers the two questions ops cannot answer today: *"is everything up?"* and
*"did last night's jobs run?"*
