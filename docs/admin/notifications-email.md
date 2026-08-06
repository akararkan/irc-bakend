# Section 7 — Notifications & Email

**Scope:** everything the platform sends at users — in-app notifications (SSE),
email, the daily trending digest, push (registry only today), announcements —
plus the preference/DND system that gates it. The admin needs three things
here: **volume visibility** (what is being sent, is it healthy), **the
announcement composer** (the one broadcast tool), and **opt-out analytics**
(are we spamming people into disabling everything).

Related: [logs-audit.md](logs-audit.md) (notification stores as records),
[operations.md](operations.md) (jobs & queues), [analytics-kpis.md](analytics-kpis.md)
(engagement KPIs), [../settings/notifications.md](../settings/notifications.md)
(the user-facing preference matrix/DND/push), [../notifications/](../notifications/)
(inbox mechanics).

---

## 1. How delivery works today (the ground truth)

Two notification stores exist side by side:

| Store | What | Key classes |
|-------|------|-------------|
| **PostgreSQL `notifications`** **[EXISTS]** | The classic inbox: social/research/qna/system rows, read state, category tabs | `user.entity.Notification`, `NotificationServiceImpl`, `NotificationController` |
| **Cassandra notification tables** **[EXISTS]** | The post-domain high-volume path with aggregation ("5 people liked…"), unread counters, active-group collapse | `post.cassandra.entity.NotificationEntity`, `NotifActiveGroupEntity`, `NotificationUnreadCounterEntity`, `NotificationLookupEntity`, `CassandraNotificationService` |

Delivery fan-out **[EXISTS]**: domain event → RabbitMQ (`irc.queue.notifications`)
→ consumer → store write → SSE push (`NotificationSseService`, owner-scoped
`{event,data}` envelope) → email decision.

**Email pipeline** **[EXISTS]** (`email/` package):
`NotificationEmailDispatcher` → per-user toggles (`User.email*Enabled` booleans +
`NotificationKind` → `PrefCategory` mapping) → **`EmailThrottle`** (Redis, same
`(recipient, groupKey)` not emailed twice within `irc.email.throttle-minutes`,
default 60) → `EmailTemplate` render → sender. Two sender paths exist:
SMTP (`EmailService`/`MailConfig`, Gmail app-password) and **Resend API**
(`ResendEmailSender`, `resend-java` on the classpath).

> **Engineering rule [EXISTS]:** `EmailTemplate.actionVerb` is an **exhaustive
> switch** over `NotificationType` — adding a new type without a case breaks the
> build. The dashboard's "types" registry (below) should surface this as a
> feature, not fight it.

**The new settings-module gates** **[EXISTS, wiring PLANNED]**: per-event×channel
matrix (`user_notification_prefs` + `NotificationPrefResolver`), DND windows
(`user_dnd` + `DndEvaluator`, IANA timezone, cross-midnight-safe), push-token
registry (`push_tokens` + `NoOpPushSender`). These are live tables/APIs but the
delivery pipeline does not consult them yet — a documented seam
([../settings/notifications.md](../settings/notifications.md)). The dashboard
must show them as **configured state**, honestly labeled "not yet enforced" until
the seam is wired.

**Retention** **[EXISTS]**: `NotificationCleanupJob` — daily 03:15, purges **read**
PG notifications older than **90 days**; unread rows are never purged.

---

## 2. Dashboard views / widgets

| Widget | Shows | Tag |
|--------|-------|-----|
| **Send volume board** | Notifications created per day, stacked by `NotificationType` / category (POSTS, QNA, RESEARCH, MENTIONS, SOCIAL, CHAT, SYSTEM); SSE-delivered vs stored-only | [PLANNED] (PG `GROUP BY type, date(created_at)`; Cassandra needs a counter rollup) |
| **Read-rate panel** | % read within 24h per type — the "are these notifications wanted" signal | [PLANNED] (PG `is_read` + timestamps) |
| **Email health strip** | Emails attempted / throttled / sent per day; active sender (SMTP vs Resend); last send error | [PARTIAL] — throttle decisions are log-only today; needs the send-ledger (below) |
| **Digest monitor** | TRENDING_DIGEST job: last run, users targeted, emails sent, opt-out count (`email_trending_enabled=false`) | [PARTIAL] — job **[EXISTS]** (daily 09:00 UTC, per-user `TRENDING_DIGEST:{date}` group-key caps 1/day); metrics are log-only |
| **Preference & DND adoption** | Per-channel opt-out rates from the matrix; % users with DND configured; timezone distribution; per-type email-toggle rates | [PLANNED] (aggregates over `user_notification_prefs`, `user_dnd`, `User.email*Enabled`) |
| **Push-token registry** | Token counts by provider/platform, stale tokens (old `last_seen_at`), honest banner: *sender is No-op — no push infra connected* | [EXISTS data / PLANNED view] (`push_tokens`) |
| **Announcement composer** | Compose + audience + schedule + preview + send-log (below) | [PLANNED] |
| **Types registry** | Static table of all `NotificationType` values: category, default channels (`NotificationDefaults`), email verb (`EmailTemplate`), bypass flags | [PLANNED] (rendered from code constants) |

## 3. Data sources

| Widget | Exact source |
|--------|--------------|
| Volume / read-rate | PG `notifications` (type, is_read, created_at) **[EXISTS]**; Cassandra `NotificationEntity` + `NotificationUnreadCounterEntity` for post-domain volume **[EXISTS, needs rollup job [PLANNED]]** |
| Email health | `EmailThrottle` Redis keys (TTL'd, ephemeral) **[EXISTS]**; `email_send_log` ledger **[PLANNED — see Actions]**; `MAIL_ENABLED`, `irc.email.*` config **[EXISTS]** |
| Digest | `TrendingNotificationJob` (`common/notification/job`) **[EXISTS]**; `User.emailTrendingEnabled` **[EXISTS]** |
| Prefs/DND/push | `user_notification_prefs`, `user_dnd`, `push_tokens` **[EXISTS]** (settings module) |
| Announcements | `NotificationService.sendSystemNotification(userId, title, body)` **[EXISTS — the per-user primitive]**; broadcast fan-out + scheduling via `AnnouncementService` / `platform_announcements` **[EXISTS (built 2026-08)]** (§4) |

## 4. Admin actions

| Method & path | What | Danger | Step-up | Audit action |
|---|---|---|---|---|
| `GET /api/v1/admin/notifications/stats` | volume/read-rate aggregates | read | — | — |
| `GET /api/v1/admin/notifications/types` | the types registry | read | — | — |
| `POST /api/v1/admin/notifications/announcements` | **Announcement composer**: `{title, body, audience{role?, activeSinceDays?}, audienceLanguage?, scheduledAt?, dryRun}` — fan-out walks keyset batches calling `sendSystemNotification` (`SYSTEM_ANNOUNCEMENT`); `dryRun=true` returns audience count only. **`audienceLanguage`** filters the audience by `User.preferredLanguage` (built 2026-08); **`scheduledAt`** (ISO-8601, future) stores the row as `Status.SCHEDULED` — a **minute sweep** (`AnnouncementService.fireDueScheduled`) fires it when due | **HIGH** (mass send) | **Yes** | audited (`AdminAuditor`) |
| `DELETE /api/v1/admin/notifications/announcements/{id}` | cancel a SCHEDULED announcement before its sweep fires (built 2026-08) | medium | **Yes** | audited |
| `GET /api/v1/admin/notifications/announcements` | send history (`platform_announcements`) + per-announcement reach | read | — | — |
| `POST /api/v1/admin/notifications/digest/run` | trigger TRENDING_DIGEST manually (respects the 1/day group-key cap — safe to re-run) | medium | — | `ADMIN_DIGEST_RUN` |
| `GET /api/v1/admin/notifications/email/stats` | sender status, throttle config, health | read | — | — |
| `POST /api/v1/admin/notifications/email/test` | send a test email to the calling admin's own address | low | — | `ADMIN_EMAIL_TEST` |
| `DELETE /api/v1/admin/notifications/push-tokens/{id}` | purge a stale/abusive token | low | — | `ADMIN_PUSH_TOKEN_PURGE` |

All **[EXISTS (built 2026-08)]** — `admin/notification/AdminNotificationController`
+ `AnnouncementService`/`PlatformAnnouncement`. Announcements now support
**scheduling** (SCHEDULED status + minute sweep + cancel) and an
**`audienceLanguage`** locale filter over `User.preferredLanguage`.

**The send-ledger [PLANNED]:** a small append-only `email_send_log`
(id, recipientId, kind, groupKey, outcome SENT|THROTTLED|FAILED|DISABLED, error,
ts). Today a throttled/failed email leaves only a log line; the ledger makes the
email-health strip and per-user "why didn't I get the email?" support answerable.
Write it from `NotificationEmailDispatcher` (one insert per decision).

## 5. Logs surfaced here

| Log | Store | Notes |
|-----|-------|-------|
| Notification rows themselves | PG + Cassandra **[EXISTS]** | the record of what was sent in-app; 90-day read-purge caveat — **volume stats must be computed from rollups, not raw counts, or history silently shrinks** |
| `email_send_log` | **[PLANNED]** | the email ledger (above) |
| Throttle decisions | Redis (TTL) **[EXISTS]** | ephemeral — visible only until TTL expiry; ledger supersedes |
| Announcement runs | `platform_announcements` **[EXISTS (built 2026-08)]** + `AdminAuditor` audit rows | who sent what to whom, when; SCHEDULED rows visible before firing |
| Digest job runs | app log **[EXISTS]** → `job_runs` ledger **[PLANNED]** ([operations.md](operations.md)) | |

## 6. Analytics & KPIs

| Metric | Definition | Source | Chart |
|--------|-----------|--------|-------|
| Notifications/day by category | created rows | PG/Cassandra **[EXISTS + rollup PLANNED]** | stacked area |
| Read rate (24h) per type | read within 24h ÷ created | PG **[PLANNED query]** | bar, sorted worst-first |
| Email attempt→sent funnel | attempted / throttled / disabled / sent / failed | ledger **[PLANNED]** | funnel |
| Digest reach | digest emails sent ÷ eligible users | job metrics **[PLANNED]** | line |
| Opt-out trend | % users disabling each channel/type over time | prefs tables **[EXISTS data]** | line per channel |
| DND adoption | % users with DND enabled; avg window length | `user_dnd` **[EXISTS data]** | stat tiles |
| Announcement engagement | read rate per announcement | PG reads **[PLANNED]** | table |

**The health question this section answers:** *rising opt-out rate on a type =
we are over-sending it.* The read-rate panel sorted worst-first is the
prioritized fix list.

## 7. Alerts & thresholds

| Alert | Condition | Why |
|-------|-----------|-----|
| Email failure spike | ledger FAILED > 5% over 1h | SMTP/Resend outage or bad template |
| Digest job missed | no run recorded by 10:00 UTC | job/scheduler failure |
| Opt-out spike | any type's disable rate +50% week-over-week | over-sending |
| Notification queue lag | `irc.queue.notifications` depth growing ([operations.md](operations.md)) | consumer stall |
| Announcement anomaly | any announcement audience > 50% of users | fat-finger guard — require an explicit `confirmLargeAudience` flag |

## 8. Permissions & safety notes

- The **announcement composer is the most abusable tool in the dashboard** —
  HIGH danger, step-up required, full audit, dry-run-first UX, and the
  large-audience confirmation flag. Consider restricting to a future
  `SUPER_ADMIN`/owner tier when RBAC evolves ([architecture.md](architecture.md)).
- Security/login alerts **bypass all preferences and DND by design**
  ([../settings/notifications.md](../settings/notifications.md)) — the dashboard
  must never offer a toggle that mutes them platform-wide.
- Preference data is per-user configuration: show **aggregates** on the
  dashboard; individual users' matrices appear only in the per-user inspection
  view ([users-roles.md](users-roles.md)) and reads are audited.

## 9. Build order

1. **Read-only stats** over PG notifications (volume, read rate) — zero risk.
2. **`email_send_log` ledger** write in `NotificationEmailDispatcher` + health strip.
3. **Digest monitor** + manual-run endpoint.
4. **Preference/DND/push adoption** aggregates (tables already exist).
5. **Announcement composer** (dry-run → send → history) — last, it's the dangerous one.
