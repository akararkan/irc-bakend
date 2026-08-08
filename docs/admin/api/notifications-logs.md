# Admin API Reference — Notifications, Announcements & Logs

Complete request/response reference for two controllers:

- `AdminNotificationController` — `/api/v1/admin/notifications/**` — notification volume/read-rate stats, the `NotificationType` registry, the platform-announcement composer (dry-run → confirm → send/schedule), the manual trending-digest trigger, the email send-ledger health strip, admin self-test email, and push-token purge.
- `AdminLogsController` — `/api/v1/admin/logs/**` — the unified Log Explorer (grammar over per-store adapters), the global login-events reader, the step-up-gated CSV export, per-admin saved views, alert-rule CRUD + the firings feed, the retention status board, and aggregate OTP telemetry.

Concept docs: [notifications-email.md](../communication/notifications-email.md), [logs-audit.md](../platform/logs-audit.md), UI wiring: [frontend-dashboard-guide.md](../frontend/README.md).

**Conventions used below**

- **Auth**: every route requires `Authorization: Bearer <JWT>`. Missing/expired token → `401`; insufficient role → `403 ACCESS_DENIED`. All errors arrive in the canonical envelope (`errorCode`, `message`, `traceId`, …) — see [frontend-error-handling.md](../../errors/frontend-error-handling.md).
- **Step-up**: endpoints marked *step-up required* (`@RequiresStepUp`) additionally need a fresh step-up marker, armed via `POST /api/v1/security/step-up` (password re-auth). Absent/expired marker → `403 STEP_UP_REQUIRED`.
- **`@JsonInclude(NON_NULL)`** is the global Jackson default (`default-property-inclusion: non_null`): `null` fields are **omitted from JSON entirely** — treat every nullable field as optional.
- **Timestamps** are `LocalDateTime` serialized as ISO-8601 local strings without zone (`"2026-08-07T09:14:02.113"`). Server clock is UTC.
- **Page sizes** are clamped server-side to `[1, 100]` (`Pages.clamp`), whatever the client sends.
- **`Page<T>` responses** are Spring Data pages. Examples below show the trimmed shape `{"content":[…],"totalElements":N,"totalPages":N,"number":0,"size":N}`; the real payload also carries the full Spring metadata (`pageable`, `sort`, `first`, `last`, `numberOfElements`, `empty`).
- **Audit**: every mutation writes an `AdminAuditor` row; the `ADMIN_*` action name is noted per endpoint.

---

## Notification stats & types

Base path `/api/v1/admin/notifications`. Class-level access: `ADMIN` (individual read endpoints widen it, as noted).

### GET /api/v1/admin/notifications/stats
Notification volume + read-rate per `NotificationType` over a window.

**Access**: `ADMIN`, `SUPPORT`, `ANALYST`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `from` | ISO date-time | now − 30d | window start |
| `to` | ISO date-time | now | window end |

**Request body** — None.

**Response** — `200 OK`. `byType` is sorted by `created` descending; `readRatePct` is rounded to one decimal.

```json
{
  "from": "2026-07-08T09:00:00",
  "to": "2026-08-07T09:00:00",
  "byType": [
    { "type": "POST_REACTED", "created": 15430, "read": 11021, "readRatePct": 71.4 },
    { "type": "NEW_MESSAGE", "created": 9822, "read": 9455, "readRatePct": 96.3 },
    { "type": "SYSTEM_ANNOUNCEMENT", "created": 1204, "read": 640, "readRatePct": 53.2 }
  ],
  "note": "Aggregated from the legacy Postgres inbox — the live write path is Cassandra (notifications_by_user), which has no cross-user aggregate; a rollup collector is the planned successor source."
}
```

**Errors** — none endpoint-specific (401/403 per conventions).

### GET /api/v1/admin/notifications/types
Static registry of every `NotificationType` with its delivery metadata.

**Access**: `ADMIN`, `SUPPORT`, `ANALYST`.

**Request body** — None.

**Response** — `200 OK`, one row per enum value. When a matching `NotificationKind` exists the row carries `prefCategory` (`SOCIAL` | `MENTIONS` | `SYSTEM` | `TRENDING`), `aggregable`, `emailEligible`. When it does not (legacy/relational-only type), `prefCategory` is `null` — dropped by `NON_NULL` — and a `note` appears instead. `category` is one of `POSTS`, `QNA`, `RESEARCH`, `MENTIONS`, `SOCIAL`, `CHAT`, `SYSTEM`.

```json
[
  { "type": "POST_REACTED", "category": "POSTS", "prefCategory": "SOCIAL", "aggregable": true, "emailEligible": true },
  { "type": "SYSTEM_ANNOUNCEMENT", "category": "SYSTEM", "prefCategory": "SYSTEM", "aggregable": false, "emailEligible": true },
  { "type": "UNFOLLOWED", "category": "SOCIAL", "note": "no NotificationKind — legacy/relational-only type" }
]
```

**Errors** — none endpoint-specific.

### POST /api/v1/admin/notifications/digest/run
Manually fire the `TRENDING_DIGEST` job (normally daily 09:00 UTC).

**Access**: `ADMIN`.

**Request body** — None.

**Response** — `202 Accepted`, empty body. Audit action: `ADMIN_DIGEST_RUN`.

Caveat (by design): the one-per-day cap is enforced on the **email** leg (Redis claim). A same-day re-run still inserts a second inbox row, because the kind is non-aggregable.

**Errors** — none endpoint-specific.

---

## Announcements — compose / list / cancel

### POST /api/v1/admin/notifications/announcements
Compose a platform announcement: count the audience (dry-run), then send immediately or schedule. Delivery is `NotificationKind.SYSTEM_ANNOUNCEMENT` (honors the SYSTEM email preference category), fanned out in 500-recipient batches via `deliverAllAsync`.

**Access**: `ADMIN` + **step-up required**.

**Request body** — `AnnouncementBody`. `title` and `body` are required; everything else optional.

| Field | Type | Meaning |
|---|---|---|
| `title` | string (≤200 in storage) | required |
| `body` | string | required |
| `audienceRole` | string | narrow to one role: `USER`, `RESEARCHER`, `SCHOLAR`, `MODERATOR`, `SUPPORT`, `ANALYST`, `ADMIN`. Omit/blank = all active users |
| `activeSinceDays` | int | narrow to users with a login in the last N days |
| `audienceLanguage` | string | narrow to `preferredLanguage`: `AR`, `CKB`, `EN` |
| `scheduledAt` | string | ISO-8601 local date-time (`2026-08-09T09:00:00`); non-null ⇒ held as `SCHEDULED` until the minute sweep fires it |
| `dryRun` | boolean (default `false`) | `true` ⇒ count the audience only, send nothing |
| `confirmLargeAudience` | boolean (default `false`) | must be `true` once the audience reaches ≥ half of all active users |

```json
{
  "title": "Scheduled maintenance Friday",
  "body": "The platform will be read-only on 2026-08-14 from 02:00 to 03:00 UTC.",
  "audienceRole": "RESEARCHER",
  "activeSinceDays": 30,
  "audienceLanguage": "EN",
  "scheduledAt": "2026-08-12T09:00:00",
  "dryRun": false,
  "confirmLargeAudience": false
}
```

**Response** — `ComposeResult`. Dry-run → `200 OK` (nothing persisted; `announcementId` is null and therefore omitted). Real send/schedule → `202 Accepted`. `scheduledAt` is echoed only when set.

```json
{ "dryRun": true, "audience": 4211 }
```

```json
{
  "announcementId": "0d9c1f4e-6a3b-4c8e-9f21-7b5a2d4e8c10",
  "dryRun": false,
  "audience": 4211,
  "scheduledAt": "2026-08-12T09:00:00"
}
```

Immediate sends start the async fan-out at once (row goes `SENDING` → `SENT`/`FAILED`); scheduled sends persist as `SCHEDULED` and a 60-second sweep fires them when due. Audit action: `ADMIN_ANNOUNCEMENT_SEND` or `ADMIN_ANNOUNCEMENT_SCHEDULE`.

**Errors**
- `INVALID_INPUT` — 400 — blank/missing `title` or `body` ("title and body are required.").
- `INVALID_SCHEDULE` — 400 — `scheduledAt` not ISO-8601 local date-time, **or** in the past.
- `INVALID_LANGUAGE` — 400 — `audienceLanguage` not one of `[AR, CKB, EN]`.
- `ILLEGAL_ARGUMENT` — 400 — `audienceRole` is not a valid role name.
- `LARGE_AUDIENCE_CONFIRMATION_REQUIRED` — 400 — audience ≥ half of all active users and `confirmLargeAudience` was not `true` (dry-runs never trigger this).
- `STEP_UP_REQUIRED` — 403 — step-up marker missing/expired.

### GET /api/v1/admin/notifications/announcements
Send history, newest first.

**Access**: `ADMIN`.

**Params**

| Param | Type | Default |
|---|---|---|
| `page` | int | 0 |
| `size` | int | 25 (clamped to ≤100) |

**Request body** — None.

**Response** — `200 OK`, `Page<PlatformAnnouncement>`. `status` ∈ `SCHEDULED`, `SENDING`, `SENT`, `FAILED`, `CANCELLED`. Null audience filters / `scheduledAt` / `completedAt` / `createdBy` are omitted (`NON_NULL`).

```json
{
  "content": [
    {
      "id": "0d9c1f4e-6a3b-4c8e-9f21-7b5a2d4e8c10",
      "title": "Scheduled maintenance Friday",
      "body": "The platform will be read-only on 2026-08-14 from 02:00 to 03:00 UTC.",
      "audienceRole": "RESEARCHER",
      "activeSinceDays": 30,
      "audienceLanguage": "EN",
      "scheduledAt": "2026-08-12T09:00:00",
      "status": "SENT",
      "targetedCount": 4211,
      "deliveredCount": 4207,
      "createdBy": "9f21c4d0-3e5a-4b7c-8d10-2a6e4f8b0c31",
      "createdAt": "2026-08-07T08:15:33.412",
      "completedAt": "2026-08-12T09:02:10.088"
    }
  ],
  "totalElements": 12,
  "totalPages": 1,
  "number": 0,
  "size": 25
}
```

> Trimmed: real responses also include Spring's `pageable`/`sort`/`first`/`last`/`numberOfElements`/`empty` metadata.

**Errors** — none endpoint-specific.

### DELETE /api/v1/admin/notifications/announcements/{id}
Cancel a `SCHEDULED` announcement before its sweep fires it (a `SENDING` run can no longer be stopped).

**Access**: `ADMIN` + **step-up required**.

**Params** — `id` (path, UUID).

**Request body** — None.

**Response** — `200 OK`. Audit action: `ADMIN_ANNOUNCEMENT_CANCEL`.

```json
{ "id": "0d9c1f4e-6a3b-4c8e-9f21-7b5a2d4e8c10", "status": "CANCELLED" }
```

**Errors**
- `ANNOUNCEMENT_NOT_FOUND` — 404 — no such id.
- `ANNOUNCEMENT_NOT_SCHEDULED` — 400 — status is not `SCHEDULED` ("Only SCHEDULED announcements can be cancelled (this one is SENT).").
- `STEP_UP_REQUIRED` — 403.

---

## Test email & email health

### GET /api/v1/admin/notifications/email/stats
Email dispatch health: outcome counts over a window + the newest ledger rows.

**Access**: `ADMIN`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `from` | ISO date-time | now − 7d | window start |
| `to` | ISO date-time | now | window end |
| `tail` | int | 25 | ledger rows returned, clamped to ≤100 |

**Request body** — None.

**Response** — `200 OK`. `byOutcome` is pre-seeded with **all** outcomes (`QUEUED`, `THROTTLED`, `DISABLED`, `MUTED`, `NO_ADDRESS`, `SKIPPED`, `FAILED`) so zeroes are explicit. `ledgerTail` rows are `EmailSendLog` entities, newest first; nullable fields (`recipientId`, `kind`, `groupKey`, `error`) are omitted when null.

```json
{
  "from": "2026-07-31T09:00:00",
  "to": "2026-08-07T09:00:00",
  "enabled": true,
  "byOutcome": {
    "QUEUED": 1874, "THROTTLED": 41, "DISABLED": 0, "MUTED": 232,
    "NO_ADDRESS": 3, "SKIPPED": 118, "FAILED": 6
  },
  "ledgerTail": [
    {
      "id": "5c1e9a2b-7d40-4f6e-8a13-0b2c4d6e8f01",
      "recipientId": "2a6e4f8b-0c31-4d75-9e02-8b1a3c5d7e90",
      "kind": "SYSTEM_ANNOUNCEMENT",
      "groupKey": "ANNOUNCEMENT:0d9c1f4e-6a3b-4c8e-9f21-7b5a2d4e8c10",
      "outcome": "QUEUED",
      "createdAt": "2026-08-07T08:59:41.020"
    }
  ]
}
```

**Errors** — none endpoint-specific.

### POST /api/v1/admin/notifications/email/test
Send a test email **to the calling admin's own address only** (no arbitrary recipients). Subject is prefixed `[TEST] `.

**Access**: `ADMIN`.

**Request body** — `TestEmailBody`; both fields required.

```json
{ "subject": "SMTP sanity check", "body": "If you can read this, outbound email works." }
```

**Response** — `202 Accepted`. `enabled=false` means the email subsystem is off — the send is a no-op logged to the ledger. Audit action: `ADMIN_EMAIL_TEST`.

```json
{ "queuedTo": "admin@example.com", "enabled": true }
```

**Errors** — none endpoint-specific.

---

## Push tokens

### DELETE /api/v1/admin/notifications/push-tokens/{id}
Purge a stale device push token by row id (rows live in `push_tokens`: per-session `FCM`/`APNS` tokens with `platform` ∈ `IOS`/`ANDROID`/`WEB`; normally self-cleaning on logout and provider `UNREGISTERED`).

**Access**: `ADMIN`.

**Params** — `id` (path, UUID — the token row id, not the user id).

**Request body** — None.

**Response** — 204 No Content. Idempotent: 204 whether or not the row existed. Audit action: `ADMIN_PUSH_TOKEN_PURGE`.

**Errors** — none endpoint-specific.

---

## Log explorer

Base path `/api/v1/admin/logs`. Class-level access: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST` (export and alert mutations tighten to `ADMIN`, as noted).

### GET /api/v1/admin/logs/explore
One query over five log stores, merged by time, newest first, each row wearing its store badge.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST`.

**Params** — a grammar query `q` **or** discrete params — both accepted; discrete params win over the corresponding `q` token. `store` (param) fully replaces any `store:` tokens.

| Param | Type | Default | Notes |
|---|---|---|---|
| `q` | string | — | grammar string, see below |
| `store` | string | all eligible | CSV of store names; overrides `store:` tokens |
| `userId` | UUID | — | overrides `user:` |
| `ip` | string | — | overrides `ip:` |
| `outcome` | string | — | uppercased; overrides `outcome:` |
| `since` | ISO date-time | — | overrides `since:` |
| `until` | ISO date-time | — | overrides `until:` |
| `text` | string | — | overrides `text:` |
| `pageSize` | int | 50 | clamped to ≤100; caps both per-store fetches and the merged result |

#### The `q` grammar

Space-separated `key:value` tokens (from `ExploreQuery`). Quoted values keep spaces (`text:"two words"`). Unknown keys (`op:`, `status:`, `path:`, `resource:`, …) are **ignored quietly** — it is a filter language, not a validator.

| Token | Value | Semantics |
|---|---|---|
| `user:` | `<uuid>` \| `@username` \| `username` | anchor to one user. A UUID is used directly; `@handle` (or any non-UUID value) is resolved to a user id via Postgres — unknown handle → 404 |
| `ip:` | address | exact IP match (login store only) |
| `outcome:` | e.g. `SUCCESS`, `FAILED`, `LOCKED` | uppercased; applied to the login store |
| `store:` | CSV of `audit`, `login`, `settings`, `consent`, `reports`, `dlq` | restrict stores; unknown names dropped; omitted ⇒ all eligible |
| `since:` | ISO date-time or relative `15m` / `2h` / `7d` | lower time bound (relative = back from now); unparseable ⇒ no bound |
| `until:` | ISO date-time or relative | upper time bound |
| `text:` | `"substring"` | case-insensitive contains; applies to audit `summary`, login `userAgent`, settings `settingKey` |

Examples:

```
q=user:@alice store:login since:7d
q=store:login outcome:FAILED ip:203.0.113.7 since:24h
q=store:settings text:"privacy" since:2h
q=user:2a6e4f8b-0c31-4d75-9e02-8b1a3c5d7e90 store:audit,consent until:2026-08-01T00:00:00
```

#### Stores

| Store | Backing store | `user:` anchor | Filters actually applied |
|---|---|---|---|
| `audit` | Cassandra `audit_log_by_user` | **required** — partitioned per user; a global scan is impossible by design. Without an anchor the store is skipped with a note | newest page of the partition; `since`/`until` + `text` (on `summary`) applied in-memory |
| `login` | Postgres `login_events` | optional | `user`/`ip`/`outcome`/`since`/`until` in SQL; `text` on `userAgent` in-memory |
| `settings` | Postgres `settings_audit` | optional | `user`/`since`/`until` in SQL; `text` on `settingKey` in-memory |
| `consent` | Postgres `consent_events` | **required** — skipped with a note otherwise | `since`/`until` in-memory |
| `reports` | Postgres `reports` | optional | `since`/`until` in SQL; `user:` matches reporter **or** target |
| `dlq` | — | — | accepted by the grammar (and used by the seeded "DLQ today" view) but the explorer has **no DLQ adapter yet** — `store:dlq` alone returns zero rows. Browse dead letters at `GET /api/v1/admin/ops/queues/dlq` |

**Request body** — None.

**Response** — `200 OK`. `query` is the canonical parsed form (`stores:all-eligible` when unrestricted). Every row is `{store, at, summary, fields}` with all `fields` values as strings. Rows are merged, sorted newest-first, and capped at `pageSize`.

```json
{
  "query": "user:2a6e4f8b-0c31-4d75-9e02-8b1a3c5d7e90 since:2026-07-31T09:00:00 stores:[audit, login]",
  "rows": [
    {
      "store": "login",
      "at": "2026-08-07T08:14:02.113",
      "summary": "PASSWORD login FAILED from 203.0.113.7",
      "fields": {
        "userId": "2a6e4f8b-0c31-4d75-9e02-8b1a3c5d7e90",
        "ip": "203.0.113.7",
        "outcome": "FAILED"
      }
    },
    {
      "store": "audit",
      "at": "2026-08-06T21:40:55",
      "summary": "UPDATE Settings CLIENT_ERROR",
      "fields": {
        "operation": "UPDATE",
        "outcome": "CLIENT_ERROR",
        "path": "/api/v1/settings/privacy",
        "status": "400"
      }
    }
  ],
  "notes": [
    "audit rows are the newest page of the user partition with filters applied in-memory — fetch more via /api/v1/admin/audit?userId=."
  ]
}
```

Row `fields` per store — `audit`: `operation` (`READ`/`CREATE`/`UPDATE`/`DELETE`/`LOGIN`/`LOGOUT`/`UPLOAD`/`SYSTEM`/`OTHER`), `outcome` (`SUCCESS`/`REDIRECT`/`CLIENT_ERROR`/`SERVER_ERROR`/`SYSTEM`), `path`, `status` · `login`: `userId`, `ip`, `outcome` · `settings`: `userId`, `key`, `ip` · `consent`: `scope`, `granted` (`"true"`/`"false"`) with summary `"<SCOPE> granted|revoked"` · `reports`: `state` (`SUBMITTED`/`TRIAGED`/`ACTIONED`/`DISMISSED`/`APPEALED`/`UPHELD`/`REVERSED`), `groupKey` (`targetId:reason`) with summary like `"SPAM report on POST 7b5a2d4e-… (SUBMITTED)"`.

`notes` values you may see (verbatim):
- `"audit store skipped — it is partitioned per user; add user:<id|@username> to include it (a global Cassandra scan is impossible by design)."`
- `"audit rows are the newest page of the user partition with filters applied in-memory — fetch more via /api/v1/admin/audit?userId=."`
- `"consent store skipped — user-anchored; add user: to include it."`

**Errors**
- `USER_NOT_FOUND` — 404 — `user:@handle` (or non-UUID `user:` value) does not resolve to an active user.

---

## Login events

### GET /api/v1/admin/logs/login-events
Global login-events reader — every filter optional, `ts` descending.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `userId` | UUID | — | exact user |
| `ip` | string | — | exact IP (blank treated as absent) |
| `outcome` | string | — | `SUCCESS` \| `FAILED` \| `LOCKED` |
| `from` | ISO date-time | — | `ts >= from` |
| `to` | ISO date-time | — | `ts <= to` |
| `page` | int | 0 | |
| `size` | int | 50 | clamped to ≤100 |

**Request body** — None.

**Response** — `200 OK`, `Page<LoginEvent>`. `method` ∈ `PASSWORD`, `OTP`, `REFRESH`, `TWO_FA`; `coarseGeo` is country/region only, never precise. Null fields omitted.

```json
{
  "content": [
    {
      "id": "e3b1c5d7-9f02-4a68-b1c3-5d7e9f024a68",
      "userId": "2a6e4f8b-0c31-4d75-9e02-8b1a3c5d7e90",
      "ts": "2026-08-07T08:14:02.113",
      "ip": "203.0.113.7",
      "coarseGeo": "IQ / Erbil",
      "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) …",
      "method": "PASSWORD",
      "outcome": "FAILED"
    }
  ],
  "totalElements": 312,
  "totalPages": 7,
  "number": 0,
  "size": 50
}
```

> Trimmed: real responses include the full Spring page metadata.

**Errors** — none endpoint-specific.

---

## Export

### POST /api/v1/admin/logs/export
CSV export of an explorer query — the most sensitive read in the system: step-up-gated, ADMIN-only, row-capped, and it writes its own audit row **including the query string** (`ADMIN_LOG_EXPORT`).

**Access**: `ADMIN` only + **step-up required**.

**Request body** — `ExportBody`; the grammar string only (discrete params are not accepted here).

```json
{ "query": "user:@alice store:login,settings since:7d" }
```

**Response** — `200 OK` with:

- `Content-Type: text/csv`
- `Content-Disposition: attachment; filename="log-export.csv"`
- `X-Row-Cap: 10000`

Only the `audit` (user-anchored), `login`, and `settings` stores are exported — audit and login pull at most 5 000 rows each, settings fills the remainder, 10 000 rows total. CSV escaping: quotes doubled, newlines flattened to spaces, comma-containing values wrapped in quotes.

```csv
store,at,summary
login,2026-08-07T08:14:02.113,PASSWORD FAILED ip=203.0.113.7
login,2026-08-06T22:03:11.870,PASSWORD SUCCESS ip=203.0.113.7
settings,2026-08-06T21:40:55.020,PRIVACY_DM_POLICY changed by 2a6e4f8b-0c31-4d75-9e02-8b1a3c5d7e90
```

**Errors**
- `STEP_UP_REQUIRED` — 403 — step-up marker missing/expired.
- `USER_NOT_FOUND` — 404 — `user:@handle` in the query does not resolve.
- 403 `ACCESS_DENIED` — non-ADMIN staff roles.

---

## Saved views

Per-admin named Log-Explorer filter sets (`AdminSavedLogView`: `{id, adminId, name, query, createdAt}`).

### GET /api/v1/admin/logs/views
List the calling admin's saved views. First call for an admin **seeds the documented default set** (persisted, then returned):

| Name | Query |
|---|---|
| Failed logins 24h | `store:login outcome:FAILED since:24h` |
| Admin actions 7d | `store:audit path:/api/v1/admin/* since:7d` |
| Settings churn 24h | `store:settings since:24h` |
| Reports today | `store:reports since:24h` |
| DLQ today | `store:dlq since:24h` |

(The `path:` token in the seeded audit view and the `dlq` store are accepted-but-inert in today's explorer — kept for forward-compat.)

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST`.

**Request body** — None.

**Response** — `200 OK`, JSON array (not paged).

```json
[
  {
    "id": "8a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d",
    "adminId": "9f21c4d0-3e5a-4b7c-8d10-2a6e4f8b0c31",
    "name": "Failed logins 24h",
    "query": "store:login outcome:FAILED since:24h",
    "createdAt": "2026-08-07T09:00:12.404"
  }
]
```

**Errors** — none endpoint-specific.

### POST /api/v1/admin/logs/views
Save a view for the calling admin. Name and query are trimmed and stored verbatim.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST`.

**Request body** — `SavedViewBody`; `name` ≤ 80 chars, both required.

```json
{ "name": "Alice audit trail", "query": "user:@alice store:audit since:30d" }
```

**Response** — `201 Created`, the persisted view.

```json
{
  "id": "7c2d3e4f-5a6b-4c7d-8e9f-1a2b3c4d5e6f",
  "adminId": "9f21c4d0-3e5a-4b7c-8d10-2a6e4f8b0c31",
  "name": "Alice audit trail",
  "query": "user:@alice store:audit since:30d",
  "createdAt": "2026-08-07T09:05:44.128"
}
```

**Errors** — none endpoint-specific.

### DELETE /api/v1/admin/logs/views/{id}
Delete one of **your own** saved views (ownership enforced; another admin's view behaves as missing).

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST`.

**Params** — `id` (path, UUID).

**Request body** — None.

**Response** — 204 No Content.

**Errors**
- `SAVEDLOGVIEW_NOT_FOUND` — 404 — id unknown **or** owned by a different admin.

---

## Alert rules & firings

`LogAlertRule` rows parameterize the built-in rule kinds a 5-minute sweep (`LogAlertSweepJob`) evaluates; each firing lands in `log_alert_firings` (deduped so a persisting condition doesn't refire every tick).

`RuleKind` values:

| Kind | Fires when |
|---|---|
| `FAILED_LOGIN_PER_ACCOUNT` | ≥ threshold `FAILED` login events / window for one user |
| `FAILED_LOGIN_PER_IP` | ≥ threshold `FAILED` login events / window from one IP |
| `REPORT_PILE_ON` | ≥ threshold reports on one `group_key` / window |
| `DLQ_ARRIVALS` | ≥ threshold parked dead letters / window |
| `OTP_ABUSE` | ≥ threshold OTP challenges / window (volume proxy) |
| `PURGE_JOB_SILENCE` | no SUCCESS account-purge `job_runs` row within window |

`Severity` values: `INFO`, `MEDIUM`, `HIGH`.

### GET /api/v1/admin/logs/alerts
List every alert rule (unpaged).

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST`.

**Request body** — None.

**Response** — `200 OK`, JSON array of `LogAlertRule`.

```json
[
  {
    "id": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
    "kind": "FAILED_LOGIN_PER_IP",
    "name": "Brute force per IP",
    "threshold": 20,
    "windowMinutes": 30,
    "severity": "HIGH",
    "enabled": true,
    "createdBy": "9f21c4d0-3e5a-4b7c-8d10-2a6e4f8b0c31",
    "createdAt": "2026-08-01T10:20:30.400"
  }
]
```

**Errors** — none endpoint-specific.

### POST /api/v1/admin/logs/alerts
Create a rule. Defaults: `name` = kind name, `threshold` = 10, `windowMinutes` = 60, `severity` = `MEDIUM`, `enabled` = true.

**Access**: `ADMIN` only + **step-up required**.

**Request body** — `AlertRuleBody`; only `kind` is effectively required.

```json
{
  "kind": "FAILED_LOGIN_PER_ACCOUNT",
  "name": "Account brute force",
  "threshold": 10,
  "windowMinutes": 60,
  "severity": "MEDIUM",
  "enabled": true
}
```

**Response** — `201 Created`, the persisted rule (same shape as the list rows above). Audit action: `ADMIN_LOG_ALERT_RULE_CREATE`.

**Errors**
- `INVALID_RULE_KIND` — 400 — `kind` missing or not one of the six kinds ("Unknown rule kind. Allowed: [FAILED_LOGIN_PER_ACCOUNT, FAILED_LOGIN_PER_IP, REPORT_PILE_ON, DLQ_ARRIVALS, OTP_ABUSE, PURGE_JOB_SILENCE]").
- `INVALID_SEVERITY` — 400 — severity not `INFO`/`MEDIUM`/`HIGH`.
- `STEP_UP_REQUIRED` — 403.

### PATCH /api/v1/admin/logs/alerts/{id}
Partial update — only non-null body fields are applied (blank `name` ignored). `kind` is immutable.

**Access**: `ADMIN` only + **step-up required**.

**Params** — `id` (path, UUID).

**Request body** — any subset of `AlertRuleBody`:

```json
{ "threshold": 30, "severity": "HIGH", "enabled": false }
```

**Response** — `200 OK`, the updated rule. Audit action: `ADMIN_LOG_ALERT_RULE_UPDATE`.

**Errors**
- `LOGALERTRULE_NOT_FOUND` — 404 — no such rule.
- `INVALID_SEVERITY` — 400.
- `STEP_UP_REQUIRED` — 403.

### DELETE /api/v1/admin/logs/alerts/{id}
Delete a rule. Idempotent (204 even if already gone). No step-up on this one.

**Access**: `ADMIN` only.

**Params** — `id` (path, UUID).

**Request body** — None.

**Response** — 204 No Content. Audit action: `ADMIN_LOG_ALERT_RULE_DELETE`.

**Errors** — none endpoint-specific.

### GET /api/v1/admin/logs/alerts/firings
The firings feed, `firedAt` descending.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `since` | ISO date-time | — | `firedAt >= since` |
| `ruleId` | UUID | — | one rule only |
| `page` | int | 0 | |
| `size` | int | 50 | clamped to ≤100 |

**Request body** — None.

**Response** — `200 OK`, `Page<LogAlertFiring>`. `detail` names the subject (user id / IP / group key) and observed-vs-threshold; `dedupKey` suppresses refires of the same condition.

```json
{
  "content": [
    {
      "id": "6b7c8d9e-0f1a-4b2c-9d3e-4f5a6b7c8d9e",
      "ruleId": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
      "ruleKind": "FAILED_LOGIN_PER_IP",
      "severity": "HIGH",
      "detail": "ip=203.0.113.7 count=27 threshold=20 window=30m",
      "dedupKey": "FAILED_LOGIN_PER_IP:203.0.113.7",
      "firedAt": "2026-08-07T08:20:00.041"
    }
  ],
  "totalElements": 4,
  "totalPages": 1,
  "number": 0,
  "size": 50
}
```

> Trimmed: real responses include the full Spring page metadata.

**Errors** — none endpoint-specific.

---

## Retention board

### GET /api/v1/admin/logs/retention
Static retention-policy board: every log/evidence store, its policy, and what enforces it. `rows` (a live count) is present only where counting is cheap (`settings_audit`, `login_events`, `reports / user_strikes`) and is omitted if the count fails.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST`.

**Request body** — None.

**Response** — `200 OK`, fixed 10-row array in this order:

```json
[
  { "store": "audit_log_by_user / by_resource", "policy": "180d table TTL", "enforcedBy": "AuditSchemaInitializer (applied at startup)" },
  { "store": "settings_audit", "policy": "2y", "enforcedBy": "RetentionSweepJob 03:20", "rows": 184223 },
  { "store": "login_events", "policy": "1y", "enforcedBy": "RetentionSweepJob 03:20", "rows": 57110 },
  { "store": "otp_challenges", "policy": "180d past expiry", "enforcedBy": "RetentionSweepJob 03:20" },
  { "store": "consent_events", "policy": "kept for account life (+post-purge — compliance evidence)", "enforcedBy": "deliberate keep" },
  { "store": "reports / user_strikes", "policy": "kept ≥2y (moderation evidence; strikes never deleted)", "enforcedBy": "deliberate keep", "rows": 1290 },
  { "store": "export_jobs + ZIP files", "policy": "48h past readiness", "enforcedBy": "RetentionSweepJob 03:20 (+ immediate delete on account purge)" },
  { "store": "dead_letters (DLQ parking lot)", "policy": "90d", "enforcedBy": "RetentionSweepJob 03:20" },
  { "store": "job_runs", "policy": "90d", "enforcedBy": "NotificationCleanupJob 03:15" },
  { "store": "notifications (read)", "policy": "90d", "enforcedBy": "NotificationCleanupJob 03:15" }
]
```

**Errors** — none endpoint-specific.

---

## OTP stats

### GET /api/v1/admin/logs/otp-stats
Aggregate-only OTP telemetry: volume + outcome mix per purpose, and hot destination hashes. Codes and raw destinations are never exposed — `destinationHash` is only a correlator for "same target hammered".

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `windowHours` | int | 24 | clamped to `[1, 720]` (30 days) |
| `burstThreshold` | int | 5 | min challenges per destination to be listed; floor 2 |

**Request body** — None.

**Response** — `200 OK`. `purpose` ∈ `LOGIN`, `PHONE_VERIFY`, `PHONE_CHANGE`, `EMAIL_CHANGE`, `PASSWORD_RESET`, `STEP_UP`. `consumed` = challenges successfully redeemed; `maxedAttempts` = challenges that hit the 3-attempt cap. `hotDestinations` is capped at 50, ordered by count descending.

```json
{
  "windowHours": 24,
  "byPurpose": [
    { "purpose": "LOGIN", "issued": 412, "consumed": 388, "maxedAttempts": 9 },
    { "purpose": "PASSWORD_RESET", "issued": 37, "consumed": 30, "maxedAttempts": 2 },
    { "purpose": "STEP_UP", "issued": 21, "consumed": 21, "maxedAttempts": 0 }
  ],
  "hotDestinations": [
    { "destinationHash": "9f2c1a7e44b8d3f6a0c5e2b19d4f7a83", "challenges": 14 }
  ],
  "note": "Aggregate-only: codes and raw destinations are never stored or shown; destinationHash is HMAC'd and useful only as a correlator."
}
```

**Errors** — none endpoint-specific.
