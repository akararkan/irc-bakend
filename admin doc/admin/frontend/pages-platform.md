# Pages — search, feed, logs, analytics & operations

Part of the [admin dashboard frontend guide](README.md).
Legend: **SU** = step-up required (§[auth-and-roles.md](auth-and-roles.md)) ·
roles in the *Who* column are the `hasRole`/`hasAnyRole` grants as coded ·
list endpoints paginate per [conventions.md](conventions.md).
Wire-level request/response JSON: [../api/](../api/README.md).

Section docs: [../platform/](../platform/README.md).

---

### 4.13 Search ops — base `/api/v1/admin/search`

`SearchAdminController` (the 7 reindexes) + `AdminSearchOpsController`. Doc:
[search-feed-trending.md](../platform/search-feed-trending.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `POST /{research\|posts\|questions\|users\|channels\|answers\|sounds}/reindex` | `drop` (default `true` — recreates the index) | — | ADMIN. **Synchronous** — can take a while; disable the button while in flight and warn about `drop=true` during peak. |
| `POST /reindex-all` | — | **SU** | ADMIN. Async **202** `{jobId}`; poll `GET /api/v1/admin/ops/jobs/search-reindex-all/runs` (the response `note` says exactly that). |
| `GET /indices` | per-index existence + doc counts (8 `irc-*` indices) | — | ADMIN, ANALYST |
| `GET /health` · `/analytics/top-queries` · `/analytics/zero-results` | window params | — | ADMIN, ANALYST |

There is deliberately **no** chat-messages reindex — don't render a slot for it.

### 4.14 Feed tuning & suggestions — bases `/api/v1/admin/feed`, `/api/v1/admin/suggestions`

`AdminFeedController`, `AdminSuggestionsController` — ADMIN, ANALYST (mutations
ADMIN). Docs: [search-feed-trending.md](../platform/search-feed-trending.md), [discovery-pymk-privacy.md](../users/discovery-privacy.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /api/v1/admin/feed/weights` · `/config` · `/explain/{userId}` · `/affinity/{userId}` | `limit` on explain | — | ADMIN, ANALYST |
| `PATCH /api/v1/admin/feed/config` | partial knob body (`enabled, rolloutPercent, wLike…, halfLife…, boost…, damp…, maxAuthorRun`) — live within ≤30 s | **SU** | ADMIN |
| `POST /api/v1/admin/feed/preview` | `{userId, limit?, overrides?}` — shadow-scores baseline vs. proposed side-by-side, persists nothing (the `note` says so) | — | ADMIN |
| `GET /api/v1/admin/suggestions/knobs` · `/explain/{userId}` | PYMK sources/weights are recompile-only — display as read-only | — | ADMIN, ANALYST |

Ideal UX: edit knobs → **Preview** (safe) → then **Apply** (step-up). Show
`rolloutPercent` prominently — config changes hit only that bucket.

### 4.15 Logs & audit — bases `/api/v1/admin/logs`, `/api/v1/admin/audit`

`AdminLogsController` (all four staff tiers) + `AuditLogController` (all four
tiers). Doc: [logs-audit.md](../platform/logs-audit.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /api/v1/admin/logs/explore` | `q` (grammar) or discrete `store` (audit/login/settings/consent/reports), `userId, ip, outcome, since, until, text, pageSize`. Audit/consent stores are user-anchored — the response `notes` explain when a store was skipped. | — | all staff |
| `GET …/logs/login-events` | pageable + filters | — | all staff |
| `POST …/logs/export` | produces **text/csv** (§5.4) | **SU** | **ADMIN** |
| `GET/POST /logs/views` · `DELETE /logs/views/{id}` | saved explorer views | — | all staff |
| `GET /logs/alerts` · `/alerts/firings` · `/retention` · `/otp-stats` | — | — | all staff |
| `POST /logs/alerts` · `PATCH /logs/alerts/{id}` | rule body | **SU** | **ADMIN** |
| `DELETE /logs/alerts/{id}` | — | — | **ADMIN** |
| `GET /api/v1/admin/audit` | **`userId` effectively required — 400 without** (Cassandra partition scope); `operation, outcome, from, to, cursor, pageSize` filtered in-memory | — | all staff |
| `GET /api/v1/admin/audit/users/{userId}` | `cursor, pageSize` keyset | — | all staff |
| `GET /api/v1/admin/audit/resources/{resourceType}/{resourceId}` | `cursor, pageSize` — "what happened to this resource"; the only view that includes anonymous traffic | — | all staff |
| `GET /api/v1/admin/audit/stream` | `?token=` SSE (§5.5) | — | all staff |

The audit browser UI must make the scope rule obvious: per-user browsing only;
the SSE stream is the global view. `otp-stats` is aggregate-only by design
(its `note` says so).

### 4.16 Analytics — base `/api/v1/admin/analytics`

`AdminAnalyticsController` — ADMIN, ANALYST (pipeline controls ADMIN). Doc:
[analytics-kpis.md](../platform/analytics-kpis.md).

| Method + path | Key params | Who |
|---------------|-----------|-----|
| `GET /overview` · `/content` · `/engagement` · `/trending` | `window` / `scope` | ADMIN, ANALYST |
| `GET /export` | `from, to, dataset` — **text/csv** (§5.4) | ADMIN, ANALYST |
| `GET /series` · `/funnel` · `/retention` · `/anomalies` · `/alerts-config` | metric/window params | ADMIN, ANALYST |
| `POST /rollup/{date}/run` · `POST /backfill` | date / range | **ADMIN** |
| `GET /events/sample` · `PUT /alerts/{metric}` | — / threshold body | **ADMIN** |

Several responses carry sourcing `note` fields (e.g. collector-sourced post
counts, set-once funnel milestones) — always render them under the chart;
they are the honesty contract of this section.

### 4.17 Operations — base `/api/v1/admin/ops`

`AdminOpsController` — **ADMIN only**. Doc: [operations.md](../platform/operations.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /health` · `/sse` · `/media-plane` · `/redis` · `/config/reconciler` | dependency rollup, emitter counts, MediaMTX, Redis INFO, enum-CHECK report | — |
| `GET /jobs` · `/jobs/{jobKey}/runs` · `/jobs/paused` | scheduled-job ledger | — |
| `POST /jobs/{jobKey}/run` | 202, whitelisted jobs only | **SU** |
| `POST /jobs/{jobKey}/pause` · `/resume` | pause returns a **`warning` field — render it** (pausing a job has consequences) | **SU** |
| `GET /queues` · `GET /queues/dlq` | `status` (PARKED/REQUEUED/DISCARDED), `routingKey` + pageable | — |
| `POST /queues/dlq/{id}/requeue` | republish to original exchange/key; `note` explains it re-parks on repeat failure | **SU** |
| `DELETE /queues/dlq/{id}` | discard (row kept, status DISCARDED — the `note` says so) | **SU** — danger zone |
| `DELETE /redis/keys` | `prefix` — allowlisted cache prefixes **only**; auth/abuse state (`sid:`, `stepup:`, `otp:`, `rl:`) is never flushable, the server 400s | **SU** — danger zone |
| `POST /es/chat-messages/backfill` | 202 + jobId; idempotent (`note`) | **SU** |
| `GET /config` | sanitized env/flag registry — **a step-up-gated read**; shows `permit-all` state with a warning field when on | **SU** |
| `POST /streams/sweep-orphans` | `graceMinutes` (30), `maxAgeHours` (12), `dryRun` (**default `false`** — the UI should default the toggle **on**) | **SU** |
