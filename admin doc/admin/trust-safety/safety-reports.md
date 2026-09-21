# Section 5 — Safety & Reports

Admin side of the Safety Center. The user-facing half already exists and is documented in [../settings/safety-center.md](../../settings/safety-center.md) (spec §18): report intake, own-report listing, reporter appeal, strikes view, security score. **The moderator/triage surface is now built (2026-08)** — `AdminSafetyController` + `ReportModerationService` drive the full state machine (triage / action / dismiss / uphold / reverse), and `StrikeService.issueStrike` has real callers. This section documents that console.

Related sections: [content-moderation.md](content-moderation.md) (content takedown primitives), [users-roles.md](../users/directory-and-roles.md) (account suspension), [logs-audit.md](../platform/logs-audit.md) (audit log + consent/settings trails), [architecture.md](../foundation/architecture.md) (access model), [admin-api-blueprint.md](../foundation/api-blueprint.md) (all endpoints, phased).

## 1. Purpose & scope

| In scope | Out of scope (owned elsewhere) |
|---|---|
| Report triage queue (dedup-grouped), report detail + evidence panel | Generic content queues & keyword blocklist → [content-moderation.md](content-moderation.md) |
| Full `ReportState` machine driving: triage, action+resolution, dismiss | Suspend/ban mechanics on the `User` entity → [users-roles.md](../users/directory-and-roles.md) |
| Appeals review queue (uphold / reverse) | Sound approval queue → [content-moderation.md](content-moderation.md) |
| Strikes ledger + threshold automation | Security score rules (user-facing, derived) → [../settings/safety-center.md](../../settings/safety-center.md) |
| Per-user moderation record (360° view) | Raw audit log explorer → [logs-audit.md](../platform/logs-audit.md) |
| `consent_events` viewer (compliance evidence) | Consent *capture* (user-facing `POST /api/v1/settings/consent`) |
| Block/restriction aggregate stats | Per-user block/restrict management (user-facing) |
| Safety SLAs, alerts, KPIs | Platform-wide analytics → [analytics-kpis.md](../platform/analytics-kpis.md) |

## 2. What is real today (foundation inventory)

| Capability | Status | Reality |
|---|---|---|
| Report entity + state machine enum | **[EXISTS]** | `settings/safety/entity/Report.java`; `ReportState {SUBMITTED, TRIAGED, ACTIONED, DISMISSED, APPEALED, UPHELD, REVERSED}` |
| Resolution vocabulary | **[EXISTS]** | `Resolution {NONE, WARNING_ISSUED, CONTENT_REMOVED, ACCOUNT_SUSPENDED, NO_ACTION}` — javadoc already mandates it stays private to the target's record |
| Report intake | **[EXISTS]** | `POST /api/v1/safety/reports` (`SafetyController`): 9 `ReportTargetType`s, 10 `ReportReason`s, `details` ≤1000 chars, rate-limited 20/h/reporter (`RateLimiter` key `safety:report`) |
| Dedup key | **[EXISTS]** | `Report.groupKey = targetId + ":" + reason`, index `idx_report_group`; `ReportRepository.findFirstByReporterIdAndGroupKeyAndStateIn` reuses a reporter's own open (SUBMITTED/TRIAGED) report instead of duplicating |
| Reporter appeal | **[EXISTS]** | `POST /api/v1/safety/reports/{id}/appeal` — only from ACTIONED\|DISMISSED, only by the reporter → APPEALED |
| Coarse-outcome projection | **[EXISTS]** | `SafetyDtos.ReportResponse.coarseOutcome(state)` — reporter only ever sees UNDER_REVIEW / ACTION_TAKEN / NO_ACTION / APPEAL_UNDER_REVIEW; the `resolution` column is never serialized to the reporter |
| Strike ledger + 90-day decay | **[EXISTS]** | `UserStrike` (`user_strikes`, index `(user_id, expires_at)`), `@PrePersist` sets `expiresAt = issuedAt + 90d`; `StrikeService.myStrikes/activeCount` filter by `isActive()` — decay is a read-time filter, rows are never deleted |
| Strike issuance method | **[EXISTS]** | `StrikeService.issueStrike(userId, reportId, reason)` — three callers since 2026-08: `AdminSafetyController.issueStrike` (`POST /api/v1/admin/safety/users/{userId}/strikes`), `ReportModerationService.action` (inline strike for USER-target reports), and `AdminUserServiceImpl.issueStrike` (`POST /api/v1/admin/users/{userId}/strikes`) |
| Triage/action/dismiss/appeal-resolution transitions | **[EXISTS]** (built 2026-08) | `ReportModerationService` sets TRIAGED, ACTIONED, DISMISSED, UPHELD and REVERSED behind `/api/v1/admin/safety/**` |
| Moderator read path over reports | **[EXISTS]** (built 2026-08) | Grouped queue `GET /api/v1/admin/safety/reports` (`ReportRepository.findOpenGroups`) + per-report detail with frozen evidence |
| Reviewer attribution on Report | **[EXISTS]** (built 2026-08) | `reports` now carries `triaged_by / actioned_by / acted_at / moderator_note` |
| Content takedown primitive | **[EXISTS]** (built 2026-08) | Admin takedown paths exist for every content type; `PostStatus.REMOVED` is written by `AdminContentService.removePost`, and `Resolution.CONTENT_REMOVED` is implemented in `ReportModerationService.action` — see [content-moderation.md](content-moderation.md) |
| Account suspension primitive | **[PARTIAL]** | A distinct **timed** suspension state still does not exist, but `AdminUserController` now carries disable/enable, lock/unlock, session revocation, credential resets, provisioning, deletion lifecycle, strikes and impersonation — the untimed account-disable primitive is real — see [users-roles.md](../users/directory-and-roles.md) |
| Consent evidence log | **[EXISTS]** | `consent_events` (append-only; `user_id, scope, granted, app_version, occurred_at`); user-facing reads `GET /api/v1/settings/consent[/{scope}]`; admin read path (built 2026-08): `GET /api/v1/admin/safety/users/{userId}/consent[?scope=]` |
| Block/restrict data | **[EXISTS]** | `user_blocks` / `user_restrictions` tables; aggregates built 2026-08 — `UserBlockRepository.blocksPerDay/topBlockedUsers/countAllBlocks`, `UserRestrictionRepository.restrictionsPerDay/countAllRestrictions` → `GET /api/v1/admin/safety/stats/blocks` |

## 3. Dashboard views / widgets

### 3.1 Reports queue (landing view)

The queue is **grouped by `group_key`** — one row per (target, reason) with an aggregated report count, not one row per report. The per-reporter dedup that exists today prevents one reporter spamming; the *queue-level* cross-reporter grouping is served by `ReportRepository.findOpenGroups` behind `GET /api/v1/admin/safety/reports` **[EXISTS]** (built 2026-08).

| Widget | Content |
|---|---|
| Queue table | One row per open group: target preview (type icon + snippet/username), reason chip, **report count** (distinct reporters), oldest-report age, state, priority flag. Default sort: priority desc, then oldest first |
| Filter bar | `state` (default SUBMITTED+TRIAGED), `targetType` (9 values), `reason` (10 values), age bucket (<4h / <24h / <72h / older), min report count, "appealed only" toggle |
| Priority lane | Pinned strip of SELF_HARM and VIOLENCE reason groups — always on top regardless of sort (SLA 4h, §8) |
| Queue health tiles | Open groups, open reports, oldest unresolved age, appeal backlog count, actions today — each tile links to the filtered view |
| Brigading signal | Groups whose report count grew >10 in the last hour get a "surge" badge (mass-reporting is itself a signal worth review — both of genuine severity and of coordinated abuse) |

### 3.2 Report detail — triage workbench

Three-pane layout: **evidence** (center), **reporter context** (left), **target's moderation record** (right). Action bar fixed at the bottom.

| Pane | Content | Notes |
|---|---|---|
| Evidence panel | Snapshot of the reported content: body/caption, media thumbnails, author, created-at, current live status ("still up" / "author-deleted" / "expired") + deep link to the live entity | See §3.3 — snapshot-at-submit is a required build item, not a nice-to-have |
| Report group | Every `reports` row in the group: reporter (pseudonymized handle for L1 moderators — §8), per-reporter `details` free text, submitted-at | `details` is the only reporter-authored text; render escaped, never as HTML |
| Reporter context | Per reporter: total reports filed, actioned-rate vs dismissed-rate ("reporter accuracy"), account age, active strikes of their own | All derivable from `reports` + `user_strikes` with new queries **[PLANNED]** |
| Target moderation record | Embedded §3.5 view: active/expired strikes, prior reports against (all states + resolutions), prior takedowns, blocks/restricts received count | The `resolution` column is exactly this "target's private moderation record" per its javadoc |
| History strip | This group's transition history: who triaged/actioned/dismissed, when, moderator note | Reviewer columns **[EXISTS]** (built 2026-08 — `triaged_by/actioned_by/acted_at/moderator_note`); mutations also land in the platform audit log ([logs-audit.md](../platform/logs-audit.md)) |

### 3.3 Evidence snapshot — why capture-at-submit is mandatory **[EXISTS]** (built 2026-08 — `report_evidence` + `ReportEvidenceService`)

Today the evidence panel could only fetch the target *live* from its domain store. That fails for exactly the content most likely to be reported:

| Target type | Live store | Volatility problem |
|---|---|---|
| POST / COMMENT | Cassandra `posts_by_id` / comment tables | Comments are **hard-deleted** (tombstone range-delete) — an author can destroy evidence instantly |
| STORY | Cassandra `stories_by_author` (row TTL 8/16/24h) | Evidence self-destructs within 24h even if nobody deletes anything |
| MESSAGE | Cassandra `message_by_id` | Disappearing messages carry row TTL; live-stream chat is **never persisted at all**; also see the id-type defect below |
| RESEARCH / QUESTION / ANSWER | Postgres | Stable, but retract/delete paths exist |
| USER / CHANNEL | Postgres | Profile fields are freely editable after being reported |

Built (2026-08): the `report_evidence` table (PG) is written at submit by `ReportEvidenceService` — one snapshot per group (unique `group_key` index; first report wins). Media bytes are *not* copied; R2 keys are recorded and R2 lifecycle rules must exempt evidence-referenced keys — coordinate with [media-storage.md](../content/media-storage.md).

> **Defect fixed (2026-08):** `Report.targetId` was `UUID`-only, so a `MESSAGE` report could not reference its Snowflake-id target. `targetId` is now nullable with a `target_ref varchar(64)` column for non-UUID targets (exactly one of the two is set); `ReportService.submit` accepts `targetRef`, and `ReportEvidenceService.captureMessage` parses the Snowflake and snapshots via `MessageByIdRepository`.

### 3.4 Appeals queue

Same workbench as §3.2 filtered to `state = APPEALED`, plus: the original decision (state it came from — ACTIONED or DISMISSED — plus `resolution`), deciding moderator, and the strike issued from this report if any (`user_strikes.report_id` link — the entity javadoc says exactly this: "each strike stores the linked report id so an appeal can be reviewed against real evidence" **[EXISTS]**).

Note the asymmetry, which the console must surface honestly: **only the reporter can appeal** (`ReportService.appeal` checks `reporterId`). The *target* of an action has no visibility into the report and no appeal path today. A target-side strike appeal (`POST /api/v1/safety/strikes/{id}/appeal`) is a recommended follow-on **[PLANNED]** — without it, REVERSED only ever corrects over-dismissal complaints from reporters, never over-enforcement against targets.

### 3.5 Per-user moderation record **[EXISTS]** (built 2026-08 — `GET /api/v1/admin/safety/users/{userId}/record`)

Single view answering "who is this user, morally": active strikes (with decay countdown), expired strikes, reports **against** them grouped by reason (with resolutions — admin-only column), reports **filed by** them + accuracy rate, content takedowns, suspension history (once [users-roles.md](../users/directory-and-roles.md) builds it), blocks/restricts received counts, consent history (§3.6), link to their audit trail (`GET /api/v1/admin/audit/users/{userId}` **[EXISTS]**).

### 3.6 Consent events viewer **[EXISTS]** (built 2026-08 — `GET /api/v1/admin/safety/users/{userId}/consent[?scope=]`)

Compliance evidence view over `consent_events`: per-user timeline of grant/revoke per scope (CONTACTS/LOCATION/PHOTOS/…), app version, occurred-at; current-state matrix per scope. Strictly read-only — the log is append-only by design and an admin must never be able to fabricate or delete consent evidence. Aggregate widget: grant rate per scope, revocations/week.

### 3.7 Block & restriction aggregates **[EXISTS]** (built 2026-08 — `GET /api/v1/admin/safety/stats/blocks`)

| Widget | Source |
|---|---|
| Blocks/restricts created per day (30d trend) | `COUNT` over `user_blocks` / `user_restrictions` grouped by created-at date — new queries; existing repos are per-user only |
| Most-blocked users (top-N inbound blocks) | `GROUP BY blocked_id ORDER BY count DESC` — a strong abuse signal that requires no report to exist |
| Overlap signal | Users in top-N inbound blocks **and** carrying open report groups get flagged in the queue |

### 3.8 Strikes ledger

Platform-wide strike view: strikes issued per day, currently-active strike distribution (how many users at 1/2/3+ active strikes — `user_strikes` + `countActive` semantics **[EXISTS]** per user; ledger read `GET /api/v1/admin/safety/strikes` built 2026-08), decay forecast (strikes expiring in next 7/30 days), threshold-automation event log (§5).

### 3.9 Privacy & discovery posture **[PLANNED]** (tables **[EXISTS]**, aggregates new)

The settings module's privacy engine ([../settings/privacy.md](../../settings/privacy.md))
is user-owned; the admin surface is **aggregate posture only** — never a tool to
inspect or override an individual's privacy choices:

| Widget | Shows | Source |
|---|---|---|
| Privacy policy distribution | For each `FieldKey`: % of users at each `VisibilityLevel` (how "closed" is the platform — e.g. % with `POSTS != EVERYONE`) | `user_privacy` JSONB (GIN-indexable) + `PrivacyDefaults` for the absent-row majority |
| Discoverability flags | % discoverable by username / phone / email / QR; % `indexable=false` (search-engine opt-outs) | `user_discoverability` (`DiscoverabilityService`) |
| QR discovery usage | Active QR tokens, rotations/week (a rotation spike can signal leaked-code concerns) | `qr_tokens` (`rotated_at`) |
| Privacy-change velocity | `settings_audit` rows with key `privacy.*` / `discovery.flags` per day — a platform-wide "users are locking down" trend is itself a trust signal worth alerting on | `settings_audit` (catalogued in [logs-audit.md](../platform/logs-audit.md)) |

**Boundary [rule]:** the `VisibilityResolver` is enforcement machinery, not an
admin bypass — admin *content* views run under their own `ADMIN` authority with
audit, never by impersonating a viewer through the resolver. No endpoint may
let an admin *edit* another user's privacy policy; the only admin write near
this area is account-level (suspend/delete) in [users-roles.md](../users/directory-and-roles.md).

## 4. Data sources (per widget, exact)

| Widget | Store | Table / class / endpoint | Status |
|---|---|---|---|
| Queue groups | PG | `reports` grouped by `group_key` over `idx_report_group`; `ReportRepository.findOpenGroups` | **[EXISTS]** (built 2026-08) |
| Report rows / detail | PG | `reports` (all columns incl. `resolution` — admin is the only surface allowed to render it) | **[EXISTS]** |
| Reviewer attribution | PG | `reports` columns `triaged_by, actioned_by, acted_at, moderator_note varchar(1000)` | **[EXISTS]** (built 2026-08) |
| Evidence snapshot | PG | `report_evidence` (§3.3, `ReportEvidenceService`) | **[EXISTS]** (built 2026-08) |
| Live target hydration | Cassandra/PG per type | `posts_by_id`, comment tables, `stories_by_author`, `message_by_id`, `research`, `questions`, `question_answers`, `users`, `conversations` | **[EXISTS]**, read-only joins |
| Reporter context | PG | `reports` by `reporter_id` (`findByReporterIdOrderByCreatedAtDesc` **[EXISTS]**) + accuracy aggregate **[PLANNED]** | mixed |
| Strikes | PG | `user_strikes`; `StrikeService.myStrikes/activeCount/issueStrike` | **[EXISTS]** |
| Consent viewer | PG | `consent_events`; `ConsentService.history/currentState` behind `GET /api/v1/admin/safety/users/{userId}/consent` | **[EXISTS]** (admin route built 2026-08) |
| Block/restrict stats | PG | `user_blocks`, `user_restrictions` — `blocksPerDay`, `topBlockedUsers`, `countAllBlocks`, `restrictionsPerDay`, `countAllRestrictions` | **[EXISTS]** (built 2026-08) |
| Moderation-record audit strip | Cassandra | `GET /api/v1/admin/audit/users/{userId}` (`AuditLogController`) | **[EXISTS]** |
| Queue health tiles / SLA clocks | PG | Aggregates over `reports` (`state`, `created_at`, `updated_at`) | **[PLANNED]** queries |

## 5. Admin actions

All routes live under `/api/v1/admin/safety/**` — inheriting the filter-chain double gate (`SecurityConfig` hard-codes `hasRole('ADMIN')` on `/api/v1/admin/**` **[EXISTS]**). Every mutation records an audit action via `AdminAuditor` → `AuditLogService.record(...)` (funnelled from every admin mutation since the 2026-08 build) and appears in the admin audit SSE stream. Step-up = re-auth via `StepUpService` (Redis `stepup:{userId}`, TTL 300s **[EXISTS]**, see [../settings/auth-sessions.md](../../settings/auth-sessions.md)).

**Built 2026-08 (`AdminSafetyController` + `ReportModerationService`)** — every row below is live except the two dependency-noted ones (takedown / suspend-target), flagged inline.

| Action | Endpoint | Params | Wraps | Danger | Step-up | Audit action |
|---|---|---|---|---|---|---|
| List queue (grouped) | `GET /api/v1/admin/safety/reports?state=&targetType=&reason=&minAgeHours=&minCount=&page=` | filters + paging | new repo queries | read | no | — (reads land in request audit log anyway) |
| Report/group detail | `GET /api/v1/admin/safety/reports/{id}` | — | `ReportRepository.findById` + hydration | read | no | — |
| Mark triaged | `POST /api/v1/admin/safety/reports/{id}/triage` | `{note?}` | `ReportModerationService.triage` → SUBMITTED→TRIAGED | low | no | `SAFETY_REPORT_TRIAGED` |
| Dismiss | `POST /api/v1/admin/safety/reports/{id}/dismiss` | `{note?, wholeGroup:bool=true}` | →DISMISSED, `resolution=NO_ACTION` | low | no | `SAFETY_REPORT_DISMISSED` |
| Action + pick resolution | `POST /api/v1/admin/safety/reports/{id}/action` | `{resolution: WARNING_ISSUED\|CONTENT_REMOVED\|ACCOUNT_SUSPENDED\|NO_ACTION, note?, issueStrike:bool, strikeReason?, wholeGroup:bool=true}` | →ACTIONED, sets `resolution`; optionally calls `StrikeService.issueStrike` **[EXISTS]** | medium–high (by resolution) | yes if resolution ≠ NO_ACTION | `SAFETY_REPORT_ACTIONED` |
| Issue strike (standalone) | `POST /api/v1/admin/safety/users/{userId}/strikes` | `{reportId?, reason}` | `StrikeService.issueStrike` **[EXISTS]** | medium | yes | `SAFETY_STRIKE_ISSUED` |
| Revoke strike | `DELETE /api/v1/admin/safety/strikes/{strikeId}` | `{note}` | set `expiresAt = now()` (decay-consistent; never delete the row) | medium | yes | `SAFETY_STRIKE_REVOKED` |
| Takedown content | *(no separate route)* | — | the content-remove primitive now exists (built 2026-08 — [content-moderation.md](content-moderation.md)); takedown is executed via the `action` transition with `resolution=CONTENT_REMOVED` plus the `/api/v1/admin/content/**` endpoints | high | yes | `SAFETY_REPORT_ACTIONED` |
| Suspend account | `POST /api/v1/admin/safety/reports/{id}/suspend-target` | `{durationDays?, note}` | **still unbuilt** — no timed-suspension primitive exists (untimed disable lives in `AdminUserController` — [users-roles.md](../users/directory-and-roles.md)) | critical | yes | `SAFETY_ACCOUNT_SUSPENDED` |
| Uphold appeal | `POST /api/v1/admin/safety/appeals/{id}/uphold` | `{note}` | APPEALED→UPHELD (original decision stands) | medium | no | `SAFETY_APPEAL_UPHELD` |
| Reverse appeal | `POST /api/v1/admin/safety/appeals/{id}/reverse` | `{note}` | APPEALED→REVERSED; auto-revokes the linked strike (via `user_strikes.report_id`); flags any takedown for restore review | high | yes | `SAFETY_APPEAL_REVERSED` |
| User moderation record | `GET /api/v1/admin/safety/users/{userId}/record` | — | composite read (§3.5) | read | no | — |
| Consent viewer | `GET /api/v1/admin/safety/users/{userId}/consent[?scope=]` | paging | `ConsentService.history/currentState` **[EXISTS]** | read | no | — |
| Block/restrict stats | `GET /api/v1/admin/safety/stats/blocks` | `?days=30` | new aggregate queries | read | no | — |

State-transition guards (enforced in `ReportModerationService`, mirroring the enum's documented lifecycle): triage only from SUBMITTED; action/dismiss from SUBMITTED or TRIAGED; uphold/reverse only from APPEALED. `wholeGroup=true` applies the transition to every open report sharing the `group_key` in one transaction — the queue is group-first, so acting per-row is the exception.

### The coarse-outcome rule (do not weaken it)

`Resolution` is **never** disclosed to the reporter — `ReportResponse` already collapses state into four coarse buckets (`coarseOutcome` switch in `SafetyDtos` **[EXISTS]**), and the admin build must not add any reporter-visible field derived from `resolution`. Why this is a hard rule, not a style choice:

1. **Harassment amplification** — telling a reporter "account suspended" confirms their report *hurt* the target, which weaponizes reporting in coordinated campaigns (report → confirm damage → escalate). The `Resolution` javadoc states this verbatim.
2. **Oracle attacks** — precise outcomes let hostile reporters map moderation thresholds by binary search ("what exactly gets a takedown vs. a warning?") and then skirt them.
3. **Target privacy** — a strike or suspension is the target's private disciplinary record; the reporter is a witness, not a party to it.

The admin console is therefore the **only** surface that renders `reports.resolution`, `user_strikes` of other users, and moderator notes. Any future notification to reporters (`REPORT_RESOLVED` NotificationType — optional **[PLANNED]**, none exists today) must carry only the coarse bucket.

## 6. Logs surfaced in this section

| Log | Store | Role here | Status |
|---|---|---|---|
| `reports` | PG | The queue's system of record; every state/resolution transition is data, not just log | **[EXISTS]** |
| `user_strikes` | PG | Ledger — rows never deleted; decay + revocation are `expires_at` writes, so the ledger is inherently historical | **[EXISTS]** |
| Platform audit log (Cassandra `audit_log_by_user` / `by_resource` + SSE) | Cassandra | Every admin mutation above lands via the request interceptor **[EXISTS]**; explicit business events via `AdminAuditor` → `AuditLogService.record` **[EXISTS]** (built 2026-08). Catalogued in [logs-audit.md](../platform/logs-audit.md) | **[EXISTS]** |
| `consent_events` | PG | Read-only compliance viewer (§3.6) | **[EXISTS]** (admin read built 2026-08) |
| `report_evidence` | PG | Frozen evidence snapshots (§3.3) | **[EXISTS]** (built 2026-08) |
| Moderator notes / transition history | PG (`reports` reviewer columns) | Who-did-what strip in the workbench | **[EXISTS]** (built 2026-08) |

## 7. Analytics & KPIs

| Metric | Definition | Source | Chart |
|---|---|---|---|
| Reports submitted /day | count of `reports` rows by `created_at` date, split by `reason` | PG `reports` **[EXISTS]**, query **[PLANNED]** | stacked area (30d) |
| Open queue depth | groups (and rows) in SUBMITTED+TRIAGED | `reports` aggregate | stat tile + sparkline |
| Median time-to-triage | `triaged_at − created_at` p50/p90 | needs reviewer columns **[PLANNED]** | line, 30d |
| Median time-to-resolution | terminal-state `acted_at − created_at` p50/p90 | same | line, 30d |
| Action rate | ACTIONED / (ACTIONED+DISMISSED) per week | `reports` | line |
| Appeal rate | APPEALED+UPHELD+REVERSED / terminal decisions | `reports` | line |
| **Reversal rate** | REVERSED / (UPHELD+REVERSED) — the moderation-quality number; rising = decisions are wrong or policy unclear | `reports` | line + threshold band |
| Strikes issued /day | `user_strikes` by `issued_at` date | PG **[EXISTS]**, query **[PLANNED]** | bar |
| Active-strike distribution | users at 1 / 2 / ≥3 active strikes | `user_strikes` where `expires_at > now()` | bar |
| Repeat-target rate | share of new groups whose target already had a prior ACTIONED report | `reports` self-join | line |
| Reporter accuracy p50 | per-reporter actioned-rate distribution | `reports` | histogram |
| Blocks+restricts /day | new rows per day | `user_blocks`/`user_restrictions` **[EXISTS]** (`blocksPerDay`/`restrictionsPerDay`, built 2026-08) | line |
| Consent grant rate per scope | granted / total latest-state per scope | `consent_events` | bar |
| Report→block correlation | % of report groups where reporter also blocked target | join on `user_blocks` | stat tile |

A date-bucketed metric store now exists (`MetricDailyService`, built 2026-08 — see [analytics-kpis.md](../platform/analytics-kpis.md)), but all of the above are computable with plain PG aggregate queries over small tables, so this section needs **no new collector pipeline**, only queries. Cache the dashboard aggregates in Redis (5-min TTL) like other stat surfaces.

## 8. SLAs, alerts & thresholds

### SLA targets (proposal — nothing enforced today)

| Class | Triage SLA | Resolution SLA | Appeal SLA |
|---|---|---|---|
| SELF_HARM, VIOLENCE | **4h** | 24h | 72h |
| HATE_SPEECH, HARASSMENT, NUDITY_SEXUAL, IMPERSONATION | 24h | 72h | 7d |
| SPAM, MISINFORMATION, COPYRIGHT, OTHER | 48h | 7d | 14d |

### Alert rules **[PLANNED]** (evaluated by a 5-min scheduled sweep; delivered as admin notifications + optional email — wiring per [notifications-email.md](../communication/notifications-email.md))

| Alert | Condition | Severity |
|---|---|---|
| Queue depth | open groups > 50 (warn) / > 200 (page) | warn/critical |
| Oldest unresolved | any SUBMITTED older than its class triage-SLA; critical at 2× SLA | warn/critical |
| Priority breach | any SELF_HARM/VIOLENCE group untriaged > 4h | critical |
| Appeal backlog | APPEALED count > 20 or oldest APPEALED > class appeal-SLA | warn |
| Report surge | platform reports/hour > 3× trailing-7d hourly baseline (brigading or real incident) | warn |
| Single-target surge | one `group_key` gains > 10 reports in 1h (auto-adds queue "surge" badge, §3.1) | warn |
| Rate-limit hammering | same reporter hits the 20/h submit limit (`safety:report` `RateLimiter` rejections) repeatedly in a day — report-spam signal | info |
| Threshold crossing | any user reaches ≥3 active strikes (see automation below) | warn |
| Reversal spike | weekly reversal rate > 20% | warn |

### Strike threshold automation **[PLANNED]**

The `StrikeService` javadoc already declares the intent ("a user's active strike count is what drives automated restrictions") — nothing implements it. Proposal, gated on the [users-roles.md](../users/directory-and-roles.md) suspension/restriction primitives:

| Active strikes | Automatic consequence | Reversible by |
|---|---|---|
| 1 | Warning notification to the user (new NotificationType) | decay |
| 2 | Feature restriction 7d (no posting/commenting) — needs an enforcement flag on `User` | decay / strike revoke |
| 3 | Auto-queue for suspension review (human decides; **never** auto-suspend) | admin |
| 5 (lifetime, incl. expired) | Flag for permanent-ban review | admin |

Evaluation runs inside the strike-issuing transaction (issue → `countActive` → apply consequence → audit `SAFETY_THRESHOLD_APPLIED`), so it can't drift; decay needs no job because `countActive` is time-filtered at read **[EXISTS]**.

## 9. Permissions & safety notes

- **Routing**: everything under `/api/v1/admin/safety/**` → double-gated (filter chain + `@PreAuthorize("hasRole('ADMIN')")`). Do not repeat the `PUT /channels/{id}/verified` mistake of annotation-only gating outside the prefix. Only real roles: the `Role` enum has since widened (see [users-roles.md](../users/directory-and-roles.md)) and the old phantom-grant controllers were normalized (2026-08) — reference only enum-backed roles in `@PreAuthorize`, never `SUPER_ADMIN`.
- **Step-up**: every action that touches a target (strike, takedown, suspend, action-with-resolution, reverse) requires a fresh `StepUpService` grant; dismiss/triage do not (keeps triage throughput fast).
- **Coarse outcome**: §5 rule — `resolution`, strikes of others, moderator notes, and reporter identities render **only** inside this console.
- **Reporter anonymity toward target**: nothing in any target-facing surface (notifications, strike record) may name or count reporters. Strike reason strings are moderator-authored; template them ("Content removed for harassment") rather than echoing reporter `details`.
- **Consent viewer & evidence are read-only**: no admin endpoint may mutate `consent_events` or `report_evidence`; both are legal-evidence stores.
- **MESSAGE-target evidence is a privacy exception**: viewing a reported DM's snapshot is admin access to private-message content — permitted *only* via the report-scoped evidence panel (snapshot of the single reported message), never as a browse capability over the chat store; aligns with the metadata-only boundary in [chat-channels-live.md](../communication/chat-channels-live.md). Live-stream chat is never persisted, so such reports are triaged without content evidence — the UI must say so explicitly instead of showing an empty panel.
- **Audit everything**: each mutation writes a business-event audit row (`AuditLogService.record`) *and* rides the request-interceptor log; the appeal-reversal path additionally notes the revoked strike id in `moderator_note`.
- **Escape hatch**: `SECURITY_PERMIT_ALL=true` opens these routes like all others — never set outside local dev (see [architecture.md](../foundation/architecture.md)).

## 10. Build order / dependencies

| # | Step | Depends on | Risk |
|---|---|---|---|
| 1 | Read-only queue + detail: admin `GET` endpoints, group-by-`group_key` queries, live target hydration | nothing — pure reads over existing tables | zero |
| 2 | Fix `MESSAGE` target-id defect (§3.3) + `report_evidence` snapshot-at-submit | 1; touches `ReportService.submit` | low |
| 3 | Reviewer-attribution migration (`triaged_by/actioned_by/acted_at/moderator_note`) + `ReportModerationService` with triage/dismiss transitions | 1 | low |
| 4 | Action endpoint + first real `StrikeService.issueStrike` caller + strike revoke + audit actions | 3 | medium |
| 5 | Appeals queue + uphold/reverse (auto strike-revoke on reverse) | 4 | medium |
| 6 | Per-user moderation record + consent viewer + block/restrict aggregates | 1 (parallel to 3–5) | zero |
| 7 | SLA clocks, KPI queries, alert sweep job | 3 (needs timestamps) | low |
| 8 | Takedown + suspend buttons | **external**: content-remove primitive ([content-moderation.md](content-moderation.md)) and suspension primitive ([users-roles.md](../users/directory-and-roles.md)); until then `WARNING_ISSUED`/`NO_ACTION` are the only fully-executable resolutions — the UI must disable the others rather than record a resolution that didn't happen | high |
| 9 | Strike threshold automation + target-side strike appeal | 4 + 8 | high |

Phase-1 (steps 1, 6) ships a genuinely useful read-only triage console with zero mutation risk, consistent with the platform-wide phasing in [admin-api-blueprint.md](../foundation/api-blueprint.md).
