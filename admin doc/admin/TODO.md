# Admin Documentation — Task Order

What to document **first**, sorted as a simple task list. Everything here is docs
only (no code). Check items off as you go. Full map: [README.md](README.md).

> **Reorganised 2026-08-08.** These 24 flat files became seven topic
> directories — `foundation/`, `users/`, `trust-safety/`, `content/`,
> `communication/`, `platform/`, plus `api/` and `frontend/`. Every link below
> was rewritten to the new paths; nothing was deleted. The one removal was the
> stale duplicate of the messages catalog, replaced by a redirect stub.

Legend: ✅ done · ⬜ to do · 🔁 needs a quick verify-against-code pass.

---

## Do first — the foundation (read/build in this order)

1. ✅ **Architecture & access** — [architecture.md](foundation/architecture.md) — the access
   model + inventory of what already exists. *Everything else assumes this.*
2. ✅ **API blueprint** — [admin-api-blueprint.md](foundation/api-blueprint.md) — every
   endpoint in one table, danger levels, phased build order.
3. ✅ **API controllers** — [api-controllers.md](foundation/api-controllers.md) — the real
   `@RestController`s + the build map (which controller each route belongs on).
4. ✅ **Logs & audit** — [logs-audit.md](platform/logs-audit.md) — the complete log catalog
   (the flagship "what gets recorded" doc).

## Then — the core admin sections

5. ✅ **Users & roles** — [users-roles.md](users/directory-and-roles.md) — directory + inspection.
6. ✅ **User administration (add & full control)** — [user-administration.md](users/administration.md)
   — **the priority action surface**: adding users + full lifecycle control.
7. ✅ **Content moderation** — [content-moderation.md](trust-safety/content-moderation.md).
8. ✅ **Safety & reports** — [safety-reports.md](trust-safety/safety-reports.md).

## Next — the remaining sections

9.  ✅ **Research & Q&A** — [research-qna.md](content/research-qna.md)
10. ✅ **Chat, channels & live** — [chat-channels-live.md](communication/chat-channels-live.md)
11. ✅ **Sound library** — [sound-library.md](content/sound-library.md)
12. ✅ **Media & storage** — [media-storage.md](content/media-storage.md)
13. ✅ **Notifications & email** — [notifications-email.md](communication/notifications-email.md)
14. ✅ **Search, feed & trending** — [search-feed-trending.md](platform/search-feed-trending.md)
15. ✅ **Discovery, PYMK & privacy** — [discovery-pymk-privacy.md](users/discovery-privacy.md)
16. ✅ **Knowledge vocabulary** — [knowledge-vocabulary.md](content/knowledge-vocabulary.md)
17. ✅ **Activity & engagement** — [activity-engagement.md](users/activity-engagement.md)
18. ✅ **Automated moderation** — [automated-moderation.md](trust-safety/automated-moderation.md)
    — the AI classifier's dashboard surface (built 2026-08-08).

## Last — the cross-cutting sections

19. ✅ **Analytics & KPIs** — [analytics-kpis.md](platform/analytics-kpis.md)
20. ✅ **Operations** — [operations.md](platform/operations.md)

---

## Open follow-ups

- ✅ **Verify the GDPR purge cascades.** Verified 2026-08-06 — the gap was REAL
  (the purge touched only the `users` row + tombstone). Fixed in the backend
  build: `AccountLifecycleService.anonymizeAndPurge` now drops
  `activity_by_user*` (+lookup), `reel_views_by_user`, `UserContactHash`
  (both kinds), `FriendSuggestionEntity` and suggestion dismissals, each
  failure-isolated.
- ✅ **`role = SCHOLAR` default decision.** RESOLVED 2026-08-06 (second pass):
  self-registration now grants **USER** (`AuthServiceImpl.register`), matching
  the doc's fix intent; RESEARCHER/SCHOLAR are admin-elevated tiers. The
  admin-create form already defaulted to USER.
- ✅ **Recon flags consolidated** → [known-issues.md](known-issues.md) — every
  flag with its post-build status (26 resolved/partial, 10 deliberate debts).
- ⬜ **Anti-abuse / rate-limiting consolidation doc** — still optional; the
  live signals now surface in `/admin/discovery/contact-sync/stats`,
  `/admin/chat/message-requests/stats` and `/admin/safety/stats/blocks`.

## Backend implementation status (2026-08-06, completeness pass)

The **admin backend documented here is implemented in full** — blueprint
phases 1–3 + impersonation (first pass), then the completeness pass closed
every previously-deferred block:

- **RBAC widening (phases 4–5)** — MODERATOR/SUPPORT/ANALYST staff tiers live
  with the §6 per-section grant matrix; chain widened; enum-CHECK reconciled.
- **Log Explorer suite** — `/admin/logs/{explore,login-events,export,views,
  alerts,alerts/firings,retention,otp-stats}` + 5-min alert sweep (6 seeded
  rules) + nightly retention sweep + GDPR log-purge cascade.
- **Full `analytics_events` pipeline** — raw event table + catch-all Rabbit
  tap, daily rollup / weekly cohort / anomaly-scan jobs, activation funnel
  (`user_first_events`), retention grid, `metric_alerts` + thresholds,
  `ADMIN_ANOMALY` notification, `/admin/analytics/{series,funnel,retention,
  rollup,backfill,events/sample,alerts-config,anomalies}` + MAU/online-now.
- **Media** — bucket reconcile (S3 LIST diff), per-role daily upload quotas
  (429 `MEDIA_QUOTA_EXCEEDED`), `/admin/media/{quotas,ops}`.
- **Search telemetry** — anonymous query-log collector (Redis top/zero +
  Cassandra 90d), `/admin/search/analytics/*`, `/admin/search/health` drift.
- **Feed runtime tuning** — `feed_ranking_config` + staged rollout bucketing,
  `GET/PATCH /admin/feed/config`, `POST /admin/feed/preview` shadow-scoring.
- **Ops extras** — DLQ parking lot (browse/requeue/discard), job pause/resume,
  Redis panel + allowlisted flush, chat-ES backfill trigger, reconciler report.
- **Chat/live** — dual-control legal holds (≤500-message bounded release),
  recordings fleet view; **sounds** — adoption counter + trending board;
  **announcements** — scheduling + locale audience; close-friends and
  sessions-p50/p95 analytics blocks.
- **Repo hygiene** — zero-caller repository methods removed (verified by
  caller-grep before every deletion), derived `findBy*` JPA methods converted
  to explicit `@Query` (equivalent JPQL, same signatures), supporting indexes
  added (`reports(state,created_at)`, `login_events(ip,ts)`,
  `media_renditions(object_key)`, `user_strikes(expires_at)`).
- **Messages catalog** — every user-facing note/warning/error/notification/
  email/header documented in
  [`../errors/user-facing-messages.md`](../errors/user-facing-messages.md)
  — and now implemented as centralized constants in
  `ak.dev.irc.app.common.messages.*`.
- **API wire reference** — request/response JSON for all **271** admin
  endpoints in [api/](api/README.md) (10 per-domain files, every key traced to
  code); frontend build guide split into [frontend/](frontend/README.md)
  + [`../errors/frontend-error-handling.md`](../errors/frontend-error-handling.md).

## 2026-08-08 — automated moderation + docs reorganisation

- **Automated text moderation shipped** — quarantine-then-publish across all 13
  text-bearing surfaces, backed by a fine-tuned toxicity classifier in its own
  container. 29 new admin endpoints across three controllers: the proactive
  review queue, runtime threshold tuning with a **dry-run against stored
  scores**, the training-data manager, and the model registry
  (retrain → gate → promote → rollback). Docs:
  [trust-safety/automated-moderation.md](trust-safety/automated-moderation.md)
  (dashboard) · [api/automated-moderation.md](api/automated-moderation.md)
  (wire) · [`../moderation/`](../moderation/README.md) (whole subsystem).
- **Sounds became admin-curated only** — the open end-user upload path is
  closed (`403` for regular users, deprecated staff-only alias). New canonical
  `POST /api/v1/admin/sounds`. See
  [content/sound-library.md](content/sound-library.md).
- **These docs were reorganised** into topic directories, and the endpoint
  count was re-derived from source (271, not ~247).
- **CSV bulk training import shipped** —
  `POST /admin/moderation/model/training-examples/import` (multipart,
  `kind=sentences|words`, `dryRun`, `allowPartial`, step-up): admin curates a
  file in Excel → exports CSV UTF-8 → uploads; word rows with `blocklist=yes`
  also land on the platform blocklist (instant exact-word ban — real words and
  invented obfuscations alike). New `TrainingExampleSource.ADMIN_IMPORT`
  (+ enum-CHECK reconciler line), new `INVALID_IMPORT_FILE` code, pure-JDK
  RFC-4180 parser (no new deps). The moderation on/off surface was also
  re-documented as global + per-surface toggles
  ([trust-safety/automated-moderation.md](trust-safety/automated-moderation.md)
  §3.5) — backend levers (`enabled`, `enabled.<type>`) already existed.

Remaining deliberate debts (rationales in [known-issues.md](known-issues.md)):
Micrometer/actuator metrics (needs new dependency — build is offline), real
MediaScanner + Rabbit media workers, sound rights/fingerprint register,
push-delivery pipeline wiring, MediaMTX kick endpoints, arch-test guardrails
(test tree is stale by policy). Compile-checked; live verification pending
(owner runs the app).
