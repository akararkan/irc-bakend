# Admin Dashboard — Users & Roles

Status legend: **[EXISTS]** = real today (class / endpoint cited) · **[PARTIAL]** = data layer or primitive exists, surface missing · **[PLANNED]** = proposed for the dashboard, not yet built.

Related: [README.md](../README.md) · [../settings/data-export-deletion.md](../../settings/data-export-deletion.md) · [../settings/README.md](../../settings/README.md) · [../search/README.md](../../search/README.md) · [../user/users.md](../../user/users.md) · [../errors/error-handling.md](../../errors/error-handling.md)

## 1. Purpose & scope

The user & role management section of the admin dashboard: find any account, inspect everything the platform knows about it (identity, role/badge, verification, sessions, security posture, moderation history, storage, activity), change its role, and control its lifecycle. Also carries user-population growth analytics and the per-user log surfaces (HTTP audit, login events, settings changes).

Out of scope here: report triage / strike issuance UX (moderation section), content takedown (content section), channel/stream admin (chat-live section). This doc references their user-facing facets (strikes against a user, reports filed by/against a user) as read-only widgets.

Ground rules baked into the platform that this section must respect:

| Rule | Consequence for the dashboard |
|---|---|
| Seven roles: `USER` / `RESEARCHER` / `SCHOLAR` / `MODERATOR` / `SUPPORT` / `ANALYST` / `ADMIN` (`user/enums/Role`) **[EXISTS]** (built 2026-08) | Role picker is a 7-value radio. `MODERATOR`/`SUPPORT`/`ANALYST` are live staff tiers with per-section grants ([architecture.md](../foundation/architecture.md) §6). Only `SUPER_ADMIN` remains phantom — never render it. |
| Badges auto-derive from role (`user/enums/BadgeType`) **[EXISTS]** | No badge editor, no verification-request queue — by design. Show the badge read-only next to the role. |
| `username` is the user-set handle, never the email | Directory shows both columns; search matches both. |
| `/api/v1/admin/**` is double-gated (`config/SecurityConfig` filter-chain `hasRole("ADMIN")` + `@PreAuthorize`) **[EXISTS]** | Every new endpoint proposed below MUST live under `/api/v1/admin/**` to inherit the second gate. |

## 2. Dashboard views / widgets

### 2.1 User directory (landing view)

Paged table, default sort `created_at DESC` (newest signups first).

| Column | Notes |
|---|---|
| Avatar + display name + @username | Avatar requires the profile fetch-join (see §3 gotcha) |
| Email (+ verified check) | `email_verified_at != null` |
| Role → badge | 7-value chip; badge derived |
| Status | Composite: enabled / locked / deletion-state / purged (see §2.3) |
| 2FA | `two_factor_enabled` boolean dot |
| Signup date | `created_at` |
| Followers / posts | From `UserStatsService` (lazy-load per row or on hover) |
| Row actions | Open detail · Change role · (proposed) Disable / Force-logout |

Toolbar: search box (FTS + trigram + ES, §3), role filter (`findActiveByRoles` **[EXISTS]**), verification filter (email-verified / phone-verified / 2FA — **[EXISTS]** queries (built 2026-08)), status filter (active / pending-deletion / purged — **[EXISTS]** queries (built 2026-08)), date-range signup filter.

### 2.2 User detail — header card

Identity block: avatar, display name, @username, email, phone (masked by default, reveal = step-up), role + badge, account age, `deleted_at` banner if soft-deleted, deletion-state banner if a `account_deletion_requests` row is open (grace countdown to `purge_after`).

### 2.3 User detail — tabs

| Tab | Contents | Status |
|---|---|---|
| **Overview** | Profile fields (bio, links, institution from `UserProfile`), stats row (posts / reels / research / questions / followers / following via `UserStatsService.statsFor`), storage usage gauge (`StorageUsageService`), security-score chip | **[EXISTS]** data + admin read endpoints (built 2026-08) |
| **Verification & security** | email `email_verified_at`, phone `phone_verified_at`, 2FA on/off (`two_factor_enabled`), recovery-codes issued?, step-up-relevant flags; account flags `is_enabled`, `is_account_non_locked` | **[EXISTS]** columns on `user/entity/User` (lines 93/114/122/133/135) |
| **Sessions** | Live session list from `refresh_tokens`: device_name, platform, user_agent, ip_address, last_seen_at, trusted_until, is_revoked/revoked_at; per-session revoke + revoke-all buttons | **[EXISTS]** (built 2026-08) — admin variants `GET /api/v1/admin/users/{userId}/sessions`, `DELETE .../sessions/{sid}`, `POST .../sessions/revoke-all` alongside the self-serve routes (`security/controller/SecurityController`) |
| **Login history** | `login_events` rows: ts, ip, coarse_geo, user_agent, method (PASSWORD/OTP/REFRESH/TWO_FA), outcome (SUCCESS/FAILED/LOCKED) | **[EXISTS]** (built 2026-08) — writer wired from `AuthServiceImpl` (`recordSuccessAndAlertIfNew` + `record`); admin reader `GET /api/v1/admin/users/{userId}/login-events` alongside the self-serve `GET /api/v1/security/login-history` |
| **Moderation** | Strikes against the user (`user_strikes`, 90-day decay `expires_at`), reports **against** (query `reports` by `target_type=USER, target_id`), reports **filed by** (`reporter_id` index) | **[EXISTS]** (built 2026-08) — admin reader `GET /api/v1/admin/users/{userId}/moderation`; `StrikeService.issueStrike` now fires from the admin strike endpoints and `ReportModerationService` |
| **Audit trail** | Per-user HTTP audit from Cassandra `audit_log_by_user`: operation, outcome, method+path, status, duration_ms, ip, user_agent, keyset-paged | **[EXISTS]** — `GET /api/v1/admin/audit/users/{userId}` (`audit/controller/AuditLogController`) |
| **Settings audit** | `settings_audit` rows: setting_key (dotted path e.g. `privacy.bio`, `security.2fa`), old/new value, ip, timestamp | **[EXISTS]** (built 2026-08) — `GET /api/v1/admin/users/{userId}/settings-audit` over `SettingsAuditService.history(userId, pageable)` |
| **Data lifecycle** | Export jobs (`export_jobs`: status/size/expiry), deletion request state machine, `deleted_accounts` tombstone check | **[EXISTS]** tables (`settings/data/`) + admin reader `GET /api/v1/admin/users/{userId}/data` (built 2026-08) — see [../settings/data-export-deletion.md](../../settings/data-export-deletion.md) |

### 2.4 Growth analytics view (population-level)

Four widgets across the top, charts below — all fed by §6.

1. Stat tiles: total users · signups today/7d · % email-verified · % 2FA.
2. Signups/day line chart (90d).
3. Role distribution donut (expected to be ~all USER).
4. Verification funnel bar: registered → email-verified → phone-verified → 2FA-enabled.
5. Deletion-pipeline strip: PENDING_DELETION count (with next-purge date) → ANONYMIZED/PURGED cumulative.

## 3. Data sources

| Widget / need | Source | Status |
|---|---|---|
| Directory list + role filter | `UserRepository.findActiveByRoles(roles, pageable)` (fetch-join, has countQuery) | **[EXISTS]** |
| Handle/name search (typed) | `UserRepository.searchUsers` / `searchUsersByRoles`; ranked FTS `searchUsersFts` (`websearch_to_tsquery('simple', :q)`) + trigram fallback `searchUsersTrgm`; user-facing `GET /api/v1/users/search` (`UserController`) | **[EXISTS]** |
| Relevance search (fuzzy, cross-field) | ES `irc-users` index via `UserSearchService`; rebuild: `POST /api/v1/admin/search/users/reindex` (`SearchAdminController`) | **[EXISTS]** |
| Profile + avatar hydration | `UserRepository.findActiveWithProfileByIdIn` — **must** use the fetch-join variant or `profileImage` silently nulls (lazy delegate to `profile.avatarUrl`) | **[EXISTS]** (gotcha) |
| Stats row | `UserStatsService.statsFor(userId)` — live COUNTs (Cassandra posts/reels + PG research/questions/follows), Redis-cached 30s. Ignore `user_profiles` denormalized counters — known-stale, always 0 | **[EXISTS]** |
| Storage gauge | `StorageUsageService` → `MediaAssetRepository.sumStoredBytes(ownerId)` (+ byType), Redis-cached 1h; dedup referrers stored at 0 bytes | **[EXISTS]** |
| Sessions | `refresh_tokens` table (`user/entity/RefreshToken`: sid, device_name, platform, user_agent, ip_address, last_seen_at, trusted_until, device_fingerprint, is_revoked, revoked_at) read through `security/session/SessionService`; revocation denylists the sid in Redis `sid:denied:{sid}` (`SessionDenylist`) | **[EXISTS]** (self-serve scope) |
| 2FA status | `User.two_factor_enabled` (+ `security/twofa` TOTP entities); self-serve `GET /api/v1/security/2fa/status` | **[EXISTS]** |
| Email/phone verification | `User.email_verified_at`, `User.phone_verified_at` | **[EXISTS]** |
| Account flags | `User.is_enabled` (set `true` at registration by `AuthServiceImpl`; admin disable sets it false), `User.is_account_non_locked` (toggled by admin lock/unlock) | **[EXISTS]** (built 2026-08) — disable/enable + lock/unlock toggles on `AdminUserController` |
| Soft-delete / purge state | `User.deleted_at`; `account_deletion_requests.status` (`PENDING_DELETION → CANCELLED \| ANONYMIZED \| PURGED`, `settings/data/enums/DeletionStatus`); `deleted_accounts` tombstone (PK = old user id) | **[EXISTS]** |
| Strikes / reports | `user_strikes` (idx `user_id,expires_at`), `reports` (idx `reporter_id,created_at` and `target_id,reason`) | **[EXISTS]** tables + admin reader (built 2026-08) |
| Per-user audit trail | Cassandra `audit_log_by_user` via `AuditLogByUserRepository.firstPage/nextPage` | **[EXISTS]** |
| Login history | `login_events` (`security/login/entity/LoginEvent`) | **[EXISTS]** — writer wired from `AuthServiceImpl` (built 2026-08) |
| Settings audit | `settings_audit` via `SettingsAuditService.history` | **[EXISTS]** — admin HTTP surface built 2026-08 |

## 4. Admin actions

Existing:

| Action | Endpoint | Status |
|---|---|---|
| Change role | `PATCH /api/v1/admin/users/{userId}/role` body `AdminChangeRoleRequest` → `AdminUserService.changeRole`; badge re-derives on next read | **[EXISTS]** (`user/controller/AdminUserController` — now one of ~30 routes on the controller: directory, detail, pii, sessions, login-events, settings-audit, moderation, data, analytics, disable/enable/lock/unlock, password/2FA/email resets, create/bulk/invite/edit, deletion/purge, strikes, bulk-action, impersonate; built 2026-08) |
| Rebuild user search index | `POST /api/v1/admin/search/users/reindex?drop=` | **[EXISTS]** |
| Read per-user audit trail | `GET /api/v1/admin/audit/users/{userId}` (+ filtered `GET /api/v1/admin/audit?userId=`) | **[EXISTS]** |

All built — A1–A16 are **[EXISTS]** (built 2026-08) on `AdminUserController`. Step-up = server calls `StepUpService.requireRecentStepUp(adminId)` **[EXISTS primitive]**, enforced via `@RequiresStepUp`; audit action = write via `AuditLogService.record` **[EXISTS]** — funneled through `AdminAuditor` on every admin mutation.

| # | Action | Proposed endpoint | Params | Danger | Step-up | Audit action |
|---|---|---|---|---|---|---|
| A1 | Directory list/search | `GET /api/v1/admin/users?q=&role=&status=&verified=&from=&to=&page=` | filters, pageable | read | no | `ADMIN_USER_LIST` (or rely on HTTP interceptor) |
| A2 | User detail (full projection) | `GET /api/v1/admin/users/{userId}` | — | read (PII) | no | `ADMIN_USER_VIEW` |
| A3 | Reveal phone/email raw | `GET /api/v1/admin/users/{userId}/pii` | — | read (high PII) | **yes** | `ADMIN_PII_REVEAL` |
| A4 | Disable account | `POST /api/v1/admin/users/{userId}/disable` body `{reason}` | reason required | **high** | **yes** | `ADMIN_USER_DISABLE` |
| A5 | Re-enable account | `POST /api/v1/admin/users/{userId}/enable` | — | medium | yes | `ADMIN_USER_ENABLE` |
| A6 | Lock account (`is_account_non_locked=false`) | `POST /api/v1/admin/users/{userId}/lock` / `.../unlock` | reason | **high** | **yes** | `ADMIN_USER_LOCK` / `_UNLOCK` |
| A7 | Force-logout-all | `POST /api/v1/admin/users/{userId}/sessions/revoke-all` | — | medium | yes | `ADMIN_SESSIONS_REVOKE_ALL` — implementation is one call: `RefreshTokenRepository.revokeAllForUser` **[EXISTS primitive]** + denylist each live sid (`SessionDenylist`) |
| A8 | Revoke one session | `DELETE /api/v1/admin/users/{userId}/sessions/{sid}` | sid | medium | yes | `ADMIN_SESSION_REVOKE` (reuse `SessionService.revoke` logic, admin-scoped) |
| A9 | 2FA reset (clear secret + disable) | `POST /api/v1/admin/users/{userId}/2fa/reset` | reason required | **critical** — classic account-takeover vector | **yes, mandatory** | `ADMIN_2FA_RESET` (+ security email to user, bypassing DND like the new-IP alert path) |
| A10 | Admin-initiated soft-delete | `POST /api/v1/admin/users/{userId}/deletion/request` | reason | **critical** | **yes** | `ADMIN_ACCOUNT_DELETE_REQUEST` — reuse `AccountLifecycleService.requestDeletion` (soft-delete + revoke-all + 30d grace) so the ONE state machine is shared |
| A11 | Cancel pending deletion | `POST /api/v1/admin/users/{userId}/deletion/cancel` | — | medium | yes | `ADMIN_ACCOUNT_DELETE_CANCEL` (reuse `cancelDeletion`) |
| A12 | Sessions list | `GET /api/v1/admin/users/{userId}/sessions` | — | read | no | interceptor |
| A13 | Login history | `GET /api/v1/admin/users/{userId}/login-events` | pageable | read | no | interceptor |
| A14 | Settings audit | `GET /api/v1/admin/users/{userId}/settings-audit` | pageable | read | no | interceptor — thin controller over `SettingsAuditService.history` **[PARTIAL]** |
| A15 | Moderation summary (strikes + reports by/against) | `GET /api/v1/admin/users/{userId}/moderation` | — | read | no | interceptor |
| A16 | Growth analytics | `GET /api/v1/admin/users/analytics?window=90d` | window | read | no | interceptor |

Explicit non-actions (by design): no badge grant/revoke, no manual verification workflow, no username/email edit on behalf of users, no password set. "Suspend" as a distinct state does not exist — `Resolution.ACCOUNT_SUSPENDED` in safety enums has no backing mechanism; until the moderation section builds it, A4/A6 (disable/lock) are the levers.

## 5. Logs surfaced in this section

| Log | Store | Read path | Status / gap |
|---|---|---|---|
| Per-user HTTP audit | Cassandra `audit_log_by_user` (partition userId, `created_at DESC`) | `GET /api/v1/admin/audit/users/{userId}` + live SSE `GET /api/v1/admin/audit/stream` | **[EXISTS]**. 180d TTL now applied on startup by `AuditSchemaInitializer` (built 2026-08) |
| Login events | PG `login_events` | self: `GET /api/v1/security/login-history`; admin: A13 **[EXISTS]** (built 2026-08) | **[EXISTS]** (built 2026-08) — `LoginEventService.record`/`recordSuccessAndAlertIfNew` wired from `AuthServiceImpl`; 1y retention via `RetentionSweepJob` |
| Settings audit | PG `settings_audit` (key, old, new, ip) | A14 **[EXISTS]** (built 2026-08) | **[EXISTS]** — 2y retention via `RetentionSweepJob` (built 2026-08) |
| Consent evidence | PG `consent_events` | self-serve `GET /api/v1/settings/consent` | **[EXISTS]** — show read-only in Data-lifecycle tab (compliance evidence, never editable) |
| OTP challenges | PG `otp_challenges` (hashed destination + code) | none (internal) | **[EXISTS]** — surface only aggregate counts (e.g. failed-OTP spike per user), never rows |
| Row-level audit stamps | `BaseAuditEntity` columns on every JPA row (who/when/ip/device/last_action) | rides each entity | **[EXISTS]** — useful in detail views ("last updated by/from") |

## 6. Analytics & KPIs

All population metrics are simple PG aggregates over `users` (+ `BaseAuditEntity.created_at`) — no new pipeline needed, only new repository queries under A16.

| Metric | Definition | Source | Chart | Status |
|---|---|---|---|---|
| Total users | `COUNT(*) WHERE deleted_at IS NULL` | `users` | stat tile | **[EXISTS]** (built 2026-08) |
| Signups/day | `COUNT(*) GROUP BY date(created_at)` (90d window) | `users.created_at` | line | **[EXISTS]** (built 2026-08) |
| Role distribution | `COUNT(*) GROUP BY role` | `users.role` | donut | **[EXISTS]** (built 2026-08) |
| Email-verified % | `email_verified_at IS NOT NULL / total` | `users` | funnel bar | **[EXISTS]** (built 2026-08) |
| Phone-verified % | `phone_verified_at IS NOT NULL / total` | `users` | funnel bar | **[EXISTS]** (built 2026-08) |
| 2FA adoption % | `two_factor_enabled / total` | `users` | funnel bar | **[EXISTS]** (built 2026-08) |
| Verification funnel | registered → email → phone → 2FA (each a subset) | `users` | ordered bars | **[EXISTS]** (built 2026-08) |
| Deletion pipeline | count per `DeletionStatus`; next `purge_after` | `account_deletion_requests` | strip | **[EXISTS]** (built 2026-08) |
| Active users (online-now proxy) | Redis `chat:presence:*` keys (30s TTL heartbeat) | `PresenceService` | stat tile | **[EXISTS]** (built 2026-08) — `onlineNow` tile on `GET /api/v1/admin/analytics/overview`; still chat-SSE-connected proxy |
| DAU / MAU | distinct users with a `login_events` SUCCESS (or audit row) per day/30d | `login_events` (wired) + daily metric rollups | line | **[EXISTS]** (built 2026-08) — `mau30d` tile on `/api/v1/admin/analytics/overview`, series via `/api/v1/admin/analytics/engagement` |
| Sessions per user (p50/p95) | non-revoked `refresh_tokens` per user | `refresh_tokens` | histogram | **[EXISTS]** (built 2026-08) — `sessions` block of `GET /api/v1/admin/users/analytics` |
| Storage top-N users | `SUM(stored_bytes) GROUP BY owner ORDER BY DESC LIMIT n` | `media_assets` | bar | **[PLANNED]** query (per-user sum **[EXISTS]**) |

### 6.1 Social-graph & close-friends stats

Follow/block/restrict aggregates live in [safety-reports.md](../trust-safety/safety-reports.md)
§3.7 (block/restrict) and [analytics-kpis.md](../platform/analytics-kpis.md) (follows/day).
**Close friends** is the piece owned here — it gates story visibility
(`CLOSE_FRIENDS` audience) and the privacy resolver's `CLOSE_FRIENDS` level:

| Metric | Definition | Source | Chart | Status |
|---|---|---|---|---|
| Close-friends adoption | % active users with ≥1 close-friends entry | `close_friends_list` (`CloseFriendsRepository`) | stat tile | **[EXISTS]** (built 2026-08) — `closeFriends` block of `GET /api/v1/admin/users/analytics` |
| List-size distribution | p50/p95 entries per adopter | same, `GROUP BY owner` | histogram | **[EXISTS]** (built 2026-08) |
| Close-friends-only story share | % of stories posted with `CLOSE_FRIENDS` visibility | story entities (Cassandra, visibility field) | line | **[PLANNED]** — needs a daily rollup; raw stories expire so count at post time |
| Privacy `CLOSE_FRIENDS` policy usage | % of `user_privacy` policies using the level | `user_privacy` JSONB | stat tile | **[PLANNED]** (GIN-index query) |

These are population aggregates only — **an admin never sees who is on whose
close-friends list** except inside an audited per-user inspection with cause
(§8 boundary).

## 7. Alerts & thresholds

| Alert | Condition | Feeds from | Severity |
|---|---|---|---|
| Signup spike | signups/hour > 5× trailing-7d hourly mean | signups query (§6) | warn (bot wave) |
| Failed-login burst per account | ≥10 `FAILED` login_events / 15min | `login_events` — seeded `LogAlertSweepJob` rule (built 2026-08) | high |
| Failed-login burst per IP | ≥30 `FAILED` across accounts from one ip / 15min | `login_events.ip` — seeded `LogAlertSweepJob` rule (built 2026-08) | high |
| Admin-role change | any A-ladder move to/from `ADMIN` | audit action `ADMIN_USER_ROLE_CHANGE` | critical — page immediately, also fires on the audit SSE stream **[EXISTS]** |
| 2FA reset performed | any A9 | `ADMIN_2FA_RESET` audit row | critical |
| Deletion-request spike | >X requests/day (churn signal) | `account_deletion_requests` | info |
| Purge job silence | no `[ACCOUNT-DELETE]` log line for >48h while PENDING rows past `purge_after` exist | purge cron `0 30 3 * * *` **[EXISTS]** | high |
| Empty login_events | table still empty N days post-deploy | trivially detectable | regression detector — the writer was wired 2026-08 (§5) |

Delivery: **[EXISTS]** (built 2026-08) — the `/api/v1/admin/logs` alert rules + `LogAlertSweepJob` (6 seeded rules, incl. both failed-login bursts). The admin audit SSE stream `GET /api/v1/admin/audit/stream` **[EXISTS]** additionally drives dashboard toasts for the two critical rows.

## 8. Permissions & safety notes

- Every proposed endpoint goes under `/api/v1/admin/**` → inherits the filter-chain `hasRole("ADMIN")` double gate **[EXISTS]**; add `@PreAuthorize("hasRole('ADMIN')")` per controller anyway (house pattern).
- **Step-up** on all mutations of another person's security posture (A3–A11): `StepUpService.requireRecentStepUp` **[EXISTS]**, Redis `stepup:{userId}` window (default 300s). 2FA reset (A9) must additionally email the target (security notification path bypasses DND).
- **Self-protection rules**: an admin must not disable/lock/delete/demote **themselves**; demoting the *last* ADMIN must be rejected — **[EXISTS]** (built 2026-08): `AdminUserServiceImpl.changeRole` carries `requireNotSelf` plus the last-admin guard (`LAST_ADMIN` conflict).
- **Audit everything**: the HTTP interceptor already records admin requests to `audit_log_by_user` **[EXISTS]**, but semantic actions also call `AuditLogService.record(...)` with a business summary — funneled through `AdminAuditor` on every admin mutation (built 2026-08).
- **PII discipline**: mask email/phone in list views; raw reveal only via A3 with step-up. Never surface `two_factor_secret`, refresh `token` values, `otp_challenges` hashes, or password columns in any projection.
- Role changes take effect on next token issue — a demoted ADMIN's live JWT still carries `ROLE_ADMIN` until expiry. Pair A-ladder demotions with A7 force-logout (revoke-all + sid denylist) to make them immediate.
- `PATCH .../role` now carries `@RequiresStepUp` and writes an `AdminAuditor` business-audit row (built 2026-08).

## 9. Build order / dependencies

1. **Wire `login_events`** — call `LoginEventService.record`/`recordSuccessAndAlertIfNew` from `AuthServiceImpl` login/refresh/2FA paths. Zero-risk, unblocks login-history tab, DAU/MAU, and both failed-login alerts. Everything downstream that reads this table is decorative until this lands.
2. **Read-only admin user API** (A1, A2, A12–A15) — pure projections over existing repos/services (`findActiveByRoles`, `searchUsersFts`, `UserStatsService`, `SessionService`-style read of `refresh_tokens`, `SettingsAuditService.history`, `reports`/`user_strikes` queries). No new state, immediate dashboard value.
3. **Growth analytics queries + A16** — five GROUP-BY queries on `users` + one on `account_deletion_requests`.
4. **Force-logout (A7/A8)** — assembles existing primitives (`revokeAllForUser` + `SessionDenylist`); prerequisite for safe role-demotion UX and for step 5.
5. **Account controls (A4–A6, A10–A11)** — first real admin mutations: enable/disable/lock toggles (columns already enforced by `User.isEnabled()`/UserDetails) + admin hooks into `AccountLifecycleService`. Requires the step-up + `AuditLogService.record` wiring pattern established here.
6. **2FA reset (A9)** — last, after step-up + audit + security-email patterns are proven; highest-risk action in the section.
7. **Hygiene riders**: apply the missing 180d TTL `ALTER TABLE` on both Cassandra audit tables; add last-admin demotion guard to `AdminUserService.changeRole`; fix the phantom `SUPER_ADMIN` in `AuditLogController`'s `@PreAuthorize` while touching admin auth.

Cross-section dependencies: moderation section owns report-triage/strike-issuance (this section only *reads* `reports`/`user_strikes`); data-lifecycle deep view (export ZIP handling, purge internals) belongs to [../settings/data-export-deletion.md](../../settings/data-export-deletion.md); alerts delivery depends on the ops section standing up actuator/metrics exposure.
