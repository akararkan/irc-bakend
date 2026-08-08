# User Administration — Add Users & Full Control — Admin Dashboard Section 14

The **account-provisioning and full-control** console — everything an admin does
*to create* and *to command* a user account. This is the companion to
[users-roles.md](directory-and-roles.md) (Section 1): that doc is the **directory,
inspection, and growth-analytics** view; **this** doc is the **action surface** —
adding users, editing them, controlling credentials and state, ending sessions,
impersonating for support, and running the lifecycle. Where the two overlap (the
per-user detail projection, sessions, deletion pipeline), Section 1 owns the
*read/inspect* view and this doc owns the *create/mutate* actions and cross-refs it.

> **Update (2026-08): this section is BUILT.** The full C/E/S/X matrix below —
> create, invite, edit, password/2FA reset, disable/lock, kill-sessions,
> delete/restore, purge, strikes, impersonate, bulk — is live on
> `AdminUserController` (~30 routes), with provisioning through
> `UserProvisioningService.provision` (the shared path extracted from
> `register`). Only "ban/suspend" as a distinct state remains absent. The
> per-action notes below retain the original which-service-it-reuses rationale.

Tag legend and ground rules: [README.md](../README.md). Related:
[users-roles.md](directory-and-roles.md) (directory & inspection),
[safety-reports.md](../trust-safety/safety-reports.md) (strikes/restrictions/bans as *moderation*),
[logs-audit.md](../platform/logs-audit.md) (audit trail every action writes),
[../settings/auth-sessions.md](../../settings/auth-sessions.md) (auth/2FA/step-up
mechanics), [../settings/data-export-deletion.md](../../settings/data-export-deletion.md)
(the deletion pipeline), [../user/users.md](../../user/users.md) (user model).

Status legend: **[EXISTS]** = real today (class/endpoint cited) · **[PARTIAL]** =
primitive exists, admin surface or wiring missing · **[PLANNED]** = proposed here.

---

## 1. Purpose & scope

| In scope | Out of scope (see) |
|----------|--------------------|
| **Adding users**: admin single-create, pre-verified provisioning, bulk import, invite-based onboarding | Public self-signup UX (`POST /api/v1/auth/register`) — user-facing, not admin |
| Identity edits (name/username/email on behalf of a user) | Population growth analytics, verification funnel → [users-roles.md](directory-and-roles.md) §2.4 |
| Credential control: admin password reset, 2FA reset, force credential rotation | The user-facing security settings → [../settings/auth-sessions.md](../../settings/auth-sessions.md) |
| Account state: enable/disable, lock/unlock, ban/suspend | Report-driven strikes & restriction state machine → [safety-reports.md](../trust-safety/safety-reports.md) |
| Session termination (kill one / kill all) | Self-serve session list → [users-roles.md](directory-and-roles.md) §2.3 |
| Lifecycle: admin-initiated delete, restore, expedite/hold purge | The purge cron & tombstone mechanics → [../settings/data-export-deletion.md](../../settings/data-export-deletion.md) |
| **Impersonation** (act-as for support) — policy + design | — |
| **Bulk operations** on user sets | — |
| The editable/viewable field inventory (`User` + `UserProfile`) | Badge editing — **impossible by design** (badges derive from role) |

---

## 2. Ground truth — how accounts are born today

There is exactly **one** way a `users` row is created: **public self-registration.**

`POST /api/v1/auth/register` (`user/controller/AuthController`, unauthenticated) →
`AuthServiceImpl.register`. **[EXISTS]**

| Fact | Detail | Consequence for the dashboard |
|------|--------|-------------------------------|
| Required fields | `fname` (≤80), `lname` (≤80), `username` (3–50), `email`, `password` (8–128) — `AuthRequests.RegisterRequest` | The admin "Add User" form mirrors these five; there is **no** `displayName` input — it's derived (`displayName = fname + " " + lname`) |
| Duplicate guard | `existsByEmail` / `existsByUsername` → `DuplicateResourceException` | Reuse verbatim in admin-create so both paths share one uniqueness rule |
| Default role | `Role.USER` (`AuthServiceImpl`) — the historical hardcoded-`SCHOLAR` default was **fixed 2026-08** | Admin-create takes an explicit `role`; the form defaults to `USER`, matching registration |
| `is_enabled` | forced `true` at register (entity default is `false`) | New accounts are immediately usable |
| Email verified? | **No** — `email_verified_at` is **never written anywhere** | Every account, self-signup or admin-made, has an unverified email today (see §7 "dead scaffolding") |
| Password | `BCryptPasswordEncoder(12)` | Admin-create/reset reuse the same encoder bean |
| Companion row | a `UserProfile` is created inline; user indexed for search (`userSearch.indexAsync`) | Admin-create must create the profile + index too, or the account is half-formed |
| Audit | entity-level `user.audit(CREATE, "User registered")` stamps `last_action`/`action_note` (not the Cassandra audit log) | Admin-create should *also* write a Cassandra audit row (§7) |

**The admin create endpoint now exists (built 2026-08):** `POST /api/v1/admin/users`
(`AdminUserController.create` → `UserProvisioningService.provision`). Every ingredient
(validation, encoder, profile creation, search indexing) is the shared `provision(...)`
extracted from `register`, so admin-made and self-made accounts are structurally identical.

---

## 3. Adding users (the centerpiece)

Four provisioning modes, all **[EXISTS]** (built 2026-08), all under `/api/v1/admin/**` (double-gated),
all **step-up-gated** (creating credentials is a sensitive act) and audited.

### 3.1 Single admin-create — `POST /api/v1/admin/users` **[EXISTS]** (built 2026-08)

Body: `{ fname, lname, username, email, role, sendInvite?, temporaryPassword?, markEmailVerified? }`.

| Choice | Behavior |
|--------|----------|
| `role` | **explicit** — form default `USER` (registration itself now defaults `USER` too), with a clear picker across the 7 roles |
| Password | either admin sets a `temporaryPassword` (BCrypt-encoded, `is_credentials_non_expired` **[PLANNED]** forced so the user must change it on first login) **or** `sendInvite=true` mints a one-time set-password link (needs the invite system, §3.4) |
| `markEmailVerified` | if true, sets `email_verified_at = now()` — the **only** intended writer of that column (§7). Use for trusted provisioning (staff, verified scholars) |
| Reuses | the extracted `provision(...)` (validation + encoder + `UserProfile` + `indexAsync`) so admin-made and self-made accounts are structurally identical |
| Audit | `ADMIN_USER_CREATE` Cassandra row + entity `audit(CREATE, "Created by admin {id}")` |

### 3.2 Pre-verified / staff provisioning **[EXISTS]** (built 2026-08)

Same endpoint with `markEmailVerified=true` and an elevated `role` (e.g. `ADMIN`,
`SCHOLAR`). Because there is **no** working email-verify flow today (§7), this admin
path is the *only* way an account can ever have a verified email — worth stating
plainly so ops know pre-verification is an admin act, not a user achievement.

### 3.3 Bulk import — `POST /api/v1/admin/users/bulk` **[EXISTS]** (built 2026-08)

CSV/JSON upload (`fname,lname,username,email,role`), server validates every row
against the same uniqueness + field rules, returns a per-row result
(`created` / `skipped:duplicate` / `error:reason`). No bulk user primitive exists
today — this is new, but it's a loop over `provision(...)` with a summary. Cap the
batch (e.g. 1,000 rows) and rate-limit; every created row is individually audited.
Pairs naturally with `sendInvite=true` for cohort onboarding (a class, an institution).

### 3.4 Invite-based onboarding **[EXISTS]** (built 2026-08)

Historically there was **no user-onboarding invite system** — the only invites in the
codebase were channel/conversation-scoped (`ConversationInvite`); the 2026-08 build
added one (`POST /api/v1/admin/users/invite` + resend/revoke). To offer
"invite by email," add a `UserInvite` (email, opaque token, role, expiry, used-at)
and an endpoint `POST /api/v1/admin/users/invite` that emails a set-password link;
consuming it creates the account pre-verified with the invited role. This reuses the
existing `VerificationToken` *shape* (which is otherwise dead, §7) — one honest
option is to revive `TokenType` with an `INVITE` value rather than add a parallel table.

---

## 4. The full-control matrix — everything an admin can do to a user

Each row: what it is, the `[EXISTS]` primitive it reuses, and the gap. Endpoint
tables with danger/step-up/audit are consolidated in §6.

| Control | Reuses (primitive) | Status | Gap to close |
|---------|--------------------|--------|--------------|
| **Inspect** (full projection, PII reveal) | `UserRepository.findActiveWithProfileByIdIn` (avatar join-fetch!), `UserStatsService`, `StorageUsageService` | **[EXISTS]** | admin read endpoints **[EXISTS]** (built 2026-08) — view owned by [users-roles.md](directory-and-roles.md) §2.3 |
| **Change role** (promote/demote) | `AdminUserService.changeRole` | **[EXISTS]** | step-up + self-guard + last-admin guard added 2026-08; badge re-derives on next read |
| **Edit identity** (fname/lname/username/email) | direct `User` setters + `existsBy*` guards | **[EXISTS]** (built 2026-08) | `PATCH /api/v1/admin/users/{id}`; email edit resets `email_verified_at` |
| **Reset password** | `BCryptPasswordEncoder`, `RefreshTokenRepository.revokeAllForUser` | **[EXISTS]** (built 2026-08) | **forgot-password is intentionally absent** — admin reset fills that hole (set temp + revoke all + force-change) |
| **Reset 2FA** | `TwoFactorService.disable(userId)` | **[EXISTS]** (built 2026-08) | admin wrapper over the self-service `disable` — **classic account-takeover vector, mandatory step-up + notify user** |
| **Enable / disable** | `is_enabled` column; `UserRepository.softDelete` sets it false | **[EXISTS]** (built 2026-08) | dedicated toggle built — `is_enabled` is no longer flipped only via soft-delete |
| **Lock / unlock** | `is_account_non_locked` column | **[EXISTS]** (built 2026-08) | column now mutated by the admin lock/unlock endpoints (historically a dead column) |
| **Ban / suspend** | `Resolution.ACCOUNT_SUSPENDED`, `ReportReason.*` enums | **[PLANNED]** | enums exist, **no backing mechanism** — "suspend" as a distinct state does not exist; disable is the lever until built (→ [safety-reports.md](../trust-safety/safety-reports.md)) |
| **Kill one session** | `SessionService.revoke(userId, sid)` | **[EXISTS]** (built 2026-08) | admin variant drops the self-only owner check. Note the denylist seam (§7) |
| **Kill all sessions** | `RefreshTokenRepository.revokeAllForUser` + `SessionDenylist.deny` per live sid | **[EXISTS]** (built 2026-08) | admin caller wired over the logout-all primitive |
| **Soft-delete (admin-initiated)** | `AccountLifecycleService.requestDeletion` | **[EXISTS]** (built 2026-08) | admin caller reuses the ONE state machine (soft-delete + revoke-all + 30d grace) |
| **Restore (within grace)** | `AccountLifecycleService.cancelDeletion` | **[EXISTS]** (built 2026-08) | same — reused for admin restore |
| **Expedite / hold purge** | `purgeExpired()` nightly cron `0 30 3 * * *`, `anonymizeAndPurge` | **[EXISTS]** (built 2026-08) | `POST .../purge/{now\|hold}`; hold = extend `purge_after` |
| **Issue strike** | `StrikeService.issueStrike(userId, reportId, reason)` | **[EXISTS]** (built 2026-08) | now called from the admin strike endpoints and `ReportModerationService.action` (→ [safety-reports.md](../trust-safety/safety-reports.md)) |
| **Impersonate (act-as)** | `admin/impersonation` | **[EXISTS]** (built 2026-08) | see §5 |
| **Bulk** (role/disable/delete on a set) | the singular primitives above | **[EXISTS]** (built 2026-08) | `POST /api/v1/admin/users/bulk-action` |

---

## 5. Impersonation (act-as for support) **[EXISTS]** (built 2026-08)

Impersonation is now implemented (`admin/impersonation`, built 2026-08). It is the
single most-requested support capability and the single most-dangerous, so it was
designed deliberately:

- **Scoped token, not a password.** `POST /api/v1/admin/users/{userId}/impersonate`
  mints a short-TTL (e.g. 10 min) impersonation JWT carrying **both** the admin's id
  (`act` claim) and the target's id (`sub`), never the target's real credentials.
- **Read-mostly by default.** Impersonation sessions should be read-only unless the
  admin explicitly elevates to write — and destructive/self-security actions
  (password, 2FA, deletion) are **always blocked** while impersonating.
- **Loudly audited & attributed.** Every action taken while impersonating writes an
  audit row tagged with *both* ids (`ADMIN_IMPERSONATE_*`); the target's own audit
  trail shows "action performed by admin {id} on your behalf."
- **Mandatory step-up + reason**, time-boxed, one target at a time, auto-expiring,
  and surfaced to the user afterward (transparency).

For most support cases, "support needs to see what the user sees" is still served
first by the read-only inspection projection ([users-roles.md](directory-and-roles.md) §2.3).

---

## 6. Admin actions — endpoint matrix

All **[EXISTS]** (built 2026-08). Every route lives under
`/api/v1/admin/**` (inherits `hasRole('ADMIN')` at the chain **and** `@PreAuthorize`).
Step-up = `StepUpService.requireRecentStepUp(adminId)` **[EXISTS]**; audit = a
Cassandra row via the audit path (§7).

### 6.1 Create / add

| # | Action | Endpoint | Danger | Step-up | Audit action |
|---|--------|----------|--------|---------|--------------|
| C1 | Create one user | `POST /api/v1/admin/users` | **high** (mints credentials) | **yes** | `ADMIN_USER_CREATE` |
| C2 | Bulk import | `POST /api/v1/admin/users/bulk` | **high** | **yes** | `ADMIN_USER_BULK_CREATE` (+ per-row) |
| C3 | Invite by email | `POST /api/v1/admin/users/invite` | medium | yes | `ADMIN_USER_INVITE` |
| C4 | Resend / revoke invite | `POST .../invite/{id}/resend` · `DELETE .../invite/{id}` | low | no | `ADMIN_INVITE_*` |

### 6.2 Edit / credentials

| # | Action | Endpoint | Danger | Step-up | Audit action |
|---|--------|----------|--------|---------|--------------|
| E1 | Change role | `PATCH /api/v1/admin/users/{id}/role` | medium | yes¹ | `ADMIN_ROLE_CHANGE` — **[EXISTS]** (¹ step-up enforced via `@RequiresStepUp`, built 2026-08) |
| E2 | Edit identity | `PATCH /api/v1/admin/users/{id}` `{fname?,lname?,username?,email?}` | **high** (email = recovery vector) | **yes** | `ADMIN_USER_EDIT` |
| E3 | Reset password | `POST /api/v1/admin/users/{id}/password/reset` `{temp?|sendLink}` | **critical** | **yes** | `ADMIN_PASSWORD_RESET` (+ revoke-all + notify) |
| E4 | Reset 2FA | `POST /api/v1/admin/users/{id}/2fa/reset` `{reason}` | **critical** (takeover vector) | **yes, mandatory** | `ADMIN_2FA_RESET` (+ security email, bypass DND) |
| E5 | Mark email verified | `POST /api/v1/admin/users/{id}/email/verify` | medium | yes | `ADMIN_EMAIL_VERIFY` — only intended writer of `email_verified_at` |

### 6.3 State / sessions / lifecycle

| # | Action | Endpoint | Danger | Step-up | Audit action |
|---|--------|----------|--------|---------|--------------|
| S1 | Disable / enable | `POST .../{id}/disable` · `.../enable` `{reason}` | **high** | **yes** | `ADMIN_USER_DISABLE` / `_ENABLE` |
| S2 | Lock / unlock | `POST .../{id}/lock` · `.../unlock` | **high** | **yes** | `ADMIN_USER_LOCK` / `_UNLOCK` (needs enforcement wiring, §7) |
| S3 | Kill all sessions | `POST .../{id}/sessions/revoke-all` | medium | yes | `ADMIN_SESSIONS_REVOKE_ALL` |
| S4 | Kill one session | `DELETE .../{id}/sessions/{sid}` | medium | yes | `ADMIN_SESSION_REVOKE` |
| S5 | Admin soft-delete | `POST .../{id}/deletion/request` `{reason}` | **critical** | **yes** | `ADMIN_ACCOUNT_DELETE_REQUEST` (reuse `requestDeletion`) |
| S6 | Restore (cancel deletion) | `POST .../{id}/deletion/cancel` | medium | yes | `ADMIN_ACCOUNT_DELETE_CANCEL` (reuse `cancelDeletion`) |
| S7 | Expedite / hold purge | `POST .../{id}/purge/{now\|hold}` | **critical** | **yes** | `ADMIN_PURGE_*` |

### 6.4 Advanced

| # | Action | Endpoint | Danger | Step-up | Audit action |
|---|--------|----------|--------|---------|--------------|
| X1 | Impersonate | `POST /api/v1/admin/users/{id}/impersonate` | **critical** | **yes** | `ADMIN_IMPERSONATE_START` / `_END` (dual-id) |
| X2 | Bulk role/disable/delete | `POST /api/v1/admin/users/bulk-action` `{ids[],action,reason}` | **critical** | **yes** | `ADMIN_BULK_*` (+ per-id) |
| X3 | Issue strike | `POST /api/v1/admin/users/{id}/strikes` `{reportId?,reason}` | **high** | yes | `ADMIN_STRIKE_ISSUE` (first caller of `issueStrike`) |

**Explicit non-actions (by design):** no badge grant/revoke (badges derive from role —
change the role instead); no manual set of another user's *current* password without
force-change; no bulk *create* without per-row audit; no impersonation of another admin.

---

## 7. Field inventory — what's viewable / editable

The complete surface behind the detail + edit forms. **Editable** = safe for E2-class
admin edit; **read-only** = system-managed; **derived** = never stored.

### 7.1 `users` (`User`, extends `BaseAuditEntity`, implements `UserDetails`)

| Field | Class | Admin treatment |
|-------|-------|-----------------|
| `id` | UUID PK | read-only |
| `fname`, `lname` | ≤80 each | **editable** (E2) |
| `username` | unique, ≤50 | **editable** (E2, uniqueness-checked) |
| `email` | unique, ≤255 | **editable** (E2 — resets `email_verified_at`) |
| `password` | ≤255, nullable | never shown; E3 only; nulled on purge |
| `phone_e164`, `phone_hmac`, `phone_verified_at` | phone | read-only (set by verified OTP via `PhoneService`) |
| `role` | enum, default **USER** (since 2026-08) | E1 only (7 roles) |
| `orcid_id`, `preferred_language`, `timezone` | | **editable** |
| `is_enabled` | default `false`, forced `true` at register | S1 — admin disable/enable toggle (built 2026-08) |
| `is_account_non_locked` | default true | S2 — mutated by admin lock/unlock since 2026-08 |
| `is_account_non_expired`, `is_credentials_non_expired` | default true | **dead columns** — never mutated (force-change would use `credentials_non_expired`) |
| `email_verified_at` | nullable | written by E5 admin verify / `markEmailVerified` provisioning (built 2026-08) — still no user-facing verify flow |
| `two_factor_enabled`, `two_factor_secret` (AES-GCM), `two_factor_last_step` | 2FA | read-only status; E4 to reset |
| `email_*_enabled` (notifications/social/mentions/system/trending) | all default true | mirror in [notifications-email.md](../communication/notifications-email.md); read-only here |
| `last_login_at` | | read-only (updated on login) |
| `deleted_at` | soft-delete marker | drives `isDeleted()`/`isEnabled()`; set by S5 |
| `getAuthorities()` | derived | `SCHOLAR` → `ROLE_SCHOLAR` **+** `ROLE_RESEARCHER`; others → own role |
| audit envelope | `created_at/by/ip/device`, `updated_*`, `last_action`, `action_note` | read-only (auto-stamped) |

### 7.2 `user_profiles` (`UserProfile`, 1:1)

Editable display block: `display_name` (≤120), `profile_bio`, `self_describer`,
`location`, `academic_title`, `institution_name`, `website_url`, `madhhab`
(FK → [knowledge-vocabulary.md](../content/knowledge-vocabulary.md)), `content_language`,
`is_for_hire`, `is_profile_locked`. Media: `avatar_url`/`avatar_s3_key`,
`cover_image_url`/`cover_image_s3_key` (→ [media-storage.md](../content/media-storage.md)).
Read-only **denormalized** counters `follower_count`/`following_count`/`research_count`/
`fatwa_count` and `profile_views` — known-stale, prefer `UserStatsService` live counts.
Collections: `specializations`, `links`, `contacts`, `attachments`.

> **Avatar gotcha (build note):** load users for the detail view via
> `UserRepository.findActiveWithProfileByIdIn` (LEFT JOIN FETCH profile) or the avatar
> comes back null while username/displayName populate — `getProfileImage()` is a lazy
> delegate to `profile.avatarUrl`.

### 7.3 Badges — **not editable, not stored**

`BadgeType` = `{VERIFIED_SCHOLAR, VERIFIED_RESEARCHER}`, computed from `role` in
`UserMapper.resolveBadges` (`SCHOLAR`→scholar, `RESEARCHER`→researcher, `USER`/`ADMIN`→none).
No badge column, no verification queue. The role picker **is** the badge control.

---

## 8. Safety rails, audit & recon flags

**Every mutation writes audit.** Two audit systems exist:
- **HTTP auto-audit [EXISTS]:** `AuditLoggingInterceptor` → `AuditLogService.recordAsync`
  writes `audit_log_by_user` + `audit_log_by_resource` (Cassandra, 180-day TTL),
  broadcast on the SSE audit stream. Every admin call is captured here automatically.
- **Entity audit [EXISTS]:** `BaseAuditEntity.audit(action, note)` stamps
  `last_action`/`action_note` + `updated_by/ip/device` on the row (role change,
  register, password change all use it).
- **`AuditLogService.record(...)` [EXISTS]** — the explicit "record this business
  action" helper is now funneled through `admin/support/AdminAuditor`, which writes
  the named `ADMIN_*` rows on every admin mutation (built 2026-08).

**Recon flags to surface on the Config/health tab (all verified against source):**

1. **Fixed 2026-08:** registration now grants `Role.USER` (still `isEnabled = true`,
   email unverified) — the historical hardcoded-`SCHOLAR` default is gone.
2. **Email verification is dead scaffolding** — `VerificationToken` + repo exist with
   `TokenType.EMAIL_VERIFY`, but **zero injectors**; `email_verified_at` is never set;
   `isEmailVerified()` always returns false. Admin E5 (or reviving the token flow) is
   the only path to a verified email.
3. **`is_account_non_locked` is now mutated by the admin lock/unlock endpoints
   (2026-08)**; `is_account_non_expired` / `is_credentials_non_expired` remain dead
   columns — never mutated. `isEnabled()` still doesn't consult the lock flag.
4. **Fixed 2026-08:** `StrikeService.issueStrike` now has callers — the admin strike
   endpoints (X3, `AdminSafetyController`) and `ReportModerationService.action`.
5. **`SessionDenylist` seam [PARTIAL]** — `isDenied(sid)` is **not** checked in
   `JwtAuthenticationFilter`, so revoking a session closes the *refresh* path
   immediately but the stateless access-JWT stays valid until expiry (~15 min).
   Admin "kill session" must set the user's expectation accordingly (or the build
   closes the seam).
6. **Phantom roles — largely resolved 2026-08.** `Role` widened to seven values
   (`MODERATOR`/`SUPPORT`/`ANALYST` staff tiers are live) and `AuditLogController` was
   normalized to `hasRole('ADMIN')`. `SUPER_ADMIN` remains phantom — residual
   `hasAnyRole(…,'SUPER_ADMIN')` grants linger in `ResearchController`; never render
   `SUPER_ADMIN` as a grantable role.

---

## 9. Permissions & safety notes

- **Double-gate everything.** All routes here are `/api/v1/admin/**` → `hasRole('ADMIN')`
  at the chain (`SecurityConfig`) **plus** `@PreAuthorize` on the method. A forgotten
  annotation can't leak an admin route, but never rely on the chain alone.
- **Step-up on state & credentials.** Role change is step-up-gated via
  `@RequiresStepUp` (built 2026-08, E1). Everything critical (create, edit-email,
  password/2FA reset, disable/lock, delete/purge, impersonate, bulk) is mandatory step-up.
- **Notify the user on takeover-relevant actions** (password reset, 2FA reset, email
  change) via the security-email path that bypasses DND, so a hijacked-admin scenario
  is visible to the account owner.
- **One deletion state machine.** Admin delete/restore (S5/S6) *must* reuse
  `AccountLifecycleService`, never a parallel path — so soft-delete, grace, purge, and
  tombstone stay identical whether the user or an admin initiated it.
- **Impersonation is the crown-jewel risk** — dual-id audit, time-boxed, read-mostly,
  never act-as another admin, always disclosed to the user (§5).
- **PII minimization.** Email/phone reveal and any export are step-up-gated and audited;
  the directory masks by default ([users-roles.md](directory-and-roles.md) §2).

---

## 10. Build order

1. **Phase 1 (read):** the inspection projection (owned by [users-roles.md](directory-and-roles.md))
   — zero-risk, surfaces existing data. Wire `AuditLogService.record` so admin reads/actions
   get meaningful audit summaries.
2. **Phase 2 (safe mutations):** extract `provision(...)` from `register`; ship **C1 create**
   and **E1 role** (already exists — just add step-up). Then E2 edit, S3/S4 sessions,
   S5/S6 delete-restore (all reuse existing services).
3. **Phase 3 (sensitive):** E3 password reset, E4 2FA reset, E5 email-verify, S1 disable/enable
   — each needs a new setter path + user notification.
4. **Phase 4 (heavy):** S2 lock/suspend (needs enforcement wiring), C2 bulk import,
   X2 bulk actions, X3 strike (first `issueStrike` caller), and finally **X1 impersonation**
   (the highest-risk, build last with full audit + policy).

Endpoint rows are consolidated in [admin-api-blueprint.md](../foundation/api-blueprint.md) §3.1.
