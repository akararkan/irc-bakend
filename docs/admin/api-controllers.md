# Admin API — Controller Reference

The admin API from the **controller/implementation** angle: every real
`@RestController` that serves an admin route today (exact mappings, DTOs, security
annotations, and the service each delegates to), the two **stray** admin-power
endpoints that live *outside* the admin prefix, the controller-layer **security
model**, and the **proposed controller layout** for the planned endpoints — so the
build knows which class each new route belongs on and what conventions bind it.

> **Relationship to the [API blueprint](admin-api-blueprint.md):** the blueprint is
> the **endpoint table** (every path, its danger level, its audit action, the phased
> order). *This* doc is the **controller reference** (the Java classes, the real
> code, the DTO shapes, and the class-by-class build plan). Blueprint = *what
> endpoints*; this = *what controllers*. They cross-link section-for-section.

Tag legend and ground rules: [README.md](README.md). Security mechanics:
[architecture.md](architecture.md). Related:
[operations.md](operations.md) §7 (the `permit-all` kill-switch, env registry).

Status legend: **[EXISTS]** = real controller in the codebase today (file cited) ·
**[PARTIAL]** = a stray/half-wired variant exists · **[PLANNED]** = proposed here.

---

## 1. The controller-layer security model

Every admin controller inherits a **two-gate** model — neither gate alone is
trusted:

| Gate | Where | Effect |
|------|-------|--------|
| **Chain-level prefix gate** | `config/SecurityConfig` — `auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")` | Any route under `/api/v1/admin/**` requires `ROLE_ADMIN` even if a `@PreAuthorize` is forgotten. **[EXISTS]** |
| **Method-level gate** | `@PreAuthorize("hasRole('ADMIN')")` on the class or method | Belt-and-braces; also the *only* gate for admin-power routes that live **outside** the prefix (the two strays, §4). **[EXISTS]** |

Key facts the build must respect:

- **The kill-switch.** `SecurityConfig` is `@ConditionalOnProperty(app.security.permit-all=false, matchIfMissing=true)` with `@EnableMethodSecurity`. When `app.security.permit-all=true` (env `SECURITY_PERMIT_ALL`), method security is **off** and the prefix matcher is **skipped** — a local-only escape hatch. In prod this flag being true is a critical alert ([operations.md](operations.md), [admin-api-blueprint.md](admin-api-blueprint.md) §7). Default (`false`/absent) = fully locked.
- **`anyRequest().permitAll()`** — the chain is otherwise permissive; per-endpoint auth outside the admin prefix is enforced by `@PreAuthorize` / manual principal checks on each controller, **not** by the chain. So a new admin route **must** sit under `/api/v1/admin/**` to inherit the prefix gate.
- **Phantom roles — normalized (built 2026-08).** `AuditLogController` and the sound-approve stray are now plain `hasRole('ADMIN')`, and `Role` has since widened to seven values (`MODERATOR`/`SUPPORT`/`ANALYST` staff tiers are live and used in per-section admin grants). `SUPER_ADMIN` remains phantom — residual `hasAnyRole(…,'SUPER_ADMIN')` grants linger outside the admin surface in `research/controller/ResearchController`; never render `SUPER_ADMIN` as grantable.
- **STATELESS + JWT.** No sessions/CSRF/formLogin; the JWT filter populates the security context, so `@PreAuthorize` sees the authorities from `User.getAuthorities()`.

---

## 2. Existing admin controllers (the real surface)

Four controllers originally served routes under `/api/v1/admin/**`. Since the 2026-08
build, **~29 admin controllers serve well over 100 endpoints** under the prefix — the
`admin/**` package (§6) plus the extended `AdminUserController`, `TagAdminController`,
`AuditLogController`, `SearchAdminController` and the new `AdminSearchOpsController`.
The four founding controllers are detailed below.

### 2.1 `AdminUserController` **[EXISTS]**

`user/controller/AdminUserController` · base `@RequestMapping("/api/v1/admin/users")`
· delegates to `AdminUserService`.

| Method | Path | Auth | Body | Returns | Service |
|--------|------|------|------|---------|---------|
| `PATCH` | `/{userId}/role` | `@PreAuthorize("hasRole('ADMIN')")` | `AdminChangeRoleRequest` `{ role: Role (@NotNull), reason?: String (≤500) }` | `UserResponse` | `adminUserService.changeRole(userId, req)` |

- **Now one of ~30 mappings on this controller** (built 2026-08: create/bulk/invite/edit,
  credentials, disable/enable/lock/unlock, sessions, deletion/purge, strikes,
  bulk-action, impersonation). Promote/demote along the 7-role
  ladder; the auto-derived badge updates on next read. Users can't self-promote.
- A now-removed `/account-type` endpoint (+ `AdminChangeAccountTypeRequest`) was
  retired with the AccountType/VerificationTier cleanup — role is the only knob.
- `reason` is captured in the user's **entity audit** (`BaseAuditEntity.audit`,
  "Role X → Y (reason)"), not the Cassandra business-audit (see §5).
- **This is the class the full add-users & full-control surface extended** — see
  [user-administration.md](user-administration.md) and §6.1 below.

### 2.2 `SearchAdminController` **[EXISTS]**

`common/search/controller/SearchAdminController` · base `/api/v1/admin/search` ·
**7 synchronous reindex hooks**, each `@PreAuthorize("hasRole('ADMIN')")`, each with
a `?drop=` toggle (default `true`).

| Method | Path | Returns | Service |
|--------|------|---------|---------|
| `POST` | `/research/reindex` | `ReindexResult` | `researchSearchService.reindexAllPublished(drop)` |
| `POST` | `/posts/reindex` | `ReindexSummary` | `postSearchService.reindexAll(drop)` |
| `POST` | `/questions/reindex` | `ReindexSummary` | `qnaSearchService.reindexAll(drop)` |
| `POST` | `/users/reindex` | `ReindexSummary` | `userSearchService.reindexAll(drop)` |
| `POST` | `/channels/reindex` | `ReindexSummary` | `channelSearchService.reindexAll(drop)` |
| `POST` | `/answers/reindex` | `ReindexSummary` | `answerSearchService.reindexAll(drop)` |
| `POST` | `/sounds/reindex` | `ReindexSummary` | `soundSearchService.reindexAll(drop)` |

- **`drop=true`** deletes+recreates the index from the current entity mapping (lands
  new `@Field`s, repairs a drifted dynamic mapping); **`drop=false`** refreshes score
  counters without touching the mapping. Runs are **synchronous** — the response body
  carries the final counts, so the caller knows when it's done.
- Response shape is uniform: `ReindexSummary(boolean indexDropped, long documentsIndexed,
  int pages, long durationMs, String note)`; research's `ReindexResult` mirrors it.
- **No `posts`/`chat-messages` self-heal note:** those two indices deliberately have
  *no* reindex hook — their Cassandra canonical stores have no efficient full-scan and
  they re-write on every mutation. (The `/posts/reindex` hook that *does* exist is the
  mapping-repair path, not a routine.)
- Detail: [search-feed-trending.md](search-feed-trending.md), [../search/indexing-and-reindex.md](../search/indexing-and-reindex.md).

### 2.3 `TagAdminController` **[EXISTS]**

`common/tag/controller/TagAdminController` · base `/api/v1/admin/tags`.

| Method | Path | Auth | Returns |
|--------|------|------|---------|
| `POST` | `/backfill-posts` | `@PreAuthorize("hasRole('ADMIN')")` | `Map<String,Object>` `{ postsScanned, postsWithHashtags, tagRowsWritten, startedAt }` |

- One-shot migration: re-indexes every post's hashtags into `content_by_tag` (posts
  created before the unified-tag commit are otherwise missing from the unified feed).
- ⚠️ **Two hazards to surface in any UI wrapper:** (1) it does a **full token-range
  scan** of `posts_by_id` (fine ≲ low millions; a large archive needs a batch job);
  (2) the per-row tag write is idempotent but the **trending-counter increments are
  NOT** — re-running double-counts trending. The dashboard must gate re-runs behind a
  type-to-confirm and record last-run.

### 2.4 `AuditLogController` **[EXISTS]**

`audit/controller/AuditLogController` · base `/api/v1/admin/audit` · **class-level**
`@PreAuthorize("hasRole('ADMIN')")` (the historical phantom `SUPER_ADMIN` branch was
normalized away in 2026-08, §1).

| Method | Path | Params | Returns | Notes |
|--------|------|--------|---------|-------|
| `GET` | `` (base) | `userId` (**required** — 400 without), `operation?`, `outcome?`, `from?`, `to?`, `pageSize=50`, `cursor?` | `List<AuditLogResponse>` | Cassandra needs a partition scope → `userId` mandatory; `operation/outcome/from/to` are applied **in-memory** to the returned slice. Use the SSE stream for a global view. |
| `GET` | `/users/{userId}` | `pageSize=50`, `cursor?` | `List<AuditLogResponse>` | per-user history, **keyset** via `AuditLogByUserRepository.firstPage`/`nextPage(userId, cursor, pageSize)` |
| `GET` | `/stream` | — (`text/event-stream`) | `SseEmitter` | realtime feed via `AuditRealtimeService.subscribe(adminId)` (Redis pub/sub, multi-instance safe); the **only admin SSE today** |

- `AuditLogResponse` (`@JsonInclude(NON_NULL)`): `id, userId, username, operation,
  outcome, resourceType, resourceId, httpMethod, path, queryString, statusCode,
  durationMs, ipAddress, userAgent, summary, errorCode, createdAt`.
- Data comes from the Cassandra `audit_log_by_user` table (180-day TTL), written by
  the HTTP interceptor (§5). Detail: [logs-audit.md](logs-audit.md).

---

## 3. Existing-controller summary

| Controller | Base path | Endpoints | Gate |
|------------|-----------|-----------|------|
| `AdminUserController` | `/api/v1/admin/users` | 1 (role change) | method `hasRole('ADMIN')` |
| `SearchAdminController` | `/api/v1/admin/search` | 7 (reindexes) | method `hasRole('ADMIN')` ×7 |
| `TagAdminController` | `/api/v1/admin/tags` | 1 (backfill) | method `hasRole('ADMIN')` |
| `AuditLogController` | `/api/v1/admin/audit` | 3 (search, user history, SSE) | **class** `hasRole('ADMIN')` |

**12 endpoints across these 4 founding controllers — historically the entire surface.**
The 2026-08 build shipped the §6 controller map: ~29 controllers, well over 100
endpoints under `/api/v1/admin/**`.

---

## 4. Stray admin-power endpoints (outside `/api/v1/admin/**`)

Two routes carry admin-only power but live under a **feature** prefix, so they miss
the chain-level prefix gate and lean on `@PreAuthorize` alone. Both were aliased
under the admin prefix and deprecated in place (built 2026-08): each stray now returns
`Deprecation` + successor-version `Link` headers and was normalized off the phantom roles.

| Endpoint | Controller | Auth | Concern |
|----------|-----------|------|---------|
| `POST /api/v1/sounds/{id}/approve` | `post/cassandra/controller/CassandraSoundController` (base `/api/v1/sounds`) | `hasRole('ADMIN')` (normalized 2026-08) + `@Deprecated` | outside prefix; deprecated with `Deprecation`/`Link` successor headers → `POST /api/v1/admin/sounds/{id}/approve` **[EXISTS]** (built 2026-08; [sound-library.md](sound-library.md) §6, [blueprint §3.3](admin-api-blueprint.md)) |
| `PUT /api/v1/channels/{id}/verified?verified=` | `chat/controller/ChannelController` (base `/api/v1`) | `hasRole('ADMIN')` | outside prefix (clean role, at least). Returns `ChannelResponse`; delegates `channelService.setVerified(id, verified)`. Aliased 2026-08 → `PATCH /api/v1/admin/channels/{id}/verified` (`AdminChannelController`); stray deprecated with successor `Link` ([chat-channels-live.md](chat-channels-live.md), [blueprint §3.5](admin-api-blueprint.md)) |

The blueprint's **stray-endpoint regression** guardrail ([§7](admin-api-blueprint.md))
is exactly for these: an arch-test that fails the build if an ADMIN-annotated route
appears outside `/api/v1/admin/**`.

---

## 5. Auditing at the controller layer

Two systems, both **[EXISTS]**, plus one wiring gap:

| System | What it captures | Wired? |
|--------|------------------|--------|
| **HTTP auto-audit** | `audit/web/AuditLoggingInterceptor` writes `audit_log_by_user` + `audit_log_by_resource` (Cassandra, 180-day TTL) for **every** authenticated `/api/**` request, then broadcasts on the SSE audit stream via `AuditRealtimeService`. | **[EXISTS]** — so every admin call is captured as "method + path + status + duration" with **zero** controller code. This is the "interceptor" audit-action in the blueprint tables. |
| **Entity audit** | `BaseAuditEntity.audit(action, note)` + `AuditingEntityListener` stamp `created_by/updated_by/*_ip/*_device/last_action/action_note` on the row. Role change uses it ("Role X → Y"). | **[EXISTS]** |
| **Business audit helper** | `AuditLogService.record(adminId, username, operation, resourceType, resourceId, summary)` — the "record *this* named business action with a meaningful summary" call. | **[EXISTS]** (built 2026-08) — funneled through `admin/support/AdminAuditor`, injected across the admin controllers/services; every admin mutation writes its named `ADMIN_*` row. |

**Convention for new controllers:** a read endpoint relies on the interceptor; a
**mutation** endpoint additionally calls `AuditLogService.record(...)` with the exact
`ADMIN_{DOMAIN}_{VERB}` string from the blueprint (§6 registry).

---

## 6. Controller layout (the build map — built 2026-08)

One `@RestController` per admin domain, each mounted under `/api/v1/admin/**`, each
class-annotated `@PreAuthorize("hasRole('ADMIN')")`, each delegating to existing
services (reuse, don't re-implement). Maps 1:1 onto the blueprint's endpoint sections
so a builder can go section → controller → methods.

| Proposed controller | Base path | Blueprint § | Doc | Status | Reuses (key services) |
|---------------------|-----------|-------------|-----|--------|-----------------------|
| `AdminUserController` *(extended the existing class)* | `/api/v1/admin/users` | 3.1 | [user-administration.md](user-administration.md), [users-roles.md](users-roles.md) | **[EXISTS]** — extended (built 2026-08) | `UserProvisioningService.provision` (ex-`AuthServiceImpl.register`), `AccountLifecycleService`, `RefreshTokenRepository`, `TwoFactorService`, `StrikeService`, `StepUpService` |
| `AdminContentController` | `/api/v1/admin/content` | 3.2 | [content-moderation.md](content-moderation.md) | **[EXISTS]** (built 2026-08) | `CassandraPostService`, `CassandraCommentService`, `CassandraStoryService`, `PostHydrator` |
| `AdminSoundController` | `/api/v1/admin/sounds` | 3.3 | [sound-library.md](sound-library.md) | **[EXISTS]** (built 2026-08; stray approve re-homed) | `CassandraSoundService` |
| `AdminResearchController` · `AdminQnaController` | `/api/v1/admin/research` · `/api/v1/admin/qna` | 3.4 | [research-qna.md](research-qna.md) | **[EXISTS]** (built 2026-08) | `ResearchServiceImpl`/`QuestionServiceImpl` (already had an ADMIN ownership bypass, now explicit+audited) |
| `AdminTagController` *(extended `TagAdminController`)* | `/api/v1/admin/tags` | 3.4 | [research-qna.md](research-qna.md) | **[EXISTS]** — extended (built 2026-08: hide/merge) | `ContentTagService`, trending suppression |
| `AdminChatController` · `AdminChannelController` · `AdminStreamController` | `/api/v1/admin/chat` · `/api/v1/admin/channels` · `/api/v1/admin/streams` | 3.5 | [chat-channels-live.md](chat-channels-live.md) | **[EXISTS]** (built 2026-08; channel-verify re-homed) | `ChannelService`, `LiveStreamService`, `StreamStageService`, `MediaControlClient` — **metadata only, never message content** |
| `AdminSafetyController` | `/api/v1/admin/safety` | 3.6 | [safety-reports.md](safety-reports.md) | **[EXISTS]** (built 2026-08) | `StrikeService` (now has callers), `ReportModerationService`, report/restriction repos |
| `AdminMediaController` · `AdminStorageController` | `/api/v1/admin/media` · storage | 3.7 | [media-storage.md](media-storage.md) | **[EXISTS]** (built 2026-08) | `StorageUsageService`, media pipeline repos |
| `AdminNotificationController` | `/api/v1/admin/notifications` | 3.8 | [notifications-email.md](notifications-email.md) | **[EXISTS]** (built 2026-08) | notification services + the announcement composer |
| `SearchAdminController` *(exists)* · `AdminFeedController` | `/api/v1/admin/search` · `/api/v1/admin/feed` | 3.9 | [search-feed-trending.md](search-feed-trending.md) | **[EXISTS]** / **[EXISTS]** (built 2026-08) | the 7 reindexers; `FeedRankingService` knob reflection |
| `AuditLogController` *(exists)* | `/api/v1/admin/audit` | 3.10 | [logs-audit.md](logs-audit.md) | **[EXISTS]** | `AuditLogByUserRepository`, `AuditRealtimeService` |
| `AdminAnalyticsController` | `/api/v1/admin/analytics` | 3.11 | [analytics-kpis.md](analytics-kpis.md) | **[EXISTS]** (built 2026-08) | the `analytics_events` collector + daily rollups |
| `AdminOpsController` | `/api/v1/admin/ops` | 3.12 | [operations.md](operations.md) | **[EXISTS]** (built 2026-08) | health probes, job-run ledger, `MediaControlClient`, queue/DLQ browse |
| `AdminDiscoveryController` · `AdminSuggestionsController` | `/api/v1/admin/discovery` · `/api/v1/admin/suggestions` | 3.13 | [discovery-pymk-privacy.md](discovery-pymk-privacy.md) | **[EXISTS]** (built 2026-08) | `FriendSuggestionService.recomputeFor`, `ContactMatchService`, `ConsentService` |
| `AdminKnowledgeController` | `/api/v1/admin/knowledge` | 3.14 | [knowledge-vocabulary.md](knowledge-vocabulary.md) | **[EXISTS]** (built 2026-08) | `TopicRepository`/`MadhhabRepository` (their **first** `save`/retire callers) + cache eviction |
| `AdminActivityController` | `/api/v1/admin/users/{id}/activity` etc. | 3.15 | [activity-engagement.md](activity-engagement.md) | **[EXISTS]** (built 2026-08) | `UserActivityService` (erase); break-glass reads — **default deny**, dual-control `BreakGlassCase` |

### 6.1 `AdminUserController` — the extension detail

The single most-extended class. Originally 1 method (role change); the 2026-08 build
appended the **C/E/S/X** method families
([user-administration.md](user-administration.md) §6):

- **Create/add** — `POST ""` (create), `POST /bulk`, `POST /invite` (needs a new
  `UserInvite`/`TokenType.INVITE`), reusing the `provision(...)` extracted from
  `AuthServiceImpl.register`.
- **Edit/credentials** — `PATCH /{id}` (identity), `POST /{id}/password/reset`,
  `POST /{id}/2fa/reset`, `POST /{id}/email/verify` (the **only** intended writer of
  `email_verified_at`).
- **State/sessions/lifecycle** — `POST /{id}/{disable|enable|lock|unlock}`,
  `POST /{id}/sessions/revoke-all`, `DELETE /{id}/sessions/{sid}`,
  `POST /{id}/deletion/{request|cancel}` (reuse `AccountLifecycleService`),
  `POST /{id}/purge/{now|hold}`.
- **Advanced** — `POST /{id}/impersonate` + `DELETE /api/v1/admin/impersonation`,
  `POST /bulk-action`, `POST /{id}/strikes` (first `StrikeService.issueStrike` caller).

---

## 7. Conventions binding every admin controller

Codify these so all admin controllers read alike (extend the arch-tests in
[blueprint §7](admin-api-blueprint.md)):

1. **Mount under `/api/v1/admin/**`** — inherits the chain gate. A stray = build-fail.
2. **Class-level `@PreAuthorize("hasRole('ADMIN')")`** — never a phantom role; per-method
   override only to *raise* (e.g. a future `@RequiresStepUp`), never to lower.
3. **Step-up on `high`/`critical`** — the `@RequiresStepUp` annotation (**[EXISTS]**
   since 2026-08, enforced by `StepUpGuardInterceptor` via `AdminWebMvcConfig`) on every
   mutating handler the blueprint marks high/critical. `StepUpService` **[EXISTS]**.
4. **Audit every mutation** — `AuditLogService.record(...)` with the blueprint's exact
   `ADMIN_{DOMAIN}_{VERB}`; reads rely on the interceptor.
5. **DTOs are records with Bean-Validation** — mirror `AdminChangeRoleRequest`
   (`@NotNull`, `@Size`); responses are `@JsonInclude(NON_NULL)` records
   (mirror `AuditLogResponse`).
6. **No secret ever in a response DTO** — `password`, `two_factor_secret`, refresh-token
   values, OTP hashes, `stream_key`/`publish_key`, chat `last_message_preview`. This is
   a build-fail arch-test ([blueprint §7](admin-api-blueprint.md)).
7. **Cassandra reads are keyset/cursor** (mirror `AuditLogController.firstPage/nextPage`),
   never token-range scans — the one exception (tag backfill) is already flagged.
8. **Error envelope** — reuse the platform's standard error response
   ([../errors/error-handling.md](../errors/error-handling.md)); 400 for a missing
   required scope (mirror the audit `userId`-required 400).
9. **Self-protection guards** — an admin cannot disable/lock/delete/demote themselves;
   demoting the last `ADMIN` is rejected (**[EXISTS]** built 2026-08 —
   `AdminUserServiceImpl.changeRole`: `requireNotSelf` + "Cannot demote the last
   ADMIN." `LAST_ADMIN` conflict).

---

## 8. Build order (controllers)

Consistent with [admin-api-blueprint.md §10](admin-api-blueprint.md) and
[architecture.md §11](architecture.md):

1. **Phase 1 (read-only controllers):** `AuditLogController` (exists), read methods on
   `AdminUserController`, `AdminOpsController` health/jobs, `AdminAnalyticsController`
   read. Wire `AuditLogService.record` so even reads/actions carry meaningful summaries.
2. **Phase 2 (safe mutations):** extend `AdminUserController` (create + role-with-step-up
   + edit + sessions + delete/restore), `AdminContentController` remove/restore,
   `AdminSoundController` (+ re-home approve), `AdminChatController` (+ re-home verify).
3. **Phase 3 (sensitive):** credential resets, `AdminSafetyController` (strike issuance),
   `AdminDiscoveryController` (contact-hash purge), `AdminKnowledgeController` (first
   write callers + cache evict).
4. **Phase 4 (heavy/highest-risk):** bulk endpoints, `AdminActivityController`
   break-glass reads (default-deny + dual-control), and **impersonation** last.
