# Admin API Reference — Users, Impersonation & Step-up

Complete request/response reference for the admin user-administration surface: `AdminUserController`
(`/api/v1/admin/users`, 32 routes), `AdminImpersonationController` (`/api/v1/admin/impersonation`),
and the step-up arming endpoint of `SecurityController` (`POST /api/v1/security/step-up`) that every
`@RequiresStepUp` route below depends on. Concepts and role semantics live in
[users-roles.md](../users-roles.md) and [user-administration.md](../user-administration.md); dashboard
integration in [frontend-dashboard-guide.md](../frontend-dashboard-guide.md). Every error arrives in
the canonical `ApiErrorResponse` envelope — see
[frontend-error-handling.md](../../errors/frontend-error-handling.md).

## Conventions

- **Auth**: `Authorization: Bearer <access-token>`. `AdminUserController` is class-gated
  `@PreAuthorize("hasRole('ADMIN')")`; individual read endpoints widen access via method-level
  `hasAnyRole` (noted per endpoint). `/api/v1/admin/**` is additionally gated at the filter chain
  (double-gated). Missing role → `403 ACCESS_DENIED`; missing/expired token → `401`.
- **Step-up**: endpoints marked **Step-up: yes** carry `@RequiresStepUp`. `StepUpGuardInterceptor`
  requires a fresh Redis marker `stepup:{adminId}`, armed via [`POST /api/v1/security/step-up`](#post-apiv1securitystep-up).
  Marker TTL is 300 s by default (`app.security.step-up.ttl-seconds` / `STEP_UP_TTL_SECONDS`).
  Absent marker → `403 STEP_UP_REQUIRED` ("This action requires you to confirm your identity.").
- **Null omission**: Jackson runs with `default-property-inclusion: non_null` (and the admin DTOs are
  additionally `@JsonInclude(NON_NULL)`), so **null fields are omitted from responses entirely**.
  Examples below only show a field as absent where it is commonly null.
- **Timestamps**: `LocalDateTime` fields serialize as UTC ISO-8601 without zone suffix
  (`"2026-08-06T18:22:10"`). The impersonation grant's `expiresAt` is an `Instant`
  (`"2026-08-07T12:15:00Z"`).
- **Paging**: standard Spring params `page` (0-based), `size` (default 25), `sort`. Page size is
  clamped server-side to 1–100 (`Pages.clamp`). Page responses use the Spring `Page` envelope —
  examples show `content`/`totalElements`/`totalPages`/`number`/`size`; the remaining standard Spring
  pageable fields (`pageable`, `sort`, `first`, `last`, `numberOfElements`, `empty`) are also present.
- **Validation**: `@Valid` body failures → `400 VALIDATION_FAILED` with `fieldErrors[]`; unparseable
  or missing-required JSON body → `400 MALFORMED_JSON`.
- **Audit**: every mutation writes an `ADMIN_*` row via `AdminAuditor` (e.g. `ADMIN_USER_DISABLE`,
  `ADMIN_PASSWORD_RESET`); even the PII read audits as `ADMIN_PII_REVEAL`.

### Route index

| Method | Path | Roles | Step-up |
|---|---|---|---|
| GET | `/api/v1/admin/users` | ADMIN, MODERATOR, SUPPORT | no |
| GET | `/api/v1/admin/users/{userId}` | ADMIN, MODERATOR, SUPPORT | no |
| GET | `/api/v1/admin/users/{userId}/pii` | ADMIN | yes |
| POST | `/api/v1/admin/users` | ADMIN | yes |
| PATCH | `/api/v1/admin/users/{userId}` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/password/reset` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/2fa/reset` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/email/verify` | ADMIN | yes |
| PATCH | `/api/v1/admin/users/{userId}/role` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/disable` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/enable` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/lock` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/unlock` | ADMIN | yes |
| GET | `/api/v1/admin/users/{userId}/sessions` | ADMIN, SUPPORT | no |
| DELETE | `/api/v1/admin/users/{userId}/sessions/{sid}` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/sessions/revoke-all` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/deletion/request` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/deletion/cancel` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/purge/now` | ADMIN | yes |
| POST | `/api/v1/admin/users/{userId}/purge/hold` | ADMIN | yes |
| GET | `/api/v1/admin/users/{userId}/login-events` | ADMIN, MODERATOR, SUPPORT | no |
| GET | `/api/v1/admin/users/{userId}/settings-audit` | ADMIN, MODERATOR, SUPPORT | no |
| GET | `/api/v1/admin/users/{userId}/moderation` | ADMIN, MODERATOR, SUPPORT | no |
| GET | `/api/v1/admin/users/{userId}/data` | ADMIN, SUPPORT | no |
| GET | `/api/v1/admin/users/analytics` | ADMIN, MODERATOR, SUPPORT, ANALYST | no |
| POST | `/api/v1/admin/users/{userId}/strikes` | ADMIN | yes |
| POST | `/api/v1/admin/users/bulk` | ADMIN | yes |
| POST | `/api/v1/admin/users/bulk-action` | ADMIN | yes |
| POST | `/api/v1/admin/users/invite` | ADMIN | yes |
| POST | `/api/v1/admin/users/invite/{inviteId}/resend` | ADMIN | no |
| DELETE | `/api/v1/admin/users/invite/{inviteId}` | ADMIN | no |
| POST | `/api/v1/admin/users/{userId}/impersonate` | ADMIN | yes |
| DELETE | `/api/v1/admin/impersonation` | ADMIN | no |
| POST | `/api/v1/security/step-up` | any authenticated user | — |

---

## Directory & detail

### GET /api/v1/admin/users
Paged, filterable user directory (`AdminUserRow` rows; email is **masked** — use the PII endpoint for
the raw value).

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`. Step-up: no.

**Params**

| Name | In | Type | Default | Constraints |
|---|---|---|---|---|
| `q` | query | string | — | free text; when present, results come from ranked FTS (top 200) and the other filters apply in-memory |
| `role` | query | enum | — | `USER` \| `RESEARCHER` \| `SCHOLAR` \| `MODERATOR` \| `SUPPORT` \| `ANALYST` \| `ADMIN` |
| `status` | query | string | — | `ACTIVE` \| `DISABLED` \| `DELETED` (with `q` also `LOCKED`) |
| `verified` | query | boolean | — | email-verified filter |
| `from` | query | ISO date-time | — | `createdAt >= from` |
| `to` | query | ISO date-time | — | `createdAt <= to` |
| `page`/`size`/`sort` | query | int/int/string | 0 / 25 / — | size clamped 1–100 |

**Request body**: None.

**Response** — `200 OK`, `Page<AdminUserRow>`:

```json
{
  "content": [
    {
      "id": "8c1f2a3b-4d5e-4f60-9a7b-1c2d3e4f5a6b",
      "username": "alice.hassan",
      "displayName": "Alice Hassan",
      "email": "a***e@example.com",
      "avatarUrl": "https://cdn.example.com/media/avatars/8c1f2a3b.webp",
      "role": "RESEARCHER",
      "badges": [
        { "type": "VERIFIED_RESEARCHER", "label": "Researcher", "colorKey": "purple", "icon": "ti-flask", "priority": 2 }
      ],
      "emailVerified": true,
      "phoneVerified": false,
      "twoFactorEnabled": true,
      "enabled": true,
      "locked": false,
      "status": "ACTIVE",
      "createdAt": "2025-11-03T09:12:44",
      "lastLoginAt": "2026-08-06T18:22:10"
    }
  ],
  "totalElements": 18234,
  "totalPages": 730,
  "number": 0,
  "size": 25
}
```

Standard Spring pageable fields are also present. `AdminUserRow` notes: `status` is derived
(`DELETED` > `LOCKED` > `DISABLED` > `ACTIVE`); `avatarUrl`, `lastLoginAt`, `deletedAt` omitted when
null; `badges` is `[]` for role `USER` (only `VERIFIED_SCHOLAR`/`VERIFIED_RESEARCHER` exist).

**Errors**
- `INVALID_STATUS_FILTER` — 400 — unknown `status` value (allowed set differs with/without `q`, see table).

### GET /api/v1/admin/users/{userId}
Full profile/detail read model for one user (`AdminUserDetail`).

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`. Step-up: no.

**Params**

| Name | In | Type | Constraints |
|---|---|---|---|
| `userId` | path | UUID | required |

**Request body**: None.

**Response** — `200 OK`:

```json
{
  "user": {
    "id": "8c1f2a3b-4d5e-4f60-9a7b-1c2d3e4f5a6b",
    "username": "alice.hassan",
    "displayName": "Alice Hassan",
    "email": "a***e@example.com",
    "avatarUrl": "https://cdn.example.com/media/avatars/8c1f2a3b.webp",
    "role": "RESEARCHER",
    "badges": [
      { "type": "VERIFIED_RESEARCHER", "label": "Researcher", "colorKey": "purple", "icon": "ti-flask", "priority": 2 }
    ],
    "emailVerified": true,
    "phoneVerified": false,
    "twoFactorEnabled": true,
    "enabled": true,
    "locked": false,
    "status": "ACTIVE",
    "createdAt": "2025-11-03T09:12:44",
    "lastLoginAt": "2026-08-06T18:22:10"
  },
  "fname": "Alice",
  "lname": "Hassan",
  "bio": "Hadith sciences researcher.",
  "location": "Erbil",
  "academicTitle": "PhD Candidate",
  "institutionName": "Salahaddin University",
  "websiteUrl": "https://alice.example.com",
  "orcidId": "0000-0002-1825-0097",
  "preferredLanguage": "EN",
  "timezone": "Asia/Baghdad",
  "stats": {
    "postCount": 128,
    "reelCount": 6,
    "researchCount": 11,
    "questionCount": 4,
    "followerCount": 940,
    "followingCount": 213
  },
  "storage": {
    "totalBytes": 73400320,
    "byType": { "IMAGE": 52428800, "VIDEO": 20971520 }
  },
  "lastAction": "UPDATE",
  "actionNote": "Identity edited by admin: username alice → alice.hassan",
  "updatedAt": "2026-08-01T14:03:27"
}
```

Notes: `preferredLanguage` ∈ `AR`/`CKB`/`EN`; `storage.byType` keys are `MediaAssetType` names
(`IMAGE`, `VIDEO`, `AUDIO`, `FILM`, `VIDEO_CLIP`); `stats`/`storage` are **omitted** if the
stats/storage backends are unavailable (degrade, not fail); a `deletion` object (`AdminDeletionState`,
shape in [Lifecycle](#lifecycle)) appears only if a deletion request exists.

**Errors**
- `USER_NOT_FOUND` — 404 — no user row with that id.

### GET /api/v1/admin/users/{userId}/pii
Raw PII reveal (unmasked email/phone). Audited as `ADMIN_PII_REVEAL` on every call.

**Access**: `ADMIN` (class-level only — MODERATOR/SUPPORT never see raw PII). Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body**: None.

**Response** — `200 OK`:

```json
{
  "email": "alice@example.com",
  "emailVerifiedAt": "2025-11-03T09:15:02",
  "phoneE164": "+9647501234567",
  "phoneVerifiedAt": "2026-01-19T10:44:31"
}
```

Unset fields (`phoneE164`, `*VerifiedAt`) are omitted when null.

**Errors**
- `STEP_UP_REQUIRED` — 403 — step-up window not armed.
- `USER_NOT_FOUND` — 404.

---

## Provisioning

### POST /api/v1/admin/users
Create a single account. Reuses the one provisioning path (`UserProvisioningService`) so
admin-created accounts are structurally identical to self-signups; default role is `USER`.

**Access**: `ADMIN`. Step-up: **yes**.

**Request body** (`AdminCreateUserRequest`):

```json
{
  "fname": "Alice",
  "lname": "Hassan",
  "username": "alice.hassan",
  "email": "alice@example.com",
  "role": "RESEARCHER",
  "temporaryPassword": "Str0ngTempPass1",
  "sendInvite": false,
  "markEmailVerified": true
}
```

Constraints: `fname`/`lname` required, ≤ 80; `username` required, 3–50; `email` required, valid,
≤ 255; `role` optional (default `USER`); `temporaryPassword` optional, 8–128;
`sendInvite`/`markEmailVerified` optional booleans. You must supply **either** `temporaryPassword`
**or** `sendInvite: true` (otherwise the account could never log in). With `sendInvite: true` a
set-password invite email is sent (7-day link).

**Response** — `201 Created`, `AdminUserDetail` (same shape as
[GET /{userId}](#get-apiv1adminusersuserid)).

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `VALIDATION_FAILED` — 400 — bean-validation failures.
- `PASSWORD_OR_INVITE_REQUIRED` — 400 — neither `temporaryPassword` nor `sendInvite=true`.
- `USER_DUPLICATE` — 409 — email or username already exists (`details` carries `resource`/`field`/`value`).

### PATCH /api/v1/admin/users/{userId}
Edit core identity fields. Only non-null body fields are applied; changing `email` clears
`emailVerifiedAt` (recovery vector changed — user must re-verify).

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body** (`AdminEditUserRequest`, all fields optional):

```json
{
  "fname": "Alice",
  "lname": "Hassan",
  "username": "alice.h",
  "email": "alice.h@example.com"
}
```

Constraints: `fname`/`lname` ≤ 80; `username` 3–50; `email` valid, ≤ 255. A body that changes
nothing is a no-op returning the current state.

**Response** — `200 OK`, `UserResponse`:

```json
{
  "id": "8c1f2a3b-4d5e-4f60-9a7b-1c2d3e4f5a6b",
  "fname": "Alice",
  "lname": "Hassan",
  "username": "alice.h",
  "email": "alice.h@example.com",
  "role": "RESEARCHER",
  "badges": [
    { "type": "VERIFIED_RESEARCHER", "label": "Researcher", "colorKey": "purple", "icon": "ti-flask", "priority": 2 }
  ],
  "isEmailVerified": false,
  "profile": {
    "displayName": "Alice Hassan",
    "avatarUrl": "https://cdn.example.com/media/avatars/8c1f2a3b.webp",
    "profileBio": "Hadith sciences researcher.",
    "location": "Erbil",
    "academicTitle": "PhD Candidate",
    "institutionName": "Salahaddin University",
    "websiteUrl": "https://alice.example.com",
    "specializations": [
      { "topicId": 3, "nameEn": "Hadith", "nameAr": "الحديث", "nameCkb": "فەرموودە" }
    ],
    "followerCount": 940,
    "followingCount": 213,
    "researchCount": 11,
    "fatwaCount": 0,
    "isForHire": false,
    "isProfileLocked": false,
    "contentLanguage": "EN",
    "profileViews": 0,
    "links": [],
    "contacts": [],
    "attachments": []
  },
  "createdAt": "2025-11-03T09:12:44"
}
```

`UserResponse` notes: email is **unmasked** here; `profileViews` is always `0` on admin reads
(private-fields flag off); `coverImageUrl`, `selfDescriber`, `madhhabId`, `madhhabName` omitted when
null; `links`/`contacts` contain only entries marked public.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `VALIDATION_FAILED` — 400.
- `USER_NOT_FOUND` — 404.
- `USER_DUPLICATE` — 409 — new username or email already taken.

---

## Credentials & 2FA

### POST /api/v1/admin/users/{userId}/password/reset
Set a temporary password (provided or server-generated 14-char) and revoke every live session. The
plaintext temporary password is returned **once**; the user is notified in-app.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body** — optional (`AdminPasswordResetRequest`); send `{}` or omit entirely to
auto-generate:

```json
{ "temporaryPassword": "N3wTempPass2026" }
```

Constraint: `temporaryPassword` 8–128 when present.

**Response** — `200 OK`:

```json
{
  "temporaryPassword": "N3wTempPass2026",
  "sessionsRevoked": 3
}
```

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `SELF_ACTION_FORBIDDEN` — 403 — admin targeting their own account (use change-password).
- `USER_NOT_FOUND` — 404.
- `VALIDATION_FAILED` — 400 — temporary password outside 8–128.

### POST /api/v1/admin/users/{userId}/2fa/reset
Disable TOTP 2FA, clear recovery codes, revoke all sessions, notify the user. For account-recovery
cases ("lost my phone").

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body** — optional (`AdminReasonRequest`):

```json
{ "reason": "Verified recovery ticket #4711 — user lost authenticator device" }
```

Constraint: `reason` ≤ 500.

**Response** — 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `SELF_ACTION_FORBIDDEN` — 403 — use your own security settings instead.
- `USER_NOT_FOUND` — 404.

### POST /api/v1/admin/users/{userId}/email/verify
Mark the user's email as verified (stamps `emailVerifiedAt` if not already set). Idempotent.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body**: None.

**Response** — 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `USER_NOT_FOUND` — 404.

---

## State machine

### PATCH /api/v1/admin/users/{userId}/role
Promote/demote along the role ladder. Same-role request is a no-op; demoting the last `ADMIN` is
rejected.

**Access**: `ADMIN` (the only role that can grant roles). Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body** (`AdminChangeRoleRequest`):

```json
{
  "role": "SCHOLAR",
  "reason": "Credentials verified by the scholarship committee"
}
```

Constraints: `role` required (`USER`/`RESEARCHER`/`SCHOLAR`/`MODERATOR`/`SUPPORT`/`ANALYST`/`ADMIN`);
`reason` optional, ≤ 500 (recorded in the audit trail).

**Response** — `200 OK`, `UserResponse` (same shape as [PATCH /{userId}](#patch-apiv1adminusersuserid);
`role` and `badges` reflect the new role).

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `VALIDATION_FAILED` / `INVALID_INPUT` — 400 — missing `role`.
- `SELF_ACTION_FORBIDDEN` — 403 — cannot change your own role.
- `USER_NOT_FOUND` — 404.
- `LAST_ADMIN` — 409 — demoting the only remaining ADMIN.

### POST /api/v1/admin/users/{userId}/disable
Disable the account (login blocked), revoke all sessions, notify the user. Idempotent.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body** — optional (`AdminReasonRequest`, `reason` ≤ 500):

```json
{ "reason": "Spam wave — pending investigation" }
```

**Response** — 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `SELF_ACTION_FORBIDDEN` — 403 — cannot disable yourself.
- `USER_NOT_FOUND` — 404.

### POST /api/v1/admin/users/{userId}/enable
Re-enable a disabled account; notifies the user.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body**: None.

**Response** — 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `USER_NOT_FOUND` — 404.

### POST /api/v1/admin/users/{userId}/lock
Lock the account (`accountNonLocked=false`) and revoke all sessions. Unlike disable, no user
notification is sent.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body** — optional (`AdminReasonRequest`, `reason` ≤ 500):

```json
{ "reason": "Credential-stuffing activity detected" }
```

**Response** — 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `SELF_ACTION_FORBIDDEN` — 403 — cannot lock yourself.
- `USER_NOT_FOUND` — 404.

### POST /api/v1/admin/users/{userId}/unlock
Clear the lock flag.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body**: None.

**Response** — 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `USER_NOT_FOUND` — 404.

---

## Sessions

### GET /api/v1/admin/users/{userId}/sessions
Active (non-expired) refresh-token sessions for the user.

**Access**: `ADMIN`, `SUPPORT`. Step-up: no.

**Params**: `userId` — path, UUID, required.

**Request body**: None.

**Response** — `200 OK`, `List<AdminSessionRow>`:

```json
[
  {
    "sid": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "deviceName": "Chrome on macOS",
    "platform": "web",
    "ip": "203.0.113.42",
    "lastSeenAt": "2026-08-06T18:22:10",
    "createdAt": "2026-08-01T07:15:00",
    "expiresAt": "2026-08-31T07:15:00",
    "trusted": true,
    "revoked": false
  }
]
```

`revokedAt` (and other null fields) omitted.

**Errors**
- `USER_NOT_FOUND` — 404.

### DELETE /api/v1/admin/users/{userId}/sessions/{sid}
Revoke one session: refresh token revoked plus the `sid` denylisted for the access-token TTL, so the
live access token dies too.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required; `sid` — path, UUID, required.

**Request body**: None.

**Response** — 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `USER_NOT_FOUND` — 404.
- `SESSION_NOT_FOUND` — 404 — unknown `sid`, or the session belongs to a different user.

### POST /api/v1/admin/users/{userId}/sessions/revoke-all
Revoke every live session (refresh tokens + sid denylist).

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body**: None.

**Response** — `200 OK`:

```json
{ "revoked": 3 }
```

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `USER_NOT_FOUND` — 404.

---

## Lifecycle

All four endpoints return the same `AdminUserDataResponse` snapshot (see
[GET /{userId}/data](#get-apiv1adminusersuseriddata)) reflecting the post-action state. State
machine: `PENDING_DELETION → CANCELLED | PURGED` (`ANONYMIZED` also exists in the enum); grace window
is 30 days.

### POST /api/v1/admin/users/{userId}/deletion/request
Start the deletion state machine on the user's behalf: soft-delete (immediate invisibility), all
sessions revoked, 30-day grace before purge.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body** — optional (`AdminReasonRequest`, `reason` ≤ 500):

```json
{ "reason": "GDPR erasure request via support ticket #5102" }
```

**Response** — `200 OK`, `AdminUserDataResponse`:

```json
{
  "exportJobs": [],
  "deletion": {
    "status": "PENDING_DELETION",
    "requestedAt": "2026-08-07T12:00:00",
    "purgeAfter": "2026-09-06T12:00:00"
  },
  "tombstoned": false
}
```

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `SELF_ACTION_FORBIDDEN` — 403 — cannot delete yourself via the admin surface.
- `USER_NOT_FOUND` — 404.
- `DELETION_PENDING` — 409 — a deletion request is already pending.

### POST /api/v1/admin/users/{userId}/deletion/cancel
Cancel a pending deletion during grace: restores visibility, request becomes `CANCELLED`.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body**: None.

**Response** — `200 OK`, `AdminUserDataResponse` (with `deletion.status: "CANCELLED"` and
`resolvedAt` set).

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `RESOURCE_NOT_FOUND` — 404 — no pending deletion to cancel.

### POST /api/v1/admin/users/{userId}/purge/now
Expedite the purge: runs the full anonymize + GDPR cascade immediately (PII overwritten, per-user
log stores dropped, tombstone written).

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body**: None.

**Response** — `200 OK`, `AdminUserDataResponse` (with `deletion.status: "PURGED"` and
`tombstoned: true`).

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `SELF_ACTION_FORBIDDEN` — 403 — cannot purge yourself.
- `RESOURCE_NOT_FOUND` — 404 — no pending deletion (purge requires `PENDING_DELETION`).

### POST /api/v1/admin/users/{userId}/purge/hold
Extend the grace window (`purgeAfter`) — e.g. for a legal hold.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**

| Name | In | Type | Default | Constraints |
|---|---|---|---|---|
| `userId` | path | UUID | — | required |
| `days` | query | int | 30 | clamped server-side to 1–365; added to the later of `purgeAfter`/now |

**Request body**: None.

**Response** — `200 OK`, `AdminUserDataResponse` (with the extended `deletion.purgeAfter`).

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `RESOURCE_NOT_FOUND` — 404 — no pending deletion to hold.

---

## Tabs / read models

### GET /api/v1/admin/users/{userId}/login-events
Login history (successes and failures), newest first.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`. Step-up: no.

**Params**: `userId` — path, UUID, required; `page`/`size`/`sort` (default size 25, clamped 1–100).

**Request body**: None.

**Response** — `200 OK`, `Page<AdminLoginEventRow>`:

```json
{
  "content": [
    {
      "ip": "203.0.113.42",
      "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
      "method": "PASSWORD",
      "outcome": "SUCCESS",
      "ts": "2026-08-06T18:22:10"
    }
  ],
  "totalElements": 57,
  "totalPages": 3,
  "number": 0,
  "size": 25
}
```

Standard Spring pageable fields are also present. `method` values written by the backend: `PASSWORD`,
`REFRESH`; `outcome`: `SUCCESS`, `FAILED`.

**Errors**
- `USER_NOT_FOUND` — 404.

### GET /api/v1/admin/users/{userId}/settings-audit
Per-user settings change trail.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`. Step-up: no.

**Params**: `userId` — path, UUID, required; `page`/`size`/`sort` (default size 25, clamped 1–100).

**Request body**: None.

**Response** — `200 OK`, `Page<AdminSettingsAuditRow>`:

```json
{
  "content": [
    {
      "settingKey": "privacy.profileVisibility",
      "oldValue": "PUBLIC",
      "newValue": "FOLLOWERS",
      "ip": "203.0.113.42",
      "createdAt": "2026-08-02T11:09:47"
    }
  ],
  "totalElements": 12,
  "totalPages": 1,
  "number": 0,
  "size": 25
}
```

Standard Spring pageable fields are also present.

**Errors**
- `USER_NOT_FOUND` — 404.

### GET /api/v1/admin/users/{userId}/moderation
Moderation summary: strikes plus the latest 50 reports against the user and 50 reports they filed.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`. Step-up: no.

**Params**: `userId` — path, UUID, required.

**Request body**: None.

**Response** — `200 OK`:

```json
{
  "activeStrikes": 1,
  "strikes": [
    {
      "id": "b47ac10b-58cc-4372-a567-0e02b2c3d479",
      "reportId": "0e4b7c11-2f6d-4c3a-9e8b-7a6c5d4e3f2a",
      "reason": "Repeated harassment in comments",
      "active": true,
      "issuedAt": "2026-07-30T16:40:12",
      "expiresAt": "2026-10-28T16:40:12"
    }
  ],
  "reportsAgainst": [
    {
      "id": "0e4b7c11-2f6d-4c3a-9e8b-7a6c5d4e3f2a",
      "targetType": "USER",
      "targetId": "8c1f2a3b-4d5e-4f60-9a7b-1c2d3e4f5a6b",
      "reason": "HARASSMENT",
      "state": "ACTIONED",
      "resolution": "WARNING_ISSUED",
      "createdAt": "2026-07-29T13:02:51"
    }
  ],
  "reportsFiled": []
}
```

Enum values — `targetType`: `USER`/`POST`/`COMMENT`/`RESEARCH`/`QUESTION`/`ANSWER`/`MESSAGE`/`CHANNEL`/`STORY`;
`reason`: `SPAM`/`HARASSMENT`/`HATE_SPEECH`/`MISINFORMATION`/`NUDITY_SEXUAL`/`VIOLENCE`/`IMPERSONATION`/`SELF_HARM`/`COPYRIGHT`/`OTHER`;
`state`: `SUBMITTED`/`TRIAGED`/`ACTIONED`/`DISMISSED`/`APPEALED`/`UPHELD`/`REVERSED`;
`resolution`: `NONE`/`WARNING_ISSUED`/`CONTENT_REMOVED`/`ACCOUNT_SUSPENDED`/`NO_ACTION`.
`strikes[].reportId` omitted when the strike wasn't tied to a report.

**Errors**
- `USER_NOT_FOUND` — 404.

### GET /api/v1/admin/users/{userId}/data
GDPR/data tab: export jobs, deletion state, tombstone flag. No existence check — an unknown id
returns empty jobs, no `deletion`, `tombstoned: false` (or `true` for a purged id).

**Access**: `ADMIN`, `SUPPORT`. Step-up: no.

**Params**: `userId` — path, UUID, required.

**Request body**: None.

**Response** — `200 OK`, `AdminUserDataResponse`:

```json
{
  "exportJobs": [
    {
      "id": "3a9d8e7f-6c5b-4a39-8271-0f1e2d3c4b5a",
      "status": "READY",
      "sizeBytes": 10485760,
      "createdAt": "2026-08-01T10:00:00",
      "readyAt": "2026-08-01T10:04:12",
      "expiresAt": "2026-08-08T10:04:12"
    }
  ],
  "deletion": {
    "status": "PENDING_DELETION",
    "requestedAt": "2026-08-05T09:00:00",
    "purgeAfter": "2026-09-04T09:00:00"
  },
  "tombstoned": false
}
```

`exportJobs[].status` ∈ `PENDING`/`RUNNING`/`READY`/`FAILED`/`EXPIRED`; `deletion.status` ∈
`PENDING_DELETION`/`CANCELLED`/`ANONYMIZED`/`PURGED`; `deletion` omitted when no request was ever
made; `deletion.resolvedAt` omitted while pending.

**Errors**: none endpoint-specific (only the shared auth errors from [Conventions](#conventions)).

---

## Analytics

### GET /api/v1/admin/users/analytics
Aggregate user KPIs: totals, signups, role distribution, verification/2FA percentages, deletion
pipeline, signups-per-day series, close-friends adoption, sessions-per-user distribution.

**Access**: `ADMIN`, `MODERATOR`, `SUPPORT`, `ANALYST`. Step-up: no.

**Params**

| Name | In | Type | Default | Constraints |
|---|---|---|---|---|
| `window` | query | int | 90 | days for `signupsByDay`; clamped server-side to 1–365 |

**Request body**: None.

**Response** — `200 OK`:

```json
{
  "totalUsers": 18234,
  "signupsToday": 41,
  "signups7d": 312,
  "roleDistribution": { "USER": 16920, "RESEARCHER": 890, "SCHOLAR": 401, "MODERATOR": 12, "SUPPORT": 6, "ANALYST": 3, "ADMIN": 2 },
  "emailVerifiedPct": 84.2,
  "phoneVerifiedPct": 31.7,
  "twoFactorPct": 12.9,
  "deletionPipeline": { "PENDING_DELETION": 14, "CANCELLED": 96, "ANONYMIZED": 0, "PURGED": 271 },
  "signupsByDay": [
    { "day": "2026-08-05", "count": 38 },
    { "day": "2026-08-06", "count": 41 }
  ],
  "closeFriends": {
    "usersWithList": 4210,
    "adoptionPct": 23.1,
    "totalEdges": 51840,
    "listSizeHistogram": { "1-5": 2900, "6-15": 980, "16-50": 290, "50+": 40 }
  },
  "sessions": {
    "p50": 1.0,
    "p95": 3.0,
    "avg": 1.42,
    "max": 9,
    "usersWithLiveSessions": 6120
  }
}
```

Notes: percentages are rounded to one decimal; `roleDistribution` contains only roles that actually
occur, while `deletionPipeline` always lists all four statuses; `closeFriends` is aggregate-only (no
individual lists) and degrades to `{ "error": "…" }` if its backend is unavailable; `sessions`
degrades likewise, and collapses to `{ "usersWithLiveSessions": 0 }` when nobody has a live session.

**Errors**: none endpoint-specific.

---

## Bulk & invites

Covers the controller's moderation/bulk block (strike issuance, bulk actions) plus bulk provisioning
and the invite lifecycle. Invite acceptance itself happens on the public set-password link
(`InviteController`), outside this admin surface.

### POST /api/v1/admin/users/{userId}/strikes
Issue a moderation strike (optionally linked to a report). Notifies the user.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body** (`AdminStrikeRequest`):

```json
{
  "reportId": "0e4b7c11-2f6d-4c3a-9e8b-7a6c5d4e3f2a",
  "reason": "Repeated harassment in comments"
}
```

Constraints: `reportId` optional UUID; `reason` required, ≤ 200.

**Response** — `201 Created`, `AdminStrikeRow`:

```json
{
  "id": "b47ac10b-58cc-4372-a567-0e02b2c3d479",
  "reportId": "0e4b7c11-2f6d-4c3a-9e8b-7a6c5d4e3f2a",
  "reason": "Repeated harassment in comments",
  "active": true,
  "issuedAt": "2026-08-07T12:05:00",
  "expiresAt": "2026-11-05T12:05:00"
}
```

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `VALIDATION_FAILED` — 400 — blank/oversized `reason`.
- `SELF_ACTION_FORBIDDEN` — 403 — cannot strike yourself.
- `USER_NOT_FOUND` — 404.

### POST /api/v1/admin/users/bulk
Bulk-create up to 1000 accounts. Per-row outcomes — one bad row never aborts the batch. Rows with
neither `temporaryPassword` nor `sendInvite` are coerced to `sendInvite: true` (unlike single
create, which rejects).

**Access**: `ADMIN`. Step-up: **yes**.

**Request body** (`AdminBulkCreateRequest` — `rows` required, 1–1000 items of
`AdminCreateUserRequest`, same constraints as [POST /api/v1/admin/users](#post-apiv1adminusers)):

```json
{
  "rows": [
    {
      "fname": "Alice", "lname": "Hassan", "username": "alice.hassan",
      "email": "alice@example.com", "role": "RESEARCHER",
      "temporaryPassword": "Str0ngTempPass1", "markEmailVerified": true
    },
    {
      "fname": "Botan", "lname": "Karim", "username": "botan.karim",
      "email": "botan@example.com"
    }
  ]
}
```

**Response** — `200 OK`, `List<AdminBulkRowResult>`:

```json
[
  {
    "index": 0,
    "username": "alice.hassan",
    "email": "alice@example.com",
    "outcome": "created",
    "userId": "8c1f2a3b-4d5e-4f60-9a7b-1c2d3e4f5a6b"
  },
  {
    "index": 1,
    "username": "botan.karim",
    "email": "botan@example.com",
    "outcome": "skipped:duplicate",
    "error": "User already exists with email: botan@example.com"
  }
]
```

`outcome` ∈ `created` / `skipped:duplicate` / `error`; `userId` present only on `created`; `error`
present only on failures.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `VALIDATION_FAILED` — 400 — missing/empty/oversized `rows` or invalid row fields.
- Per-row failures are reported in the result list, never as an HTTP error.

### POST /api/v1/admin/users/bulk-action
Apply one action to up to 100 users. Per-id outcomes; self-protection and other per-user rules apply
per id and surface as row errors.

**Access**: `ADMIN`. Step-up: **yes**.

**Request body** (`AdminBulkActionRequest`):

```json
{
  "ids": [
    "8c1f2a3b-4d5e-4f60-9a7b-1c2d3e4f5a6b",
    "1f7e6d5c-4b3a-4c2d-9e8f-0a1b2c3d4e5f"
  ],
  "action": "DISABLE",
  "role": null,
  "reason": "Coordinated spam ring"
}
```

Constraints: `ids` required, 1–100 UUIDs; `action` required (case-insensitive) —
`DISABLE` / `ENABLE` / `LOCK` / `UNLOCK` / `REQUEST_DELETION` / `CHANGE_ROLE`; `role` required only
for `CHANGE_ROLE`; `reason` optional, ≤ 500 (passed through to reason-taking actions).

**Response** — `200 OK`, `List<AdminBulkActionResult>`:

```json
[
  { "id": "8c1f2a3b-4d5e-4f60-9a7b-1c2d3e4f5a6b", "outcome": "ok" },
  {
    "id": "1f7e6d5c-4b3a-4c2d-9e8f-0a1b2c3d4e5f",
    "outcome": "error",
    "error": "User not found with id: 1f7e6d5c-4b3a-4c2d-9e8f-0a1b2c3d4e5f"
  }
]
```

`outcome` ∈ `ok` / `error`; `error` present only on failures. An unknown `action` (or `CHANGE_ROLE`
without `role`) is also captured per row — every id comes back `error` with the message; the request
itself still returns 200.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `VALIDATION_FAILED` — 400 — `ids` empty/oversized or `action` blank.

### POST /api/v1/admin/users/invite
Invite by email: creates a 7-day invite token and emails the join link (`/invite?token=…`). The
account itself is created when the invitee accepts.

**Access**: `ADMIN`. Step-up: **yes**.

**Request body** (`AdminInviteRequest`):

```json
{
  "email": "newscholar@example.com",
  "role": "SCHOLAR"
}
```

Constraints: `email` required, valid; `role` optional (default `USER`).

**Response** — `201 Created`, `AdminInviteResponse`:

```json
{
  "id": "5b8a9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d",
  "email": "newscholar@example.com",
  "role": "SCHOLAR",
  "expiresAt": "2026-08-14T12:00:00",
  "createdAt": "2026-08-07T12:00:00"
}
```

`usedAt` omitted while unused.

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `VALIDATION_FAILED` — 400.
- `USER_DUPLICATE` — 409 — an account with that email already exists.

### POST /api/v1/admin/users/invite/{inviteId}/resend
Re-send the invite email: clears any revocation and resets expiry to +7 days.

**Access**: `ADMIN`. Step-up: no.

**Params**: `inviteId` — path, UUID, required.

**Request body**: None.

**Response** — `200 OK`, `AdminInviteResponse` (same shape as above, with the new `expiresAt`).

**Errors**
- `USERINVITE_NOT_FOUND` — 404 — unknown invite id.
- `INVITE_USED` — 409 — invite was already accepted.

### DELETE /api/v1/admin/users/invite/{inviteId}
Revoke a pending invite (sets `revokedAt`; the link stops working).

**Access**: `ADMIN`. Step-up: no.

**Params**: `inviteId` — path, UUID, required.

**Request body**: None.

**Response** — 204 No Content.

**Errors**
- `USERINVITE_NOT_FOUND` — 404.

---

## Impersonation

Read-only "view as user". The minted token carries **only** `ROLE_IMPERSONATED_READ` — never the
target's roles — and the JWT filter rejects every non-GET request made with it. TTL ≤ 15 minutes, one
active impersonation per admin (starting a new one revokes the old), start/end write dual-id audit
rows (`ADMIN_IMPERSONATE_START` / `ADMIN_IMPERSONATE_END`).

### POST /api/v1/admin/users/{userId}/impersonate
Mint a 15-minute read-only impersonation token for the target user. Reason (min 10 chars) is
mandatory. Admins are never impersonatable.

**Access**: `ADMIN`. Step-up: **yes**.

**Params**: `userId` — path, UUID, required.

**Request body** (`AdminReasonRequest` — body itself is required here):

```json
{ "reason": "Support ticket #4821 — user reports missing research tab" }
```

Constraints: `reason` ≤ 500 and, for this endpoint, at least 10 characters after trimming.

**Response** — `200 OK`, `ImpersonationGrant`:

```json
{
  "token": "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiI4YzFmMmEzYi00ZDVlLTRmNjAtOWE3Yi0xYzJkM2U0ZjVhNmIifQ.3k9vX2mYw1",
  "targetUserId": "8c1f2a3b-4d5e-4f60-9a7b-1c2d3e4f5a6b",
  "targetUsername": "alice.hassan",
  "expiresAt": "2026-08-07T12:15:00Z"
}
```

Use `token` as the `Authorization: Bearer` value for GET requests only. `expiresAt` is an `Instant`
(UTC, `Z`-suffixed).

**Errors**
- `STEP_UP_REQUIRED` — 403.
- `IMPERSONATION_REASON_REQUIRED` — 400 — reason missing or under 10 characters.
- `SELF_ACTION_FORBIDDEN` — 400 — impersonating yourself (note: 400 here, unlike the 403 variant on other user actions).
- `USER_NOT_FOUND` — 404 — target missing or soft-deleted (active users only).
- `IMPERSONATION_TARGET_ADMIN` — 403 — target holds the ADMIN role.
- `MALFORMED_JSON` — 400 — request body absent or unparseable.

### DELETE /api/v1/admin/impersonation
End the caller's active impersonation session: deletes the Redis marker and denylists the
impersonation `sid`, killing the minted token immediately. Idempotent — succeeds even with no active
session (no audit row is written in that case).

**Access**: `ADMIN`. Step-up: no.

**Params**: none.

**Request body**: None.

**Response** — 204 No Content.

**Errors**: none endpoint-specific.

---

## Step-up

### POST /api/v1/security/step-up
Re-authenticate to arm the step-up window (Redis marker `stepup:{userId}`, TTL 300 s by default via
`app.security.step-up.ttl-seconds` / `STEP_UP_TTL_SECONDS`). This is the arm-step-up flow admins run
before calling any `@RequiresStepUp` route above; the dashboard should catch `403 STEP_UP_REQUIRED`,
prompt for the password (or a TOTP code), call this endpoint, then retry the original request.

**Access**: any authenticated user (`SecurityController` is class-gated `isAuthenticated()`; no
admin role required — the marker is per-user). Step-up: n/a (this is the arming endpoint).

**Request body** (`StepUpRequest` — supply exactly one of the two fields):

```json
{ "password": "correct horse battery staple" }
```

or, with a fresh TOTP code (2FA must be enabled on the account):

```json
{ "code": "492817" }
```

The password branch is tried first when both are present.

**Response** — 204 No Content (window armed).

**Errors**
- `STEP_UP_BAD_PASSWORD` — 400 — wrong password (envelope).
- `USER_NOT_FOUND` — 404 — authenticated user row missing (edge case).
- bare `400 Bad Request` with **no envelope body** — both fields blank/absent, or the TOTP `code` did
  not verify (including 2FA not enabled). One of the known envelope escapes — see
  [frontend-error-handling.md](../../errors/frontend-error-handling.md).
