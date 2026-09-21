# Pages — shell, users, activity & discovery

Part of the [admin dashboard frontend guide](README.md).
Legend: **SU** = step-up required (§[auth-and-roles.md](auth-and-roles.md)) ·
roles in the *Who* column are the `hasRole`/`hasAnyRole` grants as coded ·
list endpoints paginate per [conventions.md](conventions.md).
Wire-level request/response JSON: [../api/](../api/README.md).

Section docs: [../users/](../users/README.md).

---

### 4.1 Shell (all staff)

- Subscribe **once** to `GET /api/v1/admin/audit/stream?token=…` (§5.5) and fan
  events out to widgets client-side (filter by `operation`, `path` prefix,
  `resourceType`). Rolling audit ticker in the top strip; an
  "admin actions" feed is the same stream filtered to paths starting
  `/api/v1/admin/`.
- Health chips from `GET /api/v1/admin/ops/health` (ADMIN nav only).
- Nav = §7 tree, filtered by the §1.2 matrix.

### 4.2 Users & roles — base `/api/v1/admin/users`

`AdminUserController`. Class-level ADMIN; the read grants widen per row.
Section docs: [users-roles.md](../users/directory-and-roles.md), [user-administration.md](../users/administration.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /api/v1/admin/users` | `q, role, status, verified, from, to` + pageable | — | ADMIN, MODERATOR, SUPPORT |
| `GET …/analytics` | `window` | — | + ANALYST |
| `GET …/{userId}` | — | — | ADMIN, MODERATOR, SUPPORT |
| `GET …/{userId}/pii` | — | **SU** | ADMIN |
| `GET …/{userId}/sessions` · `…/{userId}/data` | — | — | ADMIN, SUPPORT |
| `GET …/{userId}/login-events` · `/settings-audit` · `/moderation` | pageable | — | ADMIN, MODERATOR, SUPPORT |
| `PATCH …/{userId}/role` | body role | **SU** | ADMIN |
| `POST …/{userId}/disable` · `/enable` · `/lock` · `/unlock` | body `{reason}` | **SU** | ADMIN |
| `DELETE …/{userId}/sessions/{sid}` · `POST …/{userId}/sessions/revoke-all` | — | **SU** | ADMIN |
| `POST …/{userId}/password/reset` · `/2fa/reset` · `/email/verify` | body `{reason…}` | **SU** | ADMIN |
| `POST /api/v1/admin/users` (create) · `…/bulk` · `…/invite` | body per [user-administration.md](../users/administration.md) | **SU** | ADMIN |
| `POST …/invite/{inviteId}/resend` · `DELETE …/invite/{inviteId}` | — | — | ADMIN |
| `PATCH …/{userId}` (identity edit) | `{fname?,lname?,username?,email?}` | **SU** | ADMIN |
| `POST …/{userId}/deletion/request` · `/deletion/cancel` | body `{reason}` | **SU** | ADMIN |
| `POST …/{userId}/purge/now` · `/purge/hold` | — | **SU** | ADMIN |
| `POST …/{userId}/strikes` | `{reportId?, reason}` | **SU** | ADMIN |
| `POST …/bulk-action` | `{ids[], action, reason}` | **SU** | ADMIN |
| `POST …/{userId}/impersonate` | `{reason}` (≥10 chars) | **SU** | ADMIN (§3) |

UI affordances: **self-protection is server-enforced** — admins cannot
disable/lock/demote themselves and the last ADMIN cannot be demoted
(`LAST_ADMIN` conflict); surface those errors verbatim rather than pre-hiding
buttons. Email edit resets verification. PII stays masked in detail views
until the explicit step-up-gated reveal.

### 4.18 Activity & break-glass — base `/api/v1/admin` (activity routes)

`AdminActivityController` — **ADMIN only**. Doc: [activity-engagement.md](../users/activity-engagement.md).
The per-user reads are **break-glass**: they 403 without an OPEN dual-control
case for that user, on top of step-up, and every read writes a loud audit row.

| Method + path | Key params | SU |
|---------------|-----------|----|
| `POST /api/v1/admin/breakglass/{targetUserId}` | `{kind, reason (10–1000 chars), caseRef?}` — opens PENDING_APPROVAL | **SU** |
| `POST …/breakglass/cases/{caseId}/approve` | **must be a different admin** than the opener (403 `DUAL_CONTROL_REQUIRED`) | **SU** |
| `POST …/breakglass/cases/{caseId}/close` · `GET …/breakglass/cases` | — | — |
| `GET …/users/{userId}/activity` · `/activity/summary` · `/reels/watched` | `type(s), from, to, window` + pageable — **403 without an open case** | **SU** |
| `GET …/users/{userId}/activity/export` | `from, to, format` (JSON/CSV) | **SU** |
| `POST …/users/{userId}/activity/erase` | `{type?, reason}` — the one routine action; GDPR erasure, capped batches (`note`) | **SU** |

UI: model this as a **case workflow**, not a data browser — open case →
second admin approves → reads unlock → close case. Show opener/approver on
each case and grey out Approve for the opener.

### 4.19 Knowledge vocabulary — base `/api/v1/admin/knowledge`

`AdminKnowledgeController` — **ADMIN only**. Doc: [knowledge-vocabulary.md](../content/knowledge-vocabulary.md).

| Method + path | SU |
|---------------|----|
| `GET /topics` · `GET /madhhabs` (trilingual labels + usage counts) | — |
| `POST /topics` · `POST /madhhabs` (`{nameEn, nameAr, nameCkb}`) | **SU** |
| `PATCH /topics/{id}` · `PATCH /madhhabs/{id}` | **SU** |
| `POST /topics/{id}/retire` · `POST /madhhabs/{id}/retire` (soft-retire — never hard delete) | **SU** |
| `POST /cache/evict` | — |

### 4.20 Discovery & PYMK privacy — base `/api/v1/admin`

`AdminDiscoveryController` — **ADMIN only**. Doc: [discovery-pymk-privacy.md](../users/discovery-privacy.md).

| Method + path | SU |
|---------------|----|
| `POST /users/{userId}/suggestions/recompute` | — |
| `GET /users/{userId}/suggestions` (PII read) | **SU** |
| `GET /discovery/contact-sync/stats` (`window`) | — |
| `GET /discovery/contact-sync/compliance` (PII read) | **SU** |
| `POST /users/{userId}/contact-hashes/purge` (GDPR erasure) | **SU** — danger zone |
| `GET /users/{userId}/discovery` (flags + QR-token status) | — |
| `POST /users/{userId}/qr/rotate` | **SU** |

---
