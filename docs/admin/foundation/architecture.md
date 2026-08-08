# Admin Dashboard — Architecture & Access

Section 0 of the [admin dashboard plan](../README.md). The foundation doc: how admin access
works **today**, the shape of the dashboard we propose to build on top of it, the API
conventions every new admin endpoint must follow, the complete inventory of admin surfaces
that already exist, and the RBAC / impersonation evolution path. Read this before any
section doc — every other doc assumes the conventions defined here.

| Tag | Meaning |
|-----|---------|
| **[EXISTS]** | Implemented in the backend today (class / `METHOD /path` cited) |
| **[PARTIAL]** | Partly real; the row says exactly which part |
| **[PLANNED]** | Proposed for the dashboard build — not yet coded |

---

## 1. Purpose & scope

| In scope | Out of scope (see section doc) |
|----------|-------------------------------|
| Admin access model as implemented (role, gates, escape hatch) | Per-section widgets/actions — [partition map](#9-partition-map) |
| Dashboard app shape (SPA, SSE live tiles, auth flow) | Log catalog details — [logs-audit.md](../platform/logs-audit.md) |
| API conventions binding on ALL new `/api/v1/admin/**` endpoints | KPI definitions — [analytics-kpis.md](../platform/analytics-kpis.md) |
| Inventory of every existing admin surface | Full endpoint list — [admin-api-blueprint.md](api-blueprint.md) |
| RBAC evolution (sub-admin roles), impersonation policy | Ops/runbooks — [operations.md](../platform/operations.md) |

## 2. Admin access model — as it EXISTS

The platform has **seven roles**: `USER`, `RESEARCHER`, `SCHOLAR`, the staff tiers
`MODERATOR` / `SUPPORT` / `ANALYST` (the §6 RBAC widening landed 2026-08), and `ADMIN`
(`user/enums/Role.java`). Badges derive from role; there is no separate verification
workflow.

| Layer | Mechanism | Status |
|-------|-----------|--------|
| Filter chain (belt) | `SecurityConfig.securityFilterChain` — `requestMatchers("/api/v1/admin/**").hasRole("ADMIN")` when `permit-all=false`, then `anyRequest().permitAll()` (`config/SecurityConfig.java:98-103`). A forgotten annotation can never expose an `/api/v1/admin/**` route. | **[EXISTS]** |
| Method security (braces) | `@PreAuthorize("hasRole('ADMIN')")` on every admin controller/method, enforced by `MethodSecurityConfig`. This is the platform-wide convention — the chain stays permissive so optional-auth endpoints keep working. | **[EXISTS]** |
| Escape hatch | `app.security.permit-all` (env `SECURITY_PERMIT_ALL`, **default `false`**) skips the chain-level admin rule for local testing. `@PreAuthorize` still applies unless method security is bypassed by the same flag's design; treat `SECURITY_PERMIT_ALL=true` as local-only. | **[EXISTS]** |
| Auth transport | Stateless JWT Bearer (`JwtAuthFilter`), no sessions, CSRF disabled. SSE endpoints accept `?token=` fallback (EventSource cannot set headers). | **[EXISTS]** |
| Step-up for sensitive ops | `security/stepup/StepUpService` — short-TTL Redis marker `stepup:{userId}` (TTL `STEP_UP_TTL_SECONDS`, default 300s) armed by `POST /api/v1/security/step-up` (fresh password or 2FA code). Used by the settings module and — since 2026-08 — required on sensitive admin endpoints via `@RequiresStepUp` (`admin/support/StepUpGuardInterceptor`). See [../settings/auth-sessions.md](../../settings/auth-sessions.md). | **[EXISTS]** (built 2026-08) |

### Known gate defects (fix during phase 1)

| Defect | Detail | Fix |
|--------|--------|-----|
| Stray admin endpoints outside the prefix | `PUT /api/v1/channels/{id}/verified` (`ChannelController`) and `POST /api/v1/sounds/{id}/approve` (`CassandraSoundController`) are ADMIN-gated by annotation only — no filter-chain double gate, and both are open under `SECURITY_PERMIT_ALL=true`. | **[EXISTS]** (built 2026-08) — aliases live (`PATCH /api/v1/admin/channels/{id}/verified`, `POST /api/v1/admin/sounds/{id}/approve`); strays deprecated with successor-version `Link` headers |
| Phantom roles in grants | `AuditLogController` and `CassandraSoundController` were both normalized to `hasRole('ADMIN')` before the enum widened to 7 roles. Residual: ~10 `hasAnyRole(…,'SUPER_ADMIN')` grants linger in `research/controller/ResearchController` (see §6). | **[EXISTS]** (built 2026-08) — normalized, `ResearchController` stragglers remain |
| Actuator ungated | Filter chain only gates `/api/v1/admin/**`; whatever actuator endpoints are exposed (default: health only) are reachable unauthenticated. | **[PLANNED]** gate `/actuator/**` when exposure widens; see [operations.md](../platform/operations.md) |

### Programmatic admin bypasses (not endpoints) — **[EXISTS]**

Service-layer short-circuits that already give ADMIN moderation power without dedicated endpoints:
`ResearchServiceImpl` (~L2123, ~L2274) and `QuestionServiceImpl` (~L1617-1660) skip ownership
checks when `user.getRole()==Role.ADMIN` (admin can delete/moderate others' research and Q&A).
`NotificationEventConsumer` fans some events to `List.of(Role.SCHOLAR, Role.ADMIN)`. The dashboard
should surface these powers explicitly rather than leaving them as hidden code paths.

## 3. Proposed dashboard shape — **[PLANNED]**

| Decision | Proposal | Rationale |
|----------|----------|-----------|
| App form | **Separate route-tree inside the existing ika React app** (`/Users/khi/Documents/ika`, Vite :5173) at `/admin/**`, code-split, rendered only for `role==='ADMIN'`. Standalone SPA is the fallback if bundle isolation or deploy cadence demands it — the API contract (`/api/v1/admin/**`) is identical either way. | Reuses ika's auth/token plumbing, API mappers, SSE helpers; one deploy pipeline |
| Backend | No new server — the dashboard is a pure client of `/api/v1/admin/**` on the Spring app (:8080) | Everything admin already lives (or will live) behind one prefix + one gate |
| Live tiles | Reuse the **existing audit realtime stream**: `GET /api/v1/admin/audit/stream` **[EXISTS]** (SseEmitter; events `connected` / `audit` / `heartbeat` 25s; fanned out cross-instance via Redis pub/sub `irc:audit:stream` through `AuditRealtimeService`). The shell subscribes once; widgets filter the event flow client-side (by `operation`, `path` prefix, `resourceType`). New admin SSE streams are added only when a section needs non-audit events. | Zero new realtime infra for phase 1; the audit log already sees every authenticated `/api/**` request |
| Shell layout | Left nav = the 12 sections of the [partition map](#9-partition-map); top strip = dependency health chips ([operations.md](../platform/operations.md)) + live audit ticker; global search over users/content ([users-roles.md](../users/directory-and-roles.md)) | — |
| Auth flow | Normal login → JWT; dashboard checks role claim; destructive screens call `POST /api/v1/security/step-up` and retry with the step-up marker armed (§4) | Reuses the settings module's proven step-up flow |

## 4. API conventions — binding on every new admin endpoint — **[EXISTS]** (built 2026-08)

All section docs propose endpoints against these rules; [admin-api-blueprint.md](api-blueprint.md) enforces them across the full list.

| Rule | Convention |
|------|-----------|
| Prefix & gate | Everything under `/api/v1/admin/{section}/...` — inherits the filter-chain double gate automatically. Class-level `@PreAuthorize("hasRole('ADMIN')")` mandatory anyway (both layers, always). No admin capability may ship outside the prefix again. |
| Response shape | **No envelope.** Raw DTO (or `Page<DTO>`) in `ResponseEntity<T>`, matching the rest of the API. Errors use the canonical error envelope of [../errors/error-handling.md](../../errors/error-handling.md). |
| Pagination | Spring `Pageable` (`page`, `size`, `sort`) with **`Pages.clamp`** applied server-side (existing platform pattern) — hard cap `size<=100` for admin lists. Cassandra-backed lists use cursor keyset params (`cursor`, `pageSize`) exactly like `AuditLogController` does today. |
| Date ranges | `from` / `to` as ISO-8601 instants, both optional, `from<=to` validated, defaulting to last 24h for logs and last 30d for analytics. |
| Filters | Consistent names across sections: `userId`, `status`, `type`, `q` (free text), `sort`. Enums passed by name, parsed leniently (case-insensitive, 400 with the allowed values on miss). |
| Audit trail | **Every admin mutation writes an audit row** via `AuditLogService.record(userId, username, operation, resourceType, resourceId, summary)` — the service-layer helper, funneled through `admin/support/AdminAuditor` since the 2026-08 build **[EXISTS]**. The HTTP interceptor already captures the request; the explicit `record` call adds the business-event row (`operation` per action, e.g. `UPDATE`/`DELETE`, summary = human-readable action). Each action table in the section docs names its audit action. |
| Step-up | Every action marked danger **high** or **critical** requires an armed step-up marker (`StepUpService.require(userId)` → 403 `STEP_UP_REQUIRED` when absent) per [../settings/auth-sessions.md](../../settings/auth-sessions.md). Read endpoints never require step-up. |
| Danger levels | `low` = read-only / reversible metadata; `medium` = reversible mutation (mute, unverify); `high` = user-impacting or hard-to-reverse (takedown, force-stop, strike); `critical` = irreversible or account-level (ban, purge, key rotation). Used in every "Admin actions" table platform-wide. |
| Idempotency | Mutations accept the existing `Idempotency-Key` header (24h replay via `IdempotencyFilter`) — free, already global for mutating methods. |
| Long-running work | Anything slower than ~5s (reindex-scale) returns `202` + a job id, never blocks like `SearchAdminController` does today (existing 7 reindexes stay synchronous **[EXISTS]** until migrated). |

## 5. Inventory — EXISTING admin surfaces (complete)

Everything an ADMIN can do via HTTP today. "Gate" column: **double** = prefix + annotation; **annotation** = `@PreAuthorize` only.

| # | Surface | Endpoint(s) | Gate | Status | Notes |
|---|---------|-------------|------|--------|-------|
| 1 | Role change | `PATCH /api/v1/admin/users/{userId}/role` (`AdminUserController` → `AdminUserService.changeRole`) | double | **[EXISTS]** | Now one of ~30 user-management routes (built 2026-08): disable/enable, lock/unlock, admin soft-delete/restore, purge now/hold, and bulk-action all exist on `AdminUserController`; only a distinct "suspend" state is still absent. Moves users along the 7-role ladder, badge auto-derives. |
| 2 | Search reindex ×7 | `POST /api/v1/admin/search/{research\|posts\|questions\|users\|channels\|answers\|sounds}/reindex?drop=` (`SearchAdminController`) | double | **[EXISTS]** | Synchronous, returns final counts; `drop=true` (default) recreates the index (mapping-repair path). Chat-messages index has no hook by design. |
| 3 | Tag backfill | `POST /api/v1/admin/tags/backfill-posts` (`TagAdminController`) | double | **[EXISTS]** | Full token-range scan of `posts_by_id` → `content_by_tag`. Trending counter bumps are NOT idempotent — do not re-run casually. |
| 4 | Audit browser | `GET /api/v1/admin/audit` (requires `?userId`, 400 without — Cassandra partition scope; `operation`/`outcome`/`from`/`to` filtered in-memory), `GET /api/v1/admin/audit/users/{userId}` (cursor keyset) (`AuditLogController`) | double | **[EXISTS]** | Per-user partitions only; the SSE stream is the only global view. `audit_log_by_resource` is written but has **no read endpoint** — see [logs-audit.md](../platform/logs-audit.md). |
| 5 | Audit live stream | `GET /api/v1/admin/audit/stream` (SSE via `AuditRealtimeService`, Redis `irc:audit:stream`) | double | **[EXISTS]** | The dashboard's live-tile backbone (§3). |
| 6 | Channel verified badge | `PUT /api/v1/channels/{id}/verified?verified=` (`ChannelController` → `ChannelService.setVerified`) | **annotation** | **[EXISTS]** | Outside the prefix — no double gate. Alias `PATCH /api/v1/admin/channels/{id}/verified` **[EXISTS]** (built 2026-08, `AdminChannelController`); stray deprecated with successor-version `Link`. |
| 7 | Sound approval | `POST /api/v1/sounds/{id}/approve` (`CassandraSoundController` → `CassandraSoundService.approve`); upload `autoApprove` honored only for `Role.ADMIN` | **annotation** | **[EXISTS]** | Outside the prefix; normalized to `hasRole('ADMIN')` + deprecated with successor `Link` → `POST /api/v1/admin/sounds/{id}/approve`. Pending queue + reject/archive/takedown landed 2026-08 under `/api/v1/admin/sounds` ([content-moderation.md](../trust-safety/content-moderation.md)). |
| 8 | Channel stats | `GET /api/v1/channels/{id}/stats` (`ChannelStatsService`) | channel-scoped | **[PARTIAL]** | Gated to that channel's owner/admin **member** — a platform ADMIN who is not a member gets 403; no override path. |
| 9 | Service-layer bypasses | Research/QnA moderation via `Role.ADMIN` short-circuits (§2) | n/a | **[EXISTS]** | Powers without endpoints; dashboard makes them explicit. |
| 10 | MediaMTX control API | `:9997` `/v3/...` via `MediaControlClient` (kick publisher, path config); auth hook `POST /internal/media/auth/{secret}` | machine-to-machine | **[EXISTS]** | Backend/localhost only, not role-gated — building blocks for live-stream force-stop ([chat-channels-live.md](../communication/chat-channels-live.md)). |

**Historical snapshot — superseded by the 2026-08 build.** Originally only four controller
prefixes lived under `/api/v1/admin/**` (`/admin/users`, `/admin/search`, `/admin/tags`,
`/admin/audit`), with rows 6-7 as the two strays. Today ~25 admin controllers serve the
prefix — activity, analytics, chat/channels/streams, content, discovery, feed (×2),
impersonation, knowledge, logs, media (×2), moderation, notification, ops, qna, research,
safety, search-ops, sound, support, trending — plus the four originals; the strays are
deprecated aliases. See [api-controllers.md](api-controllers.md) and
[known-issues.md](../known-issues.md).

## 6. RBAC evolution — sub-admin roles — **[EXISTS]** (built 2026-08)

`Role` was widened with three staff tiers (built 2026-08), mapped to dashboard sections per the matrix below.

| Role | Dashboard sections (RW) | Sections (RO) | Cannot |
|------|------------------------|---------------|--------|
| `MODERATOR` | Content moderation (2), Safety & reports (5), moderation parts of Research/QnA (3) and Chat/Live (4) | Users (1), Logs (9) | Change roles, touch ops (11), search reindex, see analytics exports |
| `SUPPORT` | User inspection + non-destructive account aids (1), report intake view (5) | Notifications (7), Logs (9, own-scope) | Any takedown, any role change, any ops control |
| `ANALYST` | — | Analytics (10), Logs (9), Search/feed observability (8) | Every mutation — strictly read-only |
| `ADMIN` | Everything | — | — (critical ops still require step-up) |

Implementation notes:

| Concern | Note |
|---------|------|
| Enum widening | Adding values to `@Enumerated(STRING) Role` on an existing DB trips the stale CHECK constraint — add the `(users, users_role_check)` pair to `EnumCheckConstraintReconciler` (`config/`) in the same change. |
| Gate layering | Filter chain gains `requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN","MODERATOR","SUPPORT","ANALYST")`; per-section method grants narrow from there (`hasAnyRole` per controller). Deny-by-default: a new section doc must state its role matrix. |
| Dead grants become live | The sound-approve and audit-endpoint grants were normalized to `hasRole('ADMIN')` before widening — but the sweep missed `research/controller/ResearchController` (~L103-252), which still carries ~10 `hasAnyRole(…,'SUPER_ADMIN')` grants post-widening (the `Role` javadoc's "were normalized before this enum widened" claim is wrong for that file). `SUPER_ADMIN` stays deleted, not implemented — normalize the stragglers. |
| Role changes are critical | `PATCH /admin/users/{id}/role` becomes danger **critical** + step-up once it can mint staff; ADMIN-only forever (staff roles cannot grant roles). |

## 7. Impersonation — "view as user" — **[EXISTS]** (built 2026-08)

| Rule | Detail |
|------|--------|
| Read-only, always | Impersonation issues a special short-lived token whose authority is `IMPERSONATED_READ` — accepted only by `GET` handlers; every mutation 403s regardless of the target user's own rights. No message-content access: chat content stores stay off-limits exactly as for admins ([chat-channels-live.md](../communication/chat-channels-live.md) privacy boundary). |
| Entry | `POST /api/v1/admin/users/{userId}/impersonate` — danger **critical**, step-up required, reason string mandatory (`reason` param, min 10 chars). TTL ≤ 15 min, single session, revocable via the session denylist (`sid:denied:{sid}`). |
| Audit | Start/stop each write an audit row (`operation=OTHER`, `resourceType=User`, summary `IMPERSONATE_START/END + reason`); every request made under the token is interceptor-audited under the **admin's** userId with an `impersonating={targetId}` marker in `queryString`, so the audit trail never attributes actions to the victim. |
| Visibility | Target-user notification is a product decision; the audit trail is not optional. Impersonation events surface in [logs-audit.md](../platform/logs-audit.md) as a first-class filter and count as a KPI in §11 of that doc. |
| Non-goals | No write-impersonation, ever. Support flows that need mutations get purpose-built admin actions with their own audit rows instead. |

## 8. This section's dashboard presence (shell-level views)

Architecture & access is mostly conventions, but it owns the dashboard **shell** and the staff-security screen.

### Views / widgets

| Widget | Content | Source | Status |
|--------|---------|--------|--------|
| Live audit ticker (shell top strip) | Rolling last-N admin+platform audit events, filter chips by operation/outcome | SSE `GET /api/v1/admin/audit/stream` | **[EXISTS]** (API) / **[PLANNED]** (UI) |
| Staff roster | All ADMIN (later staff-role) accounts, last login, 2FA on/off, open sessions | `users` table filtered by role; sessions per [../settings/auth-sessions.md](../../settings/auth-sessions.md) | **[PLANNED]** UI (`GET /api/v1/admin/users?role=` **[EXISTS]**, built 2026-08 — [users-roles.md](../users/directory-and-roles.md)) |
| Gate health card | `permit-all` flag state, stray admin endpoints count, phantom-grant count | Static config surface — [operations.md](../platform/operations.md) env registry | **[PLANNED]** UI (`GET /api/v1/admin/ops/config` + `/config/reconciler` **[EXISTS]**, built 2026-08) |
| Admin action feed | Audit rows where path starts `/api/v1/admin/` — who did what, when | Same audit stream/API, client-filtered | **[EXISTS]** (data) / **[PLANNED]** (UI) |

### Admin actions owned by this section

| Action | Endpoint | Params | Danger | Step-up | Audit action | Status |
|--------|----------|--------|--------|---------|--------------|--------|
| Re-home channel verify | `PATCH /api/v1/admin/channels/{id}/verified` | `verified` bool | medium | no | `UPDATE Channel VERIFY` | **[EXISTS]** (built 2026-08 — `AdminChannelController`, wraps `ChannelService.setVerified`) |
| Re-home sound approve | `POST /api/v1/admin/sounds/{id}/approve` | — | medium | no | `UPDATE Sound APPROVE` | **[EXISTS]** (built 2026-08 — `AdminSoundController`, wraps `CassandraSoundService.approve`) |
| Grant/revoke staff role | `PATCH /api/v1/admin/users/{userId}/role` | `role` | critical | **yes** | `UPDATE User ROLE_CHANGE` | **[EXISTS]** endpoint + step-up (built 2026-08) |
| Start impersonation | `POST /api/v1/admin/users/{userId}/impersonate` | `reason` | critical | **yes** | `OTHER User IMPERSONATE_START` | **[EXISTS]** (built 2026-08) |
| End impersonation | `DELETE /api/v1/admin/impersonation` | — | low | no | `OTHER User IMPERSONATE_END` | **[EXISTS]** (built 2026-08) |

### Logs surfaced here

Shell-level only: the global audit SSE tail and the `/api/v1/admin/**`-filtered admin-action
feed (both from the audit module **[EXISTS]**). The full log catalog — every store, schema,
writer, retention — is [logs-audit.md](../platform/logs-audit.md).

### Analytics & KPIs

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Admin actions/day | Count of audit rows with path prefix `/api/v1/admin/` | `audit_log_by_user` (needs aggregate query — per-user partitions today) | bar, 30d | **[PLANNED]** |
| Actions by admin | Same, grouped by admin userId | `GET /api/v1/admin/audit?userId=` per staff account | stacked bar | **[PARTIAL]** (per-user query exists; rollup manual) |
| Live audit subscribers | Admins with an open audit SSE connection | `AuditRealtimeService.adminCount()` — exposed via `GET /api/v1/admin/ops/sse` | stat tile | **[EXISTS]** (built 2026-08) |
| Step-up challenges (admin) | Step-up arms/denials by staff | `stepup:{userId}` events — not currently logged | line | **[PLANNED]** |
| Impersonation sessions | Count + total duration per week | impersonation audit rows | table | **[PLANNED]** |

### Alerts & thresholds

| Alert | Condition | Severity | Status |
|-------|-----------|----------|--------|
| Permit-all in production | `app.security.permit-all=true` outside local | critical, page | **[PLANNED]** |
| Admin action outside prefix | Audit row: ADMIN-gated mutation on a non-`/admin/**` path (regression detector for §2 strays) | warn | **[PLANNED]** |
| Role change to ADMIN | Any `ROLE_CHANGE` audit row granting ADMIN | info → staff channel, always | **[PLANNED]** |
| Impersonation started | Every `IMPERSONATE_START` | info, always visible in shell ticker | **[PLANNED]** |
| Audit stream silent | No `audit` events for >5 min while traffic exists | warn (stream or interceptor broken) | **[PLANNED]** |

## 9. Partition map

The 12 sections + API blueprint (mirrors [README.md](../README.md); one doc per section).

| # | Doc | One-line scope |
|---|-----|----------------|
| 0 | architecture.md (this doc) | Access model, API conventions, existing-surface inventory, RBAC evolution, impersonation |
| 1 | [users-roles.md](../users/directory-and-roles.md) | User directory/inspection, roles & badges, account controls, sessions/2FA, deletion pipeline, growth |
| 2 | [content-moderation.md](../trust-safety/content-moderation.md) | Post/comment/story/reel queues, sound approval queue, platform keyword blocklist, bulk actions |
| 3 | [research-qna.md](../content/research-qna.md) | Research pipeline/IRC-id/downloads, QnA oversight, tags & trending admin |
| 4 | [chat-channels-live.md](../communication/chat-channels-live.md) | Chat privacy boundary, channel verification/stats, invites, live-stream force-stop/keys/recordings, gifts |
| 5 | [safety-reports.md](../trust-safety/safety-reports.md) | Report triage queue, strikes, appeals, consent viewer, moderation SLAs |
| 6 | [media-storage.md](../content/media-storage.md) | Media pipeline board, failed-media queues, dedup/tiers, storage usage, R2 lifecycle |
| 7 | [notifications-email.md](../communication/notifications-email.md) | Notification volume, email deliverability, digest job, announcement composer |
| 8 | [search-feed-trending.md](../platform/search-feed-trending.md) | ES index health + the 7 reindexes, trending controls, feed-ranking observability, suggestions |
| 9 | [logs-audit.md](../platform/logs-audit.md) | Complete log-store catalog, unified Log Explorer, retention, alert rules |
| 10 | [analytics-kpis.md](../platform/analytics-kpis.md) | KPI tree, per-module metrics with honest sourcing, event-collection proposal |
| 11 | [operations.md](../platform/operations.md) | Dependency health, 16 scheduled jobs, RabbitMQ/DLQ, SSE/Redis ops, env registry, runbooks |
| 12 | [admin-api-blueprint.md](api-blueprint.md) | Every admin endpoint (existing + proposed), danger levels, phased build order |

## 10. Permissions & safety notes

- **Two layers, always**: prefix double-gate + `@PreAuthorize`. An endpoint protected by only one is a defect (§2 has the two current offenders).
- **Deny-by-default for new roles**: until §6 lands, every proposal in the section docs is ADMIN-only; role matrices in section docs are forward-looking.
- **Admins never see message content**: chat/DM bodies (Cassandra `message_by_conversation`, ES `irc-chat-messages`, recordings, R2 media) are out of bounds; even `conversations.last_message_preview` must be excluded from admin projections. Canonical statement in [chat-channels-live.md](../communication/chat-channels-live.md).
- **Secrets never render**: `live_streams.stream_key`, `stream_guests.publish_key` (stored plaintext by necessity) are excluded from every admin DTO.
- **Everything audited**: interceptor row for free on every admin request + explicit `AuditLogService.record` business row on every mutation (§4). The audit trail is itself admin-readable — staff actions are visible to all staff.
- **Step-up is not optional** for high/critical actions; the marker TTL (5 min) means a step-up covers a burst of related actions, not a whole shift.

## 11. Build order / dependencies

| Phase | Work | Depends on |
|-------|------|-----------|
| 1a | Dashboard shell in ika: auth/role gate, nav, audit SSE ticker, existing-surface screens (role change, 7 reindexes, tag backfill, audit browser) | Nothing — 100% **[EXISTS]** APIs |
| 1b | Convention scaffolding: `Pages.clamp` on admin lists, `AuditLogService.record` wiring pattern, step-up guard helper for admin controllers | §4 sign-off |
| 1c | Re-home the two stray endpoints (aliases + deprecation) | 1b (audit rows on the new routes) |
| 2 | Read-only section screens (users directory, log explorer, analytics phase-1 from computable-today sources) | 1a; new read endpoints per section docs |
| 3 | Mutations: moderation actions, safety triage, live-stream force-stop — each with audit row + step-up | 1b conventions proven; section docs 2/4/5 |
| 4 | RBAC widening (§6, incl. `EnumCheckConstraintReconciler` entry + grant audit) → per-role nav | Phase 3 stable (roles need actions to scope) |
| 5 | Impersonation (§7) | Phase 4 (needs authority model) + session denylist reuse |

Full sequencing with per-endpoint ordering: [admin-api-blueprint.md](api-blueprint.md).
