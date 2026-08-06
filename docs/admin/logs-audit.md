# Section 9 — Logs & Audit

> **The complete log catalog.** Every store on the platform that records *what
> happened* — schema, writers, read APIs, retention, and how the admin
> dashboard surfaces it — plus the unified **Log Explorer** spec, log-based
> alert rules, the retention policy matrix, GDPR/purge handling, and the
> integrity principles the whole section is built on.
> Tags: **[EXISTS]** = real today (class / endpoint cited) · **[PARTIAL]** =
> partially real · **[PLANNED]** = proposed for the dashboard build.
> Siblings: [architecture.md](architecture.md) ·
> [users-roles.md](users-roles.md) · [safety-reports.md](safety-reports.md) ·
> [operations.md](operations.md) · [analytics-kpis.md](analytics-kpis.md) ·
> [admin-api-blueprint.md](admin-api-blueprint.md)

---

## 1. Purpose & scope

| In scope | Out of scope (see instead) |
|----------|---------------------------|
| Request-level audit log (audit module: Cassandra tables, interceptor, admin API, live SSE tail) | Report **triage workflow** — [safety-reports.md](safety-reports.md) (this doc covers reports/strikes only *as records*) |
| Per-row `BaseAuditEntity` forensic columns on every JPA table | Job/queue **operations** — [operations.md](operations.md) (this doc covers the DLQ only *as a log*) |
| Domain append-only trails: `settings_audit`, `login_events`, `consent_events`, `otp_challenges`, `policy_acceptances` | Product **analytics/KPIs** — [analytics-kpis.md](analytics-kpis.md) |
| Moderation records (`reports`, `user_strikes`) and data-lifecycle records (`export_jobs`, `account_deletion_requests`, `deleted_accounts`) as evidence stores | Media pipeline ops — [media-storage.md](media-storage.md) (media errors appear here only as a failure *trail*) |
| Infrastructure logs: RabbitMQ DLQ drain, application log, Hibernate SQL echo, media error trail | Content stores that merely *look* like logs (chat Cassandra log, activity feed) — catalogued in §3.14 strictly to declare them **off-limits** |
| Unified Log Explorer, alert rules, retention matrix, GDPR purge behavior, integrity principles | |

Three audit layers exist today and the dashboard must present them as one
mental model:

| Layer | Granularity | Store | Answers |
|-------|------------|-------|---------|
| **Request audit** [EXISTS] | one row per API request | Cassandra `audit_log_by_user` + `audit_log_by_resource` | "what did user U do?" / "what happened to resource R?" |
| **Row-level audit** [EXISTS] | columns on every JPA row | PG `BaseAuditEntity` columns on ~every table | "who last touched *this exact row*, from where?" |
| **Domain trails** [EXISTS] | one row per domain event | PG `settings_audit`, `login_events`, `consent_events`, `otp_challenges`, `reports`, … | "what is the evidence history for X?" |

---

## 2. Dashboard views / widgets

Section layout — left-hand sub-nav with six views, unified Explorer as the landing view:

| View | Widgets on screen | Backed by |
|------|-------------------|-----------|
| **Log Explorer** (landing) [PLANNED] | Filter bar (grammar §4.1) → virtualized result table (time, store badge, user, operation, outcome, resource, ip, duration, summary) → row drawer with full record + "pivot" buttons (same user ±5 min across all stores, same resource, same IP) | Aggregation façade over §3 stores (§4) |
| **Live tail** [EXISTS today as raw SSE] | Streaming table of audit events as they happen; pause/resume; client-side filter on operation/outcome/path; per-minute rate sparkline; connected-admins count | `GET /api/v1/admin/audit/stream` (§3.1) — the only global audit view that exists today |
| **Security log** [PARTIAL] | Login-events table (outcome/method/ip/geo), failed-login spike chart, new-IP alerts feed, OTP challenge volume, step-up usage | `login_events` (§3.4 — writer wired via `AuthServiceImpl`, admin read `GET /api/v1/admin/logs/login-events`), `otp_challenges` (§3.6) |
| **Moderation trail** [PARTIAL] | Reports intake timeline, per-target report stacks (`group_key`), strikes ledger (live since 2026-08), appeal states | `reports` + `user_strikes` (§3.7); actions live in [safety-reports.md](safety-reports.md) |
| **Lifecycle & compliance** [PARTIAL] | Deletion pipeline funnel (PENDING_DELETION → PURGED), export-jobs table with expiry status, consent history viewer, policy-acceptance coverage matrix | §3.5, §3.8, §3.9 |
| **Infra logs** [PARTIAL] | DLQ casualty feed (parsed from drain log), publisher NACK/unroutable counters, media-failure queue (status + `error_message`), app-log level matrix (read-only display of config) | §3.10–§3.13 |
| **Retention board** [EXISTS (built 2026-08) — backend] | Per-store card: current retention → recommended → enforcement status (enforced / job / **unbounded ⚠**); row-count + growth trend per PG store | `GET /api/v1/admin/logs/retention` (A10) over the §7 matrix |

---

## 3. The log store catalog (data sources)

One subsection per store. This section doubles as the mandatory **"data
sources"** and **"logs surfaced in this section"** inventories — every widget
in §2 maps to exactly one subsection below.

### 3.1 Request audit log — the audit module [EXISTS]

The flagship trail: one metadata-only record per API request, dual-written to
two query-shaped Cassandra tables, tailed live over SSE.

**Pipeline** (all classes under `ak.dev.irc.app.audit`):

```
AuditLoggingInterceptor.afterCompletion  (web/, registered on /api/** by AuditWebMvcConfig)
  → builds AuditLog value object          (entity/AuditLog.java — plain DTO, NOT JPA; no PG table remains)
  → AuditLogService.recordAsync           (@Async("auditExecutor") — pool 4/16, queue 50 000, CallerRunsPolicy, threads "audit-")
      → INSERT audit_log_by_user          (skipped when userId == null — anonymous traffic can't be a partition key)
      → INSERT audit_log_by_resource      (only when resourceType AND resourceId parsed from the path)
      → AuditRealtimePublisher            (JSON AuditLogResponse → Redis pub/sub channel "irc:audit:stream")
          → AuditRealtimeSubscriber → AuditRealtimeService → every connected admin SseEmitter
```

**What the interceptor captures — and refuses to capture:**

| Behavior | Detail |
|----------|--------|
| Paths intercepted | `/api/**` (`AuditWebMvcConfig.addInterceptors`) |
| Paths skipped (`SKIP_PATTERN`, case-insensitive) | `/actuator*`, `/error*`, `/favicon*`, `/swagger*`, `/v3/api-docs*`, `/health*`, any `*/stream` or `*/stream/**`, any `*/heartbeat` — **all SSE/realtime traffic is invisible to the audit log by design** |
| Body handling | **Never read.** Metadata only — `POST /api/v1/auth/login` is recorded with no payload, so passwords cannot leak into the log |
| Duration | `preHandle` stamps request attr `ak.audit.startedAt`; `afterCompletion` computes `duration_ms` |
| Resource parsing | `parseResource`: deepest UUID path segment + preceding segment → `toResourceType` map (posts→`Post`, comments→`PostComment`, questions→`Question`, answers/reanswers/replies→`QuestionAnswer`, research→`Research`, users→`User`, notifications→`Notification`, audit→`AuditLog`, search→`Search`, else Capitalized segment) |
| Operation derivation | HTTP verb → `AuditOperation` (GET/HEAD→READ, POST→CREATE, PUT/PATCH→UPDATE, DELETE→DELETE; login/logout/multipart specials) |
| Truncation | `query_string` ≤ 1000 chars, `user_agent` ≤ 400 chars |
| Failure model | Try/catch at interceptor, service, and publisher — **audit failure never breaks the request** (accepted trade-off: silent loss under extreme failure) |

**Enums:**

| `AuditOperation` | Meaning | | `AuditOutcome` | Meaning |
|---|---|---|---|---|
| `READ` | GET, HEAD | | `SUCCESS` | 2xx |
| `CREATE` | POST (default) | | `REDIRECT` | 3xx |
| `UPDATE` | PUT, PATCH | | `CLIENT_ERROR` | 4xx |
| `DELETE` | DELETE | | `SERVER_ERROR` | 5xx / unhandled exception |
| `LOGIN` / `LOGOUT` | auth endpoints | | `SYSTEM` | non-HTTP action (job, internal) |
| `UPLOAD` | multipart POST | | | |
| `SYSTEM` / `OTHER` | non-HTTP / fallback | | | |

**Schema — `audit_log_by_user` (Cassandra, `AuditLogByUserEntity`):**

| Column | Type | Key | Meaning |
|--------|------|-----|---------|
| `user_id` | uuid | **PARTITION** | Acting user — one partition per user, **no time-bucketing** (see caveat below) |
| `created_at` | timestamp | CLUSTER ↓ DESC | Event time (UTC `Instant` at the row boundary) |
| `audit_id` | uuid | CLUSTER | Tie-breaker / stable row id |
| `username` | text | | Username **as captured at request time** (survives later renames/purges — see §8 GDPR) |
| `operation` | text | | `AuditOperation` name |
| `outcome` | text | | `AuditOutcome` name |
| `resource_type` / `resource_id` | text / uuid | | Parsed resource (nullable) |
| `http_method` / `path` / `query_string` | text | | Request line (query truncated 1000) |
| `status_code` | int | | HTTP status |
| `duration_ms` | bigint | | Wall-clock request latency |
| `ip_address` / `user_agent` | text | | Caller network identity (UA truncated 400) |
| `summary` | text | | Human line: `"METHOD path → status"` |
| `error_code` | text | | Exception simple name on failure |

**Schema — `audit_log_by_resource` (`AuditLogByResourceEntity`):** identical
payload plus `user_id` as a regular column; primary key is composite partition
**(`resource_type`, `resource_id`)** + clustering `created_at DESC, audit_id`.
This is the **only** table that captures anonymous traffic (null user), and
the only store that can answer "what happened to this Post?".

**Read APIs [EXISTS]** — `AuditLogController`, base `/api/v1/admin/audit`,
class-level `@PreAuthorize("hasAnyRole('ADMIN','MODERATOR','SUPPORT','ANALYST')")`
(the phantom `SUPER_ADMIN` was dropped; the gate now names real staff roles from
the widened seven-role model) **plus** the
`/api/v1/admin/**` filter-chain double gate:

| Endpoint | Semantics | Limits |
|----------|-----------|--------|
| `GET /api/v1/admin/audit?userId=&operation=&outcome=&from=&to=&pageSize=50&cursor=` | Audit search. **400 without `userId`** — Cassandra requires a partition anchor | `operation/outcome/from/to` filtered **in-memory on one fetched page** (a filtered page may return < pageSize rows even when more matches exist deeper) |
| `GET /api/v1/admin/audit/users/{userId}?pageSize=&cursor=` | Per-user history, pure keyset (`firstPage`/`nextPage` on `created_at`, cursor = `LocalDateTime`) | No filters, no in-memory pass |
| `GET /api/v1/admin/audit/resources/{resourceType}/{resourceId}?pageSize=&cursor=` (built 2026-08) | Per-resource history over `audit_log_by_resource` (A2) | Keyset, same cursor shape |
| `GET /api/v1/admin/audit/stream` | Global realtime SSE tail — events `connected` / `audit` / `heartbeat` (25 s), timeout 0, multi-instance safe via Redis channel `irc:audit:stream`; each connect writes an explicit audit row via `AuditLogService.record` | **The only global view** — historical global query is impossible today |

**Known gaps (feed the build order, §10):**

| Gap | Impact |
|-----|--------|
| ~~180-day TTL never applied~~ **Fixed (built 2026-08):** `audit/config/AuditSchemaInitializer` idempotently ensures `default_time_to_live = 15552000` on both audit tables at startup (create-with-TTL for fresh keyspaces, `ALTER` otherwise) | Audit log is now bounded at 180 d |
| ~~`audit_log_by_resource` has no read endpoint~~ **Fixed (built 2026-08):** `GET /api/v1/admin/audit/resources/{resourceType}/{resourceId}` (A2) exposes it | "What happened to this resource?" is now answerable via the API |
| ~~`AuditLogService.record(...)` has zero callers outside the audit module~~ **Fixed (built 2026-08):** `admin/support/AdminAuditor` funnels every admin mutation through it (`ADMIN_*` rows), the account-purge job writes a `SYSTEM` event per run, and `AuditLogController.stream` records each SSE connect | Jobs and admin mutations now leave an audit trail |
| Per-user partition has no time-bucket component | A very active user's partition grows without bound; acceptable at current scale, bounded by the 180 d TTL |

### 3.2 `BaseAuditEntity` — per-row audit columns on every table [EXISTS]

A platform-wide forensic layer: every JPA entity extending
`ak.dev.irc.app.common.BaseAuditEntity` (the norm across modules) carries a
who/when/where/what stamp maintained by JPA lifecycle hooks — no service code
required.

| Column | Type | Written | Meaning |
|--------|------|---------|---------|
| `created_at` / `updated_at` | timestamp | `@CreatedDate` / `@LastModifiedDate` | Row lifecycle timestamps (Spring auditing) |
| `created_by` / `updated_by` | uuid | `@CreatedBy` / `@LastModifiedBy` via `AuditorAwareImpl` (config/) | Acting user id from the security context |
| `created_by_ip` / `updated_by_ip` | varchar(45) | `@PrePersist` / `@PreUpdate` | `X-Forwarded-For` (first hop) → `X-Real-IP` → `remoteAddr` |
| `created_by_device` / `updated_by_device` | varchar(300) | same hooks | `User-Agent`, truncated 300 |
| `last_action` | varchar(30) enum `AuditAction` | defaults `CREATE`/`UPDATE` in hooks; overridable | Semantic action: `CREATE, UPDATE, DELETE, LOGIN, LOGOUT, FOLLOW, UNFOLLOW, BLOCK, UNBLOCK, RESTRICT, UNRESTRICT, UPLOAD, PASSWORD_CHANGE, EMAIL_VERIFY, TOKEN_REFRESH` |
| `action_note` | varchar(500) | `audit(action, note)` helper | Free-text context set by service code |

| Aspect | Detail |
|--------|--------|
| Writers | JPA `@PrePersist`/`@PreUpdate` capture the **live HTTP request context**; silently skipped in non-HTTP contexts (batch jobs, tests) — job-driven mutations get timestamps but null ip/device |
| Read APIs | None dedicated — columns ride along on each entity's own endpoints; most response mappers do **not** project them (correct: they are forensic, not product data) |
| Retention | Lives and dies with the owning row |
| Dashboard use | The row drawer in the user/content inspection views ([users-roles.md](users-roles.md)) shows this stamp per record; Log Explorer pivots "same IP" queries can hit these columns per-table [PLANNED] |
| Caveat | Hard-deleted rows (e.g. comments) take their stamp with them — the request-level log (§3.1) is the surviving record of the delete |

### 3.3 `settings_audit` — settings change trail [EXISTS; admin read built 2026-08]

| Column | Type | Meaning |
|--------|------|---------|
| `id` | uuid PK | |
| `user_id` | uuid NOT NULL | Whose settings changed |
| `setting_key` | varchar(120) | Dotted path, e.g. `privacy.bio`, `security.2fa` |
| `old_value` / `new_value` | text | Truncated at 4000 each |
| `ip` | varchar(45) | Captured by the service itself (X-Forwarded-For → remoteAddr) |
| `created_at` | timestamp | Index `idx_settings_audit_user (user_id, created_at)` |

| Aspect | Detail |
|--------|--------|
| Writers | `SettingsAuditService.record(...)` — called by `SettingsService`, `PrivacyService`, `DiscoverabilityService`, `PresencePolicyService` on every privacy/security-relevant mutation; best-effort (WARN on failure, never fails the settings write). Log prefix `[SETTINGS-AUDIT]` |
| Read APIs | `GET /api/v1/admin/users/{userId}/settings-audit` (`AdminUserController`, A3) + the `settings` store in the Log Explorer (A1) [EXISTS (built 2026-08)] |
| Retention | 2 y — enforced by `RetentionSweepJob` (nightly, built 2026-08) |
| Dashboard views | Per-user "settings changes" tab in user inspection; Log Explorer store `settings` [EXISTS (built 2026-08) — backend] |
| Why separate from §3.1 | Captures the `(key, old, new)` diff shape the generic request log cannot (bodies are never read); see [../settings/README.md](../settings/README.md) §22.3 |

### 3.4 `login_events` — login history [EXISTS — writer wired (built 2026-08)]

| Column | Type | Meaning |
|--------|------|---------|
| `id` | uuid PK | |
| `user_id` | uuid | Account (nullable for unresolvable attempts) |
| `ts` | timestamp NOT NULL | Event time; indexes `(user_id, ts)`, `(user_id, outcome)` |
| `ip` | varchar(45) | Source IP |
| `coarse_geo` | varchar(80) | Coarse country/region only — deliberately no precise geo |
| `user_agent` | varchar(400) | Device |
| `method` | varchar(20) | `PASSWORD` \| `OTP` \| `REFRESH` \| `TWO_FA` |
| `outcome` | varchar(20) | `SUCCESS` \| `FAILED` \| `LOCKED` |

| Aspect | Detail |
|--------|--------|
| Writers | `LoginEventService.record` / `recordSuccessAndAlertIfNew` (new-IP heuristic via `distinctSuccessfulIps` → security alert through `NotificationService.sendSystemNotification`, bypasses DND; prefix `[LOGIN-ALERT]`). **Wired (built 2026-08):** `AuthServiceImpl` calls `recordSuccessAndAlertIfNew` on login success and `record(userId, ip, userAgent, method, outcome)` on every attempt |
| Read APIs | `GET /api/v1/security/login-history` [EXISTS] — self-scoped (`SecurityController`, pageable). Admin read: `GET /api/v1/admin/logs/login-events` (A4, built 2026-08) |
| Retention | 1 y — enforced by `RetentionSweepJob` (nightly, built 2026-08) |
| Dashboard views | Security log view (§2): per-user login table, failed-spike chart, new-IP feed; admin read endpoint A4 [EXISTS (built 2026-08)] |
| Adjacent | The request audit log (§3.1) *does* already record `POST /auth/login` rows with `LOGIN` operation and status — a stopgap failed-login signal, but only for authenticated partitions (failed anonymous logins land in `audit_log_by_resource` only if a UUID parses, i.e. effectively nowhere useful) |

### 3.5 `consent_events` — consent evidence [EXISTS]

| Column | Type | Meaning |
|--------|------|---------|
| `id` | uuid PK | |
| `user_id` | uuid NOT NULL | |
| `scope` | varchar(60) | `CONTACTS` / `LOCATION` / `PHOTOS` / … |
| `granted` | boolean NOT NULL | Grant or revoke — **a state change is a new row; rows are never updated** |
| `app_version` | varchar(40) | Client version at consent time |
| `occurred_at` | timestamp NOT NULL | Indexes `(user_id, occurred_at)`, `(user_id, scope)` |

Writers: `ConsentService.record` via `POST /api/v1/settings/consent` [EXISTS].
Reads: `GET /api/v1/settings/consent` (history) + `GET /api/v1/settings/consent/{scope}` (current) — self-scoped [EXISTS].
Retention: **unbounded by design** — this *is* the compliance evidence for
contact-sync and similar consents; §7 keeps it exempt from purge, §8 covers the
GDPR tension. Dashboard: read-only consent viewer in Lifecycle & compliance
view; admin read proposed as A5 (§5).

### 3.6 `otp_challenges` — OTP audit trail [EXISTS]

| Column | Type | Meaning |
|--------|------|---------|
| `id` | uuid PK | |
| `destination_hash` | varchar(64) | HMAC-SHA256(E.164, pepper) — **raw phone number never stored** |
| `code_hash` | varchar(64) | HMAC-SHA256(code, pepper) — **plaintext code never stored** |
| `purpose` | varchar(20) | `OtpPurpose` enum |
| `attempts` | int | Verification attempts consumed |
| `expires_at` | timestamp NOT NULL | Index `idx_otp_expires` |
| `consumed_at` | timestamp | Null = never used |
| `ip` / `device_id` | varchar(45) / varchar(128) | Requester identity |
| `created_at` | timestamp | |

Writers: `OtpService` — row on issue, `attempts`/`consumed_at` updated on
verify. The **live** code lives in Redis (`otp:{purpose}:{destHash}`, TTL
`OTP_TTL_SECONDS`, default 300) — this PG row is the durable audit copy.
Reads: none (internal). Retention: 180 d on the indexed `expires_at` column —
enforced by `RetentionSweepJob` [EXISTS (built 2026-08)]. Dashboard: volume + failure-rate widget only
(hashes make rows individually meaningless, which is the point).

### 3.7 `reports` + `user_strikes` — moderation records [EXISTS as records; console is [safety-reports.md](safety-reports.md)]

**`reports`:**

| Column | Type | Meaning |
|--------|------|---------|
| `id` | uuid PK | |
| `reporter_id` | uuid NOT NULL | Index `(reporter_id, created_at)` |
| `target_type` / `target_id` | varchar(20) / uuid | 9 `ReportTargetType` values (USER…STORY); index `(target_id, reason)` |
| `reason` | varchar(24) | 10 `ReportReason` values (SPAM…OTHER) |
| `details` | varchar(1000) | Reporter free text |
| `state` | varchar(16) | `SUBMITTED → TRIAGED → ACTIONED\|DISMISSED → APPEALED → UPHELD\|REVERSED` — full machine driven by `ReportModerationService` (triage/action/dismiss/uphold/reverse, built 2026-08) |
| `resolution` | varchar(24) | `NONE / WARNING_ISSUED / CONTENT_REMOVED / ACCOUNT_SUSPENDED / NO_ACTION` — never shown to reporter |
| `group_key` | varchar(80) | `targetId:reason` — dedupes/stacks reports per target; indexed |
| `created_at` / `updated_at` | timestamp | |

**`user_strikes`:** `id`, `user_id`, `report_id` (evidence link), `reason`
varchar(200), `issued_at`, `expires_at` (= issued + 90 d decay; `isActive()`
filter — rows never deleted); index `(user_id, expires_at)`.
**`StrikeService.issueStrike` is live (built 2026-08)** — called from
`AdminSafetyController.issueStrike`, `ReportModerationService.action` and
`AdminUserServiceImpl.issueStrike`; the ledger is no longer write-dead.

Writers: `ReportService.submit/appeal` (user-side, rate-limited 20/h).
Reads: self-scoped `GET /api/v1/safety/reports`, `/strikes`, `/score` [EXISTS]; admin queue + detail via `AdminSafetyController` (`GET /api/v1/admin/safety/reports[/{id}]`, built 2026-08 — owned by safety-reports.md).
Retention: unbounded (correct for moderation evidence; see §7).
Dashboard: Moderation trail view (§2) is **read-only here** — intake volume,
`group_key` stacks, state distribution; every mutating action belongs to
[safety-reports.md](safety-reports.md).

### 3.8 Data-lifecycle records — `export_jobs`, `account_deletion_requests`, `deleted_accounts` [EXISTS]

| Table | Columns | Notes |
|-------|---------|-------|
| `export_jobs` | `id`, `user_id`, `status` ExportStatus(16) default `PENDING`, `file_path` varchar(500) (ZIP on worker-host temp storage), `size_bytes`, `error_message` varchar(500), `created_at`, `ready_at`, `expires_at`; idx `(user_id, created_at)` | ~~no job deletes rows or ZIP files~~ **Fixed (built 2026-08):** `RetentionSweepJob` deletes the ZIP + row at `expires_at`; the GDPR purge cascade also deletes a purged user's export jobs + files immediately |
| `account_deletion_requests` | `id`, `user_id`, `status` DeletionStatus(24) default `PENDING_DELETION`, `requested_at`, `purge_after` (= requested + 30 d), `resolved_at`; idx `(user_id, status)` | State machine record **and** audit trail of the deletion flow |
| `deleted_accounts` | `id` uuid PK **= the old user id** (not generated), `deleted_at` | Pure tombstone: prevents id reuse, keeps nothing else |

Writers: `DataPrivacyController` → export worker; `AccountLifecycleService`
(`requestDeletion` soft-deletes + revokes refresh tokens; `cancelDeletion`
restores; nightly `purgeExpired` cron `0 30 3 * * *` anonymizes
username/email/names → `deleted_user_<hash12>`, nulls password, writes the
tombstone, flips to `PURGED`; prefix `[ACCOUNT-DELETE]`).
Reads [EXISTS, self-scoped]: `POST /api/v1/privacy/export`, `GET
/api/v1/privacy/export/{jobId}[/download]`, `POST
/api/v1/account/deletion/request|cancel`.
Dashboard: deletion funnel + export table in Lifecycle & compliance view; the
purge job's per-run outcome should be written through
`AuditLogService.record(SYSTEM)` once business-event auditing is wired (§10
step 2).

### 3.9 `policy_acceptances` — policy consent [EXISTS]

| Column | Type | Meaning |
|--------|------|---------|
| `user_id` + `policy_key` | uuid + varchar(40) | **Composite PK** (`PolicyAcceptanceId`) — one current acceptance per (user, policy) |
| `version` | varchar(40) | Policy version accepted |
| `accepted_at` | timestamp NOT NULL | Set in `@PrePersist/@PreUpdate` |

Writers: `PolicyService` via `POST /api/v1/app/policies/{key}/accept`
(`AppInfoController`). Reads: `GET /api/v1/app/policies/{key}`, `GET
/api/v1/app/policies/me/accepted` [EXISTS, self-scoped].
**Integrity caveat:** unlike every other store in this catalog this table is
**upserted, not append-only** — re-accepting a new version *overwrites* the
old row, so evidence of *when the user accepted the previous version* is lost.
Recommended [PLANNED]: mirror each accept into an append-only history row (or
route it through `settings_audit` with key `policy.{key}`), keeping the upsert
table as the "current" projection. Dashboard: acceptance-coverage matrix
(users × policy versions) in Lifecycle & compliance view.

### 3.10 RabbitMQ dead-letter parking lot [EXISTS — PG-backed since 2026-08]

Every poison message is now **parked as a `dead_letters` PG row** (plus the
ERROR log line) instead of being consumed and lost.

| Aspect | Detail |
|--------|--------|
| Pipeline | All feeder queues carry `x-dead-letter-exchange=irc.dlx.exchange` + `x-message-ttl=86400000` (24 h max age); listener retries 3× (1 s → ×2 → 10 s) then reject → DLX → `irc.queue.dead-letter` |
| Drain | `RabbitMQConfig.drainDeadLetter` `@RabbitListener` consumes, logs `[RABBIT-DLQ] …`, and **persists a `dead_letters` row** (`admin/ops/DeadLetter`) — the message is parked, not dropped (built 2026-08) |
| Companions | Publisher-confirm NACKs (`[RABBIT] broker NACKed publish…`) and unroutable returns (`[RABBIT] unroutable message returned…`) at ERROR |
| Read APIs | `GET /api/v1/admin/ops/queues/dlq` + `POST …/dlq/{id}/requeue` and `DELETE …/dlq/{id}` (discard; step-up) — see [operations.md](operations.md) |
| Retention | 90 d — enforced by `RetentionSweepJob` (built 2026-08) |
| Dashboard | Infra logs view: DLQ casualty feed + arrivals/day counter, served from the `dead_letters` table |

### 3.11 Application log [EXISTS — console only]

| Aspect | Detail |
|--------|--------|
| Appender | **Console only** — pattern `%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`; no `logging.file.*`, no rolling policy (`application.yaml` logging block) |
| Levels | root INFO · `ak.dev.irc` DEBUG · `ak.dev.irc.security` DEBUG · spring-security INFO · amqp listener/connection **OFF** + amqp WARN (retry-storm silencing) · data.cassandra WARN · datastax driver ERROR · hibernate/SQL WARN · netty ERROR · awssdk WARN |
| Structured prefixes (grep-able) | `[AUDIT]` `[AUDIT-INTERCEPT]` `[AUDIT-SSE]` `[AUDIT-PUB]` `[AUDIT-SUB]` `[RABBIT]` `[RABBIT-DLQ]` `[SETTINGS-AUDIT]` `[LOGIN-ALERT]` `[ACCOUNT-DELETE]` `[NOTIF-CLEANUP]` `[SCHED-PUBLISH]` `[TRENDING]` `[CHAT-SCHEDULER]` `[SCHEMA]` |
| `app.log` at repo root | **Stale artifact** (1.3 MB, last touched 2026-04-20) from a manual output redirect — not produced by current config; delete or gitignore |
| Retention | None managed — on Railway, whatever the platform keeps of stdout |
| Dashboard | Read-only level matrix in Infra view. The dashboard should **not** attempt to tail stdout; anything worth surfacing gets a structured store (§3.10 recommendation) or an alert (§6) |

### 3.12 Hibernate SQL logging [EXISTS — hygiene issue]

`org.hibernate.SQL` is WARN, **but `spring.jpa.show-sql: true` bypasses the
logger** and prints every statement raw to stdout. Not an audit source (no
timestamps/params correlation, no user attribution) and a prod liability:
console noise, minor perf cost, and query shapes (with literal ids in some
paths) in an unmanaged log. Recommendation [PLANNED]: `show-sql: false` in
prod; use `org.hibernate.SQL=DEBUG` temporarily when diagnosing, never as a
standing log. Surfaced on the Retention board as a config warning, not a store.

### 3.13 Media error trail — `media_assets.error_message` [EXISTS]

| Aspect | Detail |
|--------|--------|
| Schema | `media_assets.error_message` varchar(300) + `status` (`MediaStatus`: `PENDING → UPLOADING → PROCESSING → READY \| FAILED_VALIDATION \| FAILED_MODERATION \| FAILED_PROCESSING`, `isTerminalFailure()`); row also carries the full `BaseAuditEntity` stamp (§3.2) |
| Writers | `MediaProcessingService.fail(assetId, status, message)` on validation/moderation/processing failure. Note: `FAILED_MODERATION` is **unreachable today** — the only `MediaScanner` is `AllowAllScanner` |
| Read APIs | None admin-scoped; asset owner sees status via media endpoints |
| Retention | Lives with the asset row |
| Dashboard | Infra view failure queue: `WHERE status IN (terminal failures) ORDER BY updated_at DESC` with `error_message`, owner, type, size — query is trivial, endpoint proposed in [media-storage.md](media-storage.md) (A-refs there); this doc only claims the *trail* |

### 3.14 NOT-logs — content stores excluded from all log tooling ⚠

These stores are append-only and log-*shaped*, which is exactly why they must
be **named and excluded** here: the Log Explorer, exports, and alert rules must
never read them. An admin surface over any of these is a privacy incident, not
a feature. Boundary rationale: [chat-channels-live.md](chat-channels-live.md).

| Store | Why it looks like a log | Why it is off-limits |
|-------|------------------------|----------------------|
| Cassandra `messages_by_conversation` / `message_by_id` / `media_by_conversation` / `chat_comment_by_post` | Bucketed, Snowflake-clustered, append-only | Private message **content**; disappearing-message TTL semantics; logical deletes. Membership-gated via `ChatPermissionEngine`, no admin bypass exists — keep it that way |
| ES `irc-chat-messages` | Indexed copy of message text | Same content, second store (disappearing messages deliberately not indexed) |
| `conversations.last_message_preview` (PG) | Innocent-looking metadata column | 160-char **content snippet** of the newest message of every DM — exclude from any admin projection |
| Live-stream recordings (`{app.streaming.recordings-dir}/<stream-id>/`) | Files accumulating on disk | Recorded content, host-only by design |
| Cassandra `activity_by_user` (+ by-type, lookup) | Richest behavioral event log in the system (~30 types incl. searches with queries + hit counts, profile views) | **Per-user private history**, user-deletable (Instagram "Your activity" model) — mining it for admin analytics breaks its contract |
| `reel_views_by_user`, `story_views_by_story`, `views_by_post`, `shares_by_post` | Append-only view/share event rows | Behavioral content backing counters; per-user or per-content privacy scope |
| Live-stream chat | — | **Never persisted at all** (SSE broadcast only): no retroactive evidence exists; policy must treat it as unrecoverable |

---

## 4. Unified Admin Log Explorer [EXISTS (built 2026-08) — backend: `AdminLogsController`]

One screen, one filter grammar, every *eligible* store (§3.1–§3.13; never
§3.14). Implementation is a thin aggregation façade — no new pipeline, no
copying data: each store keeps its own schema and the façade fans a parsed
query out to per-store adapters, then merges by time.

### 4.1 Filter grammar

```
user:<uuid|@username>  op:<READ|CREATE|UPDATE|DELETE|LOGIN|LOGOUT|UPLOAD|SYSTEM|OTHER>
outcome:<SUCCESS|REDIRECT|CLIENT_ERROR|SERVER_ERROR|SYSTEM>   status:<code|4xx|5xx>
resource:<Type[/uuid]>   ip:<addr|cidr>   path:<prefix*>   store:<audit|settings|login|consent|otp|reports|lifecycle|dlq|media>
since:<iso|relative e.g. 2h,7d>   until:<iso>   text:"<substring over summary/details/key>"
```

| Rule | Behavior |
|------|----------|
| Anchor requirement (honest grammar) | Queries against the **Cassandra audit store** require `user:` or `resource:` — the UI greys the audit store out otherwise instead of pretending a global scan is possible. PG-backed stores (settings/login/consent/otp/reports/lifecycle/media) accept anchor-free time-range queries |
| Store fan-out | `store:` omitted = all eligible stores that can serve the given anchors; results merged on time, each row wearing its store badge |
| Field mapping | Per-store adapter maps grammar → native filters (e.g. `op:` → `operation` in audit, `method`/`outcome` in login_events, no-op where inapplicable) |
| In-memory filter caveat | Audit-store `op:`/`outcome:`/time filters keep today's semantics (§3.1) until pushed down — the UI must label short pages "filtered page, fetch more" |

### 4.2 Saved views [EXISTS (built 2026-08)]

Named filter-sets per admin (PG table `admin_saved_log_views`: `id`,
`admin_id`, `name`, `query` text, `created_at`), pinned to the section nav.
Seed defaults: *Failed logins 24 h*, *Admin actions 7 d*
(`path:/api/v1/admin/*`), *5xx last hour*, *DLQ today*, *Deletion pipeline*.

### 4.3 Cross-store correlation

The row drawer's **pivot** buttons run canned correlations — this is where the
three audit layers (§1) meet:

| Pivot | Query fanned out |
|-------|-----------------|
| "This user, ±5 min" | `user:X since:t-5m until:t+5m` across all eligible stores — reconstructs a session slice: requests (audit) + settings diffs + login/OTP + lifecycle events |
| "This resource" | `audit_log_by_resource` partition read (needs endpoint A2) + reports where `target_id` matches |
| "This IP" | login_events + otp_challenges + settings_audit by `ip`; audit store only within an already-anchored user page (no IP index in Cassandra — document, don't fake) |
| "Same group_key" | Report stacks by `group_key` |

### 4.4 Export to CSV [EXISTS (built 2026-08)]

`POST /api/v1/admin/logs/export` with a grammar query → synchronous CSV
response (`text/csv`), bounded row cap (10 000). **Step-up required
(`@RequiresStepUp`); the export itself writes an explicit audit record**
(`ADMIN_LOG_EXPORT`, including the query string) via `AdminAuditor` — an
export of logs is the most sensitive read in the system.

---

## 5. Admin actions

Existing first, then proposed. All proposed paths live under `/api/v1/admin/**`
(double-gate convention — [architecture.md](architecture.md)). Danger:
🟢 read · 🟡 config/mutation · 🔴 destructive (none in this section — see §9
integrity: **no log-delete endpoints, ever**).

| # | Action | Endpoint | Params | Danger | Step-up | Audit record written |
|---|--------|----------|--------|--------|---------|---------------------|
| E1 | Search audit log (per-user anchor) | `GET /api/v1/admin/audit` **[EXISTS]** | `userId!`, `operation`, `outcome`, `from`, `to`, `pageSize`, `cursor` | 🟢 | No | Auto via interceptor (§3.1 — admin reads are themselves audited, path is `/api/**`) |
| E2 | Per-user audit history | `GET /api/v1/admin/audit/users/{userId}` **[EXISTS]** | `pageSize`, `cursor` | 🟢 | No | Auto (resourceType `User`) |
| E3 | Live audit tail | `GET /api/v1/admin/audit/stream` **[EXISTS]** | — (SSE) | 🟢 | No | `*/stream` is in `SKIP_PATTERN`, so `AuditLogController.stream` writes an explicit `AuditLogService.record` row per connect (built 2026-08) |
| A1 | Unified Log Explorer query | `GET /api/v1/admin/logs/explore` **[EXISTS (built 2026-08)]** — grammar `q` or discrete params, merged multi-store (audit/login/settings/consent/reports) | `q` (grammar §4.1), `pageSize`, per-store cursors | 🟢 | No | Auto + explicit row incl. the query for anchor-free PG sweeps |
| A2 | Resource audit history | `GET /api/v1/admin/audit/resources/{type}/{id}` **[EXISTS (built 2026-08)]** — exposes `audit_log_by_resource` via `AuditLogByResourceRepository.firstPage` | `pageSize`, `cursor` | 🟢 | No | Auto |
| A3 | Settings-audit history (admin) | `GET /api/v1/admin/users/{userId}/settings-audit` **[EXISTS (built 2026-08)]** (`AdminUserController`) — wraps `SettingsAuditService.history` | `pageable` | 🟢 | No | Auto |
| A4 | Login events (admin) | `GET /api/v1/admin/logs/login-events` **[EXISTS (built 2026-08)]** | `userId` \| `ip` \| `outcome=FAILED`, time range | 🟢 | No | Auto |
| A5 | Consent history (admin) | `GET /api/v1/admin/safety/users/{userId}/consent[?scope=]` **[EXISTS (built 2026-08)]** (`AdminSafetyController`) | `scope?`, `pageable` | 🟢 | No | Auto |
| A6 | Export logs to CSV | `POST /api/v1/admin/logs/export` **[EXISTS (built 2026-08)]** — synchronous CSV, 10 000-row cap | body: `{query}` | 🟡 | **Yes** | Explicit (`ADMIN_LOG_EXPORT` + query string) |
| A7 | Saved views CRUD | `GET/POST/DELETE /api/v1/admin/logs/views[/{id}]` **[EXISTS (built 2026-08)]** | body: `{name, query}` | 🟢 | No | Auto |
| A8 | Alert rules CRUD | `GET/POST/PATCH/DELETE /api/v1/admin/logs/alerts[/{id}]` **[EXISTS (built 2026-08)]** | rule spec (§6.2) | 🟡 | Yes (create/modify) | Explicit (`AlertRule`, old→new spec) |
| A9 | Alert firings feed | `GET /api/v1/admin/logs/alerts/firings` **[EXISTS (built 2026-08)]** | `since`, `ruleId?` | 🟢 | No | Auto |
| A10 | Retention status board | `GET /api/v1/admin/logs/retention` **[EXISTS (built 2026-08)]** | — | 🟢 | No | Auto |
| A11 | Apply Cassandra audit TTL | **automated (built 2026-08)** — `AuditSchemaInitializer` ensures the 180 d TTL idempotently at startup; no endpoint | — | 🟡 | n/a | Runbook entry — [operations.md](operations.md) |

Explicitly **not** offered: delete/redact/edit of any log row; per-store
retention override endpoints (retention changes are code+migration, reviewed);
any read over §3.14 stores.

---

## 6. Analytics & KPIs / Alerts & thresholds

### 6.1 Metrics

| Metric | Definition | Source | Chart |
|--------|-----------|--------|-------|
| Audit events/min | Rows flowing through `irc:audit:stream` | Live-tail counter [EXISTS via SSE]; historical needs a per-day rollup [PLANNED] | Sparkline (tail) / area (history) |
| Outcome mix | % SUCCESS / CLIENT_ERROR / SERVER_ERROR | Audit rows (per-anchor today; global via rollup [PLANNED]) | Stacked bar |
| p50/p95 `duration_ms` | Request latency from audit rows | §3.1 | Line, per path-prefix |
| Failed-login rate | FAILED / all login_events, per hour | §3.4 (wired, built 2026-08) | Line + threshold band |
| New-IP alerts/day | `recordSuccessAndAlertIfNew` firings | §3.4 (wired) | Bar |
| OTP failure ratio | challenges with `attempts>0 && consumed_at IS NULL` / issued | §3.6 | Line |
| Settings churn | settings_audit rows/day, top `setting_key`s | §3.3 | Bar + top-N table |
| Report intake / stack depth | reports/day; max reports per `group_key` | §3.7 | Line + leaderboard |
| Deletion funnel | counts by DeletionStatus; time-to-purge | §3.8 | Funnel |
| Export job health | success rate, ZIPs past `expires_at` still on disk | §3.8 | Stat tiles |
| DLQ arrivals/day | `[RABBIT-DLQ]` occurrences (or `dlq_events` rows once added) | §3.10 | Bar — target **0** |
| Audit executor pressure | queue depth / CallerRuns engagements on `auditExecutor` | Micrometer gauge [PLANNED — actuator exposure gap, see [operations.md](operations.md)] | Gauge |
| Admin action volume | audit rows with `path:/api/v1/admin/*` | §3.1 | Bar per admin |

### 6.2 Alert rules [EXISTS (built 2026-08)]

Rule engine: `LogAlertSweepJob` — a scheduled evaluator (every 5 min,
`irc.logs.alert-sweep-ms`) over the PG stores; **6 default rules are seeded on
first startup** (failed-login per-account / per-IP, report pile-on, DLQ
arrivals, OTP abuse, purge-job silence) and editable via the A8 CRUD
(`log_alert_rules`); firings land
in `log_alert_firings` (A9 feed) and notify admins via
`NotificationService.sendSystemNotification` (bypasses DND). The table below
remains the full rule wishlist — rows beyond the seeded six keep their stated
preconditions.

| Rule | Condition (default) | Severity | Precondition |
|------|--------------------|----------|--------------|
| Failed-login spike (per account) | ≥ 10 FAILED in 15 min for one user | High | login_events wired (§10 step 1) |
| Failed-login spike (per IP) | ≥ 50 FAILED in 60 min from one IP | High | same |
| Login from new IP for ADMIN account | any | High | same — narrows the existing new-IP alert to staff |
| 5xx surge | SERVER_ERROR > 2 % of audit events over 10 min | High | stream-side counter |
| Admin-action anomaly | `/api/v1/admin/**` rows outside the admin's trailing-30-day hour-of-day envelope, or > 3× daily baseline | Medium | per-day rollup |
| DLQ growth | any `[RABBIT-DLQ]` event; escalate ≥ 10/h | Medium/High | `dlq_events` table (§3.10) — log-grep is not a rule source |
| Broker NACK / unroutable | any occurrence | High | same mechanism |
| OTP abuse | ≥ 20 challenges/h for one `destination_hash` or IP | Medium | §3.6 |
| Report pile-on | ≥ 10 reports on one `group_key` in 24 h | Medium | §3.7 |
| Purge-job silence | no `[ACCOUNT-DELETE]`/rollup evidence of the 03:30 run by 04:30 | Medium | SYSTEM audit events wired (§10 step 2) |
| Audit write starvation | executor queue > 40 000 or CallerRuns engaged | High | Micrometer gauge |

---

## 7. Retention policy matrix

Current reality vs. recommended policy, per store. "Job" = scheduled cleanup;
"TTL" = datastore-level expiry. The core matrix is enforced since 2026-08 by
`AuditSchemaInitializer` (Cassandra TTL) + `RetentionSweepJob` (nightly PG
sweep), and surfaced live on `GET /api/v1/admin/logs/retention` (A10).

| Store | Current | Enforced by | Recommended | Rationale |
|-------|---------|-------------|-------------|-----------|
| `audit_log_by_user` / `by_resource` | 180 d TTL | `AuditSchemaInitializer` at startup (idempotent `ALTER … default_time_to_live=15552000`) [EXISTS (built 2026-08)] | — (matches javadoc intent) | Verify with `DESCRIBE TABLE` |
| `settings_audit` | 2 y | `RetentionSweepJob` [EXISTS (built 2026-08)] | Keep | Security evidence; long but finite |
| `login_events` | 1 y (writer wired) | `RetentionSweepJob` [EXISTS (built 2026-08)] | Optionally keep FAILED 90 d beyond via partial purge | Forensics + spike detection |
| `consent_events` | Unbounded | design | **Keep** for account life + 1 y post-purge (see §8) | It *is* the compliance evidence |
| `otp_challenges` | 180 d on `expires_at` | `RetentionSweepJob` [EXISTS (built 2026-08)] | Keep | Hashes only, but volume grows |
| `reports` / `user_strikes` | Unbounded / soft-decay 90 d, rows kept | `isActive()` filter only | Keep ≥ 2 y (moderation/legal evidence); never auto-delete strikes | Evidence chain `report_id → strike` |
| `export_jobs` + ZIP files | ZIP + row deleted at `expires_at` | `RetentionSweepJob` [EXISTS (built 2026-08)]; GDPR purge cascade deletes a purged user's exports immediately | Keep | Was the highest-priority retention fix — closed |
| `account_deletion_requests` | Resolved by nightly purge (30 d grace) | cron `0 30 3 * * *` [EXISTS] | Keep resolved rows 2 y | Deletion audit trail |
| `deleted_accounts` | Forever | design [EXISTS] | Forever | Id-reuse tombstone, no PII |
| `policy_acceptances` | Current row only (upsert overwrites) | — | Add append-only history (§3.9) | Version-acceptance evidence |
| `notifications` (adjacent) | READ > 90 d purged | `NotificationCleanupJob` cron `0 15 3 * * *` [EXISTS] | Keep | Reference model for job-based retention |
| `dead_letters` (DLQ parking lot) | 90 d | `RetentionSweepJob` [EXISTS (built 2026-08)] | Keep | Poison messages parked as PG rows since 2026-08 (§3.10) |
| Application log | stdout lifetime, no file appender | — | Platform log retention (Railway) or rolling file 14 d | Not an audit source |
| `media_assets.error_message` | Life of asset row | — | Keep | Rides the asset |
| §3.14 content stores | Own product rules (chat TTLs, story 24 h, user-deletable activity) | various | **Out of scope for this section — never governed by log retention** | Privacy boundary |

---

## 8. PII / GDPR handling

What happens to each log store when `AccountLifecycleService.purgeExpired`
anonymizes an account (the deletion state machine, §3.8) — **current reality
vs. required behavior**:

| Store | PII held | On purge today | Required [PLANNED] |
|-------|----------|----------------|--------------------|
| Cassandra audit tables | `username` (captured at request time), `ip_address`, `user_agent`; `user_id` remains as key | **Purged (built 2026-08)** — the purge cascade deletes the user's `audit_log_by_user` partition (`purgePartition`); `by_resource` rows rely on the now-applied 180 d TTL | Done — document in the privacy policy |
| `BaseAuditEntity` columns | ip/device on rows the user touched | Rows owned by the user are deleted/anonymized by domain cascades; **stamps on other-owned rows persist** | Acceptable (legitimate-interest forensic minimum); document |
| `settings_audit` | old/new values can embed PII (bio text, etc.), ip | **Purged (built 2026-08)** — deleted by `user_id` in the cascade | Done |
| `login_events` | ip, user_agent, coarse_geo | **Purged (built 2026-08)** — deleted by `user_id` in the cascade; retention (§7) bounds the rest | Done |
| `consent_events` | scope grants | Kept | **Keep deliberately** (defensible compliance evidence post-erasure under record-keeping exemption); document in policy |
| `otp_challenges` | ip, device_id; destination is **hashed** | Nothing | Purge step optional; hashes are already pseudonymous — rely on 180 d job |
| `reports` | reporter/target ids, free-text `details` (may contain PII) | Nothing | Keep rows (moderation evidence) but null `reporter_id` → tombstone id where reporter is the purged user; strikes keep `user_id` for repeat-abuse defense — document |
| `export_jobs` | `file_path` → ZIP **containing the user's full archive** | **Purged (built 2026-08)** — the cascade deletes the user's export rows **and ZIP files** immediately, ahead of the generic expiry sweep | Done |
| `policy_acceptances` | acceptance facts | Nothing | Keep (compliance evidence), or move to history table with user tombstone id |
| DLQ / app log lines | May embed ids in payload headers | Nothing (ephemeral stdout) | Bounded by log retention; never grep-purge |

Principles: (1) the purge job becomes the **single choreography point** — every
new log store must register a purge (or documented-keep) step; (2) erasure ≠
silence: keep `user_id`-keyed skeletons where legally defensible, remove
free-text/network identifiers; (3) every purge run writes a `SYSTEM` audit
event (§10 step 2) — deleting data is itself an auditable act.

---

## 9. Permissions, safety & integrity principles

| Principle | Statement | Status |
|-----------|-----------|--------|
| Append-only | No API mutates or deletes log rows. Retention is TTL/scheduled-job only, defined in code + reviewed migrations — never an endpoint | Holds today (no such endpoints exist); §5 keeps it that way |
| Admin reads are audited | Every `/api/v1/admin/logs/**` and `/api/v1/admin/audit/**` request passes the §3.1 interceptor (`/api/**`) and lands in the audit log itself | [EXISTS] — E3 stream-connect exception patched (explicit record per connect, built 2026-08) |
| Exports are the hottest reads | Log export requires step-up, writes an explicit `ADMIN_LOG_EXPORT` audit record with the query, caps rows (10 000) | [EXISTS (built 2026-08)] A6 |
| Metadata-only capture | Request bodies are never read anywhere in the audit pipeline; OTP stores hashes; invite tokens stored hashed platform-wide | [EXISTS] |
| Failure isolation | Audit writes are async and swallow failures — availability beats completeness for the request log; alerting on executor pressure (§6.2) covers the blind spot | [EXISTS] + gauge [PLANNED] |
| Content firewall | Log tooling never reads §3.14 stores; admin projections exclude `last_message_preview`, `live_streams.stream_key`, `stream_guests.publish_key` | Policy — enforce in code review + projection DTOs |
| Access | Everything in this section under `/api/v1/admin/**` (filter-chain double gate + `@PreAuthorize`); read surfaces admit the staff roles `ADMIN/MODERATOR/SUPPORT/ANALYST` from the widened role model. Phantom `SUPER_ADMIN` dropped from `AuditLogController` | [EXISTS] / cleanup done (built 2026-08) |
| Honest UI | In-memory-filtered pages (E1) and anchor-skipped stores (audit without `user:`) carry explicit banners — never render an empty table as "no events" | Dashboard rule |

---

## 10. Build order / dependencies

> **Status (built 2026-08):** steps 1–5 and 7–9 are shipped — login_events
> wired from `AuthServiceImpl` (step 1), business-event auditing via
> `AdminAuditor` + purge `SYSTEM` events (step 2), retention fixes via
> `AuditSchemaInitializer` / `RetentionSweepJob` / `dead_letters` (step 3),
> read endpoints A2–A5 (step 4), Explorer + saved views (step 5), alert engine
> (step 7), CSV export (step 8), GDPR purge choreography (step 9). Step 6
> (tail UI) is frontend work; step 10 hygiene is partially done
> (`SUPER_ADMIN` dropped from `AuditLogController`).

| Step | Work | Unblocks | Effort |
|------|------|----------|--------|
| 1 | **Wire `login_events`**: call `LoginEventService.record`/`recordSuccessAndAlertIfNew` from `AuthServiceImpl` (login success/fail/refresh/2FA) | Security view, 3 alert rules, §8 login purge step | S |
| 2 | **Wire business-event auditing**: call `AuditLogService.record(SYSTEM)` from the nightly purge, notification cleanup, reindexes, and future admin mutations | Job observability, purge-silence alert, integrity principle "purges are audited" | S |
| 3 | **Retention emergencies**: A11 `ALTER TABLE` TTL on both audit tables; export-ZIP cleanup job on `expires_at`; `dlq_events` table in the drain listener | Retention board turns green on its worst rows; DLQ alerting gets a real source | S–M |
| 4 | **Read-only endpoints over existing data**: A2 (by-resource — repo method already written), A3 (settings-audit — service method already written), A4, A5 | Explorer adapters; user-inspection tabs | M |
| 5 | **Log Explorer façade**: grammar parser, per-store adapters, merge, saved views (A1, A7) | The flagship screen | M–L |
| 6 | **Live tail UI** over the existing SSE stream + E3 connect-audit patch | First visible win — can ship in parallel with 4 | S |
| 7 | **Alert engine + rules CRUD** (A8, A9): evaluator job + stream counter + notification fan-out | §6.2 | M |
| 8 | **Export pipeline** (A6) reusing `export_jobs` machinery + step-up | Compliance/incident workflows | M |
| 9 | **GDPR purge choreography** (§8): per-store purge steps inside `purgeExpired`, each writing SYSTEM audit | Erasure correctness | M |
| 10 | Hygiene: `show-sql: false` (prod), delete stale `app.log`, drop `SUPER_ADMIN` from annotations, policy-acceptance history table | Cleanliness | S |

Dependency spine: **1–3 are pure backend prerequisites** with no dashboard;
4 → 5 → 7/8 is the Explorer chain; 6 is independent and demo-ready first.
Cross-section handoffs: report triage actions →
[safety-reports.md](safety-reports.md) · job/queue health & actuator exposure →
[operations.md](operations.md) · per-day metric rollups →
[analytics-kpis.md](analytics-kpis.md) · endpoint master list →
[admin-api-blueprint.md](admin-api-blueprint.md).
