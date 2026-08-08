# Admin API Blueprint — Every Endpoint in One Place

Section 12 of the [admin dashboard plan](../README.md). The consolidated endpoint catalog:
every admin API the dashboard needs — the 14 that existed pre-build merged with the
proposed surface (the 2026-08 build has since landed phases 1–3 + impersonation; see the
§9 note) — one table per dashboard section, each row carrying its danger level,
step-up requirement, and audit action. Section docs own the views/widgets/KPIs; this doc
owns the complete HTTP contract and the build sequence.

> **Companion:** [api-controllers.md](api-controllers.md) is the **controller-level**
> reference — the same surface organized by `@RestController` class (exact mappings,
> DTO shapes, security annotations, the two strays, and the one-controller-per-domain
> build map). This doc = *what endpoints*; that doc = *what controllers*.

| Tag | Meaning |
|-----|---------|
| **[EXISTS]** | Implemented today — real class or `METHOD /path` cited |
| **[PARTIAL]** | Primitive/data layer exists; the endpoint (or part of it) does not |
| **[PLANNED]** | Proposed for the dashboard build — not yet coded |

Related: [architecture.md](architecture.md) (conventions §4, existing-surface inventory §5) ·
[../settings/auth-sessions.md](../../settings/auth-sessions.md) (step-up) ·
[../errors/error-handling.md](../../errors/error-handling.md) (error envelope) ·
[logs-audit.md](../platform/logs-audit.md) (log catalog) · [operations.md](../platform/operations.md) (ops detail)

## 1. Purpose & scope

| In scope | Out of scope |
|----------|--------------|
| Every `/api/v1/admin/**` endpoint, existing + proposed, in section tables | Widget layouts, data-source deep dives, KPI definitions — per-section docs |
| The two stray admin endpoints outside the prefix + their re-homing | Non-admin user-facing APIs (documented per module under `docs/`) |
| Conventions binding on all rows (from [architecture.md §4](architecture.md)) | RBAC evolution & impersonation design detail — [architecture.md §6–7](architecture.md) |
| Danger/step-up/audit columns for every mutation | Alert delivery infra — [operations.md](../platform/operations.md) |
| Phased build order + endpoint count roll-up | — |

How to read the tables: **Params** lists query/body essentials only; every list
endpoint implicitly takes pagination per §2. **Returns** names the DTO or the real
data source class/table it projects. **Status** is honest: **[EXISTS]** rows cite the
real class; a **[PARTIAL]** note means a primitive exists but the endpoint doesn't.
**Danger / step-up** uses the four-level scale from [architecture.md §4](architecture.md);
step-up is mandatory at `high`/`critical`. **Audit action** is the business row written
via `AuditLogService.record` (reads rely on the interceptor row — see §6).

## 2. Conventions — binding on every row below

Defined in [architecture.md §4](architecture.md); summarized here because this doc enforces them across the full list.

| Rule | Convention |
|------|-----------|
| Prefix & gate | `/api/v1/admin/{section}/...` — inherits the filter-chain double gate (`config/SecurityConfig` `requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`) **plus** class-level `@PreAuthorize("hasRole('ADMIN')")`. **[EXISTS]** mechanism. No admin capability ships outside the prefix again — the two historical strays get re-homed (§4.3, §4.5). |
| Response shape | Raw DTO / `Page<DTO>` in `ResponseEntity<T>` — **no envelope**. Errors use the canonical envelope of [../errors/error-handling.md](../../errors/error-handling.md). |
| Pagination | PG-backed lists: Spring `Pageable` with `Pages.clamp` (**[EXISTS]** pattern), hard cap `size<=100`. Cassandra-backed lists: `cursor` + `pageSize` keyset, exactly like `AuditLogController` **[EXISTS]**. |
| Date ranges | `from` / `to` ISO-8601 instants, optional, `from<=to` validated; default last 24h (logs) / last 30d (analytics). |
| Filters | Consistent names: `userId`, `status`, `type`, `q`, `sort`. Enums parsed case-insensitively; 400 lists allowed values. |
| Audit | **Every mutation** writes a business audit row via `AuditLogService.record(...)` (**[EXISTS]** — funneled through `AdminAuditor` on every admin mutation, built 2026-08) *in addition to* the free interceptor row. The action name is the row's last column. |
| Step-up | `high`/`critical` rows require an armed `stepup:{userId}` marker (`StepUpService`, TTL 300s **[EXISTS]**); absent → 403 `STEP_UP_REQUIRED`. Reads never step-up (exception: PII/content reveals, marked explicitly). |
| Idempotency | Mutations honor the global `Idempotency-Key` header (24h replay, `IdempotencyFilter` **[EXISTS]**). |
| Long-running | Anything reindex-scale returns `202` + job id (**[PLANNED]**; the 7 existing reindexes stay synchronous until migrated). |

## 3. Endpoint tables

### 3.1 Users & roles

Views/KPIs: [users-roles.md](../users/directory-and-roles.md) (inspection/analytics) + **[user-administration.md](../users/administration.md) (§14 — the create/add & full-control action canon; C/E/S/X rows below)**. Base: `/api/v1/admin/users`. The provisioning rows (create/bulk/invite/edit/credentials) reuse the extracted `provision(...)` split out of `AuthServiceImpl.register` **[EXISTS]**.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/users` | `q,role,status,verified,from,to` + pageable | `Page<AdminUserRow>` — `UserRepository.findActiveByRoles`/`searchUsersFts` | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| GET | `/api/v1/admin/users/{userId}` | — | `AdminUserDetail` — User+profile fetch-join, `UserStatsService`, `StorageUsageService` | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| GET | `/api/v1/admin/users/{userId}/pii` | — | raw email/phone (masked elsewhere) | **[EXISTS]** (built 2026-08) | read / **yes** | `ADMIN_PII_REVEAL` |
| PATCH | `/api/v1/admin/users/{userId}/role` | body `AdminChangeRoleRequest` | updated user | **[EXISTS]** `AdminUserController` → `AdminUserService.changeRole` (+ self-guard & last-admin guard, built 2026-08) | critical / **yes** — `@RequiresStepUp` **[EXISTS]** (built 2026-08) | `ADMIN_USER_ROLE_CHANGE` **[EXISTS]** (built 2026-08) |
| POST | `/api/v1/admin/users/{userId}/disable` | body `{reason}` | 204 | **[EXISTS]** (built 2026-08 — `User.is_enabled` now admin-toggled) | high / **yes** | `ADMIN_USER_DISABLE` |
| POST | `/api/v1/admin/users/{userId}/enable` | — | 204 | **[EXISTS]** (built 2026-08) | medium / yes | `ADMIN_USER_ENABLE` |
| POST | `/api/v1/admin/users/{userId}/lock` · `/unlock` | body `{reason}` | 204 | **[EXISTS]** (built 2026-08 — `is_account_non_locked` now admin-toggled) | high / **yes** | `ADMIN_USER_LOCK` / `_UNLOCK` |
| GET | `/api/v1/admin/users/{userId}/sessions` | — | `refresh_tokens` projection (device, ip, last_seen, trusted_until) | **[EXISTS]** (built 2026-08; self-serve `GET /api/v1/security/sessions` **[EXISTS]**) | read / no | interceptor |
| DELETE | `/api/v1/admin/users/{userId}/sessions/{sid}` | — | 204 | **[EXISTS]** (built 2026-08, over the `SessionDenylist` primitive) | medium / yes | `ADMIN_SESSION_REVOKE` |
| POST | `/api/v1/admin/users/{userId}/sessions/revoke-all` | — | count | **[EXISTS]** (built 2026-08, over `RefreshTokenRepository.revokeAllForUser`) | medium / yes | `ADMIN_SESSIONS_REVOKE_ALL` |
| POST | `/api/v1/admin/users/{userId}/2fa/reset` | body `{reason}` | 204 + security email | **[EXISTS]** (built 2026-08) | critical / **yes** | `ADMIN_2FA_RESET` |
| POST | `/api/v1/admin/users/{userId}/deletion/request` | body `{reason}` | deletion state | **[EXISTS]** (built 2026-08 — reuses `AccountLifecycleService.requestDeletion`) | critical / **yes** | `ADMIN_ACCOUNT_DELETE_REQUEST` |
| POST | `/api/v1/admin/users/{userId}/deletion/cancel` | — | deletion state | **[EXISTS]** (built 2026-08 — reuses `cancelDeletion`) | medium / yes | `ADMIN_ACCOUNT_DELETE_CANCEL` |
| GET | `/api/v1/admin/users/{userId}/login-events` | pageable | `login_events` rows | **[EXISTS]** (built 2026-08) — writer wired from `AuthServiceImpl` login/refresh | read / no | interceptor |
| GET | `/api/v1/admin/users/{userId}/settings-audit` | pageable | `settings_audit` rows | **[EXISTS]** (built 2026-08) — thin controller over `SettingsAuditService.history` | read / no | interceptor |
| GET | `/api/v1/admin/users/{userId}/moderation` | — | strikes + reports by/against (`user_strikes`, `reports`) | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| GET | `/api/v1/admin/users/{userId}/data` | — | `export_jobs` + `account_deletion_requests` + tombstone check | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| GET | `/api/v1/admin/users/analytics` | `window` | growth aggregates (signups/day, role mix, verification funnel, deletion pipeline, close-friends adoption/list-size, sessions) | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| POST | `/api/v1/admin/users/{userId}/impersonate` | body `{reason}` (min 10 chars) | short-TTL read-only token | **[EXISTS]** (built 2026-08; [architecture.md §7](architecture.md), [user-administration.md §5](../users/administration.md)) | critical / **yes** | `ADMIN_IMPERSONATE_START` |
| DELETE | `/api/v1/admin/impersonation` | — | 204 | **[EXISTS]** (built 2026-08) | low / no | `ADMIN_IMPERSONATE_END` |
| POST | `/api/v1/admin/users` | body `{fname,lname,username,email,role,temporaryPassword?,sendInvite?,markEmailVerified?}` | created `AdminUserDetail` | **[EXISTS]** (built 2026-08) — `UserProvisioningService.provision` shared with `register` (which now defaults `Role.USER`) | high / **yes** | `ADMIN_USER_CREATE` |
| POST | `/api/v1/admin/users/bulk` | CSV/JSON `{rows[]}` | per-row `{created\|skipped\|error}` | **[EXISTS]** (built 2026-08) — loop over `provision(...)`, per-row audit | high / **yes** | `ADMIN_USER_BULK_CREATE` |
| POST | `/api/v1/admin/users/invite` | body `{email,role}` | invite id | **[EXISTS]** (built 2026-08, + resend/revoke) | medium / yes | `ADMIN_USER_INVITE` |
| PATCH | `/api/v1/admin/users/{userId}` | body `{fname?,lname?,username?,email?}` | updated user | **[EXISTS]** (built 2026-08) — email edit resets `email_verified_at` | high / **yes** | `ADMIN_USER_EDIT` |
| POST | `/api/v1/admin/users/{userId}/password/reset` | body `{temp?\|sendLink}` | 204 (+ revoke-all + notify) | **[EXISTS]** (built 2026-08) — **user-facing forgot-password remains intentionally absent** | critical / **yes** | `ADMIN_PASSWORD_RESET` |
| POST | `/api/v1/admin/users/{userId}/email/verify` | — | 204 | **[EXISTS]** (built 2026-08) — the **only** writer of `email_verified_at` (user-facing verify scaffolding still dead) | medium / yes | `ADMIN_EMAIL_VERIFY` |
| POST | `/api/v1/admin/users/{userId}/purge/{now\|hold}` | — | deletion state | **[EXISTS]** (built 2026-08) — manual expedite/hold over the nightly purge cron `0 30 3 * * *` | critical / **yes** | `ADMIN_PURGE_NOW` / `_HOLD` |
| POST | `/api/v1/admin/users/{userId}/strikes` | body `{reportId?,reason}` | strike row | **[EXISTS]** (built 2026-08) — `StrikeService.issueStrike` now has multiple callers; see [safety-reports.md](../trust-safety/safety-reports.md) | high / yes | `ADMIN_STRIKE_ISSUE` |
| POST | `/api/v1/admin/users/bulk-action` | body `{ids[],action,reason}` | per-id result | **[EXISTS]** (built 2026-08) — batch role/disable/delete over the singular primitives | critical / **yes** | `ADMIN_BULK_ACTION` |

### 3.2 Content moderation (posts / comments / stories / reels)

Views/KPIs: [content-moderation.md](../trust-safety/content-moderation.md). The admin takedown path landed 2026-08: `AdminContentController`/`AdminContentService` post remove/restore (writes `status=REMOVED` / back to `PUBLISHED`) plus comment/story deletes.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/content/posts` | `authorId,type,status,from,to,cursor,pageSize` | post rows — `posts_by_id`/`PostByAuthorRepository` + `PostHydrator` | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/content/posts/{postId}` | — | post detail + counters (`post_counters`) | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/content/posts/{postId}/remove` | body `{reason,reportId?}` | 204 | **[EXISTS]** (built 2026-08) — `AdminContentService.removePost` writes `status=REMOVED`, deletes from `irc-posts`, untags trending | high / **yes** | `ADMIN_POST_REMOVE` |
| POST | `/api/v1/admin/content/posts/{postId}/restore` | — | 204 | **[EXISTS]** (built 2026-08) — writes `PUBLISHED` back | medium / no | `ADMIN_POST_RESTORE` |
| DELETE | `/api/v1/admin/content/comments/{commentId}` | body `{reason}` | 204 | **[EXISTS]** (built 2026-08) — admin path over `CassandraCommentService.deleteComment` | high / **yes** | `ADMIN_COMMENT_DELETE` |
| DELETE | `/api/v1/admin/content/stories/{storyId}` | body `{reason}` | 204 | **[EXISTS]** (built 2026-08) — admin path over the author-only `CassandraStoryService` delete | high / **yes** | `ADMIN_STORY_DELETE` |
| GET | `/api/v1/admin/content/blocklist` | pageable | platform keyword blocklist | **[PLANNED]** — no platform blocklist exists (per-user `HiddenKeyword` is CRUD-only and unenforced) | read / no | interceptor |
| POST | `/api/v1/admin/content/blocklist` | body `{keyword}` | created row | **[PLANNED]** — reuses `KeywordNormalizer` **[EXISTS]** primitive; enforcement hook is new | medium / no | `ADMIN_BLOCKLIST_ADD` |
| DELETE | `/api/v1/admin/content/blocklist/{id}` | — | 204 | **[PLANNED]** | medium / no | `ADMIN_BLOCKLIST_REMOVE` |

### 3.2b Automated moderation (the AI classifier surface)

Views/KPIs: [automated-moderation.md](../trust-safety/automated-moderation.md).
Full request/response JSON: [api/automated-moderation.md](../api/automated-moderation.md).
Built **2026-08-08** — 29 endpoints across three controllers.

**This is a different queue from §3.2.** That one judges content that is already
live (reports, keyword hits); this one releases or buries content **nobody but
its author has seen**. Same prefix, opposite consequence — see
[trust-safety/README.md](../trust-safety/README.md#two-queues-not-one).

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/moderation/review` | `status,entityType,slaBreached,sort,page,pageSize` | queue page + live depth counts | **[EXISTS]** | read / no | interceptor |
| GET | `/api/v1/admin/moderation/review/{caseId}` | — | per-field text + raw label scores + the bands applied | **[EXISTS]** | read / no | interceptor |
| POST | `/api/v1/admin/moderation/review/{caseId}/decide` | body `{action,reason?,teachModel?}` | updated row | **[EXISTS]** | high / **no** — deliberate: a moderator works this queue continuously | `ADMIN_MODERATION_APPROVE` / `_REJECT` |
| POST | `/api/v1/admin/moderation/review/bulk` | body `{action,caseIds[≤100],reason?,teachModel?}` | per-case outcomes | **[EXISTS]** | high / **yes** | `ADMIN_MODERATION_REVIEW_BULK` |
| POST | `/api/v1/admin/moderation/review/{caseId}/rescore` | — | `{caseId,status}` | **[EXISTS]** | low / no | interceptor |
| GET | `/api/v1/admin/moderation/review/metrics` | `windowHours` | volume · bands · labels · SLA · model health · dataset | **[EXISTS]** — also `ANALYST` | read / no | interceptor |
| GET | `/api/v1/admin/moderation/settings` | — | stored overrides + fully resolved effective policy | **[EXISTS]** | read / no | interceptor |
| GET | `/api/v1/admin/moderation/settings/thresholds` | `entityType` | per-label bands for that type | **[EXISTS]** | read / no | interceptor |
| PUT | `/api/v1/admin/moderation/settings/thresholds` | body `{entityType?,labels{}}` | applied + effective | **[EXISTS]** | high / **yes** | `ADMIN_MODERATION_THRESHOLDS` |
| PUT | `/api/v1/admin/moderation/settings/hold-durations` | body `{entityType,holdMs?,inlineMs?,fallback?,enabled?}` | applied + effective | **[EXISTS]** | high / **yes** | `ADMIN_MODERATION_HOLD_CONFIG` |
| POST | `/api/v1/admin/moderation/settings/dry-run` | body `{entityType?,labels{},caseIds[≤500]}` | which past decisions would flip — **writes nothing, calls no model** | **[EXISTS]** | read / no | interceptor |
| PUT | `/api/v1/admin/moderation/settings/raw` | body `{key,value}` | overrides | **[EXISTS]** — **ADMIN only** (includes the master kill switch) | critical / **yes** | `ADMIN_MODERATION_SETTING_SET` |
| DELETE | `/api/v1/admin/moderation/settings/raw/{key}` | — | 204 | **[EXISTS]** — ADMIN only | high / **yes** | `ADMIN_MODERATION_SETTING_CLEAR` |
| POST | `/api/v1/admin/moderation/settings/reset` | — | effective | **[EXISTS]** — ADMIN only; drops every override | critical / **yes** | `ADMIN_MODERATION_SETTINGS_RESET` |
| GET | `/api/v1/admin/moderation/model/training-examples` | `source,page,pageSize` | dataset page + label/source summary | **[EXISTS]** | read / no | interceptor |
| POST | `/api/v1/admin/moderation/model/training-examples` | body `{text,labels,note?}` | created row | **[EXISTS]** | medium / no | `ADMIN_MODERATION_EXAMPLE_ADD` |
| POST | `/api/v1/admin/moderation/model/training-examples/word` | body `{word,labels,note?}` | every row created (expanded into template sentences) | **[EXISTS]** | medium / no | `ADMIN_MODERATION_WORD_ADD` |
| DELETE | `/api/v1/admin/moderation/model/training-examples/{id}` | — | 204 | **[EXISTS]** — ADMIN | medium / no | `ADMIN_MODERATION_EXAMPLE_REMOVE` |
| GET · POST · DELETE | `/api/v1/admin/moderation/model/golden-cases{,/{id}}` | — | the regression suite — **never trained on** | **[EXISTS]** | medium / no | `ADMIN_MODERATION_GOLDEN_ADD` / `_REMOVE` |
| GET | `/api/v1/admin/moderation/model/versions` | `page,pageSize` | registry + container health | **[EXISTS]** — also `ANALYST` | read / no | interceptor |
| POST | `/api/v1/admin/moderation/model/retrain` | body `{baseVersion?,notes?}` | 202 + registry row | **[EXISTS]** — needs the training container up | high / **yes** | `ADMIN_MODERATION_RETRAIN` |
| POST | `/api/v1/admin/moderation/model/retrain/refresh` | — | `{settled}` | **[EXISTS]** | low / no | interceptor |
| POST | `/api/v1/admin/moderation/model/train-callback` | header `X-Training-Token` | 204 | **[EXISTS]** — **not staff-facing**: the training container's own webhook, `permitAll()` + shared token | n/a | interceptor |
| POST | `/api/v1/admin/moderation/model/versions/{id}/promote` | body `{force?}` | updated version | **[EXISTS]** — reloads the container **before** flipping the registry | critical / **yes** | `ADMIN_MODERATION_MODEL_PROMOTE` |
| POST | `/api/v1/admin/moderation/model/versions/{id}/shadow` | — | updated version | **[EXISTS]** — status only; a real shadow replica pool is a deployment change, not code | medium / no | `ADMIN_MODERATION_MODEL_SHADOW` |
| POST | `/api/v1/admin/moderation/model/rollback` | — | restored version | **[EXISTS]** — re-promotes the last retired artifact; never re-runs training | critical / **yes** | `ADMIN_MODERATION_MODEL_ROLLBACK` |
| POST | `/api/v1/admin/moderation/model/score-probe` | body `{text}` | live per-label scores | **[EXISTS]** | read / no | interceptor |

### 3.3 Sound library

Views/KPIs: [sound-library.md](../content/sound-library.md) (the whole subsystem — Section 13) and [content-moderation.md](../trust-safety/content-moderation.md) §2.6 (the approval-queue slice). State machine `PENDING_REVIEW → APPROVED | REJECTED | ARCHIVED`. As of 2026-08 the full moderation set (approve/reject/archive/restore/takedown/recategorize/edit-metadata/hard-delete + pending queue + uploader history) lives under `/api/v1/admin/sounds` (`AdminSoundController` over `CassandraSoundService`); the historical stray approve is deprecated in place — see the dedicated doc's §6.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/sounds` | `status` (default `PENDING_REVIEW`), `cursor,pageSize` | review queue rows | **[EXISTS]** (built 2026-08) — backed by `SoundSearchService.idsByStatus` (uploaderId added to `SoundSearchDocument`) | read / no | interceptor |
| GET | `/api/v1/admin/sounds/{id}` | — | `sounds_by_id` row + `sound_counters.use_count` | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| POST | `/api/v1/admin/sounds` | body `{title,artistName?,audioUrl,coverArtUrl?,durationSeconds?,category?,official?}` | 201 + created row, always `APPROVED` | **[EXISTS]** (built 2026-08-08) — **the canonical creation path.** Sounds are admin-curated only; the user-facing upload route is closed | medium / no | `ADMIN_SOUND_CREATE` |
| POST | `/api/v1/sounds/{id}/approve` | — | 204 (idempotent) | **[EXISTS]** `CassandraSoundController` → `CassandraSoundService.approve` — **stray**, now normalized to `hasRole('ADMIN')` + `@Deprecated` with successor `Link` (2026-08) | medium / no | interceptor only |
| POST | `/api/v1/sounds` | legacy upload body | 201 + row | **[EXISTS]** — **closed to end users 2026-08-08**: now `hasAnyRole('ADMIN','MODERATOR')` + `@Deprecated` with successor `Link` → `POST /api/v1/admin/sounds`. The only route that can still mint a `PENDING_REVIEW` sound | medium / no | interceptor only |
| POST | `/api/v1/admin/sounds/{id}/approve` | — | 204 | **[EXISTS]** (built 2026-08) re-home alias (wraps `approve`); stray deprecated | medium / no | `ADMIN_SOUND_APPROVE` |
| POST | `/api/v1/admin/sounds/{id}/reject` | body `{reason}` | 204 | **[EXISTS]** (built 2026-08) — `CassandraSoundService.reject` | medium / no | `ADMIN_SOUND_REJECT` |
| POST | `/api/v1/admin/sounds/{id}/archive` | — | 204 | **[EXISTS]** (built 2026-08) — `CassandraSoundService.archive` (+ restore/takedown/recategorize/edit/hard-delete siblings) | medium / no | `ADMIN_SOUND_ARCHIVE` |

### 3.4 Research / QnA / tags

Views/KPIs: [research-qna.md](../content/research-qna.md). ADMIN already has **programmatic** moderation power here — `ResearchServiceImpl` (~L2123/L2274) and `QuestionServiceImpl` (~L1617-1660) skip ownership checks for `Role.ADMIN` — the endpoints below make those hidden powers explicit and audited.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/research` | `status,q,authorId,from,to` + pageable | `Page` over `ResearchRepository` (all 4 statuses incl. DRAFT/RETRACTED) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/research/{id}` | — | full detail incl. IRC-id, scheduledPublishAt, counters | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/research/{id}/retract` | body `{reason}` | 204 | **[PARTIAL]** — ADMIN service bypass exists; dedicated endpoint doesn't (owner route: `POST /api/v1/researches/{id}/retract` **[EXISTS]**) | high / **yes** | `ADMIN_RESEARCH_RETRACT` |
| DELETE | `/api/v1/admin/research/{id}` | body `{reason}` | 204 | **[PARTIAL]** — same bypass; endpoint **[PLANNED]** | critical / **yes** | `ADMIN_RESEARCH_DELETE` |
| GET | `/api/v1/admin/research/{id}/downloads` | `pageSize` | recent download log — `CassandraResearchEngagementService.recentDownloads` **[EXISTS]** primitive | **[PLANNED]** endpoint | read / no | interceptor |
| GET | `/api/v1/admin/qna/questions` | `status,q,authorId` + pageable | `Page` over `QuestionRepository` | **[EXISTS]** (built 2026-08 — `AdminQnaController` browse) | read / no | interceptor |
| POST | `/api/v1/admin/qna/questions/{id}/close` | body `{reason}` | 204 | **[EXISTS]** (built 2026-08) — first writer of `QuestionStatus.CLOSED` | medium / no | `ADMIN_QUESTION_CLOSE` |
| POST | `/api/v1/admin/qna/questions/{id}/reopen` | — | 204 | **[EXISTS]** (built 2026-08) | medium / no | `ADMIN_QUESTION_REOPEN` |
| POST | `/api/v1/admin/qna/questions/{id}/archive` | — | 204 | **[EXISTS]** (built 2026-08) — first writer of `ARCHIVED` | medium / no | `ADMIN_QUESTION_ARCHIVE` |
| DELETE | `/api/v1/admin/qna/questions/{id}` | body `{reason}` | 204 | **[PARTIAL]** — ADMIN bypass in `QuestionServiceImpl`; endpoint **[PLANNED]** | high / **yes** | `ADMIN_QUESTION_DELETE` |
| DELETE | `/api/v1/admin/qna/answers/{id}` | body `{reason}` | 204 | **[PARTIAL]** — same | high / **yes** | `ADMIN_ANSWER_DELETE` |
| POST | `/api/v1/admin/tags/backfill-posts` | — | `{postsScanned, postsWithHashtags, tagRowsWritten, startedAt}` | **[EXISTS]** `TagAdminController` — full token-range scan; **trending counter bumps are NOT idempotent** | high / yes **[PLANNED]** (none today) | interceptor only today |
| POST | `/api/v1/admin/tags/{tag}/hide` | `scope` | 204 | **[EXISTS]** (built 2026-08 — `TagAdminController`; + `POST /api/v1/admin/tags/merge`, and the trending override manager `AdminTrendingController`/`TrendingTagOverride` consulted by `TrendingTagJob`) | medium / no | `ADMIN_TAG_HIDE` |
| DELETE | `/api/v1/admin/tags/{tag}/hide` | `scope` | 204 | **[EXISTS]** (built 2026-08) | medium / no | `ADMIN_TAG_UNHIDE` |

### 3.5 Chat / channels / live

Views + the **privacy boundary** (metadata always, message content never, `last_message_preview` excluded from every projection): [chat-channels-live.md](../communication/chat-channels-live.md).

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| PUT | `/api/v1/channels/{id}/verified` | `verified` bool | 204 | **[EXISTS]** `ChannelController` → `ChannelService.setVerified` — **stray**: outside prefix; deprecated 2026-08 with successor `Link` | medium / no | interceptor only |
| PATCH | `/api/v1/admin/channels/{id}/verified` | `verified` bool | 204 | **[EXISTS]** (built 2026-08 — `AdminChannelController`, wraps `setVerified`); stray deprecated | medium / no | `ADMIN_CHANNEL_VERIFY` |
| GET | `/api/v1/admin/channels` | `q,verified,public,category` + pageable | metadata rows from `conversations` (type=CHANNEL) — **excludes `last_message_preview`** | **[EXISTS]** (built 2026-08 — `AdminChannelController` browse) | read / no | interceptor |
| GET | `/api/v1/admin/channels/{id}` | — | metadata + stats — reuses `ChannelStatsService.stats` with platform-admin override | **[PARTIAL]** — `GET /api/v1/channels/{id}/stats` **[EXISTS]** but member-gated (`ADMINS_ONLY`); non-member platform ADMIN gets 403 | read / no | interceptor |
| POST | `/api/v1/admin/channels/{id}/takedown` | body `{reason,reportId?}` | 204 | **[PLANNED]** — sets `deleted_at` (owner-only today) + removes from `irc-channels` | critical / **yes** | `ADMIN_CHANNEL_TAKEDOWN` |
| POST | `/api/v1/admin/channels/{id}/restore` | — | 204 | **[PLANNED]** | medium / yes | `ADMIN_CHANNEL_RESTORE` |
| GET | `/api/v1/admin/streams` | `status,hostId,from,to` + pageable | `live_streams` rows — **never `stream_key`/`publish_key`** | **[PLANNED]** — `GET /api/v1/streams/live` **[EXISTS]** (any authed user) covers the LIVE slice only | read / no | interceptor |
| POST | `/api/v1/admin/streams/{id}/force-stop` | body `{reason}` | 204 | **[PLANNED]** — primitives **[EXISTS]**: `LiveStreamService.end` (host-gated by one equals check) + `MediaControlClient.kickPublisher` for host and each guest path | critical / **yes** | `ADMIN_STREAM_FORCE_STOP` |
| POST | `/api/v1/admin/streams/{id}/rotate-key` | — | 204 (new key delivered to host only) | **[PLANNED]** — no rotation exists; enforcement point `authorizeMediaAccess` **[EXISTS]** | critical / **yes** | `ADMIN_STREAM_KEY_ROTATE` |
| GET | `/api/v1/admin/streams/{id}/recording` | — | recording metadata (file exists, size, status) | **[PLANNED]** — host-only today (`LiveStreamService.recording`); this is **content access** | read / **yes** | `ADMIN_RECORDING_VIEW` |
| DELETE | `/api/v1/admin/streams/{id}/recording` | body `{reason}` | 204 | **[PLANNED]** — `deleteRecording` **[EXISTS]** primitive, host-gated | high / **yes** | `ADMIN_RECORDING_DELETE` |
| GET | `/api/v1/admin/calls` | `from,to,type,status` + pageable | `call_sessions`+`call_participants` metadata (no content exists — P2P) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/message-requests/stats` | `from,to` | quarantine volume/accept/block aggregates over `message_requests` | **[PLANNED]** — spam signal, no reader today | read / no | interceptor |
| GET | `/api/v1/admin/gifts/top` | `window,limit` | platform gift rollup — SUM over `stream_gift_tallies` | **[PLANNED]** — per-stream board **[EXISTS]** (`GET /api/v1/streams/{id}/gifts/top`), no cross-stream view | read / no | interceptor |

### 3.6 Safety & reports

Views/SLAs: [safety-reports.md](../trust-safety/safety-reports.md). The full `ReportState` machine and `Resolution` enum **[EXISTS]**, and the moderator side is now wired (built 2026-08): `admin/safety/ReportModerationService` drives triage/action/dismiss/uphold/reverse, and `StrikeService.issueStrike` is called from `AdminSafetyController.issueStrike` (among others).

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/safety/reports` | `state,targetType,reason,targetId,from,to` + pageable | triage queue — `reports` (grouped, indexes on `target_id,reason` and `group_key`) | **[EXISTS]** (built 2026-08 — `AdminSafetyController`) | read / no | interceptor |
| GET | `/api/v1/admin/safety/reports/{id}` | — | report detail + frozen evidence + same-target siblings via `group_key` | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| POST | `/api/v1/admin/safety/reports/{id}/triage` | — | updated report | **[EXISTS]** (built 2026-08) — `SUBMITTED → TRIAGED` via `ReportModerationService` | low / no | `ADMIN_REPORT_TRIAGE` |
| POST | `/api/v1/admin/safety/reports/{id}/action` | body `{resolution, note}` | updated report | **[EXISTS]** (built 2026-08) — `→ ACTIONED` + `Resolution` (`WARNING_ISSUED`/`CONTENT_REMOVED`/`ACCOUNT_SUSPENDED`/`NO_ACTION`); resolution execution delegates to §3.1/§3.2/§3.5 actions | high / **yes** | `ADMIN_REPORT_ACTION` |
| POST | `/api/v1/admin/safety/reports/{id}/dismiss` | body `{note}` | updated report | **[EXISTS]** (built 2026-08) — `→ DISMISSED` | medium / no | `ADMIN_REPORT_DISMISS` |
| POST | `/api/v1/admin/safety/appeals/{reportId}/uphold` | body `{note}` | updated report | **[EXISTS]** (built 2026-08) — `APPEALED → UPHELD` | high / **yes** | `ADMIN_APPEAL_UPHOLD` |
| POST | `/api/v1/admin/safety/appeals/{reportId}/reverse` | body `{note}` | updated report | **[EXISTS]** (built 2026-08) — `APPEALED → REVERSED` (+ undo of the original action) | high / **yes** | `ADMIN_APPEAL_REVERSE` |
| POST | `/api/v1/admin/safety/users/{userId}/strikes` | body `{reportId, reason}` | strike | **[EXISTS]** (built 2026-08) — `AdminSafetyController.issueStrike` over `StrikeService.issueStrike` (90-day decay) | high / **yes** | `ADMIN_STRIKE_ISSUE` |
| DELETE | `/api/v1/admin/safety/strikes/{strikeId}` | body `{reason}` | 204 | **[EXISTS]** (built 2026-08) | medium / yes | `ADMIN_STRIKE_REVOKE` |
| GET | `/api/v1/admin/safety/strikes` | `userId,active` + pageable | strike ledger — `user_strikes` | **[EXISTS]** (built 2026-08; self-serve `GET /api/v1/safety/strikes` **[EXISTS]**) | read / no | interceptor |
| GET | `/api/v1/admin/safety/analytics` | `from,to` | volume by reason/target, time-to-triage/action SLAs, resolution mix | **[PLANNED]** queries | read / no | interceptor |

### 3.7 Media & storage

Views/pipeline board: [media-storage.md](../content/media-storage.md). `MediaScanner` is `AllowAllScanner` today, so `FAILED_MODERATION` is unreachable — the failed-queue reader is still built status-generic.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/media` | `status,type,ownerId,from,to` + pageable | `media_assets` rows (all `MediaStatus` values incl. the 3 failure states) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/media/{assetId}` | — | asset detail: status, stored_bytes, renditions, dedup linkage | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/media/{assetId}/reprocess` | — | 202 | **[PLANNED]** — republishes `media.process.requested` (queue **[EXISTS]**) | medium / no | `ADMIN_MEDIA_REPROCESS` |
| DELETE | `/api/v1/admin/media/{assetId}` | body `{reason}` | 202 | **[PLANNED]** — publishes `media.delete.requested` (queue **[EXISTS]**) → all R2 renditions | critical / **yes** | `ADMIN_MEDIA_DELETE` |
| GET | `/api/v1/admin/storage/usage` | `top` (default 20) | platform total + top-N owners — `SUM(media_assets.stored_bytes)` (per-user sum **[EXISTS]** in `StorageUsageService`; platform/top-N queries **[PLANNED]**) | **[PLANNED]** | read / no | interceptor |

### 3.8 Notifications & announcements

Views/deliverability: [notifications-email.md](../communication/notifications-email.md).

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/notifications/stats` | `from,to` | volume by `NotificationType`, read rates — GROUP BY over `notifications` | **[PLANNED]** queries | read / no | interceptor |
| POST | `/api/v1/admin/announcements` | body `{title, body, audience}` | 202 + job id | **[PLANNED]** — fan-out via `NotificationService.sendSystemNotification` **[EXISTS]** primitive; batched all-user fan-out job is new | high / **yes** | `ADMIN_ANNOUNCEMENT_SEND` |
| GET | `/api/v1/admin/announcements` | pageable | announcement history | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/notifications/digest/run` | `date?` | 202 | **[PLANNED]** — manual fire of `TrendingNotificationJob` **[EXISTS]** (cron 09:00 UTC); groupKey cap keeps it idempotent per day | medium / no | `ADMIN_DIGEST_RUN` |
| GET | `/api/v1/admin/email/stats` | `from,to` | send/throttle counts — `EmailThrottle` Redis keys today; real deliverability needs a Resend-webhook collector | **[PLANNED]** (collector too) | read / no | interceptor |

### 3.9 Search & feed

Views/index health: [search-feed-trending.md](../platform/search-feed-trending.md). The 7 reindexes are the platform's biggest existing admin surface.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| POST | `/api/v1/admin/search/research/reindex` | `drop` (default true) | `ReindexResult` (synchronous) | **[EXISTS]** `SearchAdminController` → `ResearchSearchService.reindexAllPublished` | high (drop deletes index) / no | interceptor only today |
| POST | `/api/v1/admin/search/posts/reindex` | `drop` | `ReindexSummary` | **[EXISTS]** `SearchAdminController` → `PostSearchService.reindexAll` — also the legacy-mapping repair path | high / no | interceptor |
| POST | `/api/v1/admin/search/questions/reindex` | `drop` | summary | **[EXISTS]** `SearchAdminController` → `QnaSearchService.reindexAll` | high / no | interceptor |
| POST | `/api/v1/admin/search/users/reindex` | `drop` | summary | **[EXISTS]** `SearchAdminController` → `UserSearchService.reindexAll` | high / no | interceptor |
| POST | `/api/v1/admin/search/channels/reindex` | `drop` | summary | **[EXISTS]** `SearchAdminController` → `ChannelSearchService.reindexAll` | high / no | interceptor |
| POST | `/api/v1/admin/search/answers/reindex` | `drop` | summary | **[EXISTS]** `SearchAdminController` → `AnswerSearchService.reindexAll` | high / no | interceptor |
| POST | `/api/v1/admin/search/sounds/reindex` | `drop` | summary | **[EXISTS]** `SearchAdminController` → `SoundSearchService.reindexAll` (chat-messages has no hook by design) | high / no | interceptor |
| GET | `/api/v1/admin/search/indices` | — | per-index existence + doc count for the 8 `irc-*` indices | **[EXISTS]** (built 2026-08 — `admin/search/AdminSearchOpsController`; store size + canonical-drift counts still missing) | read / no | interceptor |
| POST | `/api/v1/admin/search/reindex-all` | — | 202 + job id | **[PLANNED]** — sequential orchestration of the 7, async per §2 | high / **yes** | `ADMIN_SEARCH_REINDEX_ALL` |
| GET | `/api/v1/admin/feed/weights` | — | ranked-feed stage weights (engagement/affinity/freshness/diversity), read-only | **[PLANNED]** — config surface over `FeedRankingService` | read / no | interceptor |
| GET | `/api/v1/admin/feed/explain/{userId}` | `limit` | scored candidate breakdown for one user's next page (debug) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/suggestions/explain/{userId}` | — | PYMK per-source contribution breakdown (6 sources) | **[PLANNED]** | read / no | interceptor |

### 3.10 Logs & audit

The full log-store catalog (schemas, writers, retention, gaps): [logs-audit.md](../platform/logs-audit.md). This is the only section that is mostly **[EXISTS]**.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/audit` | `userId` (**required** — 400 without; Cassandra partition scope), `operation,outcome,from,to,cursor,pageSize` | audit page — filters applied in-memory to the fetched slice | **[EXISTS]** `AuditLogController` / `AuditLogByUserRepository.firstPage/nextPage` | read / no | interceptor |
| GET | `/api/v1/admin/audit/users/{userId}` | `cursor,pageSize` (default 50) | per-user keyset audit history | **[EXISTS]** `AuditLogController` | read / no | interceptor |
| GET | `/api/v1/admin/audit/stream` | `token` (SSE fallback) | SSE: `connected` / `audit` / `heartbeat` (25s) — global tail via Redis `irc:audit:stream` | **[EXISTS]** `AuditLogController` + `AuditRealtimeService` — the dashboard's live-tile backbone | read / no | n/a (streams are audit-exempt by `SKIP_PATTERN`) |
| GET | `/api/v1/admin/audit/resources/{resourceType}/{resourceId}` | `cursor,pageSize` | "what happened to this resource" — `audit_log_by_resource` | **[EXISTS]** (built 2026-08 — `AuditLogController.resourceHistory` over `AuditLogByResourceRepository`); the only view that captures anonymous traffic | read / no | interceptor |

Per-user `settings-audit` and `login-events` readers live in the users table (§3.1) — they are user-detail tabs, catalogued in [logs-audit.md](../platform/logs-audit.md).

### 3.11 Analytics & KPIs

KPI tree + honest sourcing: [analytics-kpis.md](../platform/analytics-kpis.md). The historical hard constraint (**no date-bucketed metric store**) was removed by the 2026-08 build: an `analytics_events` raw table + catch-all Rabbit tap, daily rollup / weekly cohort / anomaly-scan jobs, and a `user_first_events` funnel tracker now exist. The full suite is `/api/v1/admin/analytics/{overview, content, engagement, trending, export, series, funnel, retention, rollup/{date}/run, backfill, events/sample, alerts-config, alerts/{metric}, anomalies}`.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/analytics/overview` | — | platform stat tiles (incl. `mau30d` + `onlineNow`): users, `SUM` over `research`/`questions` counters, `conversations` totals, `media_assets` bytes, `stream_gift_tallies` coins, follower counts | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| GET | `/api/v1/admin/analytics/content` | `window` | content production counts (posts/reels via Cassandra author counts, research, questions, stories) | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| GET | `/api/v1/admin/analytics/engagement` | `window` | DAU/MAU, engagement time-series | **[EXISTS]** (built 2026-08) — backed by the wired `login_events` + daily rollups | read / no | interceptor |
| GET | `/api/v1/admin/analytics/trending` | `scope` | trending leaderboard — reads `trending_tags` snapshot **[EXISTS]** (public variant `GET /api/v1/tags/trending` **[EXISTS]**) | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| GET | `/api/v1/admin/analytics/export` | `from,to,dataset` | CSV export of any analytics dataset | **[EXISTS]** (built 2026-08) | read / no | `ADMIN_ANALYTICS_EXPORT` |

### 3.12 Operations

Jobs/queues/env registry/runbooks: [operations.md](../platform/operations.md).

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/ops/health` | — | dependency rollup: PG, Cassandra, Redis, RabbitMQ, ES, R2, MediaMTX (`:9997` ping) | **[EXISTS]** (built 2026-08 — `AdminOpsController`) | read / no | interceptor |
| GET | `/api/v1/admin/ops/jobs` | — | the `@Scheduled` jobs: schedule, last run, outcome (+ `/jobs/{jobKey}/runs`, pause/resume via Redis flags) | **[EXISTS]** (built 2026-08) — backed by the `admin/ops/{JobRun,JobRunRecorder,JobRunRepository}` ledger | read / no | interceptor |
| POST | `/api/v1/admin/ops/jobs/{jobKey}/run` | — | 202 | **[EXISTS]** (built 2026-08) — manual trigger for whitelisted jobs (trending rebuild, digest, purge, cleanup) | high / **yes** | `ADMIN_JOB_RUN` |
| GET | `/api/v1/admin/ops/queues` | — | RabbitMQ queue depths incl. the DLQ parking lot (`/queues/dlq` browse/requeue/discard) | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| GET | `/api/v1/admin/ops/sse` | — | open-emitter counts across the SSE services (incl. `AuditRealtimeService.adminCount()`) | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| GET | `/api/v1/admin/ops/config` | — | sanitized env/flag registry: `permit-all` state, `MEDIA_TRANSCODE_ENABLED`, stream bases — **secrets never rendered** (+ `/config/reconciler` enum-check report) | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| GET | `/api/v1/admin/ops/media-plane` | — | MediaMTX status: active paths/sessions via `MediaControlClient` **[EXISTS]** primitive against `:9997` | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| POST | `/api/v1/admin/ops/streams/sweep-orphans` | — | count of LIVE rows with no publisher session, ended (dry-run capable) | **[EXISTS]** (built 2026-08) — pairs `MediaControlClient` session list with `live_streams` | medium / yes | `ADMIN_STREAM_SWEEP` |

### 3.13 Discovery, PYMK & contact-sync

Knobs/privacy/abuse: [discovery-pymk-privacy.md](../users/discovery-privacy.md) (§15). PYMK weights are `static final` in `FriendSuggestionService` (recompile-only — no runtime tuning endpoint proposed).

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/suggestions/knobs` | — | the 6 sources + ~12 weight constants + gates (`MIN_SCORE`, cap 50, `DIVERSITY_HEAD`), each "recompile-only" | **[PLANNED]** — reflects `FriendSuggestionService` constants | read / no | interceptor |
| POST | `/api/v1/admin/users/{userId}/suggestions/recompute` | — | 202 | **[PARTIAL]** — `FriendSuggestionService.recomputeFor` **[EXISTS]**, no admin trigger | low / no | `ADMIN_PYMK_RECOMPUTE` |
| GET | `/api/v1/admin/users/{userId}/suggestions` | — | suggestion rows + `SuggestionDismissal`s | **[PLANNED]** | read (PII) / **yes** | interceptor |
| GET | `/api/v1/admin/discovery/contact-sync/stats` | `window` | syncs/day, avg hashes/sync, near-5k-cap, `contact:sync` rejections | **[PLANNED]** — data in `UserContactHash` | read / no | interceptor |
| GET | `/api/v1/admin/discovery/contact-sync/compliance` | — | synced-hashes ∧ CONTACTS-consent mismatch report | **[PLANNED]** — joins `UserContactHash` × `ConsentEvent` | read (PII) / **yes** | interceptor |
| POST | `/api/v1/admin/users/{userId}/contact-hashes/purge` | — | count | **[PLANNED]** — GDPR erasure of `UserContactHash` | high / **yes** | `ADMIN_CONTACT_HASH_PURGE` |
| GET | `/api/v1/admin/users/{userId}/discovery` | — | byUsername/byPhone/byEmail flags + QR-token status | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/users/{userId}/qr/rotate` | — | new opaque token | **[PLANNED]** — ⚠️ QR-resolve ignores `discover.byQr` today (`QrDiscoveryController` seam) | medium / yes | `ADMIN_QR_ROTATE` |

### 3.14 Knowledge vocabulary

Curation console: [knowledge-vocabulary.md](../content/knowledge-vocabulary.md) (§16). Historically the `topics`/`madhhabs` tables were read-only from the app; the 2026-08 build made `AdminKnowledgeController` their **first** save/retire caller (entities gained an `archived_at` column, and the cached pickers filter archived rows). Base: `/api/v1/admin/knowledge`.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/knowledge/{topics\|madhhabs}` | — | rows (trilingual labels) + usage counts | **[EXISTS]** (built 2026-08) | read / no | interceptor |
| POST | `/api/v1/admin/knowledge/{topics\|madhhabs}` | body `{nameEn,nameAr,nameCkb}` | created row | **[EXISTS]** (built 2026-08) — evicts the `@Cacheable` region | medium / **yes** | `ADMIN_VOCAB_ADD` |
| PATCH | `/api/v1/admin/knowledge/{topics\|madhhabs}/{id}` | body labels | updated row | **[EXISTS]** (built 2026-08) — evicts cache | medium / **yes** | `ADMIN_VOCAB_EDIT` |
| POST | `/api/v1/admin/knowledge/{topics\|madhhabs}/{id}/retire` | — | 204 | **[EXISTS]** (built 2026-08) — soft-retire via `archived_at` (hard-delete would fail `findById` profile validation) | high / **yes** | `ADMIN_VOCAB_RETIRE` |
| POST | `/api/v1/admin/knowledge/cache/evict` | — | 204 | **[EXISTS]** (built 2026-08) — manual evict of `knowledge-topics`/`knowledge-madhhabs` | low / no | `ADMIN_VOCAB_CACHE_EVICT` |

### 3.15 Activity & engagement

Per-user ledger + reel analytics: [activity-engagement.md](../users/activity-engagement.md) (§17). All data **[EXISTS]** in Cassandra (`activity_by_user*`, `reel_views_by_user`); the admin surface landed 2026-08 — `admin/activity/AdminActivityController` with the dual-control `BreakGlassCase` lifecycle (`POST /api/v1/admin/breakglass/{targetUserId}`, case approve/close/list).

Per the **privacy contract** ([analytics-kpis.md §12](../platform/analytics-kpis.md), [logs-audit.md §3.14](../platform/logs-audit.md)), the per-user reads are **break-glass** — an open authorized case + dual-control + step-up, else 403; population metrics use the parallel collector, never the private store.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/users/{userId}/activity` | `type,types,from,to` + pageable | activity timeline (30 `UserActivityType`s) | **[EXISTS]** (built 2026-08) | **critical** / **break-glass** | `ADMIN_ACTIVITY_BREAKGLASS_VIEW` |
| GET | `/api/v1/admin/users/{userId}/activity/summary` | `window` | per-type histogram (corroborates a case: scraper/stalking) | **[EXISTS]** (built 2026-08) | high / **break-glass** | `ADMIN_ACTIVITY_SUMMARY_VIEW` |
| GET | `/api/v1/admin/users/{userId}/reels/watched` | pageable | reel-watch history + `watched_seconds` | **[EXISTS]** (built 2026-08) | **critical** / **break-glass** | `ADMIN_REELVIEWS_BREAKGLASS_VIEW` |
| POST | `/api/v1/admin/users/{userId}/activity/erase` | body `{type?,reason}` | `{deleted:N}` | **[EXISTS]** (built 2026-08) — wraps existing batched clear-all (200×≤50); honors user-deletable | high / **yes** | `ADMIN_ACTIVITY_ERASE` |
| GET | `/api/v1/admin/users/{userId}/activity/export` | `from,to,format` | JSON/CSV | **[EXISTS]** (built 2026-08) | **critical** / **break-glass** | `ADMIN_ACTIVITY_EXPORT` |
| GET | `/api/v1/admin/analytics/engagement` | `window` | population engagement rollup | **[EXISTS]** (built 2026-08 — `AdminAnalyticsController.engagement`, collector-sourced; never reads the private store) | read / no | interceptor |

## 4. Admin SSE streams

| Stream | Events | Status | Notes |
|--------|--------|--------|-------|
| `GET /api/v1/admin/audit/stream` | `connected` / `audit` / `heartbeat` (25s) | **[EXISTS]** `AuditRealtimeService`, Redis `irc:audit:stream`, multi-instance safe | The **only** admin SSE today; the shell subscribes once and widgets filter client-side ([architecture.md §3](architecture.md)). New admin streams only when a section needs non-audit events (none proposed for phases 1–3). |

## 5. Data sources — where the Returns column points

Each row's Returns cell names its source; the per-section docs carry the full source tables. Cross-cutting rules: PG entities are read through existing repositories (fetch-join variants where the avatar gotcha applies); Cassandra reads are single-partition keyset pages only (never token-range scans — the one exception, tag backfill, is already flagged high-danger); Redis-sourced numbers (channel totals, presence) are labeled best-effort in every DTO; ES is search-only, never an analytics source.

## 6. Audit-action registry

| Rule | Detail |
|------|--------|
| Grammar | `ADMIN_{DOMAIN}_{VERB}` — the exact strings in the tables above; stored via `AuditLogService.record(adminId, username, operation, resourceType, resourceId, summary)` with `operation` = the matching `AuditOperation` (`UPDATE`/`DELETE`/`OTHER`). |
| Reads | No business row — the HTTP interceptor **[EXISTS]** already writes `audit_log_by_user` + `audit_log_by_resource` for every authenticated `/api/**` request ("interceptor" in the tables). |
| Mutations | Interceptor row **plus** the named business row. `AuditLogService.record` **[EXISTS]** (built 2026-08) — funneled through `admin/support/AdminAuditor` from virtually every admin mutation handler. |
| Visibility | All admin actions surface in the audit browser (§3.10) and the SSE ticker; staff actions are visible to all staff by design. |

## 7. Blueprint-level guardrails & alerts

| Guardrail | Condition | Severity |
|-----------|-----------|----------|
| Stray-endpoint regression | Any ADMIN-annotated route outside `/api/v1/admin/**` (build-time check or audit-stream detector) — the two known strays (§3.3, §3.5) get deprecation headers until removed | warn |
| Unaudited mutation | Admin mutation whose handler lacks an `AuditLogService.record` call (arch test) | build-fail |
| Step-up bypass | `high`/`critical` handler without the step-up guard (arch test on an annotation, e.g. `@RequiresStepUp`) | build-fail |
| Permit-all in prod | `app.security.permit-all=true` outside local | critical, page |
| Reindex during peak | Any §3.9 reindex with `drop=true` fired while search QPS is high — dashboard confirms with a warning modal | warn (UX) |
| Secret leak check | DTO review: `stream_key`, `publish_key`, `two_factor_secret`, refresh-token values, OTP hashes, `last_message_preview` must appear in **zero** admin projections | build-fail (test) |

## 8. Permissions & safety notes

- **Two gates always**: prefix double-gate + `@PreAuthorize` ([architecture.md §2](architecture.md)). All rows here are ADMIN-only until RBAC widening ([architecture.md §6](architecture.md)); the phantom `MODERATOR`/`SUPER_ADMIN` grants get normalized when the strays are re-homed.
- **Privacy boundary is structural**: no endpoint in this blueprint reads chat/DM content (Cassandra `message_by_conversation`, ES `irc-chat-messages`, R2 media, recordings). The two content-adjacent rows (recording view/delete, §3.5) are step-up-gated and audit-logged precisely because they cross it.
- **Danger scale**: `low` read-adjacent · `medium` reversible mutation · `high` user-impacting/hard-to-reverse · `critical` irreversible or account/key-level. Step-up (`StepUpService` **[EXISTS]**) is mandatory at `high`/`critical` — no exceptions, including the existing endpoints once retrofitted.
- **Pre-build endpoints were under-protected by these rules**: role change gained `@RequiresStepUp` + business-audit in the 2026-08 build; tag backfill and the 7 reindexes still predate the rules.
- **Self-protection**: admins cannot disable/lock/delete/demote themselves; demoting the last ADMIN is rejected (guard **[EXISTS]** built 2026-08 — `AdminUserServiceImpl.changeRole`: `requireNotSelf` + "Cannot demote the last ADMIN." `LAST_ADMIN` conflict).

## 9. Endpoint count summary

> **Historical pre-build snapshot.** The counts below reflect the state before the
> 2026-08 build, which shipped phases 1–3 + impersonation (~130 endpoints live across
> ~29 controllers). Rows retagged **[EXISTS]** (built 2026-08) above are not re-counted
> here; [known-issues.md](../known-issues.md) is the freshness overlay.

Rows above, by section (re-home aliases counted; SSE stream counted once):

| Section | [EXISTS] | [PARTIAL]* | [PLANNED] | Total |
|---------|----------|-----------|-----------|-------|
| 3.1 Users & roles | 1 | 0 | 28 | 29 |
| 3.2 Content moderation | 0 | 0 | 9 | 9 |
| 3.3 Sounds | 1 | 0 | 5 | 6 |
| 3.4 Research / QnA / tags | 1 | 4 | 9 | 14 |
| 3.5 Chat / channels / live | 1 | 1 | 12 | 14 |
| 3.6 Safety & reports | 0 | 1 | 10 | 11 |
| 3.7 Media & storage | 0 | 0 | 5 | 5 |
| 3.8 Notifications & announcements | 0 | 0 | 5 | 5 |
| 3.9 Search & feed | 7 | 0 | 5 | 12 |
| 3.10 Logs & audit | 3 | 1 | 0 | 4 |
| 3.11 Analytics | 0 | 0 | 5 | 5 |
| 3.12 Operations | 0 | 0 | 8 | 8 |
| 3.13 Discovery / PYMK | 0 | 1 | 7 | 8 |
| 3.14 Knowledge vocabulary | 0 | 0 | 5 | 5 |
| 3.15 Activity & engagement | 0 | 0 | 6 | 6 |
| **Total** | **14** | **8** | **119** | **141** |

\* [PARTIAL] = endpoint row whose core primitive exists (service bypass, repo query, or member-gated variant) but the admin endpoint does not.
The 14 **[EXISTS]** = role change (1) + reindexes (7) + tag backfill (1) + audit (3) + the two strays (channel verify, sound approve). Roughly **~127 new endpoints** to build (119 planned + 8 partial completions), of which **well over half are read-only** — shipping in the zero-risk phase 1. §3.1 grew with the full **add-users & full-control** action set ([user-administration.md](../users/administration.md)); §§3.13–3.15 are the newly-documented subsystems (discovery/PYMK privacy, knowledge vocabulary, activity ledger).

## 10. Phased build order

Consistent with [architecture.md §11](architecture.md); per-endpoint sequencing below.

| Phase | Theme | Endpoints | Prerequisites / notes |
|-------|-------|-----------|----------------------|
| **1 — read-only views over existing data** | Zero-risk surfacing | §3.1 directory/detail/sessions/moderation/data reads (A1–A2, A12–A16 of [users-roles.md](../users/directory-and-roles.md) + the data-lifecycle read) · §3.10 audit-by-resource · §3.6 report queue reads · §3.7 media + storage reads · §3.5 channel/stream/call/gift reads (+ stats override) · §3.4 research/qna browse + downloads · §3.3 sound queue read (needs the status query) · §3.9 index health · §3.11 overview/content/trending · §3.12 health/queues/config/media-plane | **Riders that unblock phase 1**: wire `login_events` writer into `AuthServiceImpl`; expose `SettingsAuditService.history`; apply the missing 180d TTL `ALTER TABLE` on both Cassandra audit tables; re-home the two strays (aliases + deprecation); establish the `Pages.clamp`/step-up-guard/`AuditLogService.record` scaffolding (1b) |
| **2 — moderation actions** | First real mutations | §3.6 triage/action/dismiss/appeals/strikes · §3.2 post remove/restore, comment/story delete, blocklist · §3.3 approve (re-homed)/reject/archive · §3.4 research retract/delete, question close/archive, answer delete, tag hide · §3.1 disable/lock/sessions-revoke/2FA-reset/deletion · §3.5 channel takedown | Requires phase-1b conventions proven (step-up + audit wiring); report `action` resolutions delegate to the sibling takedown endpoints, so §3.2/§3.1 land before or with §3.6 `action` |
| **3 — ops controls + collectors** | Highest blast radius + new pipelines | §3.5 force-stop/rotate-key/recording moderation + §3.12 job-run/orphan-sweep · §3.9 reindex-all (async 202) · §3.8 announcements + digest run + email-stats collector · §3.11 engagement (needs the date-bucketed rollup collector + job-run recorder) · §3.12 sse/jobs views (need their collectors) | MediaMTX control-path testing for force-stop/rotate; collector work: job-run recorder, daily metric rollups, Resend webhooks. RBAC widening + impersonation follow as phases 4–5 ([architecture.md §11](architecture.md)) |

Dependency spine: **1b scaffolding → every phase-2 mutation**; `login_events` wiring → DAU/MAU and login alerts; job-run recorder → `ops/jobs` view and `analytics/engagement`; re-homing the strays early keeps the §7 stray-endpoint guardrail green from day one.
