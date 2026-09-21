# Admin API Reference — Operations, Activity & Discovery

Complete request/response reference for three admin controllers:

- **`AdminOpsController`** (`app/admin/ops`) — operations console: dependency health, the `job_runs` ledger, manual job triggers, pause/resume, queue depths, the DLQ parking lot, SSE fleet summary, Redis panel + prefix flush, sanitized config registry, enum-CHECK reconciler report, MediaMTX media plane, orphaned-LIVE-stream sweep, chat-ES backfill.
- **`AdminActivityController`** (`app/admin/activity`) — break-glass activity forensics: dual-control case lifecycle plus the per-user reads it gates, and the routinely-available activity erasure.
- **`AdminDiscoveryController`** (`app/admin/discovery`) — PYMK inspection/recompute, contact-sync stats and consent compliance, GDPR contact-hash purge, discovery flags, forced QR rotation.

Concept docs: [operations.md](../platform/operations.md) · [activity-engagement.md](../users/activity-engagement.md) · [discovery-pymk-privacy.md](../users/discovery-privacy.md) · [frontend-dashboard-guide.md](../frontend/README.md).

## Conventions

- **Auth**: every endpoint requires an authenticated `ADMIN` (class-level `@PreAuthorize("hasRole('ADMIN')")` on all three controllers; the `/api/v1/admin/**` route gate applies on top).
- **Step-up**: endpoints marked *Step-up: yes* (`@RequiresStepUp`) return `403 STEP_UP_REQUIRED` without a fresh step-up marker. Arm it via `POST /api/v1/security/step-up`; the marker lives `app.security.step-up.ttl-seconds` (default 300 s).
- **Errors** arrive in the canonical envelope (`errorCode` is the machine field) — see [frontend-error-handling.md](../../errors/frontend-error-handling.md).
- **Null omission**: Jackson runs with `default-property-inclusion: non_null` globally — null fields **and null-valued map entries** are omitted from every body below. Treat all fields as optional in clients.
- **Timestamps**: `LocalDateTime` fields serialize without a zone suffix (`2026-08-07T03:30:00.412`); `Instant` fields are UTC `Z`-suffixed.
- **Paging**: `page`/`size` query params; every page size is clamped to **1–100** (`Pages.clamp`), defaults noted per endpoint. `Page<T>` responses use Spring's standard envelope — examples below show only the load-bearing fields (`content`, `totalElements`, `totalPages`, `number`, `size`); the real payload also carries `pageable`, `sort`, `first`, `last`, `numberOfElements`, `empty`.
- **Audit**: every mutation (and the flagged sensitive reads) writes an `AdminAuditor` row; the `ADMIN_*` action name is noted per endpoint.

---

## Operations console — `AdminOpsController`

Base path `/api/v1/admin/ops`.

### Dependency health & fleet

### GET /api/v1/admin/ops/health

Composite dependency probe — one bounded check per dependency; a down dependency degrades its entry, never the endpoint.

**Access**: ADMIN. Step-up: no.

**Request body**: None.

**Response** — `200 OK`. Top-level keys in this fixed order: `postgres`, `cassandra`, `redis`, `rabbitmq`, `elasticsearch`, `r2`, `mediamtx`, `mail`. Probe objects contain `status` + `latencyMs` (+ `error` when `DOWN`); key order *inside* probe objects is not guaranteed.

```json
{
  "postgres":      { "status": "UP", "latencyMs": 4 },
  "cassandra":     { "status": "UP", "latencyMs": 11 },
  "redis":         { "status": "UP", "latencyMs": 2 },
  "rabbitmq": {
    "status": "UP",
    "latencyMs": 23,
    "queues": {
      "irc.queue.notifications": 0,
      "irc.queue.analytics": 3,
      "irc.queue.dead-letter": 0,
      "irc.queue.media.process": 1,
      "irc.queue.media.delete": 0
    }
  },
  "elasticsearch": { "status": "UP", "latencyMs": 38 },
  "r2":            { "status": "DISABLED", "note": "R2 credentials not configured" },
  "mediamtx":      { "status": "DOWN", "latencyMs": 2004, "error": "control API unreachable" },
  "mail":          { "status": "UP" }
}
```

Variants: `rabbitmq.status` is `UP` / `DEGRADED` (a queue missing or erroring; queue value becomes `"missing"` or `"error: …"`) / `UNKNOWN` with `note` when `RabbitAdmin` isn't wired. `r2` probes `HeadBucket` when configured, else `{"status":"DISABLED","note":…}`. `mail.status` is `UP` or `DISABLED` (no probe).

**Errors**: none specific.

### GET /api/v1/admin/ops/sse

Connected-client counts across the nine SSE surfaces.

**Access**: ADMIN. Step-up: no.

**Request body**: None.

**Response** — `200 OK`, keys in this fixed order:

```json
{
  "auditAdmins": 1,
  "chatUsers": 42,
  "notificationUsers": 87,
  "activityUsers": 12,
  "qnaTopics": 5,
  "researchTopics": 3,
  "postTopics": 19,
  "storyTopics": 7,
  "storyTrayViewers": 31
}
```

**Errors**: none specific.

### Scheduled jobs & the `job_runs` ledger

### GET /api/v1/admin/ops/jobs

Static job registry joined with each job's most recent `job_runs` row.

**Access**: ADMIN. Step-up: no.

**Request body**: None.

**Response** — `200 OK`, a JSON array with one row per registry entry, in registry order: `research-scheduled-publish`, `account-purge`, `notification-cleanup`, `trending-digest`, `trending-rebuild`, `chat-scheduled-messages`, `calls-sweep-missed`, `search-reindex-all`, `sse-heartbeats`. Schedule strings are literals from the registry (unresolved `${…}` placeholders included). When a job has a recorded run the row gains `lastRun`, `lastOutcome`, `lastDurationMs`, `lastItemsProcessed`; a never-run job carries none of them (the `lastRun: null` map entry is suppressed by `non_null`).

```json
[
  {
    "job": "account-purge",
    "schedule": "cron 0 30 3 * * *",
    "description": "Anonymize + purge accounts past the 30-day grace (GDPR cascade)",
    "triggerable": true,
    "lastRun": "2026-08-07T03:30:00.412",
    "lastOutcome": "SUCCESS",
    "lastDurationMs": 1843,
    "lastItemsProcessed": 2
  },
  {
    "job": "sse-heartbeats",
    "schedule": "9 sweeps @ 15-25s",
    "description": "SSE keepalives — deliberately NOT ledgered (noise)",
    "triggerable": false
  }
]
```

**Errors**: none specific.

### GET /api/v1/admin/ops/jobs/{jobKey}/runs

Run history for one job, newest first. Also accepts ledger-only job names that aren't in the registry (`chat-message-backfill`, `search-reindex-all`).

**Access**: ADMIN. Step-up: no.

**Params**

| Param | In | Default | Notes |
|---|---|---|---|
| `jobKey` | path | — | job name as ledgered |
| `page` | query | `0` | negative values floor to 0 |
| `size` | query | `25` | clamped to 1–100 |

**Request body**: None.

**Response** — `200 OK`, `Page<JobRun>`. `JobRun.outcome` ∈ `RUNNING` `SUCCESS` `FAILED` `PARTIAL` `SKIPPED`. `triggeredBy` is the admin id for manual triggers, absent for scheduled runs; `error` (≤1000 chars) present only on failures.

```json
{
  "content": [
    {
      "id": "0e7c2f7a-4b3d-4c1e-9a52-8f0f4f6f2a11",
      "jobName": "account-purge",
      "startedAt": "2026-08-07T03:30:00.412",
      "finishedAt": "2026-08-07T03:30:02.255",
      "durationMs": 1843,
      "outcome": "SUCCESS",
      "itemsProcessed": 2,
      "itemsFailed": 0,
      "host": "irc-app-1",
      "triggeredBy": "b2a6d9e0-1c4f-4e8a-93d7-5a0c1b2d3e4f"
    }
  ],
  "totalElements": 61,
  "totalPages": 3,
  "number": 0,
  "size": 25
}
```

> Standard Spring `Page` envelope — trimmed here; see [Conventions](#conventions).

**Errors**: none specific (an unknown `jobKey` returns an empty page).

### POST /api/v1/admin/ops/jobs/{jobKey}/run

Manually trigger a whitelisted job. Redis-locked (`ops:job-lock:{jobKey}`, 10 min TTL, released on completion) so a manual run can't overlap another; the job body runs **synchronously inside the request** — the 202 returns after it finishes. Audits `ADMIN_JOB_RUN`.

**Access**: ADMIN. Step-up: **yes**.

**Params**

| Param | In | Notes |
|---|---|---|
| `jobKey` | path | must be one of the triggerable whitelist: `trending-rebuild`, `trending-digest`, `account-purge`, `notification-cleanup`, `research-scheduled-publish`, `calls-sweep-missed`, `chat-scheduled-messages` |

**Request body**: None.

**Response** — `202 Accepted`:

```json
{ "job": "trending-rebuild", "triggered": true }
```

**Errors**
- `400 JOB_NOT_TRIGGERABLE` — `jobKey` not in the whitelist (message lists it).
- `400 JOB_PAUSED` — the job is paused; resume first (`POST …/jobs/{jobKey}/resume`).
- `400 JOB_LOCKED` — a run of this job is already in progress.
- `403 STEP_UP_REQUIRED`.

### GET /api/v1/admin/ops/jobs/paused

Pause-flag snapshot for every pausable job.

**Access**: ADMIN. Step-up: no.

**Request body**: None.

**Response** — `200 OK`, `Map<String,Boolean>` over the `PAUSABLE` set (10 jobs; `Set.of` — key order is not deterministic): `retention-sweep`, `log-alert-sweep`, `analytics-daily-rollup`, `analytics-weekly-cohorts`, `analytics-anomaly-scan`, `trending-rebuild`, `trending-digest`, `notification-cleanup`, `account-purge`, `research-scheduled-publish`.

```json
{
  "retention-sweep": false,
  "log-alert-sweep": false,
  "analytics-daily-rollup": false,
  "analytics-weekly-cohorts": false,
  "analytics-anomaly-scan": false,
  "trending-rebuild": true,
  "trending-digest": false,
  "notification-cleanup": false,
  "account-purge": false,
  "research-scheduled-publish": false
}
```

**Errors**: none specific.

### POST /api/v1/admin/ops/jobs/{jobKey}/pause

Set the Redis pause flag `ops:job-paused:{jobKey}` — suppresses the job's **scheduled** runs (fail-open: if Redis is down, jobs keep running). Audits `ADMIN_JOB_PAUSE`.

**Access**: ADMIN. Step-up: **yes**.

**Params**: `jobKey` (path) — must be in the `PAUSABLE` set above.

**Request body**: None.

**Response** — `200 OK`:

```json
{
  "job": "trending-rebuild",
  "paused": true,
  "warning": "Scheduled runs are suppressed until resumed — pausing retention/GDPR sweeps defers legally-relevant deletion work."
}
```

**Errors**
- `400 JOB_NOT_PAUSABLE` — `jobKey` not in the pausable set (message lists it).
- `403 STEP_UP_REQUIRED`.

### POST /api/v1/admin/ops/jobs/{jobKey}/resume

Clear the pause flag. Audits `ADMIN_JOB_RESUME`.

**Access**: ADMIN. Step-up: **yes**.

**Params**: `jobKey` (path) — must be in the `PAUSABLE` set.

**Request body**: None.

**Response** — `200 OK`:

```json
{ "job": "trending-rebuild", "paused": false }
```

**Errors**
- `400 JOB_NOT_PAUSABLE`.
- `403 STEP_UP_REQUIRED`.

### Queues & the DLQ parking lot

### GET /api/v1/admin/ops/queues

RabbitMQ queue depths + consumer counts for the five core queues, plus the parked-DLQ count.

**Access**: ADMIN. Step-up: no.

**Request body**: None.

**Response** — `200 OK`. One entry per queue (`irc.queue.notifications`, `irc.queue.analytics`, `irc.queue.dead-letter`, `irc.queue.media.process`, `irc.queue.media.delete`), then `note`, then `dlqParked`. Per-queue value is `{"messages":N,"consumers":N}`, or `{"declared":false}` for an undeclared queue, or `{"error":"…"}`.

```json
{
  "irc.queue.notifications": { "messages": 0, "consumers": 1 },
  "irc.queue.analytics": { "messages": 3, "consumers": 1 },
  "irc.queue.dead-letter": { "messages": 0, "consumers": 1 },
  "irc.queue.media.process": { "messages": 1, "consumers": 1 },
  "irc.queue.media.delete": { "messages": 0, "consumers": 1 },
  "note": "Dead letters park in the dead_letters table (browse via GET /queues/dlq) — a nonzero dead-letter QUEUE depth means the drain consumer itself is down.",
  "dlqParked": 2
}
```

When `RabbitAdmin` isn't available the body is just `{"note":"RabbitAdmin unavailable"}`.

**Errors**: none specific.

### GET /api/v1/admin/ops/queues/dlq

Browse the `dead_letters` parking lot, newest first.

**Access**: ADMIN. Step-up: no.

**Params**

| Param | In | Default | Notes |
|---|---|---|---|
| `status` | query | — | optional; `PARKED` \| `REQUEUED` \| `DISCARDED` (case-insensitive) |
| `routingKey` | query | — | optional exact match |
| `page` / `size` | query | `0` / `25` | size clamped to 1–100 |

**Request body**: None.

**Response** — `200 OK`, `Page<DeadLetter>`. `payloadB64` is the raw payload base64-encoded (JSON bodies stay readable after decode); `typeId` is the Jackson `__TypeId__` header; `resolvedAt`/`resolvedBy` appear once requeued/discarded.

```json
{
  "content": [
    {
      "id": "7f2d9c1a-3e5b-4a86-b1c2-d4e5f6a7b8c9",
      "originalExchange": "irc.topic.exchange",
      "originalQueue": "irc.queue.notifications",
      "routingKey": "post.social.reacted",
      "typeId": "ak.dev.irc.app.post.events.PostReactedEvent",
      "payloadB64": "eyJwb3N0SWQiOiIuLi4ifQ==",
      "headersJson": "{\"x-death\":[{\"count\":3}]}",
      "status": "PARKED",
      "receivedAt": "2026-08-06T22:14:09.118"
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 25
}
```

> Standard Spring `Page` envelope — trimmed here; see [Conventions](#conventions).

**Errors**
- `400 INVALID_STATUS` — "Unknown status. Allowed: PARKED, REQUEUED, DISCARDED."

### POST /api/v1/admin/ops/queues/dlq/{id}/requeue

Republish a **PARKED** dead letter to its original exchange + routing key (fallback exchange `irc.topic.exchange`). The original `__TypeId__` header is preserved and an `x-dlq-replay-of: {id}` header is added; the row flips to `REQUEUED` with `resolvedAt`/`resolvedBy` stamped. Audits `ADMIN_DLQ_REQUEUE`.

**Access**: ADMIN. Step-up: **yes**.

**Params**: `id` (path, UUID).

**Request body**: None.

**Response** — `200 OK`:

```json
{
  "id": "7f2d9c1a-3e5b-4a86-b1c2-d4e5f6a7b8c9",
  "requeuedTo": "irc.topic.exchange/post.social.reacted",
  "note": "If the consumer still can't process it, it will re-park as a NEW row."
}
```

**Errors**
- `404 DEADLETTER_NOT_FOUND` — no `DeadLetter` with that id.
- `400 DLQ_NOT_PARKED` — "Only PARKED dead letters can be requeued (this one is REQUEUED)."
- `400 QUEUE_UNAVAILABLE` — RabbitMQ template unavailable, cannot requeue.
- `403 STEP_UP_REQUIRED`.

### DELETE /api/v1/admin/ops/queues/dlq/{id}

Discard a dead letter (any status): marks `DISCARDED` with `resolvedAt`/`resolvedBy` — the row itself is kept for the audit trail. Audits `ADMIN_DLQ_DISCARD`.

**Access**: ADMIN. Step-up: **yes**.

**Params**: `id` (path, UUID).

**Request body**: None.

**Response** — `200 OK`:

```json
{
  "id": "7f2d9c1a-3e5b-4a86-b1c2-d4e5f6a7b8c9",
  "status": "DISCARDED",
  "note": "Row kept for the audit trail; the retention sweep prunes it at 90 days."
}
```

**Errors**
- `404 DEADLETTER_NOT_FOUND`.
- `403 STEP_UP_REQUIRED`.

### Redis panel

### GET /api/v1/admin/ops/redis

Read-only `INFO` summary: memory, clients, throughput, keyspace, hit ratio.

**Access**: ADMIN. Step-up: no.

**Request body**: None.

**Response** — `200 OK`. `reachable` first, then (when present in `INFO`) this whitelist in order: `redis_version`, `uptime_in_days`, `connected_clients`, `used_memory_human`, `used_memory_peak_human`, `maxmemory_human`, `maxmemory_policy`, `total_commands_processed`, `instantaneous_ops_per_sec`, `keyspace_hits`, `keyspace_misses`, `evicted_keys`, `expired_keys`; then `hitRatio` (percent, 2 dp — omitted when hits+misses is 0, since the null entry is suppressed); then one `dbN` entry per keyspace. Values arrive as `INFO` strings.

```json
{
  "reachable": true,
  "redis_version": "7.2.4",
  "uptime_in_days": "12",
  "connected_clients": "9",
  "used_memory_human": "41.52M",
  "used_memory_peak_human": "63.10M",
  "maxmemory_human": "0B",
  "maxmemory_policy": "noeviction",
  "total_commands_processed": "1284733",
  "instantaneous_ops_per_sec": "37",
  "keyspace_hits": "804211",
  "keyspace_misses": "96214",
  "evicted_keys": "0",
  "expired_keys": "15423",
  "hitRatio": 89.31,
  "db0": "keys=18234,expires=4411,avg_ttl=734211"
}
```

On failure: `{"reachable": false, "error": "…"}`.

**Errors**: none specific.

### DELETE /api/v1/admin/ops/redis/keys

Flush all keys under an **allowlisted** cache prefix (SCAN + batched DELETE, 500 keys per batch). Auth/abuse state is never flushable by construction. Audits `ADMIN_REDIS_FLUSH` with the prefix and count.

**Access**: ADMIN. Step-up: **yes**.

**Params**

| Param | In | Notes |
|---|---|---|
| `prefix` | query, required | ≥3 chars; must start with an allowlisted prefix; matched as `prefix*` |

Flushable allowlist: `irc:search:top:`, `irc:search:zero:`, `user-profile`, `settings:`, `chat:presence:`, `email-ctx:`, `trending:`.
Never flushable (denylist beats everything, in either overlap direction): `sid:`, `stepup:`, `otp:`, `rl:`, `ops:job-`.

**Request body**: None.

**Response** — `200 OK`:

```json
{ "prefix": "chat:presence:", "deleted": 412 }
```

**Errors**
- `400 INVALID_PREFIX` — prefix shorter than 3 chars ("refusing a near-global flush").
- `400 PREFIX_FORBIDDEN` — prefix overlaps auth/abuse state (`sid:`, `stepup:`, `otp:`, `rl:`, `ops:job-`).
- `400 PREFIX_NOT_ALLOWED` — not in the allowlist (message lists it).
- `403 STEP_UP_REQUIRED`.

### Config, reconciler & backfill

### GET /api/v1/admin/ops/config

Sanitized env/flag registry — secrets never rendered, by construction. Reading it is itself audited (`ADMIN_OPS_CONFIG_VIEW`).

**Access**: ADMIN. Step-up: **yes**.

**Request body**: None.

**Response** — `200 OK`, keys in this fixed order (`permitAllWarning` appears **only** when permit-all is on — a null value is omitted):

```json
{
  "app.security.permit-all": false,
  "app.security.step-up.ttl-seconds": 300,
  "media.processing.enabled (MEDIA_TRANSCODE_ENABLED)": false,
  "app.streaming.control-api-base": "http://localhost:9997",
  "app.streaming.webrtc-base": "http://localhost:8889",
  "app.streaming.playback-base": "http://localhost:8888",
  "app.streaming.ingest-base": "rtmp://localhost:1935",
  "app.streaming.recordings-dir": "./recordings",
  "irc.email.provider": "smtp",
  "irc.email.enabled": true,
  "spring.cassandra.keyspace-name": "irc_keyspace",
  "spring.data.redis.host": "localhost",
  "app.tags.trending-refresh-ms": 600000,
  "irc.base-url": "http://localhost:5173"
}
```

With permit-all on, `permitAllWarning` follows `app.security.permit-all`: `"⚠ CRITICAL: permit-all is ON — every gate incl. /api/v1/admin/** is open"`.

**Errors**
- `403 STEP_UP_REQUIRED`.

### GET /api/v1/admin/ops/config/reconciler

Startup outcome of the enum-CHECK constraint reconciler (`EnumCheckConstraintReconciler` — drops stale `*_check` constraints that `ddl-auto: update` won't widen).

**Access**: ADMIN. Step-up: no.

**Request body**: None.

**Response** — `200 OK`, keys `lastRunAt`, `constraints`, `note`. `constraints` maps `table.constraint` → `"DROPPED_OR_ABSENT"` or `"FAILED: <cause>"` for each registered pair:

```json
{
  "lastRunAt": "2026-08-07T01:02:03.456",
  "constraints": {
    "live_streams.live_streams_recording_status_check": "DROPPED_OR_ABSENT",
    "users.users_role_check": "DROPPED_OR_ABSENT",
    "platform_announcements.platform_announcements_status_check": "DROPPED_OR_ABSENT"
  },
  "note": "Runs once per boot; DROP CONSTRAINT IF EXISTS is idempotent. When widening an @Enumerated(STRING) enum on an existing table, add its (table, <table>_<column>_check) pair to STALE_ENUM_CHECKS."
}
```

**Errors**: none specific.

### POST /api/v1/admin/ops/es/chat-messages/backfill

Re-run the `message_by_id` backfill: clears the completion marker and executes the same idempotent, bounded walk the boot path uses (only missing rows are written — safe on live traffic). Runs **async** on the task executor; progress lands in the `job_runs` ledger under `chat-message-backfill`. Audits `ADMIN_CHAT_BACKFILL_RUN`.

**Access**: ADMIN. Step-up: **yes**.

**Request body**: None.

**Response** — `202 Accepted` with the ledger job id:

```json
{
  "jobId": "5a1b2c3d-6e7f-4a8b-9c0d-1e2f3a4b5c6d",
  "note": "Idempotent (writes only missing message_by_id rows); poll GET /api/v1/admin/ops/jobs/chat-message-backfill/runs"
}
```

**Errors**
- `403 STEP_UP_REQUIRED`.

### Media plane & stream sweep

### GET /api/v1/admin/ops/media-plane

MediaMTX view: control-API reachability plus the raw path/session listings. `paths`, `webrtcSessions`, `rtmpConns` are the **raw JSON strings** returned by MediaMTX (`/v3/config/paths/list`, `/v3/webrtcsessions/list`, `/v3/rtmpconns/list`) — or the literal string `"unreachable"` when a call fails.

**Access**: ADMIN. Step-up: no.

**Request body**: None.

**Response** — `200 OK`:

```json
{
  "controlApi": "http://localhost:9997",
  "reachable": true,
  "paths": "{\"itemCount\":1,\"pageCount\":1,\"items\":[{\"name\":\"8c9d0e1f-2a3b-4c5d-6e7f-8a9b0c1d2e3f\",\"record\":true}]}",
  "webrtcSessions": "{\"itemCount\":1,\"items\":[{\"id\":\"abc123\",\"state\":\"publish\",\"path\":\"8c9d0e1f-2a3b-4c5d-6e7f-8a9b0c1d2e3f\"}]}",
  "rtmpConns": "{\"itemCount\":0,\"items\":[]}"
}
```

**Errors**: none specific.

### POST /api/v1/admin/ops/streams/sweep-orphans

End LIVE stream rows orphaned by a crash: LIVE with **no MediaMTX publisher session** (WebRTC or RTMP) past the grace window, or LIVE **beyond the hard age cap** regardless of publisher. Audits `ADMIN_STREAM_SWEEP` (only when not a dry run and something was swept).

**Access**: ADMIN. Step-up: **yes**.

**Params**

| Param | In | Default | Notes |
|---|---|---|---|
| `graceMinutes` | query | `30` | min 1; LIVE started before now−grace is a sweep candidate |
| `maxAgeHours` | query | `12` | min 1; LIVE older than this is ended even with a publisher |
| `dryRun` | query | `false` | **`true` reports without ending anything** — run this first |

**Request body**: None.

**Response** — `200 OK`, keys `dryRun`, `mediamtxReachable`, `swept`. Each swept row: `streamId`, `hostId`, `startedAt`, `reason`, plus `ended` (+ `error` on failure) only when not a dry run.

```json
{
  "dryRun": false,
  "mediamtxReachable": true,
  "swept": [
    {
      "streamId": "8c9d0e1f-2a3b-4c5d-6e7f-8a9b0c1d2e3f",
      "hostId": "b2a6d9e0-1c4f-4e8a-93d7-5a0c1b2d3e4f",
      "startedAt": "2026-08-06T19:02:44.120Z",
      "reason": "no publisher session past grace",
      "ended": true
    }
  ]
}
```

The other `reason` form is `"LIVE beyond 12h"`. Caution: if MediaMTX is unreachable (`mediamtxReachable: false`) every LIVE stream past grace looks publisher-less — check reachability before a wet run.

**Errors**
- `403 STEP_UP_REQUIRED`.

---

## Break-glass activity forensics — `AdminActivityController`

Base path `/api/v1/admin`. The privacy contract: per-user timeline / histogram / reel-history / export reads return **403 `BREAKGLASS_CASE_REQUIRED`** unless an **OPEN** dual-control case exists for that user (opened by one admin, approved by a *different* admin, auto-expiring 7 days after opening). Every gated read additionally writes a loud audit row. Erasure is the one routinely-available per-user action (no case needed).

**`BreakGlassCase` JSON shape** (returned by the lifecycle endpoints; null fields omitted):
`id`, `targetUserId`, `kind` (`LEGAL_HOLD` | `LAW_ENFORCEMENT` | `SAFETY_INVESTIGATION`), `reason`, `caseRef`, `openedBy`, `approvedBy`, `status` (`PENDING_APPROVAL` | `OPEN` | `CLOSED` | `REJECTED`), `openedAt`, `approvedAt`, `expiresAt` (defaults to `openedAt` + 7 days), `closedAt`, `openNow` (derived: `status == OPEN` and not expired).

### POST /api/v1/admin/breakglass/{targetUserId}

Open a break-glass case for a user. Created in `PENDING_APPROVAL`; useless until a second admin approves. Audits `ADMIN_BREAKGLASS_CASE_OPEN`.

**Access**: ADMIN. Step-up: **yes**.

**Params**: `targetUserId` (path, UUID).

**Request body** — `kind` required (case-insensitive), `reason` required (contract: 10–1000 chars), `caseRef` optional (≤120 chars, external ticket / legal-process id):

```json
{
  "kind": "SAFETY_INVESTIGATION",
  "reason": "Report #4211 — coordinated harassment; reviewing reporter-named windows",
  "caseRef": "SAFE-4211"
}
```

**Response** — `201 Created`:

```json
{
  "id": "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f",
  "targetUserId": "9f8e7d6c-5b4a-4392-a1b0-c9d8e7f6a5b4",
  "kind": "SAFETY_INVESTIGATION",
  "reason": "Report #4211 — coordinated harassment; reviewing reporter-named windows",
  "caseRef": "SAFE-4211",
  "openedBy": "b2a6d9e0-1c4f-4e8a-93d7-5a0c1b2d3e4f",
  "status": "PENDING_APPROVAL",
  "openedAt": "2026-08-07T09:15:00.221",
  "expiresAt": "2026-08-14T09:15:00.221",
  "openNow": false
}
```

**Errors**
- `400 INVALID_KIND` — "Unknown kind. Allowed: [LEGAL_HOLD, LAW_ENFORCEMENT, SAFETY_INVESTIGATION]".
- `403 STEP_UP_REQUIRED`.

### POST /api/v1/admin/breakglass/cases/{caseId}/approve

Approve a pending case — **dual control: the approver must be a different admin than the opener.** Flips to `OPEN`, stamps `approvedBy`/`approvedAt`. Audits `ADMIN_BREAKGLASS_CASE_APPROVE`.

**Access**: ADMIN. Step-up: **yes**.

**Params**: `caseId` (path, UUID).

**Request body**: None.

**Response** — `200 OK`: the case with `"status": "OPEN"`, `approvedBy`, `approvedAt` (and `"openNow": true` while unexpired).

**Errors**
- `404 BREAKGLASSCASE_NOT_FOUND` — no such case.
- `400 CASE_NOT_PENDING` — case is not `PENDING_APPROVAL`.
- `403 DUAL_CONTROL_REQUIRED` — approver is the admin who opened it.
- `403 STEP_UP_REQUIRED`.

### POST /api/v1/admin/breakglass/cases/{caseId}/close

Close a case (any state → `CLOSED`, `closedAt` stamped) — ends forensic access early. Audits `ADMIN_BREAKGLASS_CASE_CLOSE`.

**Access**: ADMIN. Step-up: no (closing only reduces access).

**Params**: `caseId` (path, UUID).

**Request body**: None.

**Response** — `200 OK`: the case with `"status": "CLOSED"` and `closedAt`.

**Errors**
- `404 BREAKGLASSCASE_NOT_FOUND`.

### GET /api/v1/admin/breakglass/cases

List all cases, newest first.

**Access**: ADMIN. Step-up: no.

**Params**: `page` (default 0), `size` (default 25, clamped to 1–100).

**Request body**: None.

**Response** — `200 OK`, `Page<BreakGlassCase>`:

```json
{
  "content": [
    {
      "id": "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f",
      "targetUserId": "9f8e7d6c-5b4a-4392-a1b0-c9d8e7f6a5b4",
      "kind": "SAFETY_INVESTIGATION",
      "reason": "Report #4211 — coordinated harassment; reviewing reporter-named windows",
      "caseRef": "SAFE-4211",
      "openedBy": "b2a6d9e0-1c4f-4e8a-93d7-5a0c1b2d3e4f",
      "approvedBy": "d4c3b2a1-0f9e-4d8c-b7a6-5e4f3d2c1b0a",
      "status": "OPEN",
      "openedAt": "2026-08-07T09:15:00.221",
      "approvedAt": "2026-08-07T09:22:41.005",
      "expiresAt": "2026-08-14T09:15:00.221",
      "openNow": true
    }
  ],
  "totalElements": 4,
  "totalPages": 1,
  "number": 0,
  "size": 25
}
```

> Standard Spring `Page` envelope — trimmed here; see [Conventions](#conventions).

**Errors**: none specific.

### GET /api/v1/admin/users/{userId}/activity

Break-glass read of the user's activity timeline (same rows the user sees on their own feed). **Requires an OPEN case for this user.** Audits `ADMIN_ACTIVITY_BREAKGLASS_VIEW` per read.

**Access**: ADMIN. Step-up: **yes**. Break-glass: **yes**.

**Params**

| Param | In | Default | Notes |
|---|---|---|---|
| `userId` | path | — | target user |
| `types` | query | all | optional repeat/CSV of `UserActivityType` values, OR-unioned |
| `from` / `to` | query | — | optional ISO date-times (`2026-08-01T00:00:00`), inclusive, interpreted as UTC |
| `size` | query | `25` | clamped to 1–100. **Page number is always forced to 0** — this read is the newest window only |

`UserActivityType`: `POST_CREATED`, `POST_REACTION`, `POST_COMMENT`, `POST_COMMENT_REACTION`, `POST_SHARE`, `POST_SAVED`, `REEL_WATCH`, `GLOBAL_SEARCH`, `HASHTAG_SEARCH`, `MENTION_LOOKUP`, `USER_MENTIONED`, `PROFILE_VIEW`, `FOLLOWED_USER`, `QNA_QUESTION_CREATED`, `QNA_QUESTION_SAVED`, `QNA_ANSWER_CREATED`, `QNA_REANSWER_CREATED`, `QNA_ANSWER_REACTION`, `QNA_ANSWER_FEEDBACK`, `RESEARCH_PUBLISHED`, `RESEARCH_SAVED`, `RESEARCH_REACTION`, `RESEARCH_COMMENT`, `RESEARCH_COMMENT_REACTION`, `STORY_VIEWED`, `STORY_REACTED`, `STORY_REPLIED`, `STORY_POLL_VOTED`, `SOUND_USED`.

**Request body**: None.

**Response** — `200 OK`, `Page<UserActivityResponse>`. Per-row fields are type-dependent; only non-null ones appear. Full field set: `id`, `activityType`, `reactionType`, `watchedSeconds`, `post` (`{id, postType, textPreview, thumbnailUrl, author}`), `comment` (`{id, textPreview}`), `query`, `searchScope`, `hitCount`, `targetUser`, `question` (`{id, title, author}`), `answer` (`{id, parentAnswerId, bodyPreview, accepted, author}`), `qnaReactionType`, `research` (`{id, ircId, title, coverImageUrl, author}`), `researchComment`, `createdAt`, `timeAgo`, `formattedDate`, `label`, `subtitle`. Author objects are `{id, username, fullName, avatarUrl}`.

```json
{
  "content": [
    {
      "id": "3a4b5c6d-7e8f-4a9b-8c0d-1e2f3a4b5c6d",
      "activityType": "POST_REACTION",
      "reactionType": "LIKE",
      "post": {
        "id": "0d1e2f3a-4b5c-4d6e-8f9a-0b1c2d3e4f5a",
        "postType": "IMAGE",
        "textPreview": "Fieldwork notes from the archive…",
        "thumbnailUrl": "https://cdn.example/th/0d1e.jpg",
        "author": {
          "id": "77e6d5c4-b3a2-4190-8f7e-6d5c4b3a2918",
          "username": "amira.h",
          "fullName": "Amira Hassan",
          "avatarUrl": "https://cdn.example/av/amira.jpg"
        }
      },
      "createdAt": "2026-08-06T21:40:12.334",
      "timeAgo": "12 hours ago",
      "formattedDate": "Aug 6, 2026",
      "label": "Liked a post",
      "subtitle": "You liked a post by Amira Hassan"
    }
  ],
  "totalElements": 812,
  "totalPages": 33,
  "number": 0,
  "size": 25
}
```

> Standard Spring `Page` envelope — trimmed here; see [Conventions](#conventions).

**Errors**
- `403 BREAKGLASS_CASE_REQUIRED` — no OPEN (unexpired) case for this user.
- `403 STEP_UP_REQUIRED`.

### GET /api/v1/admin/users/{userId}/activity/summary

Per-type activity histogram over a trailing window. **Requires an OPEN case.** Audits `ADMIN_ACTIVITY_SUMMARY_VIEW`.

**Access**: ADMIN. Step-up: **yes**. Break-glass: **yes**.

**Params**

| Param | In | Default | Notes |
|---|---|---|---|
| `userId` | path | — | target user |
| `window` | query | `90` | days, clamped 1–365 |

**Request body**: None.

**Response** — `200 OK`, `Map<String,Long>` of `UserActivityType` → count. **Zero-count types are omitted**; per-type count failures are silently skipped.

```json
{
  "POST_CREATED": 14,
  "POST_REACTION": 220,
  "REEL_WATCH": 1904,
  "GLOBAL_SEARCH": 77,
  "PROFILE_VIEW": 131
}
```

**Errors**
- `403 BREAKGLASS_CASE_REQUIRED`.
- `403 STEP_UP_REQUIRED`.

### GET /api/v1/admin/users/{userId}/reels/watched

Reel watch history (cursor-paged, newest first). **Requires an OPEN case.** Audits `ADMIN_REELVIEWS_BREAKGLASS_VIEW`.

**Access**: ADMIN. Step-up: **yes**. Break-glass: **yes**.

**Params**

| Param | In | Default | Notes |
|---|---|---|---|
| `userId` | path | — | target user |
| `pageSize` | query | `50` | clamped to 1–100 |
| `cursor` | query | — | optional ISO date-time; pass the last row's `watchedAt` to fetch the next page |

**Request body**: None.

**Response** — `200 OK`, a JSON array (not a `Page`), row keys in order:

```json
[
  {
    "reelViewId": "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c",
    "postId": "0d1e2f3a-4b5c-4d6e-8f9a-0b1c2d3e4f5a",
    "watchedSeconds": 23,
    "watchedAt": "2026-08-06T20:11:35.902Z"
  }
]
```

**Errors**
- `403 BREAKGLASS_CASE_REQUIRED`.
- `403 STEP_UP_REQUIRED`.

### GET /api/v1/admin/users/{userId}/activity/export

Case-evidence export of the visible window (up to the newest 1000 rows, all types) — PII egress, loudly audited (`ADMIN_ACTIVITY_EXPORT`). **Requires an OPEN case.**

**Access**: ADMIN. Step-up: **yes**. Break-glass: **yes**.

**Params**

| Param | In | Default | Notes |
|---|---|---|---|
| `userId` | path | — | target user |
| `from` / `to` | query | — | optional ISO date-times, inclusive, UTC |
| `format` | query | `json` | `json` or `csv` (case-insensitive); anything else falls back to JSON |

**Request body**: None.

**Response**
- `format=json` → `200 OK`, a bare JSON **array** of `UserActivityResponse` rows (same shape as the timeline read, no `Page` envelope).
- `format=csv` → `200 OK`, `Content-Type: text/csv`, `Content-Disposition: attachment; filename="activity-{userId}.csv"`:

```
activityId,type,createdAt
3a4b5c6d-7e8f-4a9b-8c0d-1e2f3a4b5c6d,POST_REACTION,2026-08-06T21:40:12.334
```

**Errors**
- `403 BREAKGLASS_CASE_REQUIRED`.
- `403 STEP_UP_REQUIRED`.

### POST /api/v1/admin/users/{userId}/activity/erase

Erase the user's activity rows (all types, or one type) — the routinely-available action; honors the user-deletable contract, so **no break-glass case needed**. Deletes in batches of 200, hard-capped at 10 000 rows per call. Audits `ADMIN_ACTIVITY_ERASE` with type, row count and reason.

**Access**: ADMIN. Step-up: **yes**. Break-glass: no.

**Params**: `userId` (path, UUID).

**Request body** — optional; both fields optional (`type` a `UserActivityType` name, case-insensitive; `reason` ≤500 chars, recorded in the audit row):

```json
{ "type": "REEL_WATCH", "reason": "GDPR Art. 17 request #1188" }
```

**Response** — `200 OK`:

```json
{
  "deleted": 1904,
  "note": "hard cap 10k rows per call — repeat for heavier histories"
}
```

**Errors**
- `400 INVALID_TYPE` — "Unknown activity type."
- `403 STEP_UP_REQUIRED`.

---

## Discovery / PYMK oversight — `AdminDiscoveryController`

Base path `/api/v1/admin`. Raw contact hashes are never exposed — only counts.

### POST /api/v1/admin/users/{userId}/suggestions/recompute

Fire an async PYMK recompute for one user (full 6-source pipeline). Audits `ADMIN_PYMK_RECOMPUTE`.

**Access**: ADMIN. Step-up: no.

**Params**: `userId` (path, UUID).

**Request body**: None.

**Response** — `202 Accepted`, empty body. The recompute runs `@Async`; re-read `GET …/suggestions` afterwards.

**Errors**: none specific.

### GET /api/v1/admin/users/{userId}/suggestions

Inspect the user's stored PYMK state: top-50 suggestion rows plus their dismissed candidates. Cross-user matching data — PII-classed, therefore step-up + audited (`ADMIN_PYMK_VIEW`).

**Access**: ADMIN. Step-up: **yes**.

**Params**: `userId` (path, UUID).

**Request body**: None.

**Response** — `200 OK`. Suggestion rows are score-ordered (Cassandra clustering DESC); keys per row in order `candidateId`, `storedScore` (integer — the rounded pipeline score), `reason` (human label, up to 3 signals joined with `·`), `computedAt`.

```json
{
  "userId": "9f8e7d6c-5b4a-4392-a1b0-c9d8e7f6a5b4",
  "suggestions": [
    {
      "candidateId": "77e6d5c4-b3a2-4190-8f7e-6d5c4b3a2918",
      "storedScore": 87,
      "reason": "6 mutual follows · in each other's contacts · same institution",
      "computedAt": "2026-08-06T22:10:05.481Z"
    }
  ],
  "dismissedCandidateIds": [
    "11a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c"
  ]
}
```

**Errors**
- `403 STEP_UP_REQUIRED`.

### GET /api/v1/admin/discovery/contact-sync/stats

Aggregate contact-sync privacy stats — counts only, never hashes.

**Access**: ADMIN. Step-up: no.

**Request body**: None.

**Response** — `200 OK`, keys in order. `ownersNearCap` lists owners holding ≥4500 CONTACT hashes (harvesting signal), sorted by count DESC.

```json
{
  "contactHashRows": 183440,
  "usersWithSyncedContacts": 412,
  "identityHashRows": 25011,
  "ownersNearCap": [
    { "ownerId": "9f8e7d6c-5b4a-4392-a1b0-c9d8e7f6a5b4", "hashes": 4987 }
  ],
  "capPerSync": 5000,
  "notes": [
    "Hashes are SHA-256 of phone/email — the server never sees raw contacts.",
    "Identity-hash backfill makes every active account matchable-by-default; discovery flags gate whether a match is surfaced.",
    "The contact:sync rate limit (3/24h) FAILS OPEN if Redis is down."
  ]
}
```

**Errors**: none specific.

### GET /api/v1/admin/discovery/contact-sync/compliance

Consent∧hash compliance report: users holding synced CONTACT hashes **without** an active `CONTACTS` consent (a consent-service failure counts as not consented). Audits `ADMIN_CONTACT_COMPLIANCE_VIEW`.

**Access**: ADMIN. Step-up: **yes**.

**Params**

| Param | In | Default | Notes |
|---|---|---|---|
| `scanLimit` | query | `500` | owners scanned, clamped 1–2000 |

**Request body**: None.

**Response** — `200 OK`. `scanTruncated: true` means more owners exist than were scanned — raise `scanLimit` or re-run.

```json
{
  "ownersScanned": 412,
  "scanTruncated": false,
  "ownersWithoutActiveConsent": [
    "9f8e7d6c-5b4a-4392-a1b0-c9d8e7f6a5b4"
  ]
}
```

**Errors**
- `403 STEP_UP_REQUIRED`.

### POST /api/v1/admin/users/{userId}/contact-hashes/purge

GDPR purge of **all** the user's contact-hash rows — both their uploaded `CONTACT` hashes and their server-computed `IDENTITY` hash (the account stops being matchable until the next sync/backfill). Transactional. Audits `ADMIN_CONTACT_HASH_PURGE` with the row count.

**Access**: ADMIN. Step-up: **yes**.

**Params**: `userId` (path, UUID).

**Request body**: None.

**Response** — `200 OK` (count taken before deletion):

```json
{ "deleted": 4988 }
```

**Errors**
- `403 STEP_UP_REQUIRED`.

### GET /api/v1/admin/users/{userId}/discovery

Inspect a user's discoverability flags + QR token state. Defaults (row absent): `byUsername=true`, `byPhone=false`, `byEmail=false`, `byQr=true`, `indexable=true`.

**Access**: ADMIN. Step-up: no.

**Params**: `userId` (path, UUID).

**Request body**: None.

**Response** — `200 OK`, keys in order (`qrRotatedAt` only when a token exists):

```json
{
  "byUsername": true,
  "byPhone": false,
  "byEmail": false,
  "byQr": true,
  "indexable": true,
  "qrTokenActive": true,
  "qrRotatedAt": "2026-07-30T10:05:12.400",
  "knownSeam": "QR-resolve does not yet consult discover.byQr — a rotated flag does not invalidate resolves; rotation (below) does."
}
```

Without a token, `qrTokenActive: false` and no `qrRotatedAt`.

**Errors**: none specific.

### POST /api/v1/admin/users/{userId}/qr/rotate

Force-rotate the user's QR discovery token — the old opaque value stops resolving immediately (the effective kill switch given the `knownSeam` above). Mints a token if the user had none. Audits `ADMIN_QR_ROTATE`.

**Access**: ADMIN. Step-up: **yes**.

**Params**: `userId` (path, UUID).

**Request body**: None.

**Response** — `200 OK` (`opaqueToken` is 32 chars of URL-safe base64):

```json
{
  "opaqueToken": "Ur3xkQ9dTfLa0WcYb27ZsVn41mHpKjE8",
  "rotatedAt": "2026-08-07T09:30:00.115"
}
```

**Errors**
- `403 STEP_UP_REQUIRED`.
