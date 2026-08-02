# Activity & Engagement History — Admin Dashboard Section 17

The platform's **per-user activity history** — the "your activity" timeline every
user builds as they move through the app (posts created, reactions, comments,
shares, saves, reel watches, searches, mentions, profile views, follows, Q&A
actions, research actions, story interactions, sound usage). It is a
**Cassandra-backed engagement ledger** written as a sink of events produced all
over the app, and live-streamed back to each user over SSE. Today it is entirely
**self-scoped** — a user can read and erase only their own history and there is
**no admin surface at all**. This section designs the admin view over it: a
per-user activity **forensics** panel, the **reel-view analytics** board, the
**GDPR erasure** control for activity data, and — most valuable — treating this
ledger as the **cheapest existing engagement-telemetry source** the platform has.

> This is a distinct concern from [logs-audit.md](logs-audit.md): that section is
> the *admin/security* audit trail (who did what to the platform). This one is the
> *product-engagement* trail (what a user did with the product). They share the
> "activity" word and nothing else — different stores, different retention,
> different privacy class.

Tag legend and ground rules: [README.md](README.md). Underlying mechanics live in
`app/activity`. Related: [analytics-kpis.md](analytics-kpis.md) (this ledger is a
proposed analytics source), [users-roles.md](users-roles.md) (the per-user
activity tab links here), [../settings/data-export-deletion.md](../settings/data-export-deletion.md)
(erasure obligations), [content-moderation.md](content-moderation.md) (activity as
corroborating evidence in abuse cases).

> **⚠️ Privacy contract — read this first.** The rest of `docs/admin/` already
> takes a firm, deliberate position on this ledger:
> [analytics-kpis.md](analytics-kpis.md) §12 and [logs-audit.md](logs-audit.md)
> §3.14 declare `activity_by_user` the user's **private, user-deletable history**
> (the Instagram "Your activity" model) that **admins never browse** for analytics,
> and that population metrics must come from a **parallel fan-in collector**, never
> from mining the private partitions. **This document does not overturn that
> contract — it operationalizes it.** Concretely: (1) population analytics use the
> collector (§6), never the raw store; (2) admin-invoked **erasure** is *consistent*
> with "user-deletable"; (3) per-user *browsing* of the timeline is **not** a
> routine admin tab — it is **break-glass forensics** under legal hold / active
> abuse investigation / law-enforcement process, dual-control and loudly audited
> (§3.1, §10). If you only remember one thing: **the default is no access.**

Status legend: **[EXISTS]** = real today (class / endpoint cited) · **[PARTIAL]** =
data layer exists, admin surface missing · **[PLANNED]** = proposed for the
dashboard, not yet built.

---

## 1. Purpose & scope

| In scope | Out of scope (see) |
|----------|--------------------|
| The **engagement-telemetry collector** — the sanctioned, privacy-preserving analytics path | Mining the private per-user store for analytics → **forbidden** by [analytics-kpis.md](analytics-kpis.md) §12 |
| GDPR/admin **erasure** of a user's activity data (consistent with user-deletable) | The data-export/deletion account pipeline → [../settings/data-export-deletion.md](../settings/data-export-deletion.md) |
| SSE activity-stream health (connections, heartbeat) | General SSE fleet ops → [operations.md](operations.md) §6 |
| **Break-glass** per-user timeline access under legal/abuse process (tightly gated) | Routine per-user browsing → **not a thing**; default is no access |
| Reel-watch history + reel-view analytics (collector-derived) | Notification inbox / delivery → [notifications-email.md](notifications-email.md) |

**The honest starting point:** the entire `activity` module is `me`-scoped. Every
endpoint resolves `@AuthenticationPrincipal User` and refuses a null principal;
there is **no `@PreAuthorize`, no `hasRole`, no ADMIN/MODERATOR path** anywhere in
the module. So *every admin capability in this doc is* **[PLANNED]** — and, per the
privacy contract above, most of them (the population metrics) are deliberately built
to *avoid* reading the private store at all. The data already exists and is
well-structured; the "admin lens" is intentionally narrow.

---

## 2. How it works today (ground truth)

`UserActivityServiceImpl` is a **sink**: it does not drive other modules, it records
what they did. Each `record*(...)` call (many `@Async`) writes the same logical
event into **three Cassandra tables** so the three read shapes are all O(partition):

| Table | Purpose | Key |
|-------|---------|-----|
| `activity_by_user` **[EXISTS]** | the "all my activity" feed | partition `user_id`, clustered `created_at DESC, activity_id` |
| `activity_by_user_and_type` **[EXISTS]** | the per-type filter feed | partition `(user_id, activity_type)`, clustered `created_at DESC` |
| `activity_lookup` **[EXISTS]** | point lookup by id (the delete-one path) | PK `activity_id` |
| `reel_views_by_user` **[EXISTS]** | one row per reel watch | partition `user_id`, clustered `created_at DESC, reel_view_id` |

Entities: `activity/cassandra/entity/{UserActivityEntity, UserActivityByTypeEntity,
ActivityLookupEntity, ReelViewEntity}`. Repositories are raw-CQL
`CassandraRepository` variants.

**Who writes it:** the module is fed from outside itself —
`rabbitmq/consumer/NotificationEventConsumer` (a `@RabbitListener` on the
notifications queue records post/research reaction·comment·share·comment-reaction
activities), plus direct `@Async` calls from `post/cassandra` (`CassandraPostService`,
`CassandraSaveService`), `research`, `qna`, `user` (`UserSocialServiceImpl` →
follows), and `common` (`MentionService` / `MentionController`). So RabbitMQ reaches
activity *indirectly*, through the notification consumer. **[EXISTS]**

**Live delivery** is **Redis pub/sub, not RabbitMQ**: `UserActivityRealtimePublisher`
publishes JSON to `irc:activity:{userId}`; `UserActivityRealtimeSubscriber`
re-broadcasts to that user's local SSE emitters; `UserActivityRealtimeBroadcaster`
defers the publish to `afterCommit`. Heartbeat: `UserActivityRealtimeService.heartbeat()`
`@Scheduled(fixedDelay = 25_000)` pings every 25s. **[EXISTS]**

### 2.1 The 30 activity types (`UserActivityType`) **[EXISTS]**

| Group | Values |
|-------|--------|
| Post engagement | `POST_CREATED`, `POST_REACTION`, `POST_COMMENT`, `POST_COMMENT_REACTION`, `POST_SHARE`, `POST_SAVED`, `REEL_WATCH` |
| Discovery | `GLOBAL_SEARCH`, `HASHTAG_SEARCH`, `MENTION_LOOKUP` (outgoing), `USER_MENTIONED` (incoming), `PROFILE_VIEW`, `FOLLOWED_USER` |
| Q&A | `QNA_QUESTION_CREATED`, `QNA_QUESTION_SAVED`, `QNA_ANSWER_CREATED`, `QNA_REANSWER_CREATED`, `QNA_ANSWER_REACTION`, `QNA_ANSWER_FEEDBACK` |
| Research | `RESEARCH_PUBLISHED`, `RESEARCH_SAVED`, `RESEARCH_REACTION`, `RESEARCH_COMMENT`, `RESEARCH_COMMENT_REACTION` |
| Story | `STORY_VIEWED`, `STORY_REACTED`, `STORY_REPLIED`, `STORY_POLL_VOTED` |
| Sound | `SOUND_USED` |

> Consistency note for analytics: `QNA_ANSWER_FEEDBACK` is a legacy type — the
> platform's Q&A subsystem **removed answer rating/feedback** (only author-accept
> remains). Treat `QNA_ANSWER_FEEDBACK` as a dead type when building type
> breakdowns; do not present it as a live engagement signal.

### 2.2 Existing endpoints — all `me`-scoped **[EXISTS]**

| Method | Path | Notes |
|--------|------|-------|
| `GET` | `/api/v1/users/me/activity` | paged; params `type`, `types` (union), `from`/`to` ISO instants, `page`/`size`. `totalElements` is **approximate** (Cassandra has no cheap partition count) |
| `DELETE` | `/api/v1/users/me/activity/{activityId}` | delete one entry (uses `activity_lookup`) |
| `DELETE` | `/api/v1/users/me/activity` | clear all (optional `?type=`); returns `{deleted: N}`. **Bulk cap: 200/batch, ≤50 batches (~10k rows) per call** |
| `GET` | `/api/v1/users/me/activity/stream` | SSE; JWT principal **or** `?token=<accessToken>` (EventSource can't set headers) |
| `POST` | `/api/v1/posts/{postId}/reels/view` | record a reel watch (body optional `watchedSeconds`) |
| `GET` | `/api/v1/users/me/reels/watched` | list watched reels |
| `DELETE` | `/api/v1/users/me/reels/watched/{reelViewId}` | delete one |
| `DELETE` | `/api/v1/users/me/reels/watched` | clear all watch history |

---

## 3. Dashboard views / widgets

Two surfaces: a **break-glass per-user forensics panel** (*not* a routine tab —
opened only inside an authorized case, see below) and the **population engagement**
board (which never reads the private store).

### 3.1 Break-glass "User activity" forensics panel

**This is not a tab on the user-detail page.** Per the privacy contract, admins do
not routinely browse a user's activity. This panel opens **only** from an authorized
context — an open moderation case with a legal-hold flag, an active
law-enforcement/legal request, or a safety investigation the user is the subject of —
and every open is dual-control (a second admin's approval), step-up-gated, reason-
required, and written to the audit trail *and* (where policy requires) disclosed to
the user afterward. Outside such a context the endpoints (§5 A1–A3, A5) return 403.

| Widget (visible only in an open case) | Layout / content | Status |
|--------|------------------|--------|
| **Activity timeline** | reverse-chron feed of the user's rows: icon per `UserActivityType`, target id + resolved title, timestamp; type filter chips (the six groups above), date-range picker | **[PLANNED]** — data via `activity_by_user`; break-glass read (§5 A1) |
| **Type histogram** | bar chart: count per activity type over the window — corroborates a report ("99% `PROFILE_VIEW` on one target" = stalking) | **[PLANNED]** — driven by `activity_by_user_and_type` per-type partitions |
| **Reel-watch history** | table: reel (thumb + author), `watched_seconds`, watched-at; completion ratio if the reel duration is known | **[PLANNED]** — data via `reel_views_by_user` |
| **Erasure control** | "Erase activity history" button (all / by-type) behind step-up + confirm; shows last-erased marker | **[PLANNED]** — admin wrapper over the existing self-serve clear-all (§5 A4); this one *is* routinely available (erasure honors the user-deletable contract) |
| **Evidence export** | export the visible window as JSON/CSV to attach to the case record | **[PLANNED]** |

### 3.2 Tab "Engagement" (population-level)

| Widget | Content | Status |
|--------|---------|--------|
| **Activity volume** | total activity rows/day, stacked by the six type groups (90d) | **[PLANNED]** — requires a rollup collector (§6); no population aggregate exists today |
| **Reel-view funnel** | views recorded/day, median `watched_seconds`, completion-rate distribution | **[PLANNED]** |
| **Discovery mix** | share of `GLOBAL_SEARCH` vs `HASHTAG_SEARCH` vs `PROFILE_VIEW` vs `FOLLOWED_USER` — how people find things | **[PLANNED]** |
| **Active-contributor overlap** | users with ≥1 *create* activity (`POST_CREATED`/`RESEARCH_PUBLISHED`/`QNA_*_CREATED`) in window — feeds the WALC north-star | **[PLANNED]** — see [analytics-kpis.md](analytics-kpis.md) §4.1 |
| **SSE stream health** | live `irc:activity:{userId}` connection count, heartbeat success, emitter churn | **[PARTIAL]** — heartbeat exists; a metrics counter is the build (see [operations.md](operations.md) §6.2) |

---

## 4. Data sources

| Widget / need | Source | Status |
|---|---|---|
| Per-user timeline | `UserActivityCassandraRepository` over `activity_by_user` (keyset by last `created_at`) | **[EXISTS]** data, **[PLANNED]** admin reader |
| Per-type histogram | `UserActivityByTypeRepository` over `activity_by_user_and_type` — one partition per `(user_id, type)`; sum via per-partition scans | **[EXISTS]** data |
| Reel-watch list | `ReelViewCassandraRepository` over `reel_views_by_user` | **[EXISTS]** data |
| Point delete / erase-one | `activity_lookup` (`ActivityLookupRepository`) — the delete path already reads this to find the `(user_id, type, created_at)` coordinates | **[EXISTS]** |
| Bulk erase | `UserActivityServiceImpl` clear-all (batched 200 × ≤50) | **[EXISTS]** (self-serve today) |
| Live stream | Redis pub/sub `irc:activity:{userId}` → SSE | **[EXISTS]** |
| Population aggregates | **none** — Cassandra can't cheaply count/group across users | **[PLANNED]** — needs the analytics rollup (§6) |

**Hard truth for the population tab:** the storage model is optimized for
"read one user's feed fast," the exact opposite of "count across all users." There
is **no** partition that answers "how many `REEL_WATCH` rows platform-wide today."
Every §3.2 widget therefore depends on the analytics collector, not on this store.

---

## 5. Admin actions

All **[PLANNED]** — none exist today. Every endpoint must live under
`/api/v1/admin/**` to inherit the double gate ([README.md](README.md) ground rules).
Step-up = `StepUpService.requireRecentStepUp(adminId)` **[EXISTS primitive]**; audit
row = `AuditLogService.record` **[EXISTS primitive, zero callers]**.

Actions A1–A3 and A5 are **break-glass**: they require an open authorized case
(legal hold / law-enforcement request / active investigation of this user),
**dual-control** (second-admin approval), mandatory step-up, a required reason, and
post-hoc disclosure to the user where policy requires. Absent an open case, they 403.

| # | Action | Proposed endpoint | Danger | Access gate | Audit action |
|---|--------|-------------------|--------|-------------|--------------|
| A1 | Read a user's activity timeline | `GET /api/v1/admin/users/{userId}/activity?type=&types=&from=&to=&page=` | **critical** (private behavioral PII) | **break-glass**: case + dual-control + step-up | `ADMIN_ACTIVITY_BREAKGLASS_VIEW` |
| A2 | Read a user's reel-watch history | `GET /api/v1/admin/users/{userId}/reels/watched` | **critical** (private PII) | **break-glass** | `ADMIN_REELVIEWS_BREAKGLASS_VIEW` |
| A3 | Activity type histogram (one user) | `GET /api/v1/admin/users/{userId}/activity/summary?window=90d` | **high** | **break-glass** (aggregate, still per-user private) | `ADMIN_ACTIVITY_SUMMARY_VIEW` |
| A4 | Erase a user's activity | `POST /api/v1/admin/users/{userId}/activity/erase` body `{type?, reason}` | **high** (irreversible) | step-up (routinely available — honors user-deletable) | `ADMIN_ACTIVITY_ERASE` — wraps the existing batched clear-all |
| A5 | Export activity for a case | `GET /api/v1/admin/users/{userId}/activity/export?from=&to=&format=json\|csv` | **critical** (PII egress) | **break-glass** | `ADMIN_ACTIVITY_EXPORT` |
| A6 | Population engagement rollup | `GET /api/v1/admin/analytics/engagement?window=90d` | read | none (aggregate, collector-sourced — never touches the private store) | interceptor — depends on §6 collector |

**Deliberate non-actions:** no admin *write/insert* of activity (the ledger is a
factual record — forging engagement corrupts both analytics and evidence), and no
cross-user "who viewed this profile" reverse index (would turn `PROFILE_VIEW` into a
surveillance tool; keep it forward-only per user).

> Erasure & the deletion pipeline: when an account is purged
> ([../settings/data-export-deletion.md](../settings/data-export-deletion.md)), its
> `activity_by_user*` and `reel_views_by_user` partitions **must** be dropped as
> part of the cascade. Confirm this is wired into the account-purge job; if not,
> it's a GDPR gap — activity is personal data. Flag as **[PLANNED verification]**.

---

## 6. Activity as an engagement-telemetry source **[PLANNED]**

This is the highest-leverage idea in the section. The platform has **no unified
event pipeline** today ([analytics-kpis.md](analytics-kpis.md) §6), yet this ledger
is *already* a de-facto event stream — 30 typed, timestamped, user-attributed
engagement events written on every meaningful interaction. Two options:

1. **Tee at the sink.** Where `UserActivityServiceImpl.record*` writes the three
   per-user tables, also emit into the proposed `analytics_events` Cassandra table
   (bucketed by day, not by user) so population rollups become cheap. One extra
   write, no new instrumentation — the events are already being computed.
2. **Nightly rollup job.** A `@Scheduled` job scans the day's activity and writes
   `engagement_daily` counters (per type, per group, DAU-with-activity). Heavier
   (full-scan), but requires zero change to the hot write path.

Either way, the `UserActivityType` enum **is** the event taxonomy — reuse it verbatim
so activity, analytics, and this dashboard speak one vocabulary. This is called out
as a candidate collector in [analytics-kpis.md](analytics-kpis.md) §6.3.

---

## 7. Abuse & safety signals from activity **[PLANNED]**

Abuse *detection* runs on the parallel collector aggregates (§6), **not** by scanning
private per-user partitions — the platform can flag an anomalous pattern without any
admin reading a specific person's history. Only once a flag opens an authorized case
does a human view that user's raw timeline (break-glass, §3.1). Signals worth
surfacing to [safety-reports.md](safety-reports.md):

| Signal | Pattern in the ledger | Interpretation |
|--------|----------------------|----------------|
| Scraper / bot | `GLOBAL_SEARCH` + `PROFILE_VIEW` dominate; near-zero create/engage | automated harvesting |
| Stalking | high `PROFILE_VIEW` concentrated on one `target_id` | targeted harassment — corroborates a report |
| Follow-churn | `FOLLOWED_USER` spikes (follow/unfollow farming) | growth-hack abuse |
| Mention-spam corroboration | `MENTION_LOOKUP` bursts | pairs with the mention-abuse view in [content-moderation.md](content-moderation.md) |

These are **read-only corroboration widgets** shown inside a report case, never
auto-enforcement — the ledger records intent-free actions and a human decides.

---

## 8. Logs surfaced in this section

| Log | Store | In this section |
|-----|-------|-----------------|
| The activity ledger itself | Cassandra `activity_by_user*`, `reel_views_by_user` | the primary data — **product** engagement, not audit |
| Admin reads/erasures of activity | Cassandra audit (`AuditLogService.record`) | every A1–A5 call writes an audit row → [logs-audit.md](logs-audit.md) |
| SSE connect/disconnect | app logs | stream-health widget (§3.2) |

Retention: activity rows are unbounded today (no TTL). A **[PLANNED]** retention
policy (e.g. Cassandra table TTL, or a rollup-then-trim job) belongs here — an
engagement ledger that grows forever is both a cost and a privacy-surface problem.

---

## 9. Alerts & thresholds **[PLANNED]**

| Alert | Condition | Why |
|-------|-----------|-----|
| SSE heartbeat failing | `irc:activity:*` emitters not receiving the 25s ping | live activity feed broken |
| Activity write lag | RabbitMQ notification consumer backlog (activity is fed from it) | timeline going stale — see [operations.md](operations.md) §5 |
| Ledger growth anomaly | daily row count ≫ trailing baseline for one user | bot / abuse (feeds §7) |

---

## 10. Permissions & safety notes

- **Default is no access.** A user's activity timeline is among the most sensitive
  surfaces on the platform — it reveals what they searched, whose profiles they
  viewed, what they watched. Per the privacy contract (top of doc + [analytics-kpis.md](analytics-kpis.md)
  §12), admins **do not routinely browse it**. Per-user reads (A1–A3, A5) are
  **break-glass only** — an open case, dual-control, step-up, reason, audit, and
  user disclosure. The routinely-available per-user action is **erasure** (A4),
  because deletion *honors* the user-deletable contract rather than violating it.
- **Forward-only.** No reverse "who viewed X" index — see §5 non-actions.
- **No content leakage via ids.** Timeline rows store `target_id`s; resolving them
  to titles/authors for display must respect the target's current visibility
  (a since-deleted or since-restricted target shows a tombstone, not its content).
- **Erasure is real deletion**, not soft-delete — it removes rows from all four
  tables. Irreversible; hence step-up + confirm.
