# Admin Documentation — IRC Admin Dashboard

Everything the platform's admin surface does, organised **one directory per
topic**. The backend described here is implemented — 271 endpoints across 31
controllers — and every capability carries a tag so you always know what is real
versus still proposed.

| Tag | Meaning |
|-----|---------|
| **[EXISTS]** | Implemented in the backend today (class / endpoint cited). |
| **[PARTIAL]** | Some of it exists; the doc states exactly which part. |
| **[PLANNED]** | Designed here, not yet coded. |

> **Ground rules baked into every section.** Admin routes live under
> `/api/v1/admin/**`, double-gated (`ADMIN`/staff at the filter chain **and**
> `@PreAuthorize` per controller). Every admin mutation writes an audit row.
> Destructive operations require **step-up authentication**. Admins see
> conversation **metadata and aggregates, never message content** — that
> privacy boundary is absolute and is restated wherever it applies.

---

## Where to start

| I want to… | Go to |
|---|---|
| Understand the access model / conventions | [foundation/architecture.md](foundation/architecture.md) |
| Find an endpoint's request & response JSON | [api/](api/README.md) |
| Build the dashboard UI | [frontend/](frontend/README.md) |
| See what's built vs. outstanding | [TODO.md](TODO.md) · [known-issues.md](known-issues.md) |
| Look up an error string or code | [../errors/user-facing-messages.md](../errors/user-facing-messages.md) |

---

## The directories

### [`foundation/`](foundation/README.md) — how the admin surface works at all
Access model, RBAC, step-up, API conventions, and the two whole-surface
references.

| Doc | Scope |
|---|---|
| [architecture.md](foundation/architecture.md) | Access model, API conventions, existing-admin inventory, RBAC evolution, impersonation policy |
| [api-blueprint.md](foundation/api-blueprint.md) | Every admin endpoint in one place with danger levels and the phased build order |
| [api-controllers.md](foundation/api-controllers.md) | Controller-level reference — exact mappings, DTOs, per-controller security |

### [`users/`](users/README.md) — people and their data
| Doc | Scope |
|---|---|
| [directory-and-roles.md](users/directory-and-roles.md) | Directory, user inspection, roles/badges, account controls, sessions/2FA, deletion pipeline, growth analytics |
| [administration.md](users/administration.md) | The *action* surface — adding users (single/bulk/invite), identity edits, password/2FA reset, disable/lock/ban, kill-sessions, delete/restore, **impersonation** |
| [discovery-privacy.md](users/discovery-privacy.md) | PYMK knob registry, contact-sync privacy (hashing, caps, consent), discoverability flags, QR tokens, enumeration abuse |
| [activity-engagement.md](users/activity-engagement.md) | Per-user activity ledger (30 types), reel-view analytics, GDPR erasure, SSE activity-stream health |

### [`trust-safety/`](trust-safety/README.md) — keeping content safe
| Doc | Scope |
|---|---|
| [automated-moderation.md](trust-safety/automated-moderation.md) | **The AI moderation admin surface** — review queue, threshold tuning + dry-run, teaching the model, retrain/promote/rollback |
| [content-moderation.md](trust-safety/content-moderation.md) | Reports/media/keyword inbox, post/comment/story takedown, keyword blocklist, bulk actions |
| [safety-reports.md](trust-safety/safety-reports.md) | Report triage queue, strikes ledger, appeals, moderation records, consent viewer, SLAs |

### [`content/`](content/README.md) — the content catalogs
| Doc | Scope |
|---|---|
| [research-qna.md](content/research-qna.md) | Research pipeline/DOI/downloads, Q&A oversight, tags & trending admin |
| [sound-library.md](content/sound-library.md) | The TikTok/Facebook-style audio catalog — **admin-curated only**, category curation, state machine, official sounds, trending, rights/DMCA takedown |
| [media-storage.md](content/media-storage.md) | Pipeline status board, failed-media queues, dedup/tiers, storage usage, R2 lifecycle, quotas |
| [knowledge-vocabulary.md](content/knowledge-vocabulary.md) | Curated Topics & Madhhabs taxonomy (trilingual), curation console |

### [`communication/`](communication/README.md) — chat, channels, live, notifications
| Doc | Scope |
|---|---|
| [chat-channels-live.md](communication/chat-channels-live.md) | Privacy boundaries, channel verification & stats, invite abuse, live-stream control, gift economy, legal holds |
| [notifications-email.md](communication/notifications-email.md) | Volume dashboards, email deliverability, digest job, announcement composer, preference analytics |

### [`platform/`](platform/README.md) — search, observability, operations
| Doc | Scope |
|---|---|
| [search-feed-trending.md](platform/search-feed-trending.md) | ES index health + reindexes, trending controls, feed-ranking observability, suggestions engine |
| [logs-audit.md](platform/logs-audit.md) | **The complete log catalog** — every store with schema/writers/retention, Log Explorer, alert rules, GDPR |
| [analytics-kpis.md](platform/analytics-kpis.md) | KPI tree, per-module metrics, event-collection pipeline, overview-page layout |
| [operations.md](platform/operations.md) | Dependency health, scheduled-jobs inventory, queue/DLQ ops, SSE & Redis ops, env registry, backup/DR, runbooks |

### [`api/`](api/README.md) — endpoint reference
Request/response JSON for **every** admin endpoint, one file per domain. Every
key traced to the actual controller, DTO record or `Map`-building code.

### [`frontend/`](frontend/README.md) — building the dashboard UI
Auth flow, roles → navigation, step-up handling, page-by-page endpoint map,
component patterns.

---

## Relationship to the rest of `docs/`

This folder documents **the admin surface over** systems specified elsewhere —
[settings](../settings/README.md), [moderation](../moderation/README.md),
[search](../search/), [feed](../feed/), [chat](../chat/), [post](../post/),
[research](../research/), [qna](../qna/), [notifications](../notifications/),
[user](../user/), [suggestions](../suggestions/). Section docs link out for the
underlying mechanics and stay focused on the admin view: **what the admin sees,
what the admin can do, what gets logged, and what gets measured.**

One system has docs on both sides and it is worth knowing which is which:
**automated moderation**. [`../moderation/`](../moderation/README.md) is the
whole subsystem (design, architecture, the two Python containers, end-user
behaviour); [`trust-safety/automated-moderation.md`](trust-safety/automated-moderation.md)
is only the slice a moderator touches from the dashboard.
