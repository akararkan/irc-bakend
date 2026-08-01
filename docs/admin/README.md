# Admin Documentation — IRC Admin Dashboard Plan

The complete plan and documentation for the IRC platform's **Admin Dashboard**:
every subsystem the platform has — from the **sound library** to the **privacy
engine** — mapped to a dashboard section, with the data sources, admin actions,
**logs**, and **analytics plans** for each. This is a *plan*, not an
implementation: every capability is tagged so you always know what is real
today versus what the dashboard build will add.

| Tag | Meaning |
|-----|---------|
| **[EXISTS]** | Already implemented in the backend today (class / endpoint cited). |
| **[PARTIAL]** | Some of it exists; the doc states exactly which part. |
| **[PLANNED]** | Proposed for the dashboard build — designed here, not yet coded. |

> **Ground rules baked into every section:** admin routes live under
> `/api/v1/admin/**` (double-gated `ADMIN` at the filter chain **and**
> `@PreAuthorize` — already the platform convention); every admin mutation
> writes an audit row; destructive operations require **step-up
> authentication** ([settings module](../settings/auth-sessions.md)); admins
> see conversation **metadata and aggregates, never message content** (privacy
> boundary, documented in [chat-channels-live.md](chat-channels-live.md)).

---

## Dashboard partition map

The dashboard is partitioned into 13 sections + the API blueprint. One document
per section — each contains: the on-screen views/widgets, exact data sources,
admin actions (with proposed endpoints), the logs surfaced there, KPIs & chart
specs, alerts, and permissions notes.

| # | Section | Doc | Scope |
|---|---------|-----|-------|
| 0 | **Architecture & access** | [architecture.md](architecture.md) | Access model, API conventions, existing-admin inventory, RBAC evolution, impersonation policy |
| 1 | **Users & roles** | [users-roles.md](users-roles.md) | Directory, user inspection, roles/badges, account controls, sessions/2FA, deletion pipeline, growth analytics |
| 2 | **Content moderation** | [content-moderation.md](content-moderation.md) | Posts/comments/stories/reels moderation queues, sound-approval queue slice, platform keyword blocklist, bulk actions |
| 3 | **Research & Q&A** | [research-qna.md](research-qna.md) | Research pipeline/DOI/downloads, QnA oversight, tags & trending admin |
| 4 | **Chat, channels & live** | [chat-channels-live.md](chat-channels-live.md) | Privacy boundaries, channel verification & stats, invite abuse, live-stream control (force-stop, keys, recordings), gift economy |
| 5 | **Safety & reports** | [safety-reports.md](safety-reports.md) | Report triage queue, strikes ledger, appeals, moderation records, consent viewer, SLAs |
| 6 | **Media & storage** | [media-storage.md](media-storage.md) | Pipeline status board, failed-media queues, dedup/tiers, storage usage, R2 lifecycle, quotas |
| 7 | **Notifications & email** | [notifications-email.md](notifications-email.md) | Volume dashboards, email deliverability, digest job, **announcement composer**, preference analytics |
| 8 | **Search, feed & trending** | [search-feed-trending.md](search-feed-trending.md) | ES index health + reindexes, trending controls, feed-ranking observability, suggestions engine |
| 9 | **Logs & audit** | [logs-audit.md](logs-audit.md) | **The complete log catalog** — every log store with schema/writers/retention, the unified Log Explorer, alert rules, GDPR handling |
| 10 | **Analytics & KPIs** | [analytics-kpis.md](analytics-kpis.md) | KPI tree, per-module metrics (honest EXISTS/PLANNED sourcing), event-collection proposal, overview-page layout |
| 11 | **Operations** | [operations.md](operations.md) | Dependency health, scheduled-jobs inventory, queue/DLQ ops, SSE & Redis ops, env-var registry, backup/DR, runbooks |
| 12 | **API blueprint** | [admin-api-blueprint.md](admin-api-blueprint.md) | Every admin endpoint (existing + proposed) in one place, with danger levels and the phased build order |
| 13 | **Sound library** | [sound-library.md](sound-library.md) | **The TikTok/Facebook-style audio library** — catalog & category curation, approval queue (canonical spec), full state machine, official/platform sounds, trending oversight, uploader reputation, rights/DMCA takedown, `irc-sounds` index health |

## Where to start

1. **Read [architecture.md](architecture.md)** — the access model and the
   inventory of what already exists (there is more than you might expect:
   user-role admin, 7 search reindexes, tag admin, the audit log API + live
   SSE audit stream, channel verification, sound approval).
2. **Phase-1 build is read-only**: the [API blueprint](admin-api-blueprint.md)
   sequences the build so the first phase only *surfaces existing data* (zero
   risk), phase 2 adds moderation actions, phase 3 adds ops controls and new
   analytics collectors.
3. **The two flagship docs** for what you asked: [logs-audit.md](logs-audit.md)
   (every log on the platform, catalogued) and
   [analytics-kpis.md](analytics-kpis.md) (the full measurement plan).
4. **The sound library** ([sound-library.md](sound-library.md)) is the
   TikTok/Facebook-style audio catalog, documented as its own section: the
   approval queue, curation, official/platform sounds, trending, uploader
   reputation, and rights/DMCA takedown — grounded in the real
   `app/post/cassandra` + `irc-sounds` implementation.

## Relationship to the rest of `docs/`

This folder *plans the admin surface over* systems documented elsewhere —
[settings](../settings/README.md), [search](../search/), [feed](../feed/),
[chat](../chat/), [post](../post/), [research](../research/), [qna](../qna/),
[notifications](../notifications/), [user](../user/), [suggestions](../suggestions/).
Section docs link to those for the underlying mechanics and keep themselves
focused on the admin view: **what the admin sees, what the admin can do, what
gets logged, and what gets measured.**
