# Admin Build — Known Issues & Recon Flags (consolidated)

The one list the TODO asked for: every sharp recon fact the section docs
surfaced, with its **post-build status**. The backend build (2026-08-06)
resolved most of them; the rest are deliberate debts, each with an owner note.

Status: ✅ resolved by the admin build · 🔶 partially resolved · ⬜ still open (deliberate).

## Resolved by the build

| # | Flag | Where it was documented | Resolution |
|---|------|------------------------|------------|
| 1 | `AuditLogService.record` had **zero callers** | architecture.md §4, api-controllers.md §5 | ✅ `AdminAuditor` funnels every admin mutation into it — the `ADMIN_{DOMAIN}_{VERB}` registry is live across all admin controllers |
| 2 | **180d audit TTL documented but never applied** (schema.cql is dead reference) | logs-audit.md §3.1, users-roles.md §5 | ✅ `AuditSchemaInitializer` creates-with-TTL and `ALTER`s existing tables idempotently at startup |
| 3 | `login_events` had **no writer** (table permanently empty) | users-roles.md §5/§9 | ✅ wired into `AuthServiceImpl` login success/failure + refresh (`recordSuccessAndAlertIfNew` on password logins) |
| 4 | `StrikeService.issueStrike` had **zero callers** | safety-reports.md §2, user-administration.md §8 | ✅ first callers: `AdminSafetyController` + `AdminUserController` strikes; appeal-reverse auto-revokes via `report_id` link |
| 5 | Report state machine had **no moderator side** (nothing past SUBMITTED/APPEALED) | safety-reports.md §2 | ✅ `ReportModerationService`: triage/dismiss/action/uphold/reverse + reviewer columns (`triaged_by/at`, `actioned_by`, `acted_at`, `moderator_note`) |
| 6 | `Report.targetId` was UUID-only — **MESSAGE reports couldn't reference their target** | safety-reports.md §3.3 | ✅ nullable `target_ref` column + widened submit path; evidence capture reads the single reported message |
| 7 | **No evidence snapshot** — comments hard-delete, stories TTL out before review | safety-reports.md §3.3, content-moderation.md §2.4-2.5 | ✅ `report_evidence` capture-at-submit (one per group) + `moderation_evidence` snapshot-before-delete on admin comment/story deletion |
| 8 | `PostStatus.REMOVED` was a **phantom state** (no writer; feeds didn't gate on status) | content-moderation.md §1 | ✅ admin remove/restore writes it; `PostHydrator` + the single-post read now gate non-PUBLISHED; ES filter pre-existed |
| 9 | Sound state machine was **half-built** (REJECTED/ARCHIVED unreachable; no pending queue; `SoundApprovedEvent` dead) | sound-library.md §12 | ✅ full machine incl. reject/archive/restore/takedown(+mute)/recategorize/edit/delete/import; ES-backed status queue (option B, `uploaderId` added to the doc); approve/reject events published AND consumed → uploader notifications (`SOUND_REJECTED` added to `NotificationType`/`NotificationKind`/`EmailTemplate.actionVerb`) |
| 10 | The **two stray admin endpoints** outside `/api/v1/admin/**` | architecture.md §2 | ✅ re-homed (`PATCH /admin/channels/{id}/verified`, `POST /admin/sounds/{id}/approve`); old routes answer with `Deprecation` + successor `Link` headers; phantom `MODERATOR`/`SUPER_ADMIN` grants normalized to `ADMIN` (incl. `AuditLogController`) |
| 11 | `is_account_non_locked` was a **dead column** (never mutated) | user-administration.md §8 | ✅ admin lock/unlock writes it; the JWT filter already enforced it per request |
| 12 | `is_enabled` only flipped via soft-delete | user-administration.md §4 | ✅ standalone admin disable/enable (+ session revoke + user notification) |
| 13 | `SessionDenylist` **seam**: revoked sessions kept a valid access JWT; `sid` never populated on refresh tokens | user-administration.md §8 | ✅ access tokens carry a `sid` claim, the filter checks the denylist, session rows persist sid/device/platform/IP metadata, logout denies the sid |
| 14 | **GDPR purge gap**: activity, reel views, contact hashes, suggestions survived account purge | TODO follow-up, activity-engagement.md §5, discovery-pymk-privacy.md §8 | ✅ purge cascade now drops `activity_by_user*` (+lookup), `reel_views_by_user`, `UserContactHash` (both kinds), `FriendSuggestionEntity`, suggestion dismissals |
| 15 | `audit_log_by_resource` written but **unreadable** | logs-audit.md, blueprint §3.10 | ✅ `GET /api/v1/admin/audit/resources/{type}/{id}` (+ keyset `nextPage`) |
| 16 | Audit SSE subscribe left no trace (`*/stream` skip pattern) | logs-audit.md §5 E3 | ✅ subscribe writes an explicit `ADMIN_AUDIT_STREAM_SUBSCRIBE` row |
| 17 | **No date-bucketed metric store anywhere** (DAU/MAU impossible) | analytics-kpis.md §2 | ✅ pragmatic collector: `analytics_metric_daily` + `analytics_dau_by_day`, teed from the activity sink + login events; `/admin/analytics/engagement` reads it — never the private per-user store |
| 18 | **No job-run record** ("did last night's purge run?" unanswerable) | operations.md §4.4 | ✅ `job_runs` ledger + recorder wired into purge/cleanup/digest crons, manual triggers, and reindex-all; 90d self-prune |
| 19 | Channels had **no takedown path at all**; stats were member-gated even for platform admins | chat-channels-live.md §4 | ✅ takedown/restore/unlist/freeze (+ `ChannelRights`-funnel freeze check) and `statsAsAdmin` override (topPosts stay ids-only) |
| 20 | LIVE rows orphaned by a crash persisted forever | chat-channels-live.md §9 | ✅ `POST /admin/ops/streams/sweep-orphans` (dry-run default) pairs MediaMTX session lists with `live_streams` |
| 21 | `media_assets` raw/ originals **never purged**; abandoned PENDING intents leaked (L1/L2) | media-storage.md §7 | ✅ `POST /admin/media/purge-raw/run` covers both (manual, dry-run default; no new cron — deliberate) |
| 22 | Dedup delete hazard: deleting one asset broke siblings sharing object keys (L3) | media-storage.md §7 | 🔶 the **admin** delete is dedup-safe (skips shared objects); the user-facing delete path still has the original hazard |
| 23 | Per-user `HiddenKeyword` was CRUD-only and no platform blocklist existed | content-moderation.md §2.7 | 🔶 **platform** blocklist built (BLOCK rejects at create, FLAG feeds the queue; same `KeywordNormalizer`); per-user keyword enforcement at feed assembly remains open |
| 24 | Knowledge vocabulary was migration-only (repos had no write callers) | knowledge-vocabulary.md §2 | ✅ admin CRUD with soft-retire (`archived_at`), cache eviction, usage counts |
| 25 | No impersonation mechanism | architecture.md §7 | ✅ read-only act-as: `IMPERSONATION` token type, GET-only enforced in the filter, `ROLE_IMPERSONATED_READ` only, 15-min TTL, sid-revocable, dual-id audit attribution to the ADMIN |
| 26 | Email pipeline left no queryable trail | notifications-email.md §4 | ✅ `email_send_log` ledger written on every dispatch decision |

## Still open (deliberate debts — each has a documented reason)

| # | Flag | Where | Why it stays open |
|---|------|-------|-------------------|
| A | ✅ RESOLVED (2026-08-06 completeness pass) — **registration now grants `role = USER`** (`AuthServiceImpl.register`); RESEARCHER/SCHOLAR are admin-elevated | user-administration.md §2 | — |
| B | **Email-verify scaffolding is dead** (`VerificationToken` zero injectors) | user-administration.md §8 | Admin `POST /{id}/email/verify` is the only intended writer; the invite-accept flow also pre-verifies. Reviving the self-serve token flow is a product decision |
| C | **QR-resolve ignores `discover.byQr`** | discovery-pymk-privacy.md §4 | Fix belongs to the discovery module (check the flag inside `resolve`); the admin surface names the seam and offers forced rotation as the mitigation |
| D | **`RateLimiter` fails open on Redis loss** | discovery-pymk-privacy.md §6 | Platform-wide primitive; changing to fail-closed is a availability-vs-abuse trade-off beyond the admin build. Surfaced in the contact-sync stats note |
| E | ✅ RESOLVED (2026-08-06 completeness pass) — **staff tiers live**: Role enum widened (MODERATOR/SUPPORT/ANALYST), chain + §6 per-section grant matrix applied, `EnumCheckConstraintReconciler` covers `users_role_check` | architecture.md §6 | — |
| F | **RTMP force-stop gap**: `kickPublisher` covers WebRTC sessions; an OBS (RTMP) publisher survives until MediaMTX ships `/v3/rtmpconns/kick` here | chat-channels-live.md §10 | Disclosed in the doc; the sweep + rotation cover the practical cases |
| G | ✅ RESOLVED (2026-08-06 completeness pass) — `S3StorageService.list` + `POST /admin/media/reconcile` (dry-run default, step-up) + `media_quotas` per-role daily quotas enforced at upload-intent (429 `MEDIA_QUOTA_EXCEEDED`) + `GET/PUT /admin/media/quotas` | media-storage.md §5 A9/quotas | — |
| H | ✅ RESOLVED (2026-08-06 completeness pass) — full Logs suite: `/admin/logs/{explore,login-events,export,views,alerts,alerts/firings,retention,otp-stats}`, 5-min `LogAlertSweepJob` (6 seeded rules), nightly `RetentionSweepJob`, GDPR log-purge cascade | logs-audit.md §4/§6 | — |
| I | ✅ RESOLVED (2026-08-06 completeness pass) — full pipeline: `analytics_events` + catch-all tap consumer, daily rollup / weekly cohort / anomaly-scan jobs, funnel + retention + `metric_alerts` + `ADMIN_ANOMALY`; only HLL sketches replaced by exact set-dedup MAU (no new deps offline) | analytics-kpis.md §6 | — |
| J | **Digest inbox-row duplication on same-day re-run** | notifications-email.md §4 | The 1/day cap is real on the email leg only (`TRENDING_DIGEST` is non-aggregable); the admin endpoint documents the caveat |
| K | **Micrometer/Prometheus gauges + actuator exposure** | operations.md §3.2, several §"alerts" rows | Needs the actuator/micrometer dependency — the build is offline (`mvn -o`), no new deps possible; the JobRun ledger + admin panels are the shipped observability |
| L | **Real MediaScanner + Rabbit media workers (`media.process.requested` consumer)** | media-storage.md §7/L5 | Needs an actual scanning backend + broker-driven worker topology; the in-process pipeline and admin reprocess are the shipped path |
| M | **Sound rights register / fingerprint match / counter-notice + featured shelf + upload auto-checks** | sound-library.md §6-7 | Rights/fingerprint need external tooling; the moderation state machine, takedown and trending board are shipped |
| N | **Push-delivery pipeline wiring (NotificationPrefResolver matrix + DND + push send)** | notifications-email.md §6 | The settings module stores prefs/DND and the token registry exists; an actual push provider integration is a product/deps decision |
| O | **MediaMTX RTMP/viewer kick endpoints** | chat-channels-live.md §4.9 | Same MediaMTX API gap as F — sweep + key rotation remain the mitigation |
| P | **Arch-test guardrails (stray-endpoint / unaudited-mutation / step-up-bypass build failures)** | admin-api-blueprint.md §7 | The test tree is stale by standing policy (compile-only checks); guardrails land when the test tree is revived |
