# Content Moderation — Admin Dashboard Section 2

Moderation of the social content plane: **posts, comments, stories (+polls,
highlights), reels, and the sound library**. Report triage/strike mechanics live
in [safety-reports.md](safety-reports.md); media-pipeline failures in
[media-storage.md](media-storage.md); research/QnA lifecycle in
[research-qna.md](research-qna.md); chat/channel/live content boundaries in
[chat-channels-live.md](chat-channels-live.md). Tag legend and ground rules:
[README.md](README.md).

---

## 1. Purpose & scope

| In scope | Out of scope (see) |
|----------|--------------------|
| Unified moderation queue (reports on content + failed-media + keyword hits) | Report state machine, appeals, strike ledger UI → [safety-reports.md](safety-reports.md) |
| Post takedown/restore via the real `PostStatus` machine | User suspend/ban → [users-roles.md](users-roles.md) |
| Comment moderation (hard-delete model) | Media scanner implementation & failed-asset queue → [media-storage.md](media-storage.md) |
| Story / highlight / poll moderation | Research retraction, QnA close/archive → [research-qna.md](research-qna.md) |
| Reel moderation (posts + reel-specific fan-out) | Channel-post & live-stream moderation → [chat-channels-live.md](chat-channels-live.md) |
| **Sound library approval queue** (the full flow, spec'd here) | Trending manipulation controls → [search-feed-trending.md](search-feed-trending.md) |
| Platform-level keyword blocklist (per-user exists; global proposed) | Per-user muted words UX → [../settings/privacy.md](../settings/privacy.md) |
| Bulk actions, moderation analytics | Log Explorer → [logs-audit.md](logs-audit.md) |

**Ground truth on moderation state (verified against source):**

- There is **no `ModerationState` enum anywhere in the codebase** (0 grep hits).
  The real machine is **[EXISTS]** `PostStatus { DRAFT, PUBLISHED, ARCHIVED, REMOVED }`
  (`post/enums/PostStatus.java`) — but the canonical Cassandra row
  (`PostByIdEntity.status`, table `posts_by_id`) stores a **plain String** and the
  only value any code path ever writes is `"PUBLISHED"`
  (`CassandraPostService` ~line 101). `REMOVED` is a defined-but-never-set state:
  the takedown slot exists in the enum and is waiting for a writer.
- **[EXISTS]** `GlobalSearchService.DEAD_STATUSES` (~line 205) already filters
  `DELETED / DRAFT / ARCHIVED / RETRACTED / REMOVED_BY_MODERATOR / REMOVED /
  PENDING_REVIEW / REJECTED` out of all 8 ES indices — i.e. **search-side
  enforcement of a takedown is pre-built**; the moment an admin writer sets
  `REMOVED`, search hides it defensively even before the ES delete lands.
- **No admin takedown path exists for any content type today** — every delete is
  author/owner-only (`DELETE /api/v1/posts/{id}` throws `ForbiddenException` for
  non-authors, `CassandraFeedController` ~line 284). `Resolution.CONTENT_REMOVED`
  exists in the safety enums with no implementation behind it.

---

## 2. Dashboard views / widgets

### 2.1 Unified moderation queue (landing view) [PLANNED]

One inbox, three feeders, one row shape (`targetType, targetId, source, reason,
severity, reportCount, firstSeen, lastSeen, status, assignee`):

| Feeder | Status | What flows in |
|--------|--------|---------------|
| User reports on content | **[PARTIAL]** — `reports` PG table exists with 9 target types incl. POST/COMMENT/STORY (`settings/safety/entity/Report`, `group_key = targetId:reason` dedup); **no moderator read path exists** (`SafetyController` is entirely self-serve) | Rows where `target_type ∈ {POST, COMMENT, STORY}` (+ RESEARCH/QUESTION/ANSWER routed to [research-qna.md](research-qna.md), USER/MESSAGE/CHANNEL routed to their sections) |
| `FAILED_MODERATION` media | **[PARTIAL]** — `MediaStatus.FAILED_MODERATION` + `MediaScanner` hook are real (`MediaProcessingService` ~line 75 fails the asset when `!isClean`), but the only registered impl is `MediaScanner.AllowAllScanner`, so the state is **currently unreachable in practice** | Assets that fail a real scanner once one is plugged in; row links to owning post/story/reel |
| Keyword hits | **[PLANNED]** — no platform blocklist exists (see §2.7) | Content whose text matched a platform keyword with severity `FLAG` |

Widgets on this view:

| Widget | Content |
|--------|---------|
| Queue table | Sortable by report count, age, severity; filter by targetType/source/reason; row click → content inspector (§2.2) |
| Queue depth tiles | Open items by feeder; oldest-item age; items >24h SLA |
| Repeat-offender rail | Top authors by open items (joins queue rows to author via content lookup) |
| Hot-target rail | Targets with ≥N distinct reporters in 24h (`reports` grouped on `(target_id, reason)` — index `idx (target_id, reason)` **[EXISTS]**) |

### 2.2 Content inspector (drill-in) [PLANNED]

Read-only render of the reported item with full context, safe for admin eyes
(social content only — never chat messages):

| Panel | Source |
|-------|--------|
| Content render (text, media, caption, hashtags, mentions, sound used) | `posts_by_id` **[EXISTS]** via a new admin read (author-only guards bypassed read-only) |
| Counters | `post_counters` / `comment_counters` point read **[EXISTS]** (`CounterService`) |
| Report history for this target | `reports WHERE target_id = ?` **[EXISTS]** (repo-level; needs admin endpoint — [safety-reports.md](safety-reports.md)) |
| Author capsule | user card + active strikes (`GET /api/v1/safety/strikes` logic, admin-scoped **[PLANNED]**) + prior takedowns |
| Resource audit trail | `audit_log_by_resource` — **written on every request today but has NO read endpoint** (`AuditLogByResourceRepository.firstPage` is dead code from HTTP); admin read **[PLANNED]**, also catalogued in [logs-audit.md](logs-audit.md) |
| Action bar | Takedown / restore / dismiss / issue strike / bulk-select (§4) |

### 2.3 Posts & reels

Same state machine, one extra fan-out for reels:

| Fact | Status |
|------|--------|
| `PostStatus.REMOVED` is the takedown state; nothing sets it | **[EXISTS]** enum / **[PLANNED]** writer |
| Reels are `PostType.REEL` rows that additionally fan into `reels_by_day` (`ReelsByDayEntity`) and rank via `ReelFeedService` (for-you pool) | **[EXISTS]** |
| Takedown must: set `status=REMOVED` on `posts_by_id`, delete from `irc-posts` ES, untag from trending (`POST`/`REEL` scopes, `common.tag`), drop `reels_by_day` row for reels, and suppress from feeds (feed reads already hydrate from `posts_by_id`, so the status check is one gate) | **[PLANNED]** |
| Restore = `REMOVED → PUBLISHED` + re-index + re-tag | **[PLANNED]** |
| Counters/reactions/saves are **retained** across takedown (restore keeps engagement intact) | design rule |

### 2.4 Comments — hard-delete model

**[EXISTS]** Comments have **no soft state at all**: `CassandraCommentService`
(~line 210) does a physical `DELETE` of the comment row **plus a single-tombstone
range-delete of the entire reply partition** plus lookup-row delete, and adjusts
the post counter by `1 + replyCount`. Author-only today
(`DELETE /api/v1/posts/comments/{commentId}`).

> **Takedown = deletion = irreversible.** There is no `REMOVED` state to restore
> from, and replies die with the parent. The admin flow MUST therefore:
> 1. snapshot the comment (+ reply page) into the moderation evidence store
>    **[PLANNED]** before deleting,
> 2. present a "this cannot be undone; N replies will also be deleted" confirm,
> 3. require step-up auth (§4).

Widget: comment inspector shows the thread context (parent post, sibling page —
replies are flat at depth 1) with the reply count that will be destroyed.

### 2.5 Stories, polls & highlights

| Fact | Status |
|------|--------|
| Expiry is **per-row Cassandra TTL** (`StoryLifetime H8/H16/H24`, default H24, `INSERT … USING TTL` on `stories_by_author` + `story_lookup` + `story_views`). There is **no StoryExpiryJob** — the datastore is the lifecycle. | **[EXISTS]** |
| Visibility: `StoryVisibility { PUBLIC, FOLLOWERS_ONLY, CLOSE_FRIENDS, ONLY_ME, CHANNEL }`, enforced by `CassandraStoryService.canView` (close-friends via `CloseFriendsService`) | **[EXISTS]** |
| Delete is author-only: `DELETE /api/v1/stories/{storyId}` | **[EXISTS]** |
| Polls: `StoryPollEntity`/`PollByIdEntity`/`PollVoteEntity`; `poll_counters` (A/B, **no LWT — double-vote accepted**); vote rows carry story-aligned TTL, counter table cannot be TTL'd | **[EXISTS]** |
| **Highlights persist expired stories** (`HighlightByAuthorEntity`, `StoryInHighlightEntity`, `/api/v1/highlights`) — content that "expired" can live forever in a highlight | **[EXISTS]** |
| Admin story takedown: delete story rows + poll rows + **every highlight pin of that story** | **[PLANNED]** |

> **Evidence evaporates**: `story_views_by_story` is TTL 24h and the story rows
> self-tombstone, so a story reported at hour 20 may be gone before review.
> The report intake path should snapshot story content at report time
> **[PLANNED]** — otherwise the queue row outlives its evidence. Same reasoning
> as comments: the evidence store (§5) is a hard dependency for both.

Widget: story queue rows show a TTL countdown ("expires in 3h — snapshot taken /
NOT taken") and a highlight badge when the story is pinned somewhere.

### 2.6 Sound library — approval queue (flagship of this section)

The real flow today, end to end:

| Step | Status | Detail |
|------|--------|--------|
| Upload | **[EXISTS]** `POST /api/v1/sounds` (`CassandraSoundController`) | Uploader always the JWT caller; `autoApprove=true` honored **only** for `MODERATION_ROLES = Set.of(Role.ADMIN)`; everyone else created `PENDING_REVIEW` |
| State machine | **[EXISTS]** `SoundStatus { PENDING_REVIEW, APPROVED, REJECTED, ARCHIVED }` (`post/enums/SoundStatus.java`) | Stored as plain String on `SoundEntity.status` (`sounds_by_id`) |
| Approve | **[EXISTS]** `POST /api/v1/sounds/{id}/approve` → `CassandraSoundService.approve` | Idempotent (no-op if missing/already APPROVED); writes `sounds_by_category` browse row + async ES refresh (`irc-sounds`). Gated `hasAnyRole('ADMIN','MODERATOR','SUPER_ADMIN')` — MODERATOR/SUPER_ADMIN are **phantom roles** (Role enum is USER/RESEARCHER/SCHOLAR/ADMIN), effectively ADMIN-only; endpoint sits **outside `/api/v1/admin/**`** so it lacks the filter-chain double gate |
| List pending | **MISSING** | Service javadoc literally says moderators read uploads "via a separate review query (**out of scope here**)" — no endpoint, no repository query. **Admins cannot see what awaits approval.** |
| Reject / archive | **MISSING** | `REJECTED` is never set by any code; no endpoint exists. `ARCHIVED` likewise |
| Uploader notification | **[EXISTS but DEAD]** `rabbitmq/event/post/SoundApprovedEvent` (`soundId, uploaderId, soundTitle, occurredAt`) is defined and **never published or consumed anywhere** |
| Downstream exposure | **[EXISTS]** search (`CassandraSoundService.search`) and category browse are APPROVED-only; `sounds_by_id` **retains REJECTED/ARCHIVED rows "for audit"** (per service javadoc) |
| Usage signal | **[EXISTS]** `sound_counters.use_count` (`CounterService.incrementSoundUse`), `GET /api/v1/sounds/{id}/usage`, `posts_by_sound` = every post using sound X |
| Reindex | **[EXISTS]** `POST /api/v1/admin/search/sounds/reindex` (`SearchAdminController`) |

**Approval-queue UI spec [PLANNED]:**

| Widget | Behavior | Data source |
|--------|----------|-------------|
| Pending list | `PENDING_REVIEW` sounds, oldest-first, columns: title, artist, category, duration, uploader, waiting-time; badge >48h | New listing query (§9 — `sounds_by_id` has **no status index**; needs a `sounds_by_status` Cassandra table or ES `irc-sounds` status-term query) **[PLANNED]** |
| Inline preview player | Streams `audioUrl` + shows `coverArtUrl`; waveform optional | `SoundEntity` fields **[EXISTS]** |
| Approve button | Calls approve; on success publish `SoundApprovedEvent` → notification to uploader (wire the dead event) | `CassandraSoundService.approve` **[EXISTS]**; event publish **[PLANNED]** |
| Reject button + reason | New endpoint; reason (enum + free text) stored on the sound row / decision log; uploader notified (`SoundRejectedEvent` **[PLANNED]**); row stays in `sounds_by_id` per existing audit convention | **[PLANNED]** |
| Archive (retire an APPROVED sound) | Removes `sounds_by_category` row + ES doc; existing posts keep their audio reference (`posts_by_sound` untouched) — archive stops *new* adoption only | **[PLANNED]** |
| Uploader history panel | All sounds by this uploader with per-status counts + approval ratio; flags serial re-uploaders of rejected audio | Needs uploader index (`sounds_by_uploader` table or ES term query on `uploaderId`) **[PLANNED]** |
| Usage panel (for approved/archive decisions) | `use_count` + recent `posts_by_sound` page — "this sound is in 3,200 reels" changes the takedown calculus | **[EXISTS]** data, **[PLANNED]** admin surface |

### 2.7 Keyword blocklist manager

| Layer | Status | Detail |
|-------|--------|--------|
| Per-user muted words | **[EXISTS]** (data+CRUD) / **UNENFORCED** | `HiddenKeyword` (`hidden_keywords`: `keyword_display`, `keyword_normalized` unique per user), CRUD at `GET/POST /api/v1/settings/privacy/keywords`, `DELETE /keywords/{id}` — see [../settings/privacy.md](../settings/privacy.md). Entity javadoc claims feed/notification enforcement, but **zero references exist outside settings/privacy** — feed assembly and fan-out never consult it |
| `KeywordNormalizer` | **[EXISTS]** and excellent | NFKC + strip combining marks (Arabic tashkeel, tatweel) + case-fold + **Arabic/Kurdish variant unification** (ی→ي, ى→ي, ک→ك, ة→ه, أ/إ/آ→ا) + whitespace collapse; `matchesAny` contains-scan over normalized keywords |
| Platform blocklist | **[PLANNED]** | New PG table `platform_keywords (id, keyword_display, keyword_normalized UNIQUE, severity FLAG\|BLOCK, scopes, added_by, note, created_at)` reusing the **same `KeywordNormalizer`** so Arabic/Kurdish variants can't dodge it. Normalized set cached in Redis, refreshed on mutation. Enforcement hooks at content-create paths (post/comment/story-caption/reel-caption text): `BLOCK` → reject at create with a policy error; `FLAG` → content publishes but a queue item is emitted (§2.1). Chat messages are **out of scope** (content-privacy boundary — [chat-channels-live.md](chat-channels-live.md)) |
| Manager UI | **[PLANNED]** | CRUD table with severity + scope chips; **test box** ("paste text, see which keywords match after normalization"); per-keyword 7d hit sparkline |

---

## 3. Data sources (per widget)

| Widget / flow | Store | Class / table / endpoint | Status |
|---|---|---|---|
| Queue: reports feeder | PG | `reports` (`Report`, `group_key=targetId:reason`, idx `(target_id, reason)`); writer `ReportService.submit` | **[EXISTS]** (write side only) |
| Queue: media feeder | PG | `media_assets.status = FAILED_MODERATION` (`MediaStatus`, `MediaProcessingService`, `MediaScanner`) | **[PARTIAL]** (AllowAllScanner ⇒ state unreachable) |
| Queue: keyword feeder | PG + Redis | `platform_keywords` + hit events | **[PLANNED]** |
| Post/reel content + status | Cassandra | `posts_by_id` (`PostByIdEntity.status` String; `PostStatus` enum), `reels_by_day` | **[EXISTS]** |
| Post/comment counters | Cassandra | `post_counters`, `comment_counters` (`CounterService`) | **[EXISTS]** |
| Comment rows | Cassandra | comment + reply partitions, hard-delete in `CassandraCommentService` | **[EXISTS]** |
| Stories / polls / views | Cassandra | `stories_by_author`, `story_lookup`, `story_views_by_story` (TTL), `poll_counters`, `poll_votes_by_poll_user` | **[EXISTS]** |
| Highlights | Cassandra | `HighlightByAuthorEntity`, `StoryInHighlightEntity` | **[EXISTS]** |
| Sounds | Cassandra + ES | `sounds_by_id`, `sounds_by_category`, `sound_counters`, `posts_by_sound`; `irc-sounds` index | **[EXISTS]** |
| Sound pending/uploader listing | Cassandra or ES | `sounds_by_status` / `sounds_by_uploader` tables, or `irc-sounds` term queries | **[PLANNED]** |
| Search suppression on takedown | ES | `GlobalSearchService.DEAD_STATUSES` filter (all 8 indices) | **[EXISTS]** |
| Strike issuance | PG | `user_strikes`; `StrikeService.issueStrike(userId, reportId, reason)` — implemented, 90-day decay, **zero callers, no controller** | **[EXISTS]** (service only) |
| Per-resource audit trail | Cassandra | `audit_log_by_resource` (written by interceptor; `AuditLogByUserRepository`/`AuditLogByResourceRepository`) | **[EXISTS]** write / **[PLANNED]** read |
| Evidence snapshots | PG | `moderation_evidence (id, target_type, target_id, captured_at, captured_by, payload JSONB, media_refs)` | **[PLANNED]** |
| Decision log | PG | `moderation_decisions (id, target_type, target_id, action, reason, actor_id, report_id, created_at)` | **[PLANNED]** |

---

## 4. Admin actions

All new routes under `/api/v1/admin/**` for the filter-chain double gate
([architecture.md](architecture.md)). Every action writes an audit row via
`AuditLogService.record` (the service-layer helper **exists with zero callers
today** — this section becomes its first customer) plus a `moderation_decisions`
row. Danger: L=low, M=medium, H=high (destructive/irreversible).

| Action | Endpoint | Params | Danger | Step-up | Audit action | Status |
|---|---|---|---|---|---|---|
| List queue | `GET /api/v1/admin/moderation/queue` | `source, targetType, reason, status, sort, cursor` | L | no | — (read) | **[PLANNED]** |
| Inspect content | `GET /api/v1/admin/moderation/content/{targetType}/{targetId}` | — | L | no | `MODERATION_CONTENT_VIEWED` | **[PLANNED]** |
| Resource audit trail | `GET /api/v1/admin/audit/resources/{type}/{id}` | `cursor, pageSize` | L | no | — (read) | **[PLANNED]** (repo method exists) |
| Take down post/reel | `POST /api/v1/admin/posts/{id}/takedown` | `{reason, reportId?, issueStrike?, notifyAuthor?}` | M (reversible) | no | `CONTENT_TAKEDOWN` | **[PLANNED]** — writes `PostStatus.REMOVED`, ES delete, trending untag, `reels_by_day` cleanup |
| Restore post/reel | `POST /api/v1/admin/posts/{id}/restore` | `{note}` | M | no | `CONTENT_RESTORE` | **[PLANNED]** |
| Delete comment (admin) | `DELETE /api/v1/admin/comments/{commentId}` | `{reason, reportId?}` | **H — irreversible, deletes reply subtree** | **yes** | `COMMENT_TAKEDOWN` | **[PLANNED]** — snapshots to evidence store first, then reuses hard-delete path |
| Delete story (admin) | `DELETE /api/v1/admin/stories/{storyId}` | `{reason, reportId?}` | **H — irreversible (TTL data)** | **yes** | `STORY_TAKEDOWN` | **[PLANNED]** — story + poll rows + all highlight pins |
| Unpin story from highlight | `DELETE /api/v1/admin/highlights/{highlightId}/stories/{storyId}` | `{reason}` | M | no | `HIGHLIGHT_ITEM_REMOVED` | **[PLANNED]** |
| List sounds by status | `GET /api/v1/admin/sounds` | `status=PENDING_REVIEW, uploaderId?, cursor` | L | no | — (read) | **[PLANNED]** |
| Approve sound | `POST /api/v1/sounds/{id}/approve` | — | L | no | `SOUND_APPROVED` | **[EXISTS]** — recommend admin-prefix alias `POST /api/v1/admin/sounds/{id}/approve` for the double gate, keep old path during migration |
| Reject sound | `POST /api/v1/admin/sounds/{id}/reject` | `{reasonCode, note}` | M | no | `SOUND_REJECTED` | **[PLANNED]** — sets `REJECTED`, row retained in `sounds_by_id`, ES doc removed, uploader notified |
| Archive sound | `POST /api/v1/admin/sounds/{id}/archive` | `{reason}` | M | no | `SOUND_ARCHIVED` | **[PLANNED]** — removes category row + ES; existing posts keep audio |
| Uploader sound history | `GET /api/v1/admin/sounds/uploaders/{userId}` | `cursor` | L | no | — (read) | **[PLANNED]** |
| Keyword CRUD | `GET/POST /api/v1/admin/keywords`, `PATCH/DELETE /api/v1/admin/keywords/{id}` | `{keyword, severity, scopes, note}` | M (BLOCK severity gates publishing platform-wide) | **yes for severity=BLOCK** | `KEYWORD_ADDED / UPDATED / REMOVED` | **[PLANNED]** |
| Keyword dry-run | `POST /api/v1/admin/keywords/test` | `{text}` | L | no | — (read) | **[PLANNED]** |
| Issue strike | `POST /api/v1/admin/users/{userId}/strikes` | `{reportId, reason}` | M | no | `STRIKE_ISSUED` | **[PLANNED]** controller over **[EXISTS]** `StrikeService.issueStrike` (currently uncallable); full ledger UI in [safety-reports.md](safety-reports.md) |
| Bulk action | `POST /api/v1/admin/moderation/bulk` | `{action, targets[≤100], reason}` | **H** | **yes** | one audit row per target + `MODERATION_BULK` summary | **[PLANNED]** — actions: takedown, restore, dismiss-queue-items, sound approve/reject; per-item result list (partial success allowed); hard cap + rate limit |

---

## 5. Logs surfaced in this section

| Log | Store | Role here | Status |
|---|---|---|---|
| `reports` | PG | Queue feeder + per-target report history (full triage lives in [safety-reports.md](safety-reports.md)) | **[EXISTS]** (no read API yet) |
| `moderation_decisions` | PG | Every admin verdict: who, what, why, evidence link — the takedowns/day source of truth | **[PLANNED]** |
| `moderation_evidence` | PG | Pre-deletion snapshots (comments, stories) + report-time story snapshots | **[PLANNED]** |
| `user_strikes` | PG | Strike context on the author capsule (90-day `expires_at` decay, rows never deleted) | **[EXISTS]** (write-dead until §4 controller) |
| `sounds_by_id` retained REJECTED/ARCHIVED rows | Cassandra | The existing sound "audit trail" convention — rejected rows stay out of browse but remain queryable | **[EXISTS]** |
| `audit_log_by_resource` | Cassandra | "What happened to this post?" — written today, unreadable today | **[EXISTS]** write / **[PLANNED]** read ([logs-audit.md](logs-audit.md)) |
| `media_assets` failure rows | PG | FAILED_MODERATION feeder detail | **[PARTIAL]** ([media-storage.md](media-storage.md)) |

---

## 6. Analytics & KPIs

No time-series metric store exists anywhere (all counters are lifetime scalars)
— see [analytics-kpis.md](analytics-kpis.md) for the collection proposal. Honest
sourcing per metric:

| Metric | Definition | Source | Chart | Status |
|---|---|---|---|---|
| Content velocity by type | New posts / reels / stories / comments / sounds per day | **[PLANNED]** daily rollup collector (Cassandra creates aren't date-aggregable today; `reels_by_day` partitions are the lone date-bucketed structure) | Stacked area | **[PLANNED]** |
| Takedowns per day (by type, by reason) | `moderation_decisions` count grouped by day/action/reason | Decision log | Stacked bars | **[PLANNED]** |
| Restore rate | restores ÷ takedowns, 30d | Decision log | Stat tile | **[PLANNED]** |
| Sound approval latency | decision time − upload time, p50/p95 | Interim: `updatedAt − createdAt` on `sounds_by_id` for APPROVED rows **[PARTIAL]** (updatedAt is set on approve; no reject timestamps exist); proper: decision log | Histogram + p95 tile | **[PARTIAL]→[PLANNED]** |
| Sound pending depth & oldest age | Live count / max wait of `PENDING_REVIEW` | New status listing (§9) | Stat tiles | **[PLANNED]** |
| Sound approval ratio per uploader | approved ÷ decided per uploader, flags serial abusers | Decision log + uploader index | Table | **[PLANNED]** |
| Keyword-hit rate | Hits per keyword per day; BLOCK-rejections vs FLAG-queue items | Keyword hit events (Redis counter → daily PG rollup) | Bar + per-keyword sparkline | **[PLANNED]** |
| Reports per 1k new content items | Content-report intake ÷ content velocity | `reports` (EXISTS) ÷ velocity (PLANNED) | Line | **[PARTIAL]** |
| Queue SLA | % queue items decided <24h; median time-to-decision | Queue timestamps + decision log | Line + tile | **[PLANNED]** |
| Sound usage at takedown | `use_count` of archived/rejected-after-approval sounds (blast-radius record) | `sound_counters` **[EXISTS]** captured into decision log | Table | **[PARTIAL]** |

---

## 7. Alerts & thresholds

| Alert | Condition (default) | Why |
|---|---|---|
| Queue backlog | Open queue items > 200 or oldest > 48h | Moderation capacity failing |
| Sound review stall | Any `PENDING_REVIEW` sound > 72h, or pending depth > 50 | The queue is invisible today; once visible, keep it drained |
| Report flood on one target | ≥10 distinct reporters on one `(target_id, reason)` in 1h | Viral harm or brigading — either way, look now |
| Takedown spike | Takedowns/day > 3× 7-day median | Incident or a rogue/compromised admin account |
| FAILED_MODERATION goes nonzero | First occurrence after real scanner deploy | Confirms scanner live; sustained spike = attack or miscalibration |
| BLOCK-keyword rejection spike | >100 rejects/h on one keyword | Overly-broad keyword (normalizer contains-scan is aggressive) censoring legit content |
| Bulk-action volume | >500 targets bulk-actioned by one admin in 24h | Blast-radius guard; pairs with the audit trail |
| Story evidence gap | Queue item whose story expired with no snapshot | Data-loss bug in the report-time snapshot hook |

---

## 8. Permissions & safety notes

- **ADMIN-only, and say so honestly.** `CassandraSoundController` grants
  `MODERATOR`/`SUPER_ADMIN` and `AuditLogController` grants `SUPER_ADMIN` — all
  **phantom roles** (enum: USER/RESEARCHER/SCHOLAR/ADMIN); effective gate is
  ADMIN everywhere. New endpoints should say `hasRole('ADMIN')` only; a real
  MODERATOR role is an RBAC-evolution item for [architecture.md](architecture.md).
- **Double-gate everything**: all new routes under `/api/v1/admin/**`. The one
  existing moderation endpoint (`POST /api/v1/sounds/{id}/approve`) is
  annotation-only outside that prefix (open under `SECURITY_PERMIT_ALL=true`) —
  alias it under the admin prefix and deprecate the bare path.
- **Irreversibility is explicit in the UI**: comments (hard delete + reply
  subtree) and stories (TTL data) cannot be restored. Evidence snapshot is a
  *precondition* the endpoint enforces, not a UI courtesy; step-up auth
  (`stepup:{userId}`, [../settings/auth-sessions.md](../settings/auth-sessions.md))
  required.
- **Content-privacy boundary**: this section renders posts/comments/stories/
  reels/sounds — public-plane content. It must never render chat messages,
  DM previews, or live-stream chat ([chat-channels-live.md](chat-channels-live.md)).
- **Takedown ≠ data deletion**: `REMOVED` posts keep rows, counters, and
  engagement for restore/appeal; only comments/stories are physically destroyed
  (and then only after snapshot). Appeals ride the report machine
  ([safety-reports.md](safety-reports.md)).
- **Keyword blocklist is a censorship instrument**: BLOCK severity requires
  step-up + a note; every add/remove is audited; the dry-run endpoint exists so
  admins see the normalizer's aggressive contains-matching before shipping a
  keyword.
- **Author notifications** on takedown/rejection state the reason category, not
  the reporter — reporter identity is never exposed (`Resolution` is already
  never shown to reporters, mirror that both ways).

## 9. Build order / dependencies

| # | Deliverable | Depends on | Notes |
|---|---|---|---|
| 1 | **Sound review queue read**: `sounds_by_status` Cassandra table (or `irc-sounds` status term-query) + `GET /api/v1/admin/sounds?status=` | nothing | Smallest lift, unblocks the explicitly-requested sounds UI; `sounds_by_id` has no status index, so a new table/ES query is mandatory |
| 2 | **Sound reject/archive endpoints** + wire the dead `SoundApprovedEvent` (publish on approve, add `SoundRejectedEvent`) → uploader notifications | 1 | Completes the `SoundStatus` machine; new `NotificationType` needs an `EmailTemplate.actionVerb` case ([notifications-email.md](notifications-email.md)) |
| 3 | **Post/reel takedown + restore** (`REMOVED` writer, ES delete, trending untag, `reels_by_day` cleanup) + `moderation_decisions` table | nothing (search filter pre-exists) | First real takedown; validates the audit + decision-log pattern |
| 4 | **Evidence store** (`moderation_evidence`) + snapshot-on-report hook for stories | nothing | Hard prerequisite for 5 |
| 5 | **Admin comment / story / highlight-pin deletion** (step-up gated) | 4 | Reuses existing hard-delete paths with an admin principal |
| 6 | **Unified queue read** over `reports` (content targets) + FAILED_MODERATION assets | safety triage endpoints ([safety-reports.md](safety-reports.md)); [media-storage.md](media-storage.md) scanner | Queue *writes* (dismiss/assign) live with the report state machine |
| 7 | **Strike controller** over `StrikeService.issueStrike` + takedown `issueStrike?` param | 3 | Ledger UI in [safety-reports.md](safety-reports.md) |
| 8 | **Platform keyword blocklist**: table + Redis cache + create-path hooks + manager UI + hit events | 6 (hits feed the queue) | Reuses `KeywordNormalizer` verbatim; also the natural moment to actually enforce per-user `HiddenKeyword` at feed assembly (today it is CRUD-only decoration) |
| 9 | **Bulk actions** | 3, 5 | Step-up + per-target audit + caps |
| 10 | **Analytics collectors**: daily content-velocity rollup, keyword-hit rollup, latency from decision log | 3, 8 | Feeds [analytics-kpis.md](analytics-kpis.md) |
