# Admin Dashboard — Frontend Build Guide

The build guide for **frontend developers** implementing the admin dashboard UI
(React + Vite, the ika app at `/Users/khi/Documents/ika`, route-tree `/admin/**`)
against the **fully-implemented** Spring Boot admin backend (:8080). Everything
below is real: every path was verified against the controllers under
`app/admin/**`, `app/user/controller/AdminUserController`,
`app/audit/controller/AuditLogController`, `app/common/search/controller/SearchAdminController`
and `app/common/tag/controller/TagAdminController`. You should not need to read
the backend to build the UI — but when you want the deep story of a section,
each table links its section doc.

Companions: [architecture.md](architecture.md) (access model) ·
[admin-api-blueprint.md](admin-api-blueprint.md) (endpoint catalog with danger
levels) · [api/](api/README.md) (**request/response JSON for every endpoint** — the
per-domain wire reference; use it alongside the page maps below) ·
[api-controllers.md](api-controllers.md) (controller-level reference) ·
[known-issues.md](known-issues.md) (freshness overlay) ·
[../errors/error-handling.md](../errors/error-handling.md) (the error envelope) ·
[../errors/frontend-error-handling.md](../errors/frontend-error-handling.md)
(the frontend consumption guide — codes, retries, step-up, SSE).

Legend used in the endpoint tables: **SU** = step-up required (`@RequiresStepUp`,
§2) · roles in the *Who* column are the `hasRole`/`hasAnyRole` grants as coded.

---

## 1. Auth & session plumbing

### 1.1 Tokens

| Concern | How it works |
|---------|--------------|
| Login | `POST /api/v1/auth/login` → `AuthResponse` (access + refresh token pair). Stateless JWT; no server session, CSRF disabled. |
| Sending it | `Authorization: Bearer <accessToken>` on every request. The auth filter resolves **cookie first, then header** — if the backend set token cookies at login, plain `fetch` with `credentials: 'include'` also works. |
| Refresh | `POST /api/v1/auth/refresh` (body optional — the refresh token is also read from its cookie). On a 401 from any admin call: refresh once, retry, then hard-logout to the login screen. |
| Logout | `POST /api/v1/auth/logout` (this session) · `POST /api/v1/auth/logout-all`. |
| sid-binding | Every access token is bound to a session id (`sid`). Admins can be force-logged-out via the session denylist (`sid:denied:{sid}`) — treat an unexpected 401 mid-session as "revoked", not a bug. |
| SSE | `EventSource` cannot set headers — every SSE endpoint accepts `?token=<accessJWT>` as a query-param fallback (§5.5). |

### 1.2 The double gate and the four staff roles

Everything under `/api/v1/admin/**` is gated **twice**: the security filter
chain requires a staff role for the whole prefix, and every controller carries
its own `@PreAuthorize`. The platform has seven roles; four are staff:
**ADMIN**, **MODERATOR**, **SUPPORT**, **ANALYST** (plus the non-staff
USER / RESEARCHER / SCHOLAR). The role arrives in the JWT claims — decode it
client-side to drive nav rendering, and let the server 403 be the backstop,
never the only check you skip.

Role → visible sections matrix ([architecture.md §6](architecture.md)) — this
drives which nav entries render at all:

| Role | Read-write sections | Read-only sections | Never sees |
|------|--------------------|--------------------|------------|
| **ADMIN** | Everything | — | — (critical ops still require step-up) |
| **MODERATOR** | Content moderation, Safety & reports, moderation parts of Research/QnA and Chat/Channels/Live, Sounds | Users (inspection), Logs/Audit | Role changes, Operations, Search reindexes, Analytics exports, Feed config |
| **SUPPORT** | User inspection + non-destructive account aids | Notifications (stats/registry), Logs, Safety report intake (read) | Any takedown, any role change, any ops control |
| **ANALYST** | — (strictly read-only) | Analytics, Logs/Audit, Search & feed observability | Every mutation |

Per-endpoint grants in the §4 tables are the source of truth — e.g. the users
directory reads allow MODERATOR/SUPPORT, but every user mutation is
ADMIN-only at the class level.

### 1.3 403 handling — two different animals

A 403 from an admin route means one of two things; the error envelope's
`errorCode` disambiguates:

1. **`STEP_UP_REQUIRED`** — the caller has the right role but no armed
   step-up marker. Trigger the step-up modal and auto-retry (§2).
2. **Anything else** — the role genuinely lacks the grant (or an
   impersonation token hit a non-GET). Render a "you don't have access to
   this" state; do **not** loop into the step-up modal.

Error responses across the whole API use the canonical envelope documented in
[../errors/error-handling.md](../errors/error-handling.md) — parse
`errorCode` + `message` from there; the frontend consumption patterns (retry
rules, step-up modal, 429 countdowns, SSE reconnects) are in
[../errors/frontend-error-handling.md](../errors/frontend-error-handling.md),
and user-facing strings are catalogued in
[../errors/user-facing-messages.md](../errors/user-facing-messages.md).

---

## 2. Step-up flow

Sensitive actions (danger **high**/**critical** — every **SU** row in §4)
require a short-lived re-verification marker in addition to the normal token.

| Piece | Detail |
|-------|--------|
| Arm | `POST /api/v1/security/step-up` with body `{"password": "…"}` **or** `{"code": "…"}` (2FA TOTP). `204` on success, `400` on a bad/empty credential. |
| TTL | Redis marker `stepup:{userId}`, default **300 s** (`app.security.step-up.ttl-seconds` — visible in `GET /api/v1/admin/ops/config`). One arm covers a burst of related actions, not a whole shift. |
| Enforcement | `@RequiresStepUp` on the handler → absent marker → **403 `STEP_UP_REQUIRED`**. Reads never require step-up, with deliberate exceptions: PII reveal, recordings, break-glass activity reads, contact-sync compliance, and `GET /admin/ops/config`. |

**Recommended modal UX** (matches the settings module's proven flow):

1. Fire the action optimistically. On 403 `STEP_UP_REQUIRED`, open a modal:
   "Confirm it's you" with a password field (and a 2FA-code tab when the admin
   has 2FA on).
2. `POST /api/v1/security/step-up`; on 204, **auto-retry the original
   request** with the same payload (mutations honor the `Idempotency-Key`
   header — generate one per action, reuse it on the retry, and replays
   within 24 h are deduplicated server-side).
3. Cache "armed until" client-side (now + 300 s) purely to *skip the modal
   proactively* — the server marker is authoritative; still handle a surprise
   `STEP_UP_REQUIRED` if the clock drifted.
4. Show a subtle "elevated — Ns left" chip while armed so admins understand
   why the second prompt appears later.

---

## 3. Impersonation ("view as user")

Read-only, always. No write-impersonation exists, by design.

| Step | Endpoint | Notes |
|------|----------|-------|
| Start | `POST /api/v1/admin/users/{userId}/impersonate` — body `{"reason": "…"}`, **min 10 chars**, **SU**, ADMIN-only | Returns `{token, targetUserId, targetUsername, expiresAt}` — a special JWT of type `IMPERSONATION` carrying only `ROLE_IMPERSONATED_READ`. **TTL 15 min.** One active session per admin (starting a new one revokes the old). Admins cannot be impersonated; nor can you impersonate yourself. |
| Use | Send the impersonation token as the Bearer (or `?token=` on SSE) on **GET requests only** | The auth filter rejects every non-GET before authorization even runs — expect 403 on any mutation. Chat/DM message content stays off-limits exactly as it is for admins. |
| End | `DELETE /api/v1/admin/impersonation` (with your **normal admin token**) | 204. Denylists the impersonation sid; also called implicitly when a new impersonation starts. Expiry alone also kills it. |

**Banner UX (non-negotiable):** while an impersonation token is in use, pin a
full-width, high-contrast banner: *"Viewing as @{targetUsername} — read-only ·
expires in {mm:ss} · [Exit]"*. Keep the impersonation token in memory only
(never persist it), keep the admin token untouched underneath, and hard-swap
back on Exit/expiry. Every request under the token is audit-attributed to the
**admin** with an `impersonating={targetId}` marker — tell the admin that in
the entry dialog, along with the mandatory reason field.

---

## 4. Page-by-page map

One subsection per dashboard page. *Base* is the controller's prefix; paths in
the tables are relative to it unless absolute. All list endpoints paginate per
§5.1.

### 4.1 Shell (all staff)

- Subscribe **once** to `GET /api/v1/admin/audit/stream?token=…` (§5.5) and fan
  events out to widgets client-side (filter by `operation`, `path` prefix,
  `resourceType`). Rolling audit ticker in the top strip; an
  "admin actions" feed is the same stream filtered to paths starting
  `/api/v1/admin/`.
- Health chips from `GET /api/v1/admin/ops/health` (ADMIN nav only).
- Nav = §7 tree, filtered by the §1.2 matrix.

### 4.2 Users & roles — base `/api/v1/admin/users`

`AdminUserController`. Class-level ADMIN; the read grants widen per row.
Section docs: [users-roles.md](users-roles.md), [user-administration.md](user-administration.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /api/v1/admin/users` | `q, role, status, verified, from, to` + pageable | — | ADMIN, MODERATOR, SUPPORT |
| `GET …/analytics` | `window` | — | + ANALYST |
| `GET …/{userId}` | — | — | ADMIN, MODERATOR, SUPPORT |
| `GET …/{userId}/pii` | — | **SU** | ADMIN |
| `GET …/{userId}/sessions` · `…/{userId}/data` | — | — | ADMIN, SUPPORT |
| `GET …/{userId}/login-events` · `/settings-audit` · `/moderation` | pageable | — | ADMIN, MODERATOR, SUPPORT |
| `PATCH …/{userId}/role` | body role | **SU** | ADMIN |
| `POST …/{userId}/disable` · `/enable` · `/lock` · `/unlock` | body `{reason}` | **SU** | ADMIN |
| `DELETE …/{userId}/sessions/{sid}` · `POST …/{userId}/sessions/revoke-all` | — | **SU** | ADMIN |
| `POST …/{userId}/password/reset` · `/2fa/reset` · `/email/verify` | body `{reason…}` | **SU** | ADMIN |
| `POST /api/v1/admin/users` (create) · `…/bulk` · `…/invite` | body per [user-administration.md](user-administration.md) | **SU** | ADMIN |
| `POST …/invite/{inviteId}/resend` · `DELETE …/invite/{inviteId}` | — | — | ADMIN |
| `PATCH …/{userId}` (identity edit) | `{fname?,lname?,username?,email?}` | **SU** | ADMIN |
| `POST …/{userId}/deletion/request` · `/deletion/cancel` | body `{reason}` | **SU** | ADMIN |
| `POST …/{userId}/purge/now` · `/purge/hold` | — | **SU** | ADMIN |
| `POST …/{userId}/strikes` | `{reportId?, reason}` | **SU** | ADMIN |
| `POST …/bulk-action` | `{ids[], action, reason}` | **SU** | ADMIN |
| `POST …/{userId}/impersonate` | `{reason}` (≥10 chars) | **SU** | ADMIN (§3) |

UI affordances: **self-protection is server-enforced** — admins cannot
disable/lock/demote themselves and the last ADMIN cannot be demoted
(`LAST_ADMIN` conflict); surface those errors verbatim rather than pre-hiding
buttons. Email edit resets verification. PII stays masked in detail views
until the explicit step-up-gated reveal.

### 4.3 Moderation queue & bulk — base `/api/v1/admin/moderation`

`AdminModerationController` — ADMIN, MODERATOR. Doc: [content-moderation.md](content-moderation.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /queue` | `source` (`reports`\|`media`\|`keywords`), `targetType`, `page`, `pageSize` | — |
| `POST /queue/keywords/{hitId}/resolve` | — | — |
| `POST /bulk` | `{action, targets:[{type,id}] (≤100), reason}` — actions incl. TAKEDOWN/RESTORE, per-target results | **SU** |

Render the merged queue with a per-row **source badge**; bulk returns
per-target `{outcome, error}` — show a result list, never a bare toast.

### 4.4 Content moderation — base `/api/v1/admin/content`

`AdminContentController` — ADMIN, MODERATOR.

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /posts` · `GET /posts/{postId}` | filters + cursor | — |
| `POST /posts/{postId}/remove` | `{reason, reportId?}` | **SU** |
| `POST /posts/{postId}/restore` | — | — |
| `DELETE /comments/{commentId}` · `/stories/{storyId}` | `{reason}` | **SU** |
| `DELETE /highlights/{highlightId}/stories/{storyId}` | — | — |
| `GET /blocklist` · `PATCH /blocklist/{id}` · `DELETE /blocklist/{id}` · `POST /blocklist/test` | — | — |
| `POST /blocklist` | keyword body — BLOCK severity gates publishing platform-wide | **SU** |

Post remove is reversible (`restore` writes PUBLISHED back); comment/story
deletes are **hard deletes** — danger-zone confirm (§6).

### 4.5 Safety & reports — base `/api/v1/admin/safety`

`AdminSafetyController` — ADMIN, MODERATOR (report reads also SUPPORT).
Doc: [safety-reports.md](safety-reports.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /reports` · `GET /reports/{id}` | `state, targetType, reason, targetId, from, to` + pageable | — | + SUPPORT |
| `POST /reports/{id}/triage` · `/dismiss` | `{note}` | — | ADMIN, MODERATOR |
| `POST /reports/{id}/action` | `{resolution, note}` — `WARNING_ISSUED`/`CONTENT_REMOVED`/`ACCOUNT_SUSPENDED`/`NO_ACTION` | **SU** | ADMIN, MODERATOR |
| `POST /appeals/{reportId}/uphold` · `/reverse` | `{note}` | **SU** | ADMIN, MODERATOR |
| `POST /users/{userId}/strikes` · `DELETE /strikes/{strikeId}` | `{reportId, reason}` | **SU** | ADMIN, MODERATOR |
| `GET /strikes` | `userId, active` + pageable | — | — |
| `GET /users/{userId}/record` · `/users/{userId}/consent` | — | — | — |
| `GET /stats/blocks` · `GET /analytics` | `from, to` | — | — |

Strikes decay after 90 days — show the expiry. Report detail includes frozen
evidence + same-target siblings; render the `note` trail.

### 4.6 Research — base `/api/v1/admin/research`

`AdminResearchController` — ADMIN, MODERATOR. Doc: [research-qna.md](research-qna.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /api/v1/admin/research` · `/top` · `/flags` · `/{id}` · `/{id}/downloads` | `status, q, authorId` + pageable | — |
| `POST /{id}/unpublish` · `/{id}/retract` | `{reason}` | **SU** |
| `DELETE /{id}` | `{reason}` | **SU** — hard delete, danger zone |
| `POST /{id}/flags` · `POST /flags/{flagId}/resolve` | flag body / note | — |

### 4.7 QnA — base `/api/v1/admin/qna`

`AdminQnaController` — ADMIN, MODERATOR.

| Method + path | SU |
|---------------|----|
| `GET /questions` (`status, q, authorId` + pageable) | — |
| `POST /questions/{id}/close` · `/reopen` · `/archive` | — |
| `DELETE /questions/{id}` · `DELETE /answers/{answerId}` (body `{reason}`) | **SU** |

### 4.8 Tags & trending — bases `/api/v1/admin/tags`, `/api/v1/admin/trending`

`TagAdminController` + `AdminTrendingController` — ADMIN only.

| Method + path | SU | Notes |
|---------------|----|-------|
| `POST /api/v1/admin/tags/backfill-posts` | — | Full token-range scan; **trending counter bumps are NOT idempotent — never re-run casually**. Danger-zone confirm (§6). |
| `POST /api/v1/admin/tags/{tag}/hide` · `DELETE …/hide` | — | `scope` param |
| `POST /api/v1/admin/tags/merge` | — | — |
| `GET /api/v1/admin/trending/overrides` · `DELETE …/overrides/{id}` | — | — |
| `POST /api/v1/admin/trending/overrides` | **SU** | Editorializes a public surface — say so in the confirm |
| `POST /api/v1/admin/trending/rebuild` | — | — |

### 4.9 Chat, channels & live — bases `/api/v1/admin/chat`, `…/channels`, `…/streams`

`AdminChatController`, `AdminChannelController`, `AdminStreamController`,
`LegalHoldController`. ADMIN + MODERATOR except where noted. Doc:
[chat-channels-live.md](chat-channels-live.md). **Privacy boundary:** metadata
always, message content never — DTOs exclude `last_message_preview`, stream
keys, publish keys. Do not build UI that implies content is viewable.

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /api/v1/admin/chat/conversations` · `/overview` · `/calls` · `/calls/stats` · `/message-requests/stats` | filters + pageable / `from,to` | — | ADMIN, MODERATOR |
| `GET /api/v1/admin/channels` · `/{id}` · `/{id}/stats` · `/{id}/invite-links` | `q, verified, public, category` + pageable | — | ADMIN, MODERATOR |
| `PATCH /api/v1/admin/channels/{id}/verified` | `verified` bool | — | ADMIN, MODERATOR |
| `POST …/channels/{id}/takedown` · `/restore` · `/freeze` | `{reason, reportId?}` | **SU** | ADMIN, MODERATOR |
| `POST …/channels/{id}/unlist` · `/unfreeze` · `/invite-links/{inviteId}/revoke` | — | — | ADMIN, MODERATOR |
| `GET /api/v1/admin/streams` · `/{id}` · `/gifts/top` | `status, hostId` + pageable / `window, limit` | — | ADMIN, MODERATOR |
| `GET …/streams/{id}/recording` · `GET …/streams/recordings` | — | **SU** | content-adjacent read — loud confirm |
| `POST …/streams/{id}/force-stop` | `{reason}` | **SU** | ADMIN, MODERATOR |
| `POST …/streams/{id}/rotate-key` | — | **SU** | **ADMIN only** — key-level control never delegates |
| `DELETE …/streams/{id}/stage/{userId}` (remove guest) · `DELETE …/streams/{id}/recording` | `{reason}` | **SU** | ADMIN, MODERATOR |

The recordings fleet view caps detailed rows and says so in a `note` field —
render it. Rotate-key delivers the new key **to the host only**; the response
never contains it.

**Legal holds — base `/api/v1/admin/chat/legal-holds`** (`LegalHoldController`,
**ADMIN only**, the sole message-content release path, dual-control):

| Method + path | SU | Notes |
|---------------|----|-------|
| `GET /api/v1/admin/chat/legal-holds` | — | `status` = OPEN/APPROVED/EXECUTED/REJECTED |
| `POST /api/v1/admin/chat/legal-holds` | **SU** | `{conversationId, reason}` — reason is a case/court reference, mandatory |
| `POST …/{id}/approve` · `/reject` | **SU** | **A different admin than the opener** — self-approval 409s. Optional `{note}`. |
| `POST …/{id}/execute` | **SU** | One-shot: releases the newest ≤500 messages, flips to EXECUTED, cannot be re-run. Response carries a `warning` string — display it prominently in the export view. |

UI: show the opened-by/approved-by pair on every hold; grey out Approve for
the opener; treat Execute as the most dangerous button in the dashboard (§6).

### 4.10 Sounds — base `/api/v1/admin/sounds`

`AdminSoundController` — ADMIN, MODERATOR. Doc: [sound-library.md](sound-library.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /api/v1/admin/sounds` | `status` (default PENDING_REVIEW), cursor + pageSize | — |
| `GET /status-counts` · `/uploaders/{userId}` · `/{id}` · `/trending` | — | — |
| `POST /{id}/approve` · `/reject` · `/archive` · `/restore` | `{reason}` on reject | — |
| `POST /{id}/takedown` | `{reason}` (rights/DMCA) | **SU** |
| `POST /{id}/category` · `PATCH /{id}` (metadata) · `POST /{id}/trending-exclude` · `POST /import` | — | — |
| `DELETE /{id}` | — | **SU** — hard delete, danger zone |

The legacy stray `POST /api/v1/sounds/{id}/approve` is deprecated (successor
`Link` header) — the dashboard must call only the `/admin/sounds` routes. Same
for the channel-verify stray `PUT /api/v1/channels/{id}/verified`.

### 4.11 Media & storage — bases `/api/v1/admin/media`, `/api/v1/admin/storage`

`AdminMediaController`, `AdminStorageController` — ADMIN only. Doc:
[media-storage.md](media-storage.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /api/v1/admin/media` · `/{assetId}` · `/status-summary` · `/ops` | `status, type, ownerId` + pageable | — |
| `POST /{assetId}/reprocess` | — | — |
| `DELETE /{assetId}` | `{reason}` | **SU** — deletes all R2 renditions, danger zone |
| `POST /purge-raw/run` | `dryRun` (**default `true`**) | — |
| `POST /reconcile` | `dryRun` (**default `true`**) — S3 LIST diff; `dryRun=false` deletes orphans | **SU** |
| `GET /quotas` · `PUT /quotas/{role}` | per-role daily upload quotas | **SU** on PUT |
| `GET /api/v1/admin/storage/usage` | `top` (default 20) | — |

Reconcile/purge-raw responses echo `dryRun` and carry explanatory `note`
fields — render them, and default the UI toggle to dry-run.

### 4.12 Notifications & announcements — base `/api/v1/admin/notifications`

`AdminNotificationController` — ADMIN (stats/types also SUPPORT, ANALYST).
Doc: [notifications-email.md](notifications-email.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /stats` | `from, to` (ISO date-times, default last 30d) | — | + SUPPORT, ANALYST |
| `GET /types` | — (static registry of every NotificationType) | — | + SUPPORT, ANALYST |
| `POST /announcements` | `{title, body, audienceRole?, audienceLanguage?, activeSinceDays?, scheduledAt?, dryRun?, confirmLargeAudience?}` | **SU** | ADMIN |
| `GET /announcements` · `DELETE /announcements/{id}` (cancel a SCHEDULED one) | pageable | **SU** on DELETE | ADMIN |
| `POST /digest/run` | `date?` | — | ADMIN |
| `GET /email/stats` · `POST /email/test` · `DELETE /push-tokens/{id}` | — | — | ADMIN |

**Announcement composer flow (build it exactly like this):** always send
`dryRun: true` first → the 200 response reports the computed `audience` size;
show it ("This will notify N users"). Real send returns **202**. If the
audience is **≥ half the platform**, the server 400s unless
`confirmLargeAudience: true` — surface that as a second, explicit checkbox,
never auto-set it. `scheduledAt` is ISO-8601 local date-time; scheduled
announcements are cancellable until the sweep fires them. The `/stats`
response carries an honest `note` about its legacy-inbox sourcing — render it.

### 4.13 Search ops — base `/api/v1/admin/search`

`SearchAdminController` (the 7 reindexes) + `AdminSearchOpsController`. Doc:
[search-feed-trending.md](search-feed-trending.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `POST /{research\|posts\|questions\|users\|channels\|answers\|sounds}/reindex` | `drop` (default `true` — recreates the index) | — | ADMIN. **Synchronous** — can take a while; disable the button while in flight and warn about `drop=true` during peak. |
| `POST /reindex-all` | — | **SU** | ADMIN. Async **202** `{jobId}`; poll `GET /api/v1/admin/ops/jobs/search-reindex-all/runs` (the response `note` says exactly that). |
| `GET /indices` | per-index existence + doc counts (8 `irc-*` indices) | — | ADMIN, ANALYST |
| `GET /health` · `/analytics/top-queries` · `/analytics/zero-results` | window params | — | ADMIN, ANALYST |

There is deliberately **no** chat-messages reindex — don't render a slot for it.

### 4.14 Feed tuning & suggestions — bases `/api/v1/admin/feed`, `/api/v1/admin/suggestions`

`AdminFeedController`, `AdminSuggestionsController` — ADMIN, ANALYST (mutations
ADMIN). Docs: [search-feed-trending.md](search-feed-trending.md), [discovery-pymk-privacy.md](discovery-pymk-privacy.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /api/v1/admin/feed/weights` · `/config` · `/explain/{userId}` · `/affinity/{userId}` | `limit` on explain | — | ADMIN, ANALYST |
| `PATCH /api/v1/admin/feed/config` | partial knob body (`enabled, rolloutPercent, wLike…, halfLife…, boost…, damp…, maxAuthorRun`) — live within ≤30 s | **SU** | ADMIN |
| `POST /api/v1/admin/feed/preview` | `{userId, limit?, overrides?}` — shadow-scores baseline vs. proposed side-by-side, persists nothing (the `note` says so) | — | ADMIN |
| `GET /api/v1/admin/suggestions/knobs` · `/explain/{userId}` | PYMK sources/weights are recompile-only — display as read-only | — | ADMIN, ANALYST |

Ideal UX: edit knobs → **Preview** (safe) → then **Apply** (step-up). Show
`rolloutPercent` prominently — config changes hit only that bucket.

### 4.15 Logs & audit — bases `/api/v1/admin/logs`, `/api/v1/admin/audit`

`AdminLogsController` (all four staff tiers) + `AuditLogController` (all four
tiers). Doc: [logs-audit.md](logs-audit.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /api/v1/admin/logs/explore` | `q` (grammar) or discrete `store` (audit/login/settings/consent/reports), `userId, ip, outcome, since, until, text, pageSize`. Audit/consent stores are user-anchored — the response `notes` explain when a store was skipped. | — | all staff |
| `GET …/logs/login-events` | pageable + filters | — | all staff |
| `POST …/logs/export` | produces **text/csv** (§5.4) | **SU** | **ADMIN** |
| `GET/POST /logs/views` · `DELETE /logs/views/{id}` | saved explorer views | — | all staff |
| `GET /logs/alerts` · `/alerts/firings` · `/retention` · `/otp-stats` | — | — | all staff |
| `POST /logs/alerts` · `PATCH /logs/alerts/{id}` | rule body | **SU** | **ADMIN** |
| `DELETE /logs/alerts/{id}` | — | — | **ADMIN** |
| `GET /api/v1/admin/audit` | **`userId` effectively required — 400 without** (Cassandra partition scope); `operation, outcome, from, to, cursor, pageSize` filtered in-memory | — | all staff |
| `GET /api/v1/admin/audit/users/{userId}` | `cursor, pageSize` keyset | — | all staff |
| `GET /api/v1/admin/audit/resources/{resourceType}/{resourceId}` | `cursor, pageSize` — "what happened to this resource"; the only view that includes anonymous traffic | — | all staff |
| `GET /api/v1/admin/audit/stream` | `?token=` SSE (§5.5) | — | all staff |

The audit browser UI must make the scope rule obvious: per-user browsing only;
the SSE stream is the global view. `otp-stats` is aggregate-only by design
(its `note` says so).

### 4.16 Analytics — base `/api/v1/admin/analytics`

`AdminAnalyticsController` — ADMIN, ANALYST (pipeline controls ADMIN). Doc:
[analytics-kpis.md](analytics-kpis.md).

| Method + path | Key params | Who |
|---------------|-----------|-----|
| `GET /overview` · `/content` · `/engagement` · `/trending` | `window` / `scope` | ADMIN, ANALYST |
| `GET /export` | `from, to, dataset` — **text/csv** (§5.4) | ADMIN, ANALYST |
| `GET /series` · `/funnel` · `/retention` · `/anomalies` · `/alerts-config` | metric/window params | ADMIN, ANALYST |
| `POST /rollup/{date}/run` · `POST /backfill` | date / range | **ADMIN** |
| `GET /events/sample` · `PUT /alerts/{metric}` | — / threshold body | **ADMIN** |

Several responses carry sourcing `note` fields (e.g. collector-sourced post
counts, set-once funnel milestones) — always render them under the chart;
they are the honesty contract of this section.

### 4.17 Operations — base `/api/v1/admin/ops`

`AdminOpsController` — **ADMIN only**. Doc: [operations.md](operations.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /health` · `/sse` · `/media-plane` · `/redis` · `/config/reconciler` | dependency rollup, emitter counts, MediaMTX, Redis INFO, enum-CHECK report | — |
| `GET /jobs` · `/jobs/{jobKey}/runs` · `/jobs/paused` | scheduled-job ledger | — |
| `POST /jobs/{jobKey}/run` | 202, whitelisted jobs only | **SU** |
| `POST /jobs/{jobKey}/pause` · `/resume` | pause returns a **`warning` field — render it** (pausing a job has consequences) | **SU** |
| `GET /queues` · `GET /queues/dlq` | `status` (PARKED/REQUEUED/DISCARDED), `routingKey` + pageable | — |
| `POST /queues/dlq/{id}/requeue` | republish to original exchange/key; `note` explains it re-parks on repeat failure | **SU** |
| `DELETE /queues/dlq/{id}` | discard (row kept, status DISCARDED — the `note` says so) | **SU** — danger zone |
| `DELETE /redis/keys` | `prefix` — allowlisted cache prefixes **only**; auth/abuse state (`sid:`, `stepup:`, `otp:`, `rl:`) is never flushable, the server 400s | **SU** — danger zone |
| `POST /es/chat-messages/backfill` | 202 + jobId; idempotent (`note`) | **SU** |
| `GET /config` | sanitized env/flag registry — **a step-up-gated read**; shows `permit-all` state with a warning field when on | **SU** |
| `POST /streams/sweep-orphans` | `graceMinutes` (30), `maxAgeHours` (12), `dryRun` (**default `false`** — the UI should default the toggle **on**) | **SU** |

### 4.18 Activity & break-glass — base `/api/v1/admin` (activity routes)

`AdminActivityController` — **ADMIN only**. Doc: [activity-engagement.md](activity-engagement.md).
The per-user reads are **break-glass**: they 403 without an OPEN dual-control
case for that user, on top of step-up, and every read writes a loud audit row.

| Method + path | Key params | SU |
|---------------|-----------|----|
| `POST /api/v1/admin/breakglass/{targetUserId}` | `{kind, reason (10–1000 chars), caseRef?}` — opens PENDING_APPROVAL | **SU** |
| `POST …/breakglass/cases/{caseId}/approve` | **must be a different admin** than the opener (403 `DUAL_CONTROL_REQUIRED`) | **SU** |
| `POST …/breakglass/cases/{caseId}/close` · `GET …/breakglass/cases` | — | — |
| `GET …/users/{userId}/activity` · `/activity/summary` · `/reels/watched` | `type(s), from, to, window` + pageable — **403 without an open case** | **SU** |
| `GET …/users/{userId}/activity/export` | `from, to, format` (JSON/CSV) | **SU** |
| `POST …/users/{userId}/activity/erase` | `{type?, reason}` — the one routine action; GDPR erasure, capped batches (`note`) | **SU** |

UI: model this as a **case workflow**, not a data browser — open case →
second admin approves → reads unlock → close case. Show opener/approver on
each case and grey out Approve for the opener.

### 4.19 Knowledge vocabulary — base `/api/v1/admin/knowledge`

`AdminKnowledgeController` — **ADMIN only**. Doc: [knowledge-vocabulary.md](knowledge-vocabulary.md).

| Method + path | SU |
|---------------|----|
| `GET /topics` · `GET /madhhabs` (trilingual labels + usage counts) | — |
| `POST /topics` · `POST /madhhabs` (`{nameEn, nameAr, nameCkb}`) | **SU** |
| `PATCH /topics/{id}` · `PATCH /madhhabs/{id}` | **SU** |
| `POST /topics/{id}/retire` · `POST /madhhabs/{id}/retire` (soft-retire — never hard delete) | **SU** |
| `POST /cache/evict` | — |

### 4.20 Discovery & PYMK privacy — base `/api/v1/admin`

`AdminDiscoveryController` — **ADMIN only**. Doc: [discovery-pymk-privacy.md](discovery-pymk-privacy.md).

| Method + path | SU |
|---------------|----|
| `POST /users/{userId}/suggestions/recompute` | — |
| `GET /users/{userId}/suggestions` (PII read) | **SU** |
| `GET /discovery/contact-sync/stats` (`window`) | — |
| `GET /discovery/contact-sync/compliance` (PII read) | **SU** |
| `POST /users/{userId}/contact-hashes/purge` (GDPR erasure) | **SU** — danger zone |
| `GET /users/{userId}/discovery` (flags + QR-token status) | — |
| `POST /users/{userId}/qr/rotate` | **SU** |

---

## 5. Cross-cutting conventions

### 5.1 Pagination — two shapes

| Store | Shape | Frontend handling |
|-------|-------|-------------------|
| Postgres-backed lists | Spring `Pageable`: `page`, `size`, `sort` → `Page<DTO>` (`content`, `totalElements`, `totalPages`, `number`) | Classic pager. `size` is clamped server-side to **≤100** (`Pages.clamp`) — asking for more silently gets 100; don't build a "show 500" option. |
| Cassandra-backed lists (audit, sounds queue, content posts) | Keyset: `cursor` (ISO-8601 date-time of the last row) + `pageSize` → plain array | Infinite scroll / "Load more": pass the last row's `createdAt` as the next `cursor`. No total counts exist — don't render "page X of Y". |

Responses are **raw DTOs / `Page<DTO>` — no success envelope**. Only errors
are enveloped ([../errors/error-handling.md](../errors/error-handling.md)).

### 5.2 Time & filters

- Ranges: `from` / `to` as ISO-8601 date-times, both optional; server
  defaults are last **24 h** for logs and last **30 d** for analytics.
  Windowed endpoints take `window`/`windowDays`-style params instead.
- Filter names are consistent everywhere: `userId`, `status`, `type`, `q`,
  `sort`. Enums are parsed **case-insensitively**; a bad value 400s and the
  error message lists the allowed values — show that message, it's written
  for humans.
- Mutations accept an `Idempotency-Key` header (24 h replay dedup) — send one
  on every dangerous POST so step-up retries and double-clicks are safe.

### 5.3 Async jobs (202 + jobId)

Long-running triggers (`/search/reindex-all`, `/ops/jobs/{jobKey}/run`,
`/ops/es/chat-messages/backfill`, non-dry-run announcements) return **202**
with `{jobId, note}`. Poll `GET /api/v1/admin/ops/jobs/{jobKey}/runs` for the
outcome; the 7 single-index reindexes are the exception — synchronous,
returning final counts in the response.

### 5.4 CSV exports

Three endpoints produce `text/csv`:

| Endpoint | Gate |
|----------|------|
| `GET /api/v1/admin/analytics/export?from&to&dataset` | ADMIN, ANALYST |
| `POST /api/v1/admin/logs/export` | ADMIN + **SU** |
| `GET /api/v1/admin/users/{userId}/activity/export?format=csv` | ADMIN + **SU** + open break-glass case |

`EventSource`-style tricks don't apply — plain `fetch` with the Bearer
header, read the blob, honor the `Content-Disposition` filename, trigger a
client-side download. Don't open these in a new tab (no header there).

### 5.5 The audit SSE stream

`GET /api/v1/admin/audit/stream?token=<accessJWT>` — the **only** admin SSE.
Events: `connected`, `audit` (one JSON row per audited request, fanned out
cross-instance via Redis), `heartbeat` every 25 s. Rules:

- One `EventSource` in the shell; widgets filter the event flow client-side
  by `operation`, `outcome`, `path` prefix, `resourceType`.
- Pass the token as `?token=` (EventSource cannot set headers). Reconnect
  with backoff on error; a token refresh means tearing down and reopening
  with the new token.
- If no `audit` events arrive for >5 min while you're generating traffic
  yourself, surface a "stream may be broken" warning — the backend treats
  that as an alert condition too.

### 5.6 Honest `note` / `warning` fields

Many admin responses carry a `note` (data-sourcing caveats, cap notices,
idempotency promises) or `warning` (legal-hold export, permit-all on, job
pause). These are part of the API contract: **always render them** — a muted
info line under the widget for `note`, an alert banner for `warning`. Never
swallow them.

---

## 6. Danger-zone UX rules

Client-side **typed confirmation** (user types the resource name / "DELETE" /
the username) before submitting, on top of the server's step-up gate:

| Action | Why |
|--------|-----|
| `POST /admin/users/{id}/purge/now` | Expedites irreversible GDPR purge past the nightly cron |
| `POST /admin/users/{id}/deletion/request` · `POST /admin/users/bulk-action` | Account-level, batch blast radius |
| `DELETE /admin/research/{id}` · `DELETE /admin/qna/questions/{id}` · `DELETE /admin/qna/answers/{id}` · `DELETE /admin/content/comments/{id}` · `DELETE /admin/content/stories/{id}` | Hard deletes — no restore path |
| `DELETE /admin/sounds/{id}` · `DELETE /admin/media/{assetId}` | Hard delete incl. all R2 renditions |
| `DELETE /admin/ops/queues/dlq/{id}` | Discards a dead letter (row kept, message gone) |
| `DELETE /admin/ops/redis/keys?prefix=` | Cache flush — even allowlisted, it's a stampede risk |
| `POST /admin/notifications/announcements` (non-dry-run) | Mass notification; pair with the dry-run-first flow and the `confirmLargeAudience` checkbox (§4.12) |
| `POST /admin/channels/{id}/takedown` · `POST /admin/streams/{id}/force-stop` · `POST /admin/streams/{id}/rotate-key` | Kills a live public surface / invalidates a host's key |
| `POST /admin/chat/legal-holds/{id}/execute` | Releases private message content — the single most sensitive action in the product |
| `POST /admin/tags/backfill-posts` | Trending bumps are not idempotent; re-runs corrupt counts |

Further rules:

- **Dry-run first, always**: where a `dryRun` param exists (announcements,
  media reconcile, purge-raw, stream orphan sweep) default the toggle **on**
  — even where the API default is `false` (sweep-orphans).
- **Dual-control is a second person, not a second click**: legal holds and
  break-glass cases require a *different* admin to approve. Build the UI as a
  two-actor workflow; disable Approve for the opener rather than letting the
  409/403 teach them.
- **Never render secrets** — and the API already guarantees you can't:
  `stream_key`, `publish_key`, 2FA secrets, refresh-token values, OTP hashes
  and `conversations.last_message_preview` are excluded from every admin DTO
  by construction. Do not add UI fields for them, do not log tokens (access,
  impersonation) to the console, and don't persist the impersonation token.
- Surface the self-protection errors (`SELF_ACTION_FORBIDDEN`, `LAST_ADMIN`,
  `IMPERSONATION_TARGET_ADMIN`) as friendly inline messages — the server
  always wins these arguments.

---

## 7. Suggested route / nav tree

Role annotations = who sees the nav entry (from §1.2; per-endpoint grants in
§4 still apply within a page). `[A]`=ADMIN `[M]`=MODERATOR `[S]`=SUPPORT
`[AN]`=ANALYST.

```
/admin                                  shell: health chips [A] + audit ticker [A M S AN]
├── /users                              directory + detail tabs        [A M S]
│   ├── /users/:id                      profile · sessions · login-events · settings-audit
│   │                                   · moderation · data-lifecycle · (PII reveal, SU) [A]
│   ├── /users/new, /users/invites      create / bulk / invite         [A]
│   └── /users/analytics                growth                          [A M S AN]
├── /moderation                         unified queue + bulk            [A M]
│   ├── /moderation/content             posts / comments / stories      [A M]
│   ├── /moderation/blocklist           platform keywords               [A M]
│   └── /moderation/sounds              approval queue + catalog        [A M]
├── /safety                             reports triage                  [A M] (reads: +S)
│   ├── /safety/reports/:id             detail + evidence + action
│   ├── /safety/strikes                 ledger
│   └── /safety/analytics               volumes / SLAs / blocks
├── /research                           browse · flags · retract/delete [A M]
├── /qna                                questions · close/archive/delete[A M]
├── /tags                               hide/merge · trending overrides · backfill [A]
├── /chat                               conversations meta · calls · msg-requests [A M]
│   ├── /chat/channels(:id)             browse · verify · takedown/freeze · invite links [A M]
│   ├── /chat/streams(:id)              live board · force-stop · recordings (SU)
│   │                                   · rotate-key [A only]           [A M]
│   └── /chat/legal-holds               dual-control content release    [A]
├── /media                              assets · status board · reconcile · quotas [A]
│   └── /media/storage                  usage top-N                     [A]
├── /notifications                      stats + type registry           [A S AN]
│   ├── /notifications/announcements    composer (dry-run flow)         [A]
│   └── /notifications/email            deliverability + test send      [A]
├── /search                             index health · reindexes · query analytics [A] (health/indices: +AN)
├── /feed                               weights · config (SU) · preview · explain [A] (reads: +AN)
│   └── /feed/suggestions               PYMK knobs + explain            [A AN]
├── /logs                               explorer · saved views · alerts · retention [A M S AN]
│   └── /logs/audit                     per-user/per-resource browser + live stream [A M S AN]
├── /analytics                          overview · series · funnel · retention · export [A AN]
├── /ops                                health · jobs · queues/DLQ · SSE · Redis
│                                       · config (SU) · media-plane · sweeps [A]
├── /activity                           break-glass cases + gated reads [A]
├── /knowledge                          topics & madhhabs curation      [A]
└── /discovery                          contact-sync stats/compliance · per-user flags [A]
```

Hide, don't disable, sections the role can't see; inside a visible section,
render mutation buttons disabled-with-tooltip when the role lacks the grant
(e.g. MODERATOR on the users directory). Impersonation lives as an action on
the user detail page, not a nav entry — its banner (§3) is global.
