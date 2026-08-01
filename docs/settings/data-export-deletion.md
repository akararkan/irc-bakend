# Data Export & Account Deletion (§16)

**[B] Backend-owned.** Package `settings.data`.

## Data export

`DataExportService.requestExport(userId)`:
- Rate-limited **1 per 30 days** (`RateLimiter`).
- Creates an `export_jobs` row (PENDING) and runs the build on a pure-JDK
  single-thread `ExecutorService` (no reliance on `@EnableAsync`): gather profile
  + account → JSON → **ZIP** to a temp file → status `READY` + `expires_at` (+48h).
- **`POST /api/v1/privacy/export`** → 202 `{jobId}`;
  **`GET /api/v1/privacy/export/{jobId}`** → status;
  **`GET /api/v1/privacy/export/{jobId}/download`** streams the ZIP
  (`Content-Disposition: attachment`).

> **Seam:** production should stream to R2 and return a **presigned 48h URL**
> (spec §16); this build serves the archive from a temp file for self-containment.
> History deletion (`DELETE /api/v1/privacy/history/{type}`, `search`|`watch`) is a
> best-effort stub — the platform has no such history tables yet.

## Account deletion — a state machine, not a `DELETE`

`AccountLifecycleService`:

```
ACTIVE ──request──► PENDING_DELETION ──30-day grace──► ANONYMIZED / PURGED
             │                              │
        (deletedAt set,               (scheduled purge)
     sessions revoked NOW)
```

- **`POST /api/v1/account/deletion/request`** — sets `users.deleted_at = now`
  (the account behaves as deleted **immediately** — invisible platform-wide via
  the existing `deletedAt` semantics), revokes all sessions
  (`RefreshTokenRepository.revokeAllForUser`), and writes an
  `account_deletion_requests` row (`PENDING_DELETION`, `purge_after = +30d`). The
  grace period exists only for recovery.
- **`POST /api/v1/account/deletion/cancel`** — clears `deleted_at`, restores the
  account, marks the request `CANCELLED`.
- **`@Scheduled` purge** — for requests past `purge_after`: anonymise PII
  (`username → deleted_user_…`, email/name blanked, password cleared), write a
  `deleted_accounts` tombstone (old id + timestamp, prevents id reuse), mark
  `PURGED`. Each user is wrapped in try/catch so one failure doesn't abort the batch.

> **Seam:** the request endpoint should require [step-up](auth-sessions.md).
> Messages the user sent into others' conversations are attributed to a deleted
> user rather than removed (the existing chat behaviour), mirroring every major
> platform.
