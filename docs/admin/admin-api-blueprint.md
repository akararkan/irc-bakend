# Admin API Blueprint — Every Endpoint in One Place

Section 12 of the [admin dashboard plan](README.md). The consolidated endpoint catalog:
every admin API the dashboard needs — the **14 that exist today** merged with the
proposed surface — one table per dashboard section, each row carrying its danger level,
step-up requirement, and audit action. Section docs own the views/widgets/KPIs; this doc
owns the complete HTTP contract and the build sequence.

| Tag | Meaning |
|-----|---------|
| **[EXISTS]** | Implemented today — real class or `METHOD /path` cited |
| **[PARTIAL]** | Primitive/data layer exists; the endpoint (or part of it) does not |
| **[PLANNED]** | Proposed for the dashboard build — not yet coded |

Related: [architecture.md](architecture.md) (conventions §4, existing-surface inventory §5) ·
[../settings/auth-sessions.md](../settings/auth-sessions.md) (step-up) ·
[../errors/error-handling.md](../errors/error-handling.md) (error envelope) ·
[logs-audit.md](logs-audit.md) (log catalog) · [operations.md](operations.md) (ops detail)

## 1. Purpose & scope

| In scope | Out of scope |
|----------|--------------|
| Every `/api/v1/admin/**` endpoint, existing + proposed, in section tables | Widget layouts, data-source deep dives, KPI definitions — per-section docs |
| The two stray admin endpoints outside the prefix + their re-homing | Non-admin user-facing APIs (documented per module under `docs/`) |
| Conventions binding on all rows (from [architecture.md §4](architecture.md)) | RBAC evolution & impersonation design detail — [architecture.md §6–7](architecture.md) |
| Danger/step-up/audit columns for every mutation | Alert delivery infra — [operations.md](operations.md) |
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
| Response shape | Raw DTO / `Page<DTO>` in `ResponseEntity<T>` — **no envelope**. Errors use the canonical envelope of [../errors/error-handling.md](../errors/error-handling.md). |
| Pagination | PG-backed lists: Spring `Pageable` with `Pages.clamp` (**[EXISTS]** pattern), hard cap `size<=100`. Cassandra-backed lists: `cursor` + `pageSize` keyset, exactly like `AuditLogController` **[EXISTS]**. |
| Date ranges | `from` / `to` ISO-8601 instants, optional, `from<=to` validated; default last 24h (logs) / last 30d (analytics). |
| Filters | Consistent names: `userId`, `status`, `type`, `q`, `sort`. Enums parsed case-insensitively; 400 lists allowed values. |
| Audit | **Every mutation** writes a business audit row via `AuditLogService.record(...)` (**[PARTIAL]** — helper exists, zero callers today) *in addition to* the free interceptor row. The action name is the row's last column. |
| Step-up | `high`/`critical` rows require an armed `stepup:{userId}` marker (`StepUpService`, TTL 300s **[EXISTS]**); absent → 403 `STEP_UP_REQUIRED`. Reads never step-up (exception: PII/content reveals, marked explicitly). |
| Idempotency | Mutations honor the global `Idempotency-Key` header (24h replay, `IdempotencyFilter` **[EXISTS]**). |
| Long-running | Anything reindex-scale returns `202` + job id (**[PLANNED]**; the 7 existing reindexes stay synchronous until migrated). |

## 3. Endpoint tables

### 3.1 Users & roles

Views/KPIs: [users-roles.md](users-roles.md) (rows below = its A-series). Base: `/api/v1/admin/users`.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/users` | `q,role,status,verified,from,to` + pageable | `Page<AdminUserRow>` — `UserRepository.findActiveByRoles`/`searchUsersFts` | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/users/{userId}` | — | `AdminUserDetail` — User+profile fetch-join, `UserStatsService`, `StorageUsageService` | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/users/{userId}/pii` | — | raw email/phone (masked elsewhere) | **[PLANNED]** | read / **yes** | `ADMIN_PII_REVEAL` |
| PATCH | `/api/v1/admin/users/{userId}/role` | body `AdminChangeRoleRequest` | updated user | **[EXISTS]** `AdminUserController` → `AdminUserService.changeRole` | critical / **yes [PLANNED]** (none today) | `ADMIN_USER_ROLE_CHANGE` **[PLANNED]** |
| POST | `/api/v1/admin/users/{userId}/disable` | body `{reason}` | 204 | **[PLANNED]** (`User.is_enabled` column **[PARTIAL]** — never toggled today) | high / **yes** | `ADMIN_USER_DISABLE` |
| POST | `/api/v1/admin/users/{userId}/enable` | — | 204 | **[PLANNED]** | medium / yes | `ADMIN_USER_ENABLE` |
| POST | `/api/v1/admin/users/{userId}/lock` · `/unlock` | body `{reason}` | 204 | **[PLANNED]** (`is_account_non_locked` **[PARTIAL]** — never toggled) | high / **yes** | `ADMIN_USER_LOCK` / `_UNLOCK` |
| GET | `/api/v1/admin/users/{userId}/sessions` | — | `refresh_tokens` projection (device, ip, last_seen, trusted_until) | **[PLANNED]** (self-serve `GET /api/v1/security/sessions` **[EXISTS]**) | read / no | interceptor |
| DELETE | `/api/v1/admin/users/{userId}/sessions/{sid}` | — | 204 | **[PLANNED]** (`SessionDenylist` **[EXISTS]** primitive) | medium / yes | `ADMIN_SESSION_REVOKE` |
| POST | `/api/v1/admin/users/{userId}/sessions/revoke-all` | — | count | **[PLANNED]** (`RefreshTokenRepository.revokeAllForUser` **[EXISTS]** primitive) | medium / yes | `ADMIN_SESSIONS_REVOKE_ALL` |
| POST | `/api/v1/admin/users/{userId}/2fa/reset` | body `{reason}` | 204 + security email | **[PLANNED]** | critical / **yes** | `ADMIN_2FA_RESET` |
| POST | `/api/v1/admin/users/{userId}/deletion/request` | body `{reason}` | deletion state | **[PLANNED]** (reuses `AccountLifecycleService.requestDeletion` **[EXISTS]**) | critical / **yes** | `ADMIN_ACCOUNT_DELETE_REQUEST` |
| POST | `/api/v1/admin/users/{userId}/deletion/cancel` | — | deletion state | **[PLANNED]** (reuses `cancelDeletion` **[EXISTS]**) | medium / yes | `ADMIN_ACCOUNT_DELETE_CANCEL` |
| GET | `/api/v1/admin/users/{userId}/login-events` | pageable | `login_events` rows | **[PLANNED]** — table **[PARTIAL]**: no writer wired (`LoginEventService.record` zero callers) | read / no | interceptor |
| GET | `/api/v1/admin/users/{userId}/settings-audit` | pageable | `settings_audit` rows | **[PLANNED]** — thin controller over `SettingsAuditService.history` **[PARTIAL]** (no HTTP surface) | read / no | interceptor |
| GET | `/api/v1/admin/users/{userId}/moderation` | — | strikes + reports by/against (`user_strikes`, `reports`) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/users/{userId}/data` | — | `export_jobs` + `account_deletion_requests` + tombstone check | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/users/analytics` | `window` | growth aggregates (signups/day, role mix, verification funnel, deletion pipeline) | **[PLANNED]** queries | read / no | interceptor |
| POST | `/api/v1/admin/users/{userId}/impersonate` | body `{reason}` (min 10 chars) | short-TTL read-only token | **[PLANNED]** ([architecture.md §7](architecture.md)) | critical / **yes** | `ADMIN_IMPERSONATE_START` |
| DELETE | `/api/v1/admin/impersonation` | — | 204 | **[PLANNED]** | low / no | `ADMIN_IMPERSONATE_END` |

### 3.2 Content moderation (posts / comments / stories / reels)

Views/KPIs: [content-moderation.md](content-moderation.md). No admin content endpoint exists today — every delete path is author-only; `PostStatus.REMOVED` is a phantom state no code writes.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/content/posts` | `authorId,type,status,from,to,cursor,pageSize` | post rows — `posts_by_id`/`PostByAuthorRepository` + `PostHydrator` | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/content/posts/{postId}` | — | post detail + counters (`post_counters`) | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/content/posts/{postId}/remove` | body `{reason,reportId?}` | 204 | **[PLANNED]** — writes `status=REMOVED` (column exists, only `PUBLISHED` ever written today), deletes from `irc-posts`, untags trending | high / **yes** | `ADMIN_POST_REMOVE` |
| POST | `/api/v1/admin/content/posts/{postId}/restore` | — | 204 | **[PLANNED]** | medium / no | `ADMIN_POST_RESTORE` |
| DELETE | `/api/v1/admin/content/comments/{commentId}` | body `{reason}` | 204 | **[PLANNED]** — extends `CassandraCommentService.deleteComment` (author-only today) with admin path | high / **yes** | `ADMIN_COMMENT_DELETE` |
| DELETE | `/api/v1/admin/content/stories/{storyId}` | body `{reason}` | 204 | **[PLANNED]** — `CassandraStoryService` delete is author-only today | high / **yes** | `ADMIN_STORY_DELETE` |
| GET | `/api/v1/admin/content/blocklist` | pageable | platform keyword blocklist | **[PLANNED]** — no platform blocklist exists (per-user `HiddenKeyword` is CRUD-only and unenforced) | read / no | interceptor |
| POST | `/api/v1/admin/content/blocklist` | body `{keyword}` | created row | **[PLANNED]** — reuses `KeywordNormalizer` **[EXISTS]** primitive; enforcement hook is new | medium / no | `ADMIN_BLOCKLIST_ADD` |
| DELETE | `/api/v1/admin/content/blocklist/{id}` | — | 204 | **[PLANNED]** | medium / no | `ADMIN_BLOCKLIST_REMOVE` |

### 3.3 Sound library

Views/KPIs: [sound-library.md](sound-library.md) (the whole subsystem — Section 13) and [content-moderation.md](content-moderation.md) §2.6 (the approval-queue slice). State machine `PENDING_REVIEW → APPROVED | REJECTED | ARCHIVED`; only the approve transition exists, and it lives **outside the prefix**. The dedicated doc adds the planned reject/archive/**takedown**/restore/re-categorize/edit/delete + official bulk-seed + trending-exclude endpoints — see its §6.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/sounds` | `status` (default `PENDING_REVIEW`), `cursor,pageSize` | review queue rows | **[PLANNED]** — no pending-review query exists anywhere (`CassandraSoundService` javadoc: "out of scope"); needs a status-keyed table/query | read / no | interceptor |
| GET | `/api/v1/admin/sounds/{id}` | — | `sounds_by_id` row + `sound_counters.use_count` | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/sounds/{id}/approve` | — | 204 (idempotent) | **[EXISTS]** `CassandraSoundController` → `CassandraSoundService.approve` — **stray**: annotation-only gate, phantom `MODERATOR`/`SUPER_ADMIN` grants | medium / no | interceptor only today |
| POST | `/api/v1/admin/sounds/{id}/approve` | — | 204 | **[PLANNED]** re-home alias (wraps `approve` **[EXISTS]**); deprecate stray | medium / no | `ADMIN_SOUND_APPROVE` |
| POST | `/api/v1/admin/sounds/{id}/reject` | body `{reason}` | 204 | **[PLANNED]** — `SoundStatus.REJECTED` is never set by any code today | medium / no | `ADMIN_SOUND_REJECT` |
| POST | `/api/v1/admin/sounds/{id}/archive` | — | 204 | **[PLANNED]** — `ARCHIVED` likewise unreachable today | medium / no | `ADMIN_SOUND_ARCHIVE` |

### 3.4 Research / QnA / tags

Views/KPIs: [research-qna.md](research-qna.md). ADMIN already has **programmatic** moderation power here — `ResearchServiceImpl` (~L2123/L2274) and `QuestionServiceImpl` (~L1617-1660) skip ownership checks for `Role.ADMIN` — the endpoints below make those hidden powers explicit and audited.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/research` | `status,q,authorId,from,to` + pageable | `Page` over `ResearchRepository` (all 4 statuses incl. DRAFT/RETRACTED) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/research/{id}` | — | full detail incl. IRC-id, scheduledPublishAt, counters | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/research/{id}/retract` | body `{reason}` | 204 | **[PARTIAL]** — ADMIN service bypass exists; dedicated endpoint doesn't (owner route: `POST /api/v1/researches/{id}/retract` **[EXISTS]**) | high / **yes** | `ADMIN_RESEARCH_RETRACT` |
| DELETE | `/api/v1/admin/research/{id}` | body `{reason}` | 204 | **[PARTIAL]** — same bypass; endpoint **[PLANNED]** | critical / **yes** | `ADMIN_RESEARCH_DELETE` |
| GET | `/api/v1/admin/research/{id}/downloads` | `pageSize` | recent download log — `CassandraResearchEngagementService.recentDownloads` **[EXISTS]** primitive | **[PLANNED]** endpoint | read / no | interceptor |
| GET | `/api/v1/admin/qna/questions` | `status,q,authorId` + pageable | `Page` over `QuestionRepository` | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/qna/questions/{id}/close` | body `{reason}` | 204 | **[PLANNED]** — `QuestionStatus.CLOSED` is checked (blocks answers) but never set by any code | medium / no | `ADMIN_QUESTION_CLOSE` |
| POST | `/api/v1/admin/qna/questions/{id}/reopen` | — | 204 | **[PLANNED]** | medium / no | `ADMIN_QUESTION_REOPEN` |
| POST | `/api/v1/admin/qna/questions/{id}/archive` | — | 204 | **[PLANNED]** — `ARCHIVED` likewise never set | medium / no | `ADMIN_QUESTION_ARCHIVE` |
| DELETE | `/api/v1/admin/qna/questions/{id}` | body `{reason}` | 204 | **[PARTIAL]** — ADMIN bypass in `QuestionServiceImpl`; endpoint **[PLANNED]** | high / **yes** | `ADMIN_QUESTION_DELETE` |
| DELETE | `/api/v1/admin/qna/answers/{id}` | body `{reason}` | 204 | **[PARTIAL]** — same | high / **yes** | `ADMIN_ANSWER_DELETE` |
| POST | `/api/v1/admin/tags/backfill-posts` | — | `{postsScanned, postsWithHashtags, tagRowsWritten, startedAt}` | **[EXISTS]** `TagAdminController` — full token-range scan; **trending counter bumps are NOT idempotent** | high / yes **[PLANNED]** (none today) | interceptor only today |
| POST | `/api/v1/admin/tags/{tag}/hide` | `scope` | 204 | **[PLANNED]** — trending suppression list + removal from `trending_tags`; no such mechanism exists | medium / no | `ADMIN_TAG_HIDE` |
| DELETE | `/api/v1/admin/tags/{tag}/hide` | `scope` | 204 | **[PLANNED]** | medium / no | `ADMIN_TAG_UNHIDE` |

### 3.5 Chat / channels / live

Views + the **privacy boundary** (metadata always, message content never, `last_message_preview` excluded from every projection): [chat-channels-live.md](chat-channels-live.md).

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| PUT | `/api/v1/channels/{id}/verified` | `verified` bool | 204 | **[EXISTS]** `ChannelController` → `ChannelService.setVerified` — **stray**: outside prefix, annotation-only gate | medium / no | interceptor only today |
| PATCH | `/api/v1/admin/channels/{id}/verified` | `verified` bool | 204 | **[PLANNED]** re-home alias (wraps `setVerified` **[EXISTS]**); deprecate stray | medium / no | `ADMIN_CHANNEL_VERIFY` |
| GET | `/api/v1/admin/channels` | `q,verified,public,category` + pageable | metadata rows from `conversations` (type=CHANNEL) — **excludes `last_message_preview`** | **[PLANNED]** — no admin browse exists | read / no | interceptor |
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

Views/SLAs: [safety-reports.md](safety-reports.md). The full `ReportState` machine and `Resolution` enum **[EXISTS]** but only the user side is wired (`SafetyController`) — nothing ever advances a report past `SUBMITTED`/`APPEALED`, and `StrikeService.issueStrike` **[PARTIAL]** has zero callers.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/safety/reports` | `state,targetType,reason,targetId,from,to` + pageable | triage queue — `reports` (indexes on `target_id,reason` and `group_key` ready) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/safety/reports/{id}` | — | report detail + same-target siblings via `group_key` | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/safety/reports/{id}/triage` | — | updated report | **[PLANNED]** — `SUBMITTED → TRIAGED` (never set today) | low / no | `ADMIN_REPORT_TRIAGE` |
| POST | `/api/v1/admin/safety/reports/{id}/action` | body `{resolution, note}` | updated report | **[PLANNED]** — `→ ACTIONED` + `Resolution` (`WARNING_ISSUED`/`CONTENT_REMOVED`/`ACCOUNT_SUSPENDED`/`NO_ACTION`); resolution execution delegates to §3.1/§3.2/§3.5 actions | high / **yes** | `ADMIN_REPORT_ACTION` |
| POST | `/api/v1/admin/safety/reports/{id}/dismiss` | body `{note}` | updated report | **[PLANNED]** — `→ DISMISSED` | medium / no | `ADMIN_REPORT_DISMISS` |
| POST | `/api/v1/admin/safety/appeals/{reportId}/uphold` | body `{note}` | updated report | **[PLANNED]** — `APPEALED → UPHELD` | high / **yes** | `ADMIN_APPEAL_UPHOLD` |
| POST | `/api/v1/admin/safety/appeals/{reportId}/reverse` | body `{note}` | updated report | **[PLANNED]** — `APPEALED → REVERSED` (+ undo of the original action) | high / **yes** | `ADMIN_APPEAL_REVERSE` |
| POST | `/api/v1/admin/safety/users/{userId}/strikes` | body `{reportId, reason}` | strike | **[PARTIAL]** — `StrikeService.issueStrike` **[EXISTS]** (90-day decay), zero callers; endpoint **[PLANNED]** | high / **yes** | `ADMIN_STRIKE_ISSUE` |
| DELETE | `/api/v1/admin/safety/strikes/{strikeId}` | body `{reason}` | 204 | **[PLANNED]** | medium / yes | `ADMIN_STRIKE_REVOKE` |
| GET | `/api/v1/admin/safety/strikes` | `userId,active` + pageable | strike ledger — `user_strikes` | **[PLANNED]** (self-serve `GET /api/v1/safety/strikes` **[EXISTS]**) | read / no | interceptor |
| GET | `/api/v1/admin/safety/analytics` | `from,to` | volume by reason/target, time-to-triage/action SLAs, resolution mix | **[PLANNED]** queries | read / no | interceptor |

### 3.7 Media & storage

Views/pipeline board: [media-storage.md](media-storage.md). `MediaScanner` is `AllowAllScanner` today, so `FAILED_MODERATION` is unreachable — the failed-queue reader is still built status-generic.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/media` | `status,type,ownerId,from,to` + pageable | `media_assets` rows (all `MediaStatus` values incl. the 3 failure states) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/media/{assetId}` | — | asset detail: status, stored_bytes, renditions, dedup linkage | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/media/{assetId}/reprocess` | — | 202 | **[PLANNED]** — republishes `media.process.requested` (queue **[EXISTS]**) | medium / no | `ADMIN_MEDIA_REPROCESS` |
| DELETE | `/api/v1/admin/media/{assetId}` | body `{reason}` | 202 | **[PLANNED]** — publishes `media.delete.requested` (queue **[EXISTS]**) → all R2 renditions | critical / **yes** | `ADMIN_MEDIA_DELETE` |
| GET | `/api/v1/admin/storage/usage` | `top` (default 20) | platform total + top-N owners — `SUM(media_assets.stored_bytes)` (per-user sum **[EXISTS]** in `StorageUsageService`; platform/top-N queries **[PLANNED]**) | **[PLANNED]** | read / no | interceptor |

### 3.8 Notifications & announcements

Views/deliverability: [notifications-email.md](notifications-email.md).

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/notifications/stats` | `from,to` | volume by `NotificationType`, read rates — GROUP BY over `notifications` | **[PLANNED]** queries | read / no | interceptor |
| POST | `/api/v1/admin/announcements` | body `{title, body, audience}` | 202 + job id | **[PLANNED]** — fan-out via `NotificationService.sendSystemNotification` **[EXISTS]** primitive; batched all-user fan-out job is new | high / **yes** | `ADMIN_ANNOUNCEMENT_SEND` |
| GET | `/api/v1/admin/announcements` | pageable | announcement history | **[PLANNED]** | read / no | interceptor |
| POST | `/api/v1/admin/notifications/digest/run` | `date?` | 202 | **[PLANNED]** — manual fire of `TrendingNotificationJob` **[EXISTS]** (cron 09:00 UTC); groupKey cap keeps it idempotent per day | medium / no | `ADMIN_DIGEST_RUN` |
| GET | `/api/v1/admin/email/stats` | `from,to` | send/throttle counts — `EmailThrottle` Redis keys today; real deliverability needs a Resend-webhook collector | **[PLANNED]** (collector too) | read / no | interceptor |

### 3.9 Search & feed

Views/index health: [search-feed-trending.md](search-feed-trending.md). The 7 reindexes are the platform's biggest existing admin surface.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| POST | `/api/v1/admin/search/research/reindex` | `drop` (default true) | `ReindexResult` (synchronous) | **[EXISTS]** `SearchAdminController` → `ResearchSearchService.reindexAllPublished` | high (drop deletes index) / no | interceptor only today |
| POST | `/api/v1/admin/search/posts/reindex` | `drop` | `ReindexSummary` | **[EXISTS]** `SearchAdminController` → `PostSearchService.reindexAll` — also the legacy-mapping repair path | high / no | interceptor |
| POST | `/api/v1/admin/search/questions/reindex` | `drop` | summary | **[EXISTS]** `SearchAdminController` → `QnaSearchService.reindexAll` | high / no | interceptor |
| POST | `/api/v1/admin/search/users/reindex` | `drop` | summary | **[EXISTS]** `SearchAdminController` → `UserSearchService.reindexAll` | high / no | interceptor |
| POST | `/api/v1/admin/search/channels/reindex` | `drop` | summary | **[EXISTS]** `SearchAdminController` → `ChannelSearchService.reindexAll` | high / no | interceptor |
| POST | `/api/v1/admin/search/answers/reindex` | `drop` | summary | **[EXISTS]** `SearchAdminController` → `AnswerSearchService.reindexAll` | high / no | interceptor |
| POST | `/api/v1/admin/search/sounds/reindex` | `drop` | summary | **[EXISTS]** `SearchAdminController` → `SoundSearchService.reindexAll` (chat-messages has no hook by design) | high / no | interceptor |
| GET | `/api/v1/admin/search/indices` | — | per-index health/doc-count/mapping-version for the 8 indices | **[PLANNED]** — ES `_cat`/`_count` wrapper | read / no | interceptor |
| POST | `/api/v1/admin/search/reindex-all` | — | 202 + job id | **[PLANNED]** — sequential orchestration of the 7, async per §2 | high / **yes** | `ADMIN_SEARCH_REINDEX_ALL` |
| GET | `/api/v1/admin/feed/weights` | — | ranked-feed stage weights (engagement/affinity/freshness/diversity), read-only | **[PLANNED]** — config surface over `FeedRankingService` | read / no | interceptor |
| GET | `/api/v1/admin/feed/explain/{userId}` | `limit` | scored candidate breakdown for one user's next page (debug) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/suggestions/explain/{userId}` | — | PYMK per-source contribution breakdown (6 sources) | **[PLANNED]** | read / no | interceptor |

### 3.10 Logs & audit

The full log-store catalog (schemas, writers, retention, gaps): [logs-audit.md](logs-audit.md). This is the only section that is mostly **[EXISTS]**.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/audit` | `userId` (**required** — 400 without; Cassandra partition scope), `operation,outcome,from,to,cursor,pageSize` | audit page — filters applied in-memory to the fetched slice | **[EXISTS]** `AuditLogController` / `AuditLogByUserRepository.firstPage/nextPage` | read / no | interceptor |
| GET | `/api/v1/admin/audit/users/{userId}` | `cursor,pageSize` (default 50) | per-user keyset audit history | **[EXISTS]** `AuditLogController` | read / no | interceptor |
| GET | `/api/v1/admin/audit/stream` | `token` (SSE fallback) | SSE: `connected` / `audit` / `heartbeat` (25s) — global tail via Redis `irc:audit:stream` | **[EXISTS]** `AuditLogController` + `AuditRealtimeService` — the dashboard's live-tile backbone | read / no | n/a (streams are audit-exempt by `SKIP_PATTERN`) |
| GET | `/api/v1/admin/audit/resources/{resourceType}/{resourceId}` | `cursor,pageSize` | "what happened to this resource" — `audit_log_by_resource` | **[PARTIAL]** — table written on every request, `AuditLogByResourceRepository.firstPage` **[EXISTS]** but has **no endpoint**; the only view that captures anonymous traffic | read / no | interceptor |

Per-user `settings-audit` and `login-events` readers live in the users table (§3.1) — they are user-detail tabs, catalogued in [logs-audit.md](logs-audit.md).

### 3.11 Analytics & KPIs

KPI tree + honest sourcing: [analytics-kpis.md](analytics-kpis.md). Hard constraint carried into every row: **no date-bucketed metric store exists** — Cassandra counters are lifetime scalars, point-readable per id only. Phase-1 analytics ship only what is SQL-aggregable today; time-series/DAU/MAU wait for phase-3 collectors.

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/analytics/overview` | — | platform stat tiles from computable-today aggregates: users, `SUM` over `research`/`questions` counters, `conversations` totals, `media_assets` bytes, `stream_gift_tallies` coins, follower counts | **[PLANNED]** queries (all sources **[EXISTS]**) | read / no | interceptor |
| GET | `/api/v1/admin/analytics/content` | `window` | content production counts (posts/reels via Cassandra author counts, research, questions, stories) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/analytics/engagement` | `window` | DAU/MAU, engagement time-series | **[PLANNED]** — **requires phase-3 collectors** (`login_events` wiring + a date-bucketed rollup table); impossible from today's stores | read / no | interceptor |
| GET | `/api/v1/admin/analytics/trending` | `scope` | trending leaderboard — reads `trending_tags` snapshot **[EXISTS]** (public variant `GET /api/v1/tags/trending` **[EXISTS]**) | **[PLANNED]** (thin admin wrapper + usage history) | read / no | interceptor |
| GET | `/api/v1/admin/analytics/export` | `from,to,dataset` | CSV export of any analytics dataset | **[PLANNED]** | read / no | `ADMIN_ANALYTICS_EXPORT` |

### 3.12 Operations

Jobs/queues/env registry/runbooks: [operations.md](operations.md).

| Method | Path | Params | Returns | Status | Danger / step-up | Audit action |
|--------|------|--------|---------|--------|------------------|--------------|
| GET | `/api/v1/admin/ops/health` | — | dependency rollup: PG, Cassandra, Redis, RabbitMQ, ES, R2, MediaMTX (`:9997` ping) | **[PLANNED]** — only `/actuator/health` **[EXISTS]** (and is chain-ungated; prometheus registry on classpath but not web-exposed) | read / no | interceptor |
| GET | `/api/v1/admin/ops/jobs` | — | the 16 `@Scheduled` jobs: schedule, last run, outcome | **[PLANNED]** — needs a job-run recorder (none exists; today's evidence is log lines like `[SCHED-PUBLISH]`) | read / no | interceptor |
| POST | `/api/v1/admin/ops/jobs/{jobKey}/run` | — | 202 | **[PLANNED]** — manual trigger for whitelisted jobs (trending rebuild, digest, purge, cleanup) | high / **yes** | `ADMIN_JOB_RUN` |
| GET | `/api/v1/admin/ops/queues` | — | RabbitMQ queue depths incl. `irc.queue.dead-letter` (drained-to-log today) — management-API proxy | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/ops/sse` | — | open-emitter counts across the 9 SSE services (generalize `AuditRealtimeService.adminCount()` **[PARTIAL]** — in-memory, log-only) | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/ops/config` | — | sanitized env/flag registry: `permit-all` state, `MEDIA_TRANSCODE_ENABLED`, stream bases — **secrets never rendered** | **[PLANNED]** | read / no | interceptor |
| GET | `/api/v1/admin/ops/media-plane` | — | MediaMTX status: active paths/sessions via `MediaControlClient` **[EXISTS]** primitive against `:9997` | **[PLANNED]** endpoint | read / no | interceptor |
| POST | `/api/v1/admin/ops/streams/sweep-orphans` | — | count of LIVE rows with no publisher session, ended | **[PLANNED]** — no cleanup exists for streams orphaned by a crash; pairs `MediaControlClient` session list with `live_streams` | medium / yes | `ADMIN_STREAM_SWEEP` |

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
| Mutations | Interceptor row **plus** the named business row. `AuditLogService.record` **[PARTIAL]** — implemented, zero callers; wiring it is deliverable 1b of the build ([architecture.md §11](architecture.md)). |
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
- **Existing endpoints are under-protected by these rules**: role change, tag backfill, and the 7 reindexes have neither step-up nor business-audit today — retrofit in phase 1b/1c rather than grandfathering.
- **Self-protection**: admins cannot disable/lock/delete/demote themselves; demoting the last ADMIN is rejected (guard **[PLANNED]** — `AdminUserService.changeRole` has no such check).

## 9. Endpoint count summary

Rows above, by section (re-home aliases counted; SSE stream counted once):

| Section | [EXISTS] | [PARTIAL]* | [PLANNED] | Total |
|---------|----------|-----------|-----------|-------|
| 3.1 Users & roles | 1 | 0 | 19 | 20 |
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
| **Total** | **14** | **7** | **92** | **113** |

\* [PARTIAL] = endpoint row whose core primitive exists (service bypass, repo query, or member-gated variant) but the admin endpoint does not.
The 14 **[EXISTS]** = role change (1) + reindexes (7) + tag backfill (1) + audit (3) + the two strays (channel verify, sound approve). Roughly **~99 new endpoints** to build (92 planned + 7 partial completions), of which **~51 are read-only** — just over half of the new surface ships in the zero-risk phase 1.

## 10. Phased build order

Consistent with [architecture.md §11](architecture.md); per-endpoint sequencing below.

| Phase | Theme | Endpoints | Prerequisites / notes |
|-------|-------|-----------|----------------------|
| **1 — read-only views over existing data** | Zero-risk surfacing | §3.1 directory/detail/sessions/moderation/data reads (A1–A2, A12–A16 of [users-roles.md](users-roles.md) + the data-lifecycle read) · §3.10 audit-by-resource · §3.6 report queue reads · §3.7 media + storage reads · §3.5 channel/stream/call/gift reads (+ stats override) · §3.4 research/qna browse + downloads · §3.3 sound queue read (needs the status query) · §3.9 index health · §3.11 overview/content/trending · §3.12 health/queues/config/media-plane | **Riders that unblock phase 1**: wire `login_events` writer into `AuthServiceImpl`; expose `SettingsAuditService.history`; apply the missing 180d TTL `ALTER TABLE` on both Cassandra audit tables; re-home the two strays (aliases + deprecation); establish the `Pages.clamp`/step-up-guard/`AuditLogService.record` scaffolding (1b) |
| **2 — moderation actions** | First real mutations | §3.6 triage/action/dismiss/appeals/strikes · §3.2 post remove/restore, comment/story delete, blocklist · §3.3 approve (re-homed)/reject/archive · §3.4 research retract/delete, question close/archive, answer delete, tag hide · §3.1 disable/lock/sessions-revoke/2FA-reset/deletion · §3.5 channel takedown | Requires phase-1b conventions proven (step-up + audit wiring); report `action` resolutions delegate to the sibling takedown endpoints, so §3.2/§3.1 land before or with §3.6 `action` |
| **3 — ops controls + collectors** | Highest blast radius + new pipelines | §3.5 force-stop/rotate-key/recording moderation + §3.12 job-run/orphan-sweep · §3.9 reindex-all (async 202) · §3.8 announcements + digest run + email-stats collector · §3.11 engagement (needs the date-bucketed rollup collector + job-run recorder) · §3.12 sse/jobs views (need their collectors) | MediaMTX control-path testing for force-stop/rotate; collector work: job-run recorder, daily metric rollups, Resend webhooks. RBAC widening + impersonation follow as phases 4–5 ([architecture.md §11](architecture.md)) |

Dependency spine: **1b scaffolding → every phase-2 mutation**; `login_events` wiring → DAU/MAU and login alerts; job-run recorder → `ops/jobs` view and `analytics/engagement`; re-homing the strays early keeps the §7 stray-endpoint guardrail green from day one.
