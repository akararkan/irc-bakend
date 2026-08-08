# Admin Dashboard — Chat, Channels, Calls & Live Streaming

Section 4 of the [admin dashboard plan](../README.md). Covers the entire `chat` module surface:
DMs/groups, Telegram-parity broadcast channels, voice/video calls, and live streaming
(stage, gifts, recordings). Underlying mechanics: [../chat/README.md](../../chat/README.md),
[../chat/live-streaming.md](../../chat/live-streaming.md), [../chat/calls.md](../../chat/calls.md),
[../chat/message-requests.md](../../chat/message-requests.md).

| Tag | Meaning |
|-----|---------|
| **[EXISTS]** | Real today — class or `METHOD /path` cited. |
| **[PARTIAL]** | Partly real; the gap is stated. |
| **[PLANNED]** | Proposed for the dashboard build. Not coded. |

---

## 1. Purpose & scope

| In scope | Out of scope (see) |
|----------|--------------------|
| Privacy boundary: what admins may ever see of chat | Report triage queue mechanics → [safety-reports.md](../trust-safety/safety-reports.md) |
| Channel oversight: verification, stats, join requests, invite links, takedown | Chat message search index ops → [search-feed-trending.md](../platform/search-feed-trending.md) |
| Groups/DMs: aggregate stats + message-request quarantine stats | R2 media objects & media pipeline → [media-storage.md](../content/media-storage.md) |
| Calls: volume/duration/missed analytics | MediaMTX process health / env vars → [operations.md](../platform/operations.md) |
| Live: live-now board, stream detail (stage/gifts), force-stop, key rotation, recordings, history | Notification fan-out (STREAM_STARTED etc.) → [notifications-email.md](notifications-email.md) |

Governance reality this section must respect: chat moderation today is **entirely
conversation-scoped self-governance** — `ChannelRights.can` (owner = all rights, admin =
`AdminRights` JSONB) layered on `GroupPermissions.can`. Platform staff currently reach into
this subsystem at exactly **two** points: the channel verified toggle and the channel ES
reindex. Everything else below is the plan to close that gap without ever crossing the
content line.

---

## 2. Privacy boundary — metadata yes, content never

**The single most important rule of this dashboard section.** The admin dashboard exposes
conversation **metadata, counts, membership, and reports** — never message content. Today
this is trivially true (no admin API reads any content store); the dashboard build must
keep it true by construction.

### 2.1 What the dashboard MAY expose (all metadata, Postgres control-plane)

| Data | Source **[EXISTS]** | Notes |
|------|---------------------|-------|
| Conversation shell | `conversations` (`Conversation`): type DIRECT/GROUP/CHANNEL, title, handle, is_public, verified, category, member_count, post_count, owner_id, disappearing_seconds, created/deleted_at | **Exclude `last_message_preview`** — see 2.3 |
| Membership | `conversation_members`: role OWNER/ADMIN/MEMBER, status, join_source, joined_at, muted, admin_rights JSONB | Do **not** surface per-member read positions (`last_read_message_id`, unread_count) — behavioral surveillance, no admin need |
| Invites / join requests | `conversation_invites` (SHA-256 `token_hash` only — safe), `conversation_join_requests` | Plaintext invite token is unrecoverable by design |
| Message requests | `message_requests`: status, message_count, requester/recipient | Quarantine spam signal (§5.2) |
| Calls | `call_sessions` + `call_participants` | Pure signaling metadata; **no call content exists server-side** (P2P WebRTC) |
| Live streams | `live_streams`, `stream_guests`, `stream_viewers`, `stream_gift_tallies` | **Never render `stream_key` / `publish_key`** — plaintext secrets (§8) |
| Reports against chat targets | `reports` where target_type ∈ MESSAGE, CHANNEL, USER | Report row carries reporter-supplied `details`, not the message body |
| Aggregates | Redis `chat:chtotals:*`, `chat:chtop:*`, `chat:chposttypes:*`, `chat:presence:*` | Best-effort, loss-tolerant |

### 2.2 Where message CONTENT lives (never queried by the dashboard)

| # | Store | What's in it |
|---|-------|--------------|
| 1 | **Cassandra chat keyspace** — `message_by_conversation` (body, media `List<MediaRef>`, poll, location, contact), `message_by_id`, `media_by_conversation`, `chat_comment_by_post` | The canonical message log. Bucketed, Snowflake-clustered, membership-gated via `ChatPermissionEngine`. Disappearing messages carry per-row TTL |
| 2 | **Elasticsearch `irc-chat-messages`** | Message text of **non-disappearing** messages only (2026-07-22 privacy fix) |
| 3 | **Recording files** — `{app.streaming.recordings-dir:./recordings}/<stream-uuid>/*.mp4` | Recorded live A/V, on local disk outside all datastores |
| 4 | **Cloudflare R2 objects** referenced by `MediaRef` keys | Chat media blobs |

Access to any of these four stores requires the **legal-hold process
[EXISTS (built 2026-08)]** — `admin/chat/LegalHoldController`,
`/api/v1/admin/chat/legal-holds` — there is deliberately no other path:

| Legal-hold control [EXISTS (built 2026-08)] | Implementation |
|------------------------------|--------|
| Trigger | `POST /api/v1/admin/chat/legal-holds` — documented legal/safety obligation, case ID mandatory |
| Authorization | **Dual control**: the opening ADMIN cannot approve their own hold — a **second admin** must `POST …/{id}/approve` (or `/reject`); only then can `POST …/{id}/execute` run. Step-up auth throughout |
| Scope | Single conversation; execute (APPROVED holds only, once) releases the **newest ≤500 messages**, bucket-walked newest-first — never bulk export |
| Audit | Dedicated `LEGAL_HOLD_*` audit actions plus the immutable `legal_holds` record (`LegalHold` entity); surfaced in [logs-audit.md](../platform/logs-audit.md) |
| Non-recoverable by design | Live-stream chat (broadcast-only SSE, **never persisted** — `LiveStreamService.chat`) and call media (P2P, server relays signals only). Policy must state: no retroactive evidence exists for these |

### 2.3 Known metadata→content leak edges (must be handled in phase 1)

| Edge | Reality | Dashboard rule |
|------|---------|----------------|
| `conversations.last_message_preview` | 160-char snippet of the newest message. Disappearing conversations already redact it (2026-07-22 fix); **normal DMs do not** | **[PLANNED]** admin projections must exclude the column — a "metadata-only" `SELECT *` would leak the latest snippet of every DM on the platform |
| ES `irc-chat-messages` | Second content store; reachable via global search plumbing | Dashboard never queries it; reindex ops stay in [search-feed-trending.md](../platform/search-feed-trending.md) |
| `live_streams.stream_key`, `stream_guests.publish_key` | Stored **plaintext** (MediaMTX auth compares them in `LiveStreamService.authorizeMediaAccess`) | Never selected into any admin DTO; key rotation action (§6) returns nothing |

---

## 3. Dashboard views / widgets

| View | What the admin sees | Status |
|------|---------------------|--------|
| **Chat overview** | Tiles: total conversations by type (DIRECT/GROUP/CHANNEL), channels verified count, live-now count, calls today, pending message requests. Trend sparklines 30d | **[PLANNED]** (all derivable from `conversations`/`call_sessions`/`message_requests`/`live_streams` by SQL) |
| **Channel directory** | Filterable table of channels: handle, title, verified badge, member_count, post_count, owner, category, is_public, created_at. Row → channel detail | **[PLANNED]** — no admin browse endpoint exists today |
| **Channel detail** | The full `ChannelStatsResponse` panel (§4.2) + join-request queue snapshot + invite-link table (use_count vs max_uses, expiry, revoked) + reports against this channel | **[PARTIAL]** — stats exist but are member-gated; rest planned |
| **Groups/DMs aggregates** | Counts only: conversations by type/size buckets, disappearing-mode adoption %, group member_count distribution. **No per-DM drill-down list** (DM existence between two named users is itself sensitive — expose only on report or legal hold) | **[PLANNED]** |
| **Message-request quarantine board** | PENDING/ACCEPTED/DECLINED/BLOCKED funnel, top requesters by declined+blocked count (spam signal), aging of PENDING | **[PLANNED]** over `message_requests` **[EXISTS]** |
| **Calls board** | Volume by day (VOICE vs VIDEO), answer rate, missed rate, avg/percentile duration | **[PLANNED]** over `call_sessions` **[EXISTS]** |
| **Live-now board** | All LIVE streams ordered by viewer count: host, title, live viewers, peak, started_at, recording flag, guest count. Row → stream detail | **[PARTIAL]** — `GET /api/v1/streams/live` **[EXISTS]** (any authenticated user) already lists LIVE streams by viewerCount; admin filters (by host, by open reports, stale-LIVE flag) planned |
| **Stream detail** | Stream metadata + stage roster (`StreamGuest`: status, muted, publish_path — key hidden) + gift leaderboard (`StreamGiftTally` top supporters) + viewer trail summary + recording status/size + **Force-stop / Rotate-key / Delete-recording** buttons | **[PARTIAL]** — data exists (§4.5–4.7); admin actions planned |
| **Stream history** | Ended streams: duration, peak viewers, unique viewers, total gift coins, recording status/size. Per-host history | **[PLANNED]** queries over `live_streams` + `stream_viewers` + `stream_gift_tallies` **[EXISTS]** |
| **Recordings manager** | Disk usage total + per-stream (`RecordingStorageService.totalBytes`), orphaned dirs, delete action | **[PARTIAL]** — fleet-listing backend built 2026-08 (§4.7); UI planned |

---

## 4. Data sources — per widget, exact classes/endpoints

### 4.1 Channel verification **[EXISTS]**

| Item | Detail |
|------|--------|
| Endpoint | `PUT /api/v1/channels/{id}/verified?verified=true|false` → `ChannelController.setVerified` → `ChannelService.setVerified(channelId, verified)` |
| Effect | Flips `Conversation.verified`, reindexes into `irc-channels` |
| Gate | Method-level `@PreAuthorize("hasRole('ADMIN')")` **only** — the path is NOT under `/api/v1/admin/**`, so it misses the filter-chain double gate (`SecurityConfig` hardcodes only `/api/v1/admin/**`) and is open under `SECURITY_PERMIT_ALL=true` |
| Plan | **[PLANNED]** relocate/alias to `POST /api/v1/admin/channels/{id}/verified` for the double gate; keep old path until frontend migrates |

### 4.2 Channel stats **[EXISTS]** — `ChannelStatsService.stats` → `GET /api/v1/channels/{id}/stats`

The real fields of `ChannelStatsResponse` (chat/dto/response):

| Field | Source |
|-------|--------|
| `subscriberCount` | `conversations.member_count` |
| `onlineSubscribers` | `PresenceService.onlineAmong` over active member ids (Redis `chat:presence:*`, 30s TTL) |
| `joinedLast7Days`, `joinedLast30Days` | `ConversationMemberRepository.countJoinedSince` |
| `leftLast30Days` | `countLeftSince` |
| `mutedCount`, `notificationsEnabledCount` | muted count + (subscribers − muted) |
| `postCount` | `conversations.post_count` (live, non-deleted channel posts) |
| `postsByType` | Redis hash `chat:chposttypes:{channelId}` (best-effort) |
| `totalViews`, `totalForwards` | Redis hash `chat:chtotals:{channelId}` (best-effort; HLL-deduped first-sight views via `ChannelPostMetricsService`) |
| `joinsByDay` (30d, ISO date → count) | `memberRepo.joinsByDay` |
| `joinsBySource` | `join_source` column: OWNER/DISCOVERY/INVITE_LINK/JOIN_REQUEST/ADDED_BY_ADMIN/COMMENT/UNKNOWN |
| `topPosts` (top 10 by views) | Redis ZSET `chat:chtop:{channelId}` → `MessageQueryService.messagesByIds` |

**Gate caveat [PARTIAL]:** requires the caller to be that channel's OWNER/ADMIN **member**
(`memberRepo.findMember` + `isAdminOrOwner`, else `ADMINS_ONLY`). A platform ADMIN who is
not a member is locked out — the dashboard needs a platform-admin override read
**[PLANNED]** (§6). Note `topPosts` returns `MessageResponse` objects, i.e. channel-post
content: acceptable for **broadcast channel** posts (public-by-nature) but the override
endpoint should return ids+view counts only, not bodies, to keep the content line crisp.
Redis-flush caveat: `chat:chtotals`/`chat:chtop`/`chat:chposttypes` silently reset if Redis
is wiped while per-post `message_counters` (Cassandra) stay correct.

### 4.3 Join-request oversight

| Item | Status | Detail |
|------|--------|--------|
| Data | **[EXISTS]** | `ConversationJoinRequest`: status PENDING/APPROVED/REJECTED, decidedBy, decidedAt, inviteId |
| Channel-scoped decisions | **[EXISTS]** | `POST /api/v1/channels/{id}/join-requests/{userId}/approve|reject` — channel admin scope (`AdminRights.canApproveJoinRequests`) |
| Admin visibility | **[PLANNED]** | Read-only queue metrics per channel: pending count, aging, approve/reject ratio, requests arriving via which invite (`inviteId` join). The dashboard does **not** decide requests for the channel — oversight, not substitution |

### 4.4 Invite-link abuse monitoring

| Item | Status | Detail |
|------|--------|--------|
| Data | **[EXISTS]** | `ConversationInvite`: SHA-256 `token_hash` (plaintext shown once, unrecoverable), `max_uses`, `use_count`, `expires_at`, `revoked`, `requires_approval` |
| Owner/admin revoke | **[EXISTS]** | `DELETE /api/v1/conversations/{id}/invite-links/{inviteId}` (conversation-scoped, via `ChannelRights.can(canInviteUsers)`) |
| Abuse widget | **[PLANNED]** | Table per channel: links with use_count velocity (Δ/day), unlimited links (`max_uses` null) older than N days, non-expiring links, links feeding rejected join-requests. Platform-admin force-revoke **[PLANNED]** reuses the same `revoked` flag |

### 4.5 Live-now + viewer counts

| Item | Status | Detail |
|------|--------|--------|
| Live list | **[EXISTS]** | `GET /api/v1/streams/live` (`LiveStreamController`) — all LIVE ordered by viewerCount; `GET /api/v1/streams/{id}` for metadata |
| Viewer counts | **[EXISTS]** | `StreamViewerRepository.countByStreamIdAndActiveTrue(streamId)`, `findActiveViewerIds(streamId)`; durable `live_streams.viewer_count` / `peak_viewer_count` updated on `POST /api/v1/streams/{id}/join|leave`; `deactivateAll` on end |
| Per-user viewer trail | **[EXISTS]** | `StreamViewer` rows: stream_id, user_id, active, joined_at, left_at |
| Stage roster | **[EXISTS]** | `GET /api/v1/streams/{id}/stage` (`StreamStageController`); `StreamGuest`: status, muted (authoritative client-side flag), publish_path, publish_key (**hidden from admin UI**) |
| Admin filters (host / reports / stale-LIVE) | **[PLANNED]** | Join `live_streams` × `reports` × MediaMTX session liveness |

### 4.6 Gifts — the symbolic-gift economy

| Item | Status | Detail |
|------|--------|--------|
| Catalogue | **[EXISTS]** | `StreamGift` enum, ROSE(1)…TROPHY(100) coins; `GET /api/v1/streams/gifts/catalog`. **Symbolic score only** — no wallet, purchase, or payout |
| Per-stream leaderboard | **[EXISTS]** | `GET /api/v1/streams/{id}/gifts/top` → `StreamStageService.topSupporters` → `StreamGiftTallyRepository.findByStreamIdOrderByCoinsDescLastGiftAtDesc`; `StreamGiftTally`(stream_id+user_id unique, coins, gift_count, last_gift_at), atomic JPQL increment |
| Individual gift events | **[EXISTS]** (ephemeral) | Broadcast-only SSE, never stored — per-gift forensics impossible by design |
| Platform rollups | **[PLANNED]** | Cross-stream totals, top gifters platform-wide, coins/stream distribution — plain SQL over `stream_gift_tallies`. **If gifts ever monetize, this is the first gap to close** (plus per-user gifting history and admin reset) |

### 4.7 Recordings

| Item | Status | Detail |
|------|--------|--------|
| Storage service | **[EXISTS]** | `RecordingStorageService`: `hasRecording`, `listParts`, `totalBytes(streamId)`, `sizeOf(part)`, `modifiedAt(part)`, `resolvePart` (path-traversal guarded), `combineParts` (ffmpeg concat), `deleteRecording(streamId)` |
| Lifecycle | **[EXISTS]** | `RecordingStatus`: DISABLED / RECORDING / PAUSED / PROCESSING / AVAILABLE / EMPTY / DELETED on `live_streams.recording_status` + `recording_enabled` |
| Files | **[EXISTS]** | fMP4 parts at `{app.streaming.recordings-dir:./recordings}/<stream-uuid>/`; MediaMTX `recordDeleteAfter: 0s` — **the app owns retention, and no expiry job exists** |
| Host endpoints | **[EXISTS]** | `GET/DELETE /api/v1/streams/{id}/recording`, `GET …/recording/download`, `POST …/recording/start|stop` — all host-only (`hostId` ownership checks in `LiveStreamService`) |
| Admin fleet listing | **[EXISTS (built 2026-08)]** | `GET /api/v1/admin/streams/recordings` (step-up): per-stream bytes + part counts, **orphan-dir flags** (recordings dirs with no matching stream row), fleet disk total. Takedown/disk-dashboard UI remain the frontend build |

### 4.8 Calls (metadata only)

`CallSession` (conversation_id, initiator_id, type VOICE/VIDEO, status incl. MISSED,
started_at/answered_at/ended_at, end_reason) + `CallParticipant` (state, joined_at/left_at)
**[EXISTS]** in Postgres. `CallService.sweepMissed` (20s sweep) marks ring-timeouts
MISSED/`no_answer` → CALL_MISSED notification. **Derivable:** calls/day by type, answer
rate, missed rate, duration percentiles (`answered_at→ended_at`), ring-time, participants
per call. **No admin read endpoint exists** — stats API **[PLANNED]**. No content is ever
derivable: media is P2P; the server only relays signals (`POST /api/v1/calls/{callId}/signal`).

### 4.9 MediaMTX control plane — what `MediaControlClient` can really do

| Capability | Status | Detail |
|------------|--------|--------|
| `ensurePath(streamId, record)` | **[EXISTS]** | `POST /v3/config/paths/add` — per-stream path config incl. record flag |
| `pauseRecording` / `resumeRecording` | **[EXISTS]** | PATCH path config record flag mid-broadcast |
| `removeRecordingPath(streamId)` | **[EXISTS]** | `DELETE /v3/config/paths/delete/{id}` |
| `kickPublisher(path)` | **[EXISTS]** (WebRTC only) | Lists `/v3/webrtcsessions/list`, POSTs `/v3/webrtcsessions/kick/{id}` for `state=publish` sessions on the path. **Covers WHIP (browser) publishers only** |
| Kick RTMP publishers (OBS/ffmpeg via :1935) | **[PLANNED]** | Would need `/v3/rtmpconns/list` + `/v3/rtmpconns/kick/{id}` — not implemented; an OBS publisher survives today's `kickPublisher` |
| Kick viewers / close WHEP+HLS readers | **[PLANNED]** | Not implemented; playback of a LIVE path is public per the auth hook |
| Control API reachability | **[EXISTS]** | `:9997`, localhost-bound in docker-compose, `app.streaming.control-api-base`; excluded from the auth hook. Auth enforcement point: `POST /internal/media/auth/{secret}` (`MediaAuthController`) → `LiveStreamService.authorizeMediaAccess` — publish requires `?pass=` stream/guest key |

---

## 5. Aggregate-only zones

### 5.1 Groups & DMs — aggregates, never lists

The dashboard shows **counts and distributions only** over `conversations` /
`conversation_members` **[EXISTS]** via **[PLANNED]** SQL: conversations by type, group
size buckets, disappearing-mode adoption, growth curves. There is deliberately **no
"browse all DMs" view** — enumerating who talks to whom is surveillance metadata. Per-
conversation drill-down unlocks only from a report row (target_type MESSAGE/CHANNEL, →
[safety-reports.md](../trust-safety/safety-reports.md)) or a legal hold (§2.2), and even then shows
metadata unless the hold authorizes content.

### 5.2 Message-request quarantine stats

`MessageRequest` **[EXISTS]** (status PENDING/ACCEPTED/DECLINED/BLOCKED, first_message_id,
message_count) is recipient-controlled (`/api/v1/message-requests` + accept|decline|block).
Admin widget **[PLANNED]**: funnel rates, aging PENDING, and the platform's best organic
spam signal — **requesters with high BLOCKED+DECLINED counts across many distinct
recipients** → feed into [safety-reports.md](../trust-safety/safety-reports.md) triage as a signal source.

---

## 6. Admin actions

Convention (per [architecture.md](../foundation/architecture.md)): all new routes under
`/api/v1/admin/**` for the filter-chain double gate; every mutation writes an audit row;
step-up = re-auth via the settings module ([../settings/auth-sessions.md](../../settings/auth-sessions.md)).

| Action | Endpoint | Params | Danger | Step-up | Audit action | Status |
|--------|----------|--------|--------|---------|--------------|--------|
| Toggle channel verified | `PUT /api/v1/channels/{id}/verified` | `verified` bool | Low | No | via HTTP interceptor only today | **[EXISTS]** (gate caveat §4.1) |
| Relocated verified toggle | `POST /api/v1/admin/channels/{id}/verified` | `verified`, reason | Low | No | `CHANNEL_VERIFY_SET` | **[PLANNED]** |
| Browse conversations (metadata) | `GET /api/v1/admin/chat/conversations` | type, handle/title q, ownerId, page | Low (read) | No | `ADMIN_CHAT_BROWSE` | **[PLANNED]** — projection excludes `last_message_preview`, keys |
| Channel stats override | `GET /api/v1/admin/channels/{id}/stats` | — | Low (read) | No | `ADMIN_CHANNEL_STATS_READ` | **[PLANNED]** — reuses `ChannelStatsService` minus member gate; topPosts as ids+counts only |
| Force-revoke invite link | `POST /api/v1/admin/conversations/{id}/invite-links/{inviteId}/revoke` | reason | Medium | No | `INVITE_FORCE_REVOKED` | **[PLANNED]** — sets existing `revoked` flag |
| Freeze channel posting | `POST /api/v1/admin/channels/{id}/freeze` | reason, until? | High | Yes | `CHANNEL_FROZEN` | **[PLANNED]** — new flag checked in `ChannelRights.can` funnel |
| Channel takedown | `POST /api/v1/admin/channels/{id}/takedown` | reason, reportId? | **Critical** | **Yes** | `CHANNEL_TAKEDOWN` | **[PLANNED]** — sets `deleted_at` (today owner-only via `DELETE /api/v1/conversations/{id}`), de-indexes from `irc-channels`, notifies owner; reversible restore endpoint paired |
| Remove channel from discovery | `POST /api/v1/admin/channels/{id}/unlist` | reason | Medium | No | `CHANNEL_UNLISTED` | **[PLANNED]** — flips `is_public` (today owner-editable only via `PATCH /api/v1/channels/{id}`) |
| Call stats | `GET /api/v1/admin/calls/stats` | from, to, type | Low (read) | No | `ADMIN_CALL_STATS_READ` | **[PLANNED]** |
| Quarantine stats | `GET /api/v1/admin/message-requests/stats` | from, to | Low (read) | No | `ADMIN_MSGREQ_STATS_READ` | **[PLANNED]** |
| Live-now (admin view) | `GET /api/v1/admin/streams/live` | hostId?, reported?, stale? | Low (read) | No | `ADMIN_STREAMS_READ` | **[PLANNED]** — until built, reuse `GET /api/v1/streams/live` **[EXISTS]** |
| **Force-stop stream** | `POST /api/v1/admin/streams/{id}/force-stop` | reason, reportId? | **Critical** | **Yes** | `STREAM_FORCE_STOPPED` | **[PLANNED]** — `LiveStreamService.end` minus the `hostId` equality check, + `MediaControlClient.kickPublisher` on the stream path **and every active guest `publish_path`**, + `viewerRepo.deactivateAll`, + finalize recording. RTMP publishers survive until the RTMP kick lands (§4.9). Orphaned LIVE rows now have a manual fix — `POST /api/v1/admin/ops/streams/sweep-orphans` (built 2026-08) — though no scheduled cleanup exists |
| **Rotate/revoke stream key** | `POST /api/v1/admin/streams/{id}/rotate-key` | reason | High | **Yes** | `STREAM_KEY_ROTATED` | **[PLANNED]** — update `live_streams.stream_key` (never rotated today; enforcement already live via the `/internal/media/auth/{secret}` hook) + `kickPublisher` so the old key dies immediately. Response returns **nothing** — new key visible to host only. Guest keys: revoked implicitly on guest REMOVED / stream end (`guestRepo.removeAllActive`); per-guest admin revoke = stage-remove + kick |
| Remove stream guest | `DELETE /api/v1/admin/streams/{id}/stage/{userId}` | reason | High | Yes | `STREAM_GUEST_REMOVED` | **[PLANNED]** — host-only today (`StreamStageService.requireOwnedStream`) |
| List recordings + disk usage | `GET /api/v1/admin/streams/recordings` | hostId?, status?, minBytes? | Low (read) | **Yes** | audited | **[EXISTS (built 2026-08)]** — `live_streams` × `RecordingStorageService.totalBytes`: per-stream bytes/parts, orphan-dir flags, fleet total |
| Delete recording | `DELETE /api/v1/admin/streams/{id}/recording` | reason, reportId? | **Critical** | **Yes** | `RECORDING_DELETED_BY_ADMIN` | **[PLANNED]** — reuses `RecordingStorageService.deleteRecording`; irreversible (files are the only copy) |
| Legal hold open/approve/execute | `POST /api/v1/admin/chat/legal-holds` (+ `/{id}/approve` / `/reject` / `/execute`, `GET` list) | conversationId, reason (case ref) | **Critical** | **Yes** (both admins) | `LEGAL_HOLD_OPEN/APPROVE/REJECT/EXECUTE` | **[EXISTS (built 2026-08)]** — §2.2; the only content-access path (dual control, newest ≤500 messages) |

---

## 7. Logs surfaced in this section

Full catalog: [logs-audit.md](../platform/logs-audit.md). This section's log panel filters to:

| Log | Source | What this section shows |
|-----|--------|-------------------------|
| Admin action audit | Cassandra `audit_log_by_user` / `audit_log_by_resource` **[EXISTS]** (writers) | Every action from §6 — typed rows now written via `admin/support/AdminAuditor` → `AuditLogService.record` on every admin mutation (built 2026-08), on top of the generic HTTP interceptor rows |
| Live audit tail | `GET /api/v1/admin/audit/stream` (SSE) **[EXISTS]** | Real-time feed filtered to `/api/v1/channels/**`, `/api/v1/streams/**`, `/api/v1/admin/chat/**` paths |
| Reports (MESSAGE/CHANNEL/USER targets) | PG `reports` **[EXISTS]** | Chat-scoped report volume; triage lives in [safety-reports.md](../trust-safety/safety-reports.md) |
| Stream lifecycle trail | `live_streams` + `stream_viewers` + `stream_guests` rows **[EXISTS]** | Who hosted/joined/guested when; per-viewer joined_at/left_at |
| MediaMTX auth decisions | App log (`MediaAuthController` / `authorizeMediaAccess`) **[PARTIAL]** — console only, no store | **[PLANNED]**: count rejected publishes per key/IP as a brute-force signal |
| **Not loggable — say so in the UI** | Live-stream chat (never persisted), call content (never exists), individual gift events (broadcast-only) | The dashboard must render an explicit "no retroactive evidence exists" note on these |

---

## 8. Analytics & KPIs

| Metric | Definition | Source | Chart | Status |
|--------|------------|--------|-------|--------|
| Conversations by type | count(`conversations`) group by type, 30d trend | PG SQL | Stacked area | **[PLANNED]** query, **[EXISTS]** data |
| Channel subscriber growth | joinsByDay per channel; top-N channels by 30d net join | `ChannelStatsService` **[EXISTS]** / cross-channel SQL **[PLANNED]** | Line + leaderboard | **[PARTIAL]** |
| Verified channel coverage | verified=true / total public channels | PG SQL | Stat tile | **[PLANNED]** |
| Invite-link velocity | Δ`use_count`/day per link; flags unlimited+non-expiring | `conversation_invites` | Table + spark | **[PLANNED]** |
| Join-request approval rate | APPROVED/(APPROVED+REJECTED) 30d, per channel | `conversation_join_requests` | Bar | **[PLANNED]** |
| Message-request funnel | PENDING→ACCEPTED/DECLINED/BLOCKED rates | `message_requests` | Funnel | **[PLANNED]** |
| Quarantine spam index | requesters with ≥N BLOCKED across ≥M recipients, 7d | `message_requests` | Table | **[PLANNED]** |
| Call volume / answer rate | calls/day by type; answered/total; MISSED share | `call_sessions` | Line + stat tiles | **[PLANNED]** |
| Call duration p50/p95 | `ended_at−answered_at` percentiles | `call_sessions` | Box/percentile band | **[PLANNED]** |
| Concurrent live streams | count(status=LIVE) sampled | `live_streams` | Line (sampled) | **[PLANNED]** (no time-series store — needs sampling job) |
| Live viewers now / peak | Σ`viewer_count` live; `peak_viewer_count` per stream | `live_streams`, `StreamViewerRepository` **[EXISTS]** | Stat tile + histogram | **[PARTIAL]** |
| Stream duration | `ended_at−started_at` distribution | `live_streams` | Histogram | **[PLANNED]** |
| Unique viewers / stream | count(`stream_viewers`) per stream | `stream_viewers` | Histogram | **[PLANNED]** |
| Gifts per stream / top gifters | Σcoins, Σgift_count per stream; platform top-N | `stream_gift_tallies` | Leaderboard + bar | **[PARTIAL]** (per-stream **[EXISTS]** via `/gifts/top`; rollup planned) |
| Recordings disk usage | Σ`totalBytes` across AVAILABLE recordings; growth/day | `RecordingStorageService` + `live_streams` | Area + tile | **[PLANNED]** |
| Channel post reach | totalViews/totalForwards per channel (HLL-deduped) | Redis `chat:chtotals` via `ChannelStatsService` **[EXISTS]** | Bar | **[PARTIAL]** (best-effort, resets on Redis flush) |

**Honesty note (per [analytics-kpis.md](../platform/analytics-kpis.md)):** nothing in chat is
date-bucketed except `joinsByDay`; any "over time" chart beyond joins needs either SQL over
timestamp columns (calls, streams — fine) or a **[PLANNED]** sampling collector
(concurrent-live, viewers-now). Platform-wide message counts are **not computable** —
message rows live in per-conversation Cassandra partitions with no scan index, and
`conversations.post_count` covers channels only.

---

## 9. Alerts & thresholds

| Alert | Condition (tunable) | Source | Action |
|-------|---------------------|--------|--------|
| **Stale LIVE stream** | status=LIVE with 0 active viewers AND no MediaMTX publish session for > 30 min, or LIVE > 12 h | `live_streams` × `/v3/webrtcsessions/list` | Offer force-stop (§6) or run the **manual sweep** `POST /api/v1/admin/ops/streams/sweep-orphans` (step-up, dry-run capable — built 2026-08, see [operations.md](../platform/operations.md)); no *scheduled* cleanup job yet |
| Invite-link burst | use_count Δ > 100/h on one link, or one channel minting > 20 links/day | `conversation_invites` | Review + force-revoke |
| Join-request flood | > 200 PENDING/h on one channel | `conversation_join_requests` | Possible raid; freeze option |
| Quarantine spam | one requester BLOCKED by ≥ 10 distinct recipients / 24 h | `message_requests` | Escalate to [safety-reports.md](../trust-safety/safety-reports.md) |
| Missed-call anomaly | MISSED share > 60% platform-wide / 1 h | `call_sessions` | Signaling/SSE health check → [operations.md](../platform/operations.md) |
| Recordings disk | recordings dir > 80% of volume, or one stream > 20 GB | `RecordingStorageService.totalBytes` | Admin delete / retention run |
| Publish-auth failures | > 20 rejected `?pass=` attempts on one path / 10 min | media auth hook log **[PLANNED]** counter | Rotate key |
| Reported live stream | any open report with target = live host while status=LIVE | `reports` × `live_streams` | Pin to top of live-now board — a live stream is the only truly time-critical moderation object on the platform |

All thresholds are **[PLANNED]**; no alerting infrastructure exists in this subsystem today.

---

## 10. Permissions & safety notes

| Rule | Rationale |
|------|-----------|
| Everything here is ADMIN-only, new routes under `/api/v1/admin/**` | Filter-chain double gate; the existing verified toggle (§4.1) is the cautionary tale — annotation-only, open under `SECURITY_PERMIT_ALL=true` |
| Never select `stream_key` / `publish_key` / `last_message_preview` into admin DTOs | §2.3 — plaintext secrets and content leak edge |
| Content access ONLY via legal hold (dual admin, step-up, case ID, per-access audit) | §2.2; there is no other read path today and the build must not create one |
| Step-up auth on force-stop, key rotation, takedown, recording delete, legal hold | Irreversible or rights-impacting; [../settings/auth-sessions.md](../../settings/auth-sessions.md) step-up **[EXISTS]** |
| Every mutation writes a typed audit row (`AdminAuditor` → `AuditLogService.record` **[EXISTS (built 2026-08)]**) | HTTP-interceptor rows alone don't capture reason/reportId |
| Oversight, not substitution: admins monitor join requests / invites / stage but the conversation's own `ChannelRights` governance keeps operating | Preserves the module's self-governance model; admin powers are takedown-shaped, not management-shaped |
| Aggregate-only for DMs/groups; no browse-by-participant | §5.1 — who-talks-to-whom is sensitive metadata |
| Force-stop UI must disclose the RTMP gap until `/v3/rtmpconns/kick` lands | An OBS publisher is not actually disconnected by today's `kickPublisher` (§4.9) |
| Live chat & calls: no retroactive evidence — moderation must happen live | §7; set reporter expectations in the UI |

---

## 11. Build order / dependencies

| Phase | Deliverable | Depends on | Risk |
|-------|-------------|-----------|------|
| **1a** | Read-only browse: `GET /admin/chat/conversations` (safe projection), `GET /admin/channels/{id}/stats` override, calls + quarantine stats endpoints | Nothing new — SQL over existing tables; `ChannelStatsService` refactor to skip member gate for ADMIN | Zero (reads) |
| **1b** | Live-now admin board reusing `GET /api/v1/streams/live` + `StreamViewerRepository`; stream history queries; per-stream gift leaderboard reuse | Nothing new | Zero |
| **1c** | Relocate verified toggle under `/api/v1/admin/**` (alias, keep old path) | SecurityConfig untouched (double gate is automatic under the prefix) | Low |
| **2a** | **Force-stop** (`end` minus host check + `kickPublisher` host & guest paths + `deactivateAll` + finalize recording) + typed audit writes | 1b; `MediaControlClient` **[EXISTS]** | Medium — test recording finalization on forced end |
| **2b** | **Key rotation** (column update + kick), stream-guest admin remove | 2a | Medium |
| **2c** | Invite force-revoke, channel unlist/freeze, channel takedown + restore | 1a; freeze needs a new flag in the `ChannelRights.can` funnel | Medium-high (takedown reversibility) |
| **2d** | RTMP publisher kick in `MediaControlClient` (`/v3/rtmpconns/list|kick`) | 2a | Low |
| **3a** | Recordings manager: global listing, disk usage, orphan scan, admin delete, retention policy job | 1b | Medium (irreversible deletes) |
| **3b** | Sampling collectors (concurrent-live, viewers-now time series), publish-auth failure counter, alert rules §9 | 1a/1b; a metrics store decision from [analytics-kpis.md](../platform/analytics-kpis.md) | Low |
| **3c** | **Legal-hold process**: hold records, dual approval, scoped content readers over the four content stores, dedicated audit actions | Everything above; org-level policy sign-off | High — build last, gate hardest |

Cross-references: existing admin inventory in [architecture.md](../foundation/architecture.md) ·
endpoint master list in [admin-api-blueprint.md](../foundation/api-blueprint.md) · report triage in
[safety-reports.md](../trust-safety/safety-reports.md) · log catalog in [logs-audit.md](../platform/logs-audit.md) ·
MediaMTX/infra runbooks in [operations.md](../platform/operations.md).
