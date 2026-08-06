# Admin API Reference — Safety, Reports & Audit

Complete endpoint reference for the two controllers that expose the moderation console and the
audit log:

- `src/main/java/ak/dev/irc/app/admin/safety/AdminSafetyController.java` — base path
  `/api/v1/admin/safety`: grouped triage queue, workbench detail with frozen evidence, the report
  state-machine transitions, the strikes ledger, the per-user moderation record, the read-only
  consent viewer, and block/restriction aggregates.
- `src/main/java/ak/dev/irc/app/audit/controller/AuditLogController.java` — base path
  `/api/v1/admin/audit`: Cassandra-backed audit reads (per-user, per-resource) and the realtime
  SSE stream.

Concept docs: [safety-reports.md](../safety-reports.md) (report state machine, evidence, strikes),
[logs-audit.md](../logs-audit.md) (audit pipeline, Cassandra pivots, retention),
[frontend-dashboard-guide.md](../frontend-dashboard-guide.md) (how the dashboard consumes these).
Error envelope: [frontend-error-handling.md](../../errors/frontend-error-handling.md).

## Conventions

- **Auth**: Bearer JWT on every endpoint. The SSE stream additionally accepts `?token=<jwt>`
  (SSE endpoints carry their own auth via the token query param).
- **Roles**: `AdminSafetyController` is class-gated `hasAnyRole('ADMIN','MODERATOR')`; the two
  read endpoints in *Reports queue & detail* widen to `SUPPORT`. `AuditLogController` is
  class-gated `hasAnyRole('ADMIN','MODERATOR','SUPPORT','ANALYST')` (SUPPORT own-scope refinement
  is an open item in [known-issues.md](../known-issues.md)). Missing role → 403.
- **Step-up**: endpoints marked `@RequiresStepUp` require a fresh step-up marker
  (`stepup:{userId}` in Redis), armed via `POST /api/v1/security/step-up` (password re-auth).
  Absent marker → **403** `STEP_UP_REQUIRED` ("This action requires you to confirm your
  identity.").
- **Audit trail**: every mutation writes an `ADMIN_*` business audit row through `AdminAuditor`
  (action string in the row's `summary`, truncated at 500 chars) in addition to the interceptor's
  request row.
- **Timestamps**: entity/DTO date-times serialize as zone-less ISO local date-time
  (`"2026-08-05T10:02:11.654321"`). Audit `createdAt` is UTC wall-clock. Exceptions are called
  out per endpoint (the queue's `firstSeen`/`lastSeen` are stringified SQL timestamps).
- **Page sizes**: clamped to `[1, 100]` (`Pages.clamp`) wherever noted. `Page<T>` responses use
  the standard Spring Data shape; examples below are abbreviated to
  `content`/`totalElements`/`totalPages`/`number`/`size` — the full payload also carries
  `pageable`, `sort`, `first`, `last`, `numberOfElements`, `empty`.
- **Bean-validation caveat**: the request records declare `@NotBlank`/`@Size` constraints but the
  handlers take them without `@Valid`, so they are not enforced as `VALIDATION_FAILED`; the
  effective guards are the server-side parses and truncations documented per endpoint.
- Unparseable query params (bad UUID/enum/date) → **400** `TYPE_MISMATCH`; missing required
  params → **400** `MISSING_PARAMETER` (both via the standard envelope).

### Enums

| Enum | Values |
|---|---|
| `ReportState` | `SUBMITTED`, `TRIAGED`, `ACTIONED`, `DISMISSED`, `APPEALED`, `UPHELD`, `REVERSED` — lifecycle `SUBMITTED → TRIAGED → ACTIONED \| DISMISSED → (APPEALED → UPHELD \| REVERSED)` |
| `ReportReason` | `SPAM`, `HARASSMENT`, `HATE_SPEECH`, `MISINFORMATION`, `NUDITY_SEXUAL`, `VIOLENCE`, `IMPERSONATION`, `SELF_HARM`, `COPYRIGHT`, `OTHER` |
| `Resolution` | `NONE`, `WARNING_ISSUED`, `CONTENT_REMOVED`, `ACCOUNT_SUSPENDED`, `NO_ACTION` (`NONE` is the pre-verdict default and is rejected by the action endpoint) |
| `ReportTargetType` | `USER`, `POST`, `COMMENT`, `RESEARCH`, `QUESTION`, `ANSWER`, `MESSAGE`, `CHANNEL`, `STORY` |
| `AuditOperation` | `READ`, `CREATE`, `UPDATE`, `DELETE`, `LOGIN`, `LOGOUT`, `UPLOAD`, `SYSTEM`, `OTHER` |
| `AuditOutcome` | `SUCCESS`, `REDIRECT`, `CLIENT_ERROR`, `SERVER_ERROR`, `SYSTEM` |

### Shared shapes

**`AdminReportRow`** — every report returned by this console. `@JsonInclude(NON_NULL)`: null
fields are omitted (e.g. `triagedBy`/`triagedAt` before triage, `moderatorNote`/`details` when
empty). `targetRef` is the effective target reference — the UUID as a string, or the raw string
ref for non-UUID targets (chat-message Snowflakes). Field order:

```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "reporterId": "0b2c4d6e-8f01-4a23-b456-7890acbde001",
  "targetType": "POST",
  "targetRef": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
  "reason": "HARASSMENT",
  "state": "ACTIONED",
  "resolution": "CONTENT_REMOVED",
  "groupKey": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f:HARASSMENT",
  "details": "Repeated abusive replies under my post",
  "moderatorNote": "Verified against evidence | removed via content console",
  "triagedBy": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "triagedAt": "2026-08-05T09:12:44.123456",
  "actionedBy": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "actedAt": "2026-08-05T10:02:11.654321",
  "createdAt": "2026-08-04T21:40:03.111222"
}
```

**`UserStrike`** — the JPA entity serialized directly (nulls included; `active` is the derived
`isActive()` = `expiresAt` in the future; `expiresAt` defaults to `issuedAt + 90 days`):

```json
{
  "id": "5f2b7d9c-1e3a-4c5b-9d7e-2f4a6b8c0d1e",
  "userId": "0b2c4d6e-8f01-4a23-b456-7890acbde001",
  "reportId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "reason": "Report actioned: CONTENT_REMOVED",
  "issuedAt": "2026-08-05T10:02:11.654321",
  "expiresAt": "2026-11-03T10:02:11.654321",
  "active": true
}
```

**`AuditLogResponse`** — every audit read and the SSE `audit` event. `@JsonInclude(NON_NULL)`:
absent fields (no `resourceId`, no `errorCode` on success, `userId`/`username` absent on
anonymous rows in the resource pivot) are omitted. Field order:

```json
{
  "id": "e29b2c31-41d4-4716-a655-446655440000",
  "userId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "username": "mod.sara",
  "operation": "UPDATE",
  "outcome": "SUCCESS",
  "resourceType": "Report",
  "resourceId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "httpMethod": "POST",
  "path": "/api/v1/admin/safety/reports/7c9e6679-7425-40de-944b-e07fc1f90ae7/action",
  "queryString": "wholeGroup=true",
  "statusCode": 200,
  "durationMs": 41,
  "ipAddress": "203.0.113.7",
  "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) …",
  "summary": "ADMIN_REPORT_ACTION — CONTENT_REMOVED — verified",
  "errorCode": "REPORT_STATE_INVALID",
  "createdAt": "2026-08-05T10:02:11.654321"
}
```

---

## Reports queue & detail

### GET /api/v1/admin/safety/reports
Grouped triage queue — one row per `(target, reason)` group, oldest first.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT` (method-level override; SUPPORT is read-only intake).

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `state` | string | — | One `ReportState` value (case-insensitive). Omitted/blank → open queue: `SUBMITTED` + `TRIAGED`. |
| `targetType` | string | — | `ReportTargetType` value (uppercased verbatim into the SQL filter). |
| `reason` | string | — | `ReportReason` value (uppercased verbatim into the SQL filter). |
| `page` | int | `0` | Offset paging: `OFFSET page*pageSize`. |
| `pageSize` | int | `50` | Clamped to `[1, 100]`. |

**Request body** — None.

**Response** — `200 OK`, bare JSON array (no page wrapper, no total). Key order as shown:

```json
[
  {
    "groupKey": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f:HARASSMENT",
    "targetType": "POST",
    "targetRef": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
    "reason": "HARASSMENT",
    "reporterCount": 4,
    "reportCount": 6,
    "firstSeen": "2026-08-04 21:40:03.111222",
    "lastSeen": "2026-08-05 08:12:19.334455",
    "state": "SUBMITTED"
  }
]
```

Notes:
- `firstSeen`/`lastSeen` are stringified SQL timestamps (space separator, no `T`, no zone) —
  unlike every other timestamp in this document.
- `state` is `MIN(state)` as text across the group — the alphabetically smallest state string
  among the matched rows, not necessarily the anchor report's state.
- `targetRef` here comes from the `target_id` column only; for `MESSAGE`-target groups (string
  Snowflake refs, `target_id = null`) it is `null` — open the detail endpoint for the real ref.

**Errors**
- `INVALID_STATE` — 400 — `state` does not parse to a `ReportState` (message lists allowed values).

### GET /api/v1/admin/safety/reports/{id}
Workbench detail: the report, its whole group, the frozen evidence, and linked strikes.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT` (method-level override).

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `id` | UUID (path) | — | Report id. |

**Request body** — None.

**Response** — `200 OK`. Key order: `report`, `group`, `evidence`, `linkedStrikes`.
`report` is an [`AdminReportRow`](#shared-shapes); `group` is every sibling sharing the
`groupKey`, oldest first (includes `report` itself). `evidence` is the raw `ReportEvidence`
entity (nulls included) or JSON `null` when nothing was captured; `linkedStrikes` are raw
[`UserStrike`](#shared-shapes) rows whose `reportId` = this report.

```json
{
  "report": { "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "reporterId": "0b2c4d6e-8f01-4a23-b456-7890acbde001", "targetType": "POST", "targetRef": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f", "reason": "HARASSMENT", "state": "TRIAGED", "resolution": "NONE", "groupKey": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f:HARASSMENT", "details": "Repeated abusive replies", "triagedBy": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", "triagedAt": "2026-08-05T09:12:44.123456", "createdAt": "2026-08-04T21:40:03.111222" },
  "group": [
    { "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "reporterId": "0b2c4d6e-8f01-4a23-b456-7890acbde001", "targetType": "POST", "targetRef": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f", "reason": "HARASSMENT", "state": "TRIAGED", "resolution": "NONE", "groupKey": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f:HARASSMENT", "triagedBy": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", "triagedAt": "2026-08-05T09:12:44.123456", "createdAt": "2026-08-04T21:40:03.111222" }
  ],
  "evidence": {
    "id": "9d8c7b6a-5e4f-4d3c-2b1a-0f9e8d7c6b5a",
    "reportId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "groupKey": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f:HARASSMENT",
    "targetType": "POST",
    "targetRef": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
    "authorId": "0b2c4d6e-8f01-4a23-b456-7890acbde002",
    "contentText": "frozen text of the reported post at first-report time…",
    "mediaRefs": "posts/3f8a1c2e/media-1.jpg,posts/3f8a1c2e/media-2.jpg",
    "entityState": "PUBLISHED",
    "capturedAt": "2026-08-04T21:40:03.222333"
  },
  "linkedStrikes": []
}
```

Evidence is snapshot-at-first-report and append-only; `mediaRefs` is a comma-separated string of
storage references (never bytes), `authorId`/`contentText`/`mediaRefs` may be `null` when capture
could not resolve them, `entityState` is a free-form live-store marker (`"PUBLISHED"`,
`"not captured"`, …).

**Errors**
- `RESOURCE_NOT_FOUND` — 404 — no report with `id`.

---

## Triage & resolution

All three transition endpoints return the refreshed anchor report as an
[`AdminReportRow`](#shared-shapes) and audit as `ADMIN_REPORT_TRIAGE` / `ADMIN_REPORT_DISMISS` /
`ADMIN_REPORT_ACTION`. `wholeGroup` defaults to **true** (absent body or absent field): the
transition applies to every eligible sibling of the report's `groupKey` in one transaction.
Notes append to `moderatorNote` with `" | "` separators, truncated at 1000 chars.

### POST /api/v1/admin/safety/reports/{id}/triage
Advance a report (or its whole group) `SUBMITTED → TRIAGED`, stamping `triagedBy`/`triagedAt`.

**Access**: `ADMIN`, `MODERATOR` (class level). No step-up.

**Request body** — optional:

```json
{
  "note": "Looks credible — needs a resolution decision",
  "wholeGroup": true
}
```

`note` ≤ 1000 chars (declared). With `wholeGroup=true`, only siblings still in `SUBMITTED`
advance; others are left untouched.

**Response** — `200 OK`, [`AdminReportRow`](#shared-shapes) with `state: "TRIAGED"`.

**Errors**
- `RESOURCE_NOT_FOUND` — 404 — no report with `id`.
- `REPORT_STATE_INVALID` — 409 — anchor report is not in `SUBMITTED`
  ("Cannot triage a report in state %s.").

### POST /api/v1/admin/safety/reports/{id}/dismiss
Close a report (or group) as unfounded: `SUBMITTED|TRIAGED → DISMISSED`, `resolution` set to
`NO_ACTION`, `actionedBy`/`actedAt` stamped.

**Access**: `ADMIN`, `MODERATOR` (class level). No step-up.

**Request body** — optional, same shape as triage:

```json
{
  "note": "Reported post does not violate policy",
  "wholeGroup": true
}
```

**Response** — `200 OK`, [`AdminReportRow`](#shared-shapes) with `state: "DISMISSED"`,
`resolution: "NO_ACTION"`. Group application skips siblings not in `SUBMITTED`/`TRIAGED`.

**Errors**
- `RESOURCE_NOT_FOUND` — 404 — no report with `id`.
- `REPORT_STATE_INVALID` — 409 — anchor report is not in `SUBMITTED`/`TRIAGED`.

### POST /api/v1/admin/safety/reports/{id}/action
Record the verdict: `SUBMITTED|TRIAGED → ACTIONED` with a concrete `Resolution`, optionally
issuing a strike against the reported user. Records the verdict only — takedown/suspension
execution belongs to the sibling admin endpoints.

**Access**: `ADMIN`, `MODERATOR` (class level). **@RequiresStepUp**.

**Request body** — required:

```json
{
  "resolution": "CONTENT_REMOVED",
  "note": "Verified against frozen evidence",
  "issueStrike": true,
  "strikeReason": "Harassment in post replies",
  "wholeGroup": true
}
```

| Field | Notes |
|---|---|
| `resolution` | Required. One of `WARNING_ISSUED`, `CONTENT_REMOVED`, `ACCOUNT_SUSPENDED`, `NO_ACTION` (case-insensitive). `NONE` and unknown values are rejected. |
| `note` | Optional, ≤ 1000 (declared). |
| `issueStrike` | Optional, default `false`. Only valid when the report's target is a `USER` — the strike lands on `targetId`, linked to this report. For content targets issue the strike explicitly via the strikes endpoint. |
| `strikeReason` | Optional, ≤ 200 (declared). Blank → defaults to `"Report actioned: {resolution}"`. |
| `wholeGroup` | Optional, default `true`. |

**Response** — `200 OK`, [`AdminReportRow`](#shared-shapes) with `state: "ACTIONED"` and the
given `resolution`. Group application skips siblings not in `SUBMITTED`/`TRIAGED`.

**Errors**
- `INVALID_RESOLUTION` — 400 — `resolution` missing/`NONE`
  ("resolution is required (WARNING_ISSUED, CONTENT_REMOVED, ACCOUNT_SUSPENDED, NO_ACTION).")
  or unparseable ("Unknown resolution. Allowed: …").
- `RESOURCE_NOT_FOUND` — 404 — no report with `id`.
- `REPORT_STATE_INVALID` — 409 — anchor report is not in `SUBMITTED`/`TRIAGED`.
- `STRIKE_TARGET_AMBIGUOUS` — 400 — `issueStrike=true` on a non-`USER` target
  ("issueStrike inline is only supported for USER-target reports; use
  POST /api/v1/admin/safety/users/{userId}/strikes for content authors.").
- `STEP_UP_REQUIRED` — 403 — step-up window not armed.

---

## Appeals

Both appeal verdicts operate on the single report only (`wholeGroup` is ignored), require the
report to be in `APPEALED`, stamp `actionedBy`/`actedAt`, and audit as `ADMIN_APPEAL_UPHOLD` /
`ADMIN_APPEAL_REVERSE`.

### POST /api/v1/admin/safety/appeals/{reportId}/uphold
Reject the appeal: `APPEALED → UPHELD` (original resolution stands).

**Access**: `ADMIN`, `MODERATOR` (class level). **@RequiresStepUp**.

**Request body** — optional:

```json
{
  "note": "Second review confirms the original decision"
}
```

**Response** — `200 OK`, [`AdminReportRow`](#shared-shapes) with `state: "UPHELD"`.

**Errors**
- `RESOURCE_NOT_FOUND` — 404 — no report with `reportId`.
- `REPORT_STATE_INVALID` — 409 — report is not in `APPEALED`.
- `STEP_UP_REQUIRED` — 403 — step-up window not armed.

### POST /api/v1/admin/safety/appeals/{reportId}/reverse
Grant the appeal: `APPEALED → REVERSED`. Any still-active strikes linked to this report
(`user_strikes.report_id`) are auto-revoked (their `expiresAt` set to now); the note gains a
`" [revoked strike(s): N]"` suffix when that happens.

**Access**: `ADMIN`, `MODERATOR` (class level). **@RequiresStepUp**.

**Request body** — optional:

```json
{
  "note": "Evidence does not support the original action"
}
```

**Response** — `200 OK`, [`AdminReportRow`](#shared-shapes) with `state: "REVERSED"`.

**Errors**
- `RESOURCE_NOT_FOUND` — 404 — no report with `reportId`.
- `REPORT_STATE_INVALID` — 409 — report is not in `APPEALED`.
- `STEP_UP_REQUIRED` — 403 — step-up window not armed.

---

## Strikes

### POST /api/v1/admin/safety/users/{userId}/strikes
Issue a manual strike against a user (the content-author path for non-USER-target reports).
Expiry auto-sets to `issuedAt + 90 days`. Audits as `ADMIN_STRIKE_ISSUE` and best-effort sends
the user a system notification ("Account warning" / "A moderation strike was recorded on your
account: {reason}. Strikes expire automatically after 90 days." — delivery failure is swallowed).

**Access**: `ADMIN`, `MODERATOR` (class level). **@RequiresStepUp**.

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `userId` | UUID (path) | — | Strike target. Not validated against the user table — a wrong UUID silently creates an orphan row. |

**Request body** — required:

```json
{
  "reportId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "reason": "Harassment in post replies"
}
```

`reportId` optional (links the strike to a report for appeal review); `reason` required,
≤ 200 chars (declared).

**Response** — `201 Created`, the raw [`UserStrike`](#shared-shapes) row.

**Errors**
- `STEP_UP_REQUIRED` — 403 — step-up window not armed.

### DELETE /api/v1/admin/safety/strikes/{strikeId}
Revoke an active strike (decay-consistent: `expiresAt` is set to now; rows are never deleted).
Audits as `ADMIN_STRIKE_REVOKE`.

**Access**: `ADMIN`, `MODERATOR` (class level). **@RequiresStepUp**.

**Request body** — optional `{"note": "Issued in error"}` (note goes to the audit row only).

**Response** — 204 No Content.

**Errors**
- `RESOURCE_NOT_FOUND` — 404 — no strike with `strikeId`.
- `STRIKE_NOT_ACTIVE` — 409 — strike already expired/revoked ("Strike is not active.").
- `STEP_UP_REQUIRED` — 403 — step-up window not armed.

### GET /api/v1/admin/safety/strikes
The strikes ledger, newest first.

**Access**: `ADMIN`, `MODERATOR` (class level).

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `userId` | UUID | — | Restrict to one user. |
| `active` | boolean | — | Post-filter by `active` **within the fetched page** — `totalElements` stays the unfiltered total; `content` may hold fewer than `size` rows. |
| `page` | int | `0` | Spring `Pageable`. |
| `size` | int | `25` | Clamped to `[1, 100]`. Sort params are ignored — order is fixed `issuedAt DESC`. |

**Request body** — None.

**Response** — `200 OK`, `Page<UserStrike>` (abbreviated — see [Conventions](#conventions)):

```json
{
  "content": [
    {
      "id": "5f2b7d9c-1e3a-4c5b-9d7e-2f4a6b8c0d1e",
      "userId": "0b2c4d6e-8f01-4a23-b456-7890acbde001",
      "reportId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "reason": "Report actioned: CONTENT_REMOVED",
      "issuedAt": "2026-08-05T10:02:11.654321",
      "expiresAt": "2026-11-03T10:02:11.654321",
      "active": true
    }
  ],
  "totalElements": 14,
  "totalPages": 1,
  "number": 0,
  "size": 25
}
```

**Errors** — none beyond auth/role and param `TYPE_MISMATCH`.

---

## Per-user record

### GET /api/v1/admin/safety/users/{userId}/record
The 360-degree moderation record for one user (workbench right pane).

**Access**: `ADMIN`, `MODERATOR` (class level).

**Request body** — None.

**Response** — `200 OK`. Key order: `activeStrikes`, `strikes`, `reportsAgainst`,
`reportsFiled`. `strikes` is the full ledger (active and expired, newest first, raw
[`UserStrike`](#shared-shapes) rows); `reportsAgainst` are USER-target reports about this user
and `reportsFiled` the reports this user submitted — both as
[`AdminReportRow`](#shared-shapes) arrays, newest first, capped at the latest 50 each (no page
wrapper).

```json
{
  "activeStrikes": 2,
  "strikes": [
    {
      "id": "5f2b7d9c-1e3a-4c5b-9d7e-2f4a6b8c0d1e",
      "userId": "0b2c4d6e-8f01-4a23-b456-7890acbde001",
      "reportId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "reason": "Report actioned: CONTENT_REMOVED",
      "issuedAt": "2026-08-05T10:02:11.654321",
      "expiresAt": "2026-11-03T10:02:11.654321",
      "active": true
    }
  ],
  "reportsAgainst": [
    { "id": "b1a2c3d4-0000-4a7b-8c9d-0e1f2a3b4c5d", "reporterId": "c2b3a4d5-1111-4a7b-8c9d-0e1f2a3b4c5d", "targetType": "USER", "targetRef": "0b2c4d6e-8f01-4a23-b456-7890acbde001", "reason": "IMPERSONATION", "state": "SUBMITTED", "resolution": "NONE", "groupKey": "0b2c4d6e-8f01-4a23-b456-7890acbde001:IMPERSONATION", "createdAt": "2026-08-06T18:20:00.000000" }
  ],
  "reportsFiled": []
}
```

**Errors** — none beyond auth/role (an unknown `userId` returns empty collections and
`activeStrikes: 0`).

---

## Consent & blocks/mutes aggregates

### GET /api/v1/admin/safety/users/{userId}/consent
Read-only compliance evidence — consent grants/revocations. Never editable from any admin
surface. Two modes: point-read of one scope, or the paged history.

**Access**: `ADMIN`, `MODERATOR` (class level).

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `scope` | string | — | Consent scope, e.g. `CONTACTS`, `LOCATION`, `PHOTOS` (uppercased). Present → point-read mode. |
| `page` | int | `0` | History mode only. |
| `size` | int | `25` | History mode only; clamped to `[1, 100]`; sort ignored — fixed `occurredAt DESC`. |

**Request body** — None.

**Response** — `200 OK`.

With `scope` (two keys, order not guaranteed — built via `Map.of`; `granted` is the latest
event's value, `false` when no event exists):

```json
{
  "scope": "CONTACTS",
  "granted": true
}
```

Without `scope` — `Page<ConsentEvent>` (raw entity, nulls included; abbreviated page):

```json
{
  "content": [
    {
      "id": "d4c3b2a1-9e8f-4d3c-2b1a-0f9e8d7c6b5a",
      "userId": "0b2c4d6e-8f01-4a23-b456-7890acbde001",
      "scope": "CONTACTS",
      "granted": false,
      "appVersion": "ios-2.14.0",
      "occurredAt": "2026-08-01T08:30:12.000000"
    }
  ],
  "totalElements": 7,
  "totalPages": 1,
  "number": 0,
  "size": 25
}
```

**Errors** — none beyond auth/role.

### GET /api/v1/admin/safety/stats/blocks
Population-level block/restriction aggregates (no report needs to exist — most-blocked is a raw
abuse signal).

**Access**: `ADMIN`, `MODERATOR` (class level).

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `days` | int | `30` | Window for the per-day series; clamped to `[1, 365]`. |
| `top` | int | `20` | Size of `mostBlockedUsers`; clamped to `[1, 100]`. |

**Request body** — None.

**Response** — `200 OK`. Top-level key order as shown; keys inside the array items are built via
`Map.of` (order not guaranteed). `day` is a stringified SQL date; per-day series are ascending by
day, `mostBlockedUsers` descending by count.

```json
{
  "totalBlocks": 1834,
  "totalRestrictions": 412,
  "blocksPerDay": [
    { "day": "2026-08-05", "count": 12 },
    { "day": "2026-08-06", "count": 9 }
  ],
  "restrictionsPerDay": [
    { "day": "2026-08-05", "count": 3 }
  ],
  "mostBlockedUsers": [
    { "userId": "0b2c4d6e-8f01-4a23-b456-7890acbde002", "inboundBlocks": 57 }
  ]
}
```

**Errors** — none beyond auth/role and param `TYPE_MISMATCH`.

---

## Stats

### GET /api/v1/admin/safety/analytics
Moderation KPI rollup: queue depth, reason/state distributions, appeal reversal rate, strike
counters.

**Access**: `ADMIN`, `MODERATOR` (class level).

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `from` | ISO date-time | now − 30d | e.g. `2026-07-08T00:00:00`. Bounds `byReason` only. |
| `to` | ISO date-time | now | Bounds `byReason` only. |

**Request body** — None.

**Response** — `200 OK`. Key order as shown. `byReason` is windowed by `from`/`to`, descending by
count; `byState`, `openQueue` (`SUBMITTED`+`TRIAGED`), `appealBacklog` (`APPEALED`),
`strikesActive` and `strikesIssued30d` are all-time/now-relative regardless of the window.
`reversalRate` = `REVERSED / (UPHELD + REVERSED) × 100`, one decimal, `0.0` when no appeal has
been decided.

```json
{
  "from": "2026-07-08T00:00:00",
  "to": "2026-08-07T09:00:00",
  "openQueue": 12,
  "appealBacklog": 3,
  "byReason": {
    "HARASSMENT": 41,
    "SPAM": 17,
    "OTHER": 5
  },
  "byState": {
    "SUBMITTED": 9,
    "TRIAGED": 3,
    "ACTIONED": 45,
    "DISMISSED": 12,
    "APPEALED": 3,
    "UPHELD": 6,
    "REVERSED": 2
  },
  "reversalRate": 25.0,
  "strikesActive": 14,
  "strikesIssued30d": 9
}
```

**Errors** — none beyond auth/role and param `TYPE_MISMATCH` (malformed `from`/`to`).

---

## Audit reads

All reads return bare JSON arrays of [`AuditLogResponse`](#shared-shapes) (no page wrapper) in
newest-first order (Cassandra clustering `created_at DESC`), cursor-paginated: fetch with no
`cursor`, then pass the `createdAt` of the last row you received to get the strictly-older next
page; an empty array means the end. Rows expire at the table's 180-day TTL.

### GET /api/v1/admin/audit
Filtered audit search, scoped to one user (Cassandra needs a partition — `userId` is effectively
required; use the SSE stream for a global live view).

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST` (class level).

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `userId` | UUID | — | **Required in practice** — absent → `400` with an **empty body** (a known envelope escape: `ResponseEntity.badRequest().build()`, no JSON). |
| `operation` | `AuditOperation` | — | Exact-match filter. |
| `outcome` | `AuditOutcome` | — | Exact-match filter. |
| `from` | ISO date-time | — | Inclusive lower bound on `createdAt` (interpreted as UTC). |
| `to` | ISO date-time | — | Inclusive upper bound on `createdAt` (interpreted as UTC). |
| `pageSize` | int | `50` | Passed straight to the Cassandra `LIMIT` (not clamped). |
| `cursor` | ISO date-time | — | `createdAt` of the last row from the previous page; returns strictly-older rows. |

**Request body** — None.

**Response** — `200 OK`, array of [`AuditLogResponse`](#shared-shapes). The
`operation`/`outcome`/`from`/`to` filters are applied **in-memory to the fetched slice** — a page
can come back with fewer than `pageSize` rows (even zero) while older matching rows still exist;
keep advancing `cursor` until the raw slice is exhausted (empty array).

```json
[
  {
    "id": "e29b2c31-41d4-4716-a655-446655440000",
    "userId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
    "username": "mod.sara",
    "operation": "UPDATE",
    "outcome": "SUCCESS",
    "resourceType": "Report",
    "resourceId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "httpMethod": "POST",
    "path": "/api/v1/admin/safety/reports/7c9e6679-7425-40de-944b-e07fc1f90ae7/action",
    "statusCode": 200,
    "durationMs": 41,
    "ipAddress": "203.0.113.7",
    "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) …",
    "summary": "ADMIN_REPORT_ACTION — CONTENT_REMOVED — verified",
    "createdAt": "2026-08-05T10:02:11.654321"
  }
]
```

**Errors**
- 400 (empty body, no envelope) — `userId` missing.
- `TYPE_MISMATCH` — 400 — malformed `userId`/`operation`/`outcome`/`from`/`to`/`cursor`.

### GET /api/v1/admin/audit/users/{userId}
Per-user audit history ("what has user U done lately") — same partition as the search endpoint,
without the in-memory filters.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST` (class level).

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `userId` | UUID (path) | — | Partition key. Unknown user → empty array. |
| `pageSize` | int | `50` | Passed straight to the Cassandra `LIMIT` (not clamped). |
| `cursor` | ISO date-time | — | Strictly-older pagination as above. |

**Request body** — None.

**Response** — `200 OK`, array of [`AuditLogResponse`](#shared-shapes) (same item shape as
above).

**Errors** — none beyond auth/role and param `TYPE_MISMATCH`.

### GET /api/v1/admin/audit/resources/{resourceType}/{resourceId}
"What happened to this resource" — the `audit_log_by_resource` pivot. The only audit view that
includes anonymous traffic: rows without a principal simply omit `userId`/`username`
(`NON_NULL`).

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST` (class level).

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `resourceType` | string (path) | — | Exact stored type string as recorded by the audit pipeline (e.g. `Report`, `UserStrike`, `User`, `AuditStream`) — case-sensitive partition component. |
| `resourceId` | UUID (path) | — | Partition component. |
| `pageSize` | int | `50` | Clamped to `[1, 100]` (unlike the other two reads). |
| `cursor` | ISO date-time | — | Strictly-older pagination as above. |

**Request body** — None.

**Response** — `200 OK`, array of [`AuditLogResponse`](#shared-shapes); `resourceType`/
`resourceId` are echoed from the path into every row. Example anonymous row:

```json
[
  {
    "id": "f30c3d42-52e5-4827-b766-557766551111",
    "operation": "READ",
    "outcome": "CLIENT_ERROR",
    "resourceType": "Report",
    "resourceId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "httpMethod": "GET",
    "path": "/api/v1/admin/safety/reports/7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "statusCode": 401,
    "durationMs": 3,
    "ipAddress": "198.51.100.24",
    "userAgent": "curl/8.6.0",
    "errorCode": "AUTH_UNAUTHORIZED",
    "createdAt": "2026-08-05T10:01:58.004000"
  }
]
```

**Errors** — none beyond auth/role and param `TYPE_MISMATCH` (malformed `resourceId`/`cursor`).

---

## Audit SSE stream

### GET /api/v1/admin/audit/stream
Realtime firehose of audit rows (`Content-Type: text/event-stream`). Fan-out is per-instance but
fed through Redis pub/sub, so events from every app instance arrive on any subscription. Multiple
admins and multiple tabs per admin may subscribe; there is no server-side timeout and no
`Last-Event-ID` replay — on reconnect you resume live-only (backfill gaps via the read
endpoints). Subscribing itself writes an audit row (`READ` on `AuditStream`, summary
`ADMIN_AUDIT_STREAM_SUBSCRIBE`) because `*/stream` paths are excluded from the request
interceptor.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST` (class level). Authenticate with the
Bearer header or `?token=<jwt>` (the standard SSE token query param — required for the browser
`EventSource` API, which cannot set headers).

**Params**

| Name | Type | Default | Constraints |
|---|---|---|---|
| `token` | string (query) | — | JWT, alternative to the `Authorization` header. |

**Request body** — None.

**Response** — `200 OK`, SSE stream. Named events:

| Event | When | Payload |
|---|---|---|
| `connected` | Once, immediately on subscribe | `{"adminId": "...", "timestamp": "..."}` |
| `audit` | Every audit row written anywhere in the cluster | One [`AuditLogResponse`](#shared-shapes) (`NON_NULL`) |
| `heartbeat` | Every 25 s | `{"timestamp": "..."}` |

Example `audit` event on the wire:

```
event: audit
data: {"id":"e29b2c31-41d4-4716-a655-446655440000","userId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","username":"mod.sara","operation":"UPDATE","outcome":"SUCCESS","resourceType":"Report","resourceId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","httpMethod":"POST","path":"/api/v1/admin/safety/reports/7c9e6679-7425-40de-944b-e07fc1f90ae7/action","statusCode":200,"durationMs":41,"ipAddress":"203.0.113.7","userAgent":"Mozilla/5.0 …","summary":"ADMIN_REPORT_ACTION — CONTENT_REMOVED — verified","createdAt":"2026-08-05T10:02:11.654321"}
```

**Errors**
- `AUTH_UNAUTHORIZED` — 401 — no authenticated principal ("Admin authentication required").
- 403 — role missing (standard access-denied envelope).
