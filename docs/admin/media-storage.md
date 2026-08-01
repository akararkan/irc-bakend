# Media & Storage — Admin Dashboard Section

Pipeline & storage operations for the admin dashboard. Builds on the media
pipeline spec in [../settings/messaging-media.md](../settings/messaging-media.md)
(§15 storage report, §20 upload/quality/compression) and the config reference in
[../settings/config.md](../settings/config.md). Sibling sections: sound library
*approval* lives in [content-moderation.md](content-moderation.md); queue/DLQ and
dependency health in [operations.md](operations.md); the full log catalog in
[logs-audit.md](logs-audit.md); metric conventions in
[analytics-kpis.md](analytics-kpis.md).

| Tag | Meaning |
|-----|---------|
| **[EXISTS]** | Real today — class or `METHOD /path` cited |
| **[PARTIAL]** | Partly real; the gap is stated |
| **[PLANNED]** | Proposed for the dashboard build — not yet coded |

---

## 1. Purpose & scope

One place for an admin to answer: **is the media pipeline healthy, where do the
bytes live, who is consuming them, and what is stuck or leaking?** Covers the
`media/` module (assets, renditions, worker, tiers, dedup), R2 object layout and
lifecycle, per-user storage accounting, the *three separate media paths* that
exist today (an honest map + convergence plan), upload quotas, and transcode
operations (ffmpeg, worker pool, RabbitMQ media queues).

Out of scope: live-stream recordings on local disk (`{app.streaming.recordings-dir}`)
and the MediaMTX plane — see [chat-channels-live.md](chat-channels-live.md);
sound *moderation* — see [content-moderation.md](content-moderation.md).

---

## 2. The pipeline today (ground truth)

**[EXISTS]** Package `ak.dev.irc.app.media`. Flow (spec §20.4):

1. `POST /api/v1/media/upload-intent` **[EXISTS]** (`MediaUploadController`) —
   body `{mime, sizeBytes, sha256, type}` + header `X-Media-Tier`. Validates the
   declared size against the type cap, **dedups on `sha256`**, else creates a
   `PENDING` `media_assets` row and returns a **30-min presigned PUT** to key
   `raw/{assetId}` (`S3StorageService.presignPut`).
2. Client PUTs bytes **directly to R2** (never through the JVM).
3. `POST /api/v1/media/{id}/complete` **[EXISTS]** — flips `PENDING→PROCESSING`,
   downloads `raw/{id}`, submits to `MediaProcessingService`. Idempotent: any
   non-`PENDING` status is a silent no-op (this is also why there is **no retry
   path** today — see §5).
4. Worker: scan (`MediaScanner`) → transcode (`ImageProcessor` /
   `VideoProcessor`) → upload renditions under `media/{assetId}/…` → accumulate
   `stored_bytes` → `READY` + `purge_original_at = now + 7d`.
5. `GET /api/v1/media/{id}` status polling; `DELETE /api/v1/media/{id}` owner
   delete. All owner-gated (`NOT_MEDIA_OWNER`).

**Status machine** **[EXISTS]** (`media/enums/MediaStatus.java`):
`PENDING → UPLOADING → PROCESSING → READY | FAILED_VALIDATION |
FAILED_MODERATION | FAILED_PROCESSING` (`isTerminalFailure()`).

| Status | Reachable today? | Notes |
|--------|-----------------|-------|
| PENDING | yes | intent created, client not done (or abandoned — see §7 leaks) |
| UPLOADING | **no — declared but never set** | `complete()` jumps `PENDING→PROCESSING` directly |
| PROCESSING | yes | worker owns it |
| READY | yes | terminal success |
| FAILED_VALIDATION | yes | bad/corrupt image (`ImageProcessor` throws), or raw object missing at complete (`MEDIA_RAW_MISSING`) |
| FAILED_MODERATION | **unreachable in practice** | only `MediaScanner.AllowAllScanner` is registered — always clean |
| FAILED_PROCESSING | rare | unexpected worker exception (e.g. rendition upload failed); video transcode failure does **not** land here — it falls back to passthrough |

**Tiers** **[EXISTS]** (`MediaTier`, column `requested_tier`, default `HIGH`,
lenient parse): `DATA_SAVER` (img 1080 / vid 480p), `STANDARD` (1440 / 720p),
`HIGH` (1920 / 1080p). Tier can only lower quality; cap `min(tier, platform cap)`.

**1080 hard cap** **[PARTIAL]** — `MediaProperties.video.maxShortEdge = 1080`
("do not raise") is enforced by the ffmpeg scale filter in `VideoProcessor`…
**only when `media.processing.enabled=true` and ffmpeg is present**. The default
is `false` (`MEDIA_TRANSCODE_ENABLED:false`), so today video is stored
**passthrough at original resolution** (sole rendition labeled `original`,
`width`/`height` left `null`). The cap is policy, not yet fact — the dashboard
must show this honestly (§4 Transcode Ops widget, §8 KPI `passthrough_share`).

**Dedup** **[EXISTS]** — `MediaAssetRepository.findFirstByContentHashAndStatus(sha256, READY)`;
a hit creates a *reference row* (`storedBytes = 0` so bytes are never
double-counted) plus copied `media_renditions` rows pointing at the **same
`object_key`s**. See §7 for the delete/refcount hazard this creates.

**Tables** **[EXISTS]**: `media_assets` (`MediaAsset` — indexes
`idx_media_owner(owner_id, created_at)`, `idx_media_hash(content_hash)`,
`idx_media_status(status)`; columns incl. `type`, `status`, `content_hash`,
`original_bytes`, `stored_bytes`, `width/height/duration_ms`, `requested_tier`,
`blurhash`, `mime`, `error_message`, `purge_original_at`) and `media_renditions`
(`MediaRendition` — composite PK `(media_id, label)`, `object_key`, `url`,
`bytes`, `width/height`, `mime`).

**Types & caps** **[EXISTS]** (`MediaAssetType`, `MediaProperties.limits`):
IMAGE (25 MB, long edge 1920, 100 MP bomb guard, EXIF/GPS stripped), VIDEO /
FILM / VIDEO_CLIP (512 MB cap in code — the per-type FILM 10 min / CLIP 90 s
200 MB limits are javadoc'd intent, **not enforced**), AUDIO (stored as-is,
`application/octet-stream` — Opus transcode is a declared seam).

---

## 3. The three media paths (honest map)

There is **no single media pipeline** today. Three disjoint paths hold bytes or
references; only Path B does accounting. Everything below is **[EXISTS]** —
this table is the map the whole section is built on.

| Path | Who uses it | How bytes move | R2 prefix(es) | `media_assets` row? | `stored_bytes` counted? | Scan? | Re-encode / cap? | Dedup? |
|------|------------|----------------|---------------|--------------------|------------------------|-------|------------------|--------|
| **A — legacy direct multipart** (`S3StorageService.upload(file, prefix)` through the JVM; 500 MB multipart cap) | posts (`CassandraFeedController` → `posts/media`), stories (`CassandraStoryController` → `stories/media`, `stories/thumb`), chat DMs/groups (`MessageController` → `chat/media`), channels (`ChannelService` → `chat/channel`), avatars/covers (`UserProfileServiceImpl` → `users/avatars/{userId}`, `users/covers/{userId}`), QnA (`QuestionServiceImpl` → `qna/{questionId}/answers/inline|voice`, `…/{answerId}/attachments|sources`), research (`ResearchServiceImpl` → `research/{researchId}/media`, `research/comments/voice|media`) | multipart POST → app server → R2 | see left | **no** | **no** | no | no — originals stored as uploaded | no |
| **B — new media pipeline** (`media/` module, §2) | **no domain surface consumes it yet** — the API is live but posts/chat/etc. still use Path A; its only downstream consumer is the storage report (`StorageUsageService`) | presigned PUT direct to R2 → worker renditions | `raw/{assetId}`, `media/{assetId}/…` | yes | yes | hook (AllowAll) | yes (when transcode on) | yes (sha256) |
| **C — sounds: URL reference only** | sound library (`CassandraSoundController.upload` — request body carries `audioUrl` / `coverArtUrl` **strings**) | **no byte custody at all** — the platform stores whatever URL the client sends (often a Path-A proxy URL, possibly external) | none of its own | no | no | no | no | no |

Adjacent stores that are *not* R2: live recordings on local disk
(`{app.streaming.recordings-dir}/<stream-id>/*.mp4` — [chat-channels-live.md](chat-channels-live.md));
personal-data **export ZIPs on app-host temp files** (`DataExportService` →
`Files.createTempFile("irc-export-{jobId}.zip")`, path in `export_jobs.file_path`)
— **not** an R2 `export/` prefix, and no cleanup job despite `expires_at`
**[PARTIAL]**.

**Consequences the dashboard must not hide:** per-user storage usage
(`stored_bytes SUM`) counts **only Path B**, i.e. today ≈ nothing — avatars,
post/story/chat/research/QnA media are all invisible to quota/usage math; chat
media is unscanned and unrecompressed; sounds may point at bytes the platform
does not control.

### Convergence proposal [PLANNED]

| Phase | Move | Mechanics |
|-------|------|-----------|
| C1 | New **post/story/reel** uploads → Path B | client does intent→PUT→complete, then passes `assetId`s to the post create API; keep Path A read compatibility (old keys keep serving via the proxy) |
| C2 | **Chat** → Path B | `MediaRef.storageKey` is already proxy-key-shaped (`GET /api/v1/media/{key}` with Range support) — point it at pipeline rendition keys; disappearing-message TTL applies to the *reference*, delete-all-renditions rides `irc.queue.media.delete` |
| C3 | **Avatars/covers** → Path B with `profileEdge=512` rendition (`MediaProperties.image.profileEdge` already exists) |
| C4 | **Sounds** → real upload: intent with `type=AUDIO`, approval flow keeps gating visibility ([content-moderation.md](content-moderation.md)) |
| C5 | **Research/QnA attachments** last (PDF/doc types need a `DOCUMENT` asset type + validation) |
| C6 | Backfill: reconcile job (§5 A9) imports legacy Path-A keys into `media_assets` rows with `stored_bytes` from object HEAD, so usage/quota math becomes whole-platform |

---

## 4. Dashboard views / widgets

**View 1 — Pipeline board** (landing view)

| Widget | On screen |
|--------|-----------|
| Status funnel | Count tiles per `MediaStatus` (PENDING / PROCESSING / READY / 3×FAILED), 24h delta each; UPLOADING tile hidden until it becomes reachable |
| Failed-media queues | Three tabs — FAILED_VALIDATION, FAILED_MODERATION (empty today, AllowAllScanner note inline), FAILED_PROCESSING — each a table: asset id, owner, type, mime, `error_message`, age; row actions **Inspect** / **Retry** / **Delete** (§5) |
| Stuck detector | PENDING older than 1 h (abandoned intents) and PROCESSING older than 30 min (worker died mid-asset) — both are leak sources, §7 |
| Processing latency | p50/p95 `updated_at − created_at` of assets reaching a terminal status, 7-day sparkline (proxy metric — see §6 caveat) |
| Dedup hit-rate | READY rows with `stored_bytes = 0` ÷ all READY rows, trended; bytes saved estimate = Σ `original_bytes` of reference rows |
| Tier distribution | Donut of `requested_tier` over last 30 days of assets |
| 1080-cap compliance | Banner: current `media.processing.enabled` value + ffmpeg availability; % of READY video assets with `min(width,height) ≤ 1080`; **`width IS NULL` = passthrough/unverified** bucket shown explicitly |

**View 2 — Storage**

| Widget | On screen |
|--------|-----------|
| Global totals | Platform `SUM(stored_bytes)` + per-`type` split (IMAGE/VIDEO/AUDIO/FILM/VIDEO_CLIP); big-number tiles + stacked bar. Caveat badge: "Path B only" (§3) |
| Growth trend | Daily Σ `stored_bytes` of assets by `created_at` day, 90-day line |
| Top consumers | Top-50 owners by `SUM(stored_bytes)` with asset counts; click-through to the user in [users-roles.md](users-roles.md) |
| R2 layout | Read-only prefix map (the §3 table) with object-count/bytes per prefix once reconciliation (A9) exists; until then shows "unknown — DB-derived only" for Path A prefixes |
| raw/ purge | Backlog count `purge_original_at < now` (rows whose original is *due* for deletion), last sweep time/result — **red state today: the purge job does not exist** (§7) |
| Export & recordings | Pointer tiles: export ZIP count/bytes on app host (`export_jobs`), recordings dir size — links to owning sections |

**View 3 — Transcode ops**

| Widget | On screen |
|--------|-----------|
| Flags | `media.processing.enabled` (env `MEDIA_TRANSCODE_ENABLED`, default **false**), `ffmpegBin`/`ffprobeBin`, timeout 600 s — read-only from config |
| ffmpeg probe | Live result of the availability check (`ffmpeg -version`, 5 s wait — same logic as `VideoProcessor.ffmpegAvailable()`) |
| Worker pool | `MediaProcessingService` executor: fixed 2 threads, queue cap 64, CallerRunsPolicy; gauges for active/queued + **caller-runs saturation counter** (saturation = transcodes running on API request threads = user-visible latency) |
| RabbitMQ media queues | `irc.queue.media.process` / `irc.queue.media.delete` depth + consumer count (consumer count is **0 by design today** — declared+bound with DLX + 24 h TTL, routing keys `media.process.requested`/`media.delete.requested`, **no publisher, no consumer**: the in-process executor is the documented seam). Link: [operations.md](operations.md) |
| Passthrough share | % of video renditions labeled `original` vs `1080p` — the "is the cap actually being enforced" number |

---

## 5. Data sources & admin actions

### Data sources (per widget)

| Widget | Source | Status |
|--------|--------|--------|
| Status funnel, failed queues, stuck detector | `media_assets` (`idx_media_status`); new `COUNT … GROUP BY status` + status/age browse queries | **[PARTIAL]** table + index exist; the queries and any admin endpoint do not |
| Latency, tier, dedup, cap compliance | `media_assets` columns (`created_at/updated_at`, `requested_tier`, `stored_bytes=0` proxy, `width/height`) | **[PARTIAL]** columns exist, no aggregation endpoint |
| Per-user usage | `MediaAssetRepository.sumStoredBytes(ownerId)` / `sumStoredBytesByType(ownerId)` via `StorageUsageService` (Redis-cached 1 h, key `storage:usage:{userId}`) | **[EXISTS]** |
| Platform totals / top consumers / growth | same table, owner-unfiltered `SUM` + `GROUP BY owner_id` + `GROUP BY day` | **[PLANNED]** — the unfiltered queries are not written (recon-confirmed) |
| raw/ purge backlog | `MediaAssetRepository.findByPurgeOriginalAtBefore(cutoff)` | **[PARTIAL]** query **exists with zero callers** — no job, no endpoint |
| Transcode flags | `MediaProperties` (`media.*` config block) | **[EXISTS]** (needs a read-only exposure endpoint) |
| Worker pool gauges | private `ThreadPoolExecutor` in `MediaProcessingService` | **[PLANNED]** — not exposed; needs a getter or Micrometer binding (note: `/actuator/prometheus` is not web-exposed today — see [operations.md](operations.md)) |
| Queue depths | RabbitMQ management API / `RabbitAdmin.getQueueInfo` | **[PLANNED]** exposure; queues themselves **[EXISTS]** (`RabbitMQConfig`) |
| R2 per-prefix object stats | R2 `ListObjectsV2` walk | **[PLANNED]** — deliberately absent today (LIST is slow and billed; §15 mandates DB-derived sums) — only via the A9 reconcile job, never on page load |

### Admin actions

All **[PLANNED]** unless tagged; all under `/api/v1/admin/media/**` so the
filter-chain double gate applies; every mutation writes an audit row
(`AuditLogService.record` — the currently-uncalled business-event writer, see
[logs-audit.md](logs-audit.md)).

| Action | Endpoint (proposed) | Params | Danger | Step-up? | Audit action |
|--------|--------------------|--------|--------|----------|--------------|
| Browse assets | `GET /api/v1/admin/media/assets` | `status`, `type`, `ownerId`, `from`, `to`, keyset page | Low | no | `MEDIA_ADMIN_LIST` |
| Inspect asset | `GET /api/v1/admin/media/assets/{id}` | — (metadata + renditions + `error_message`; content preview is a separate click, §9) | Low | no | `MEDIA_ADMIN_INSPECT` |
| Retry failed | `POST /api/v1/admin/media/assets/{id}/retry` | only `FAILED_PROCESSING`/`FAILED_VALIDATION`; 410 if `raw/{id}` already purged/missing | Medium | no | `MEDIA_ADMIN_RETRY` |
| Admin delete (takedown) | `DELETE /api/v1/admin/media/assets/{id}` | `reason` required | **High** | **yes** | `MEDIA_ADMIN_DELETE` |
| Run raw purge now | `POST /api/v1/admin/media/purge-raw/run` | `dryRun` (default true) | Medium | no | `MEDIA_RAW_PURGE_RUN` |
| Storage summary | `GET /api/v1/admin/media/storage/summary` | — | Low | no | — (read) |
| Top consumers | `GET /api/v1/admin/media/storage/top-consumers` | `limit≤100` | Low | no | — (read) |
| Ops snapshot | `GET /api/v1/admin/media/ops` | flags + ffmpeg probe + pool + queue depths | Low | no | — (read) |
| Reconcile orphans | `POST /api/v1/admin/media/reconcile` | `prefix`, `dryRun` (default true), `maxObjects` | **High** (deletes objects when `dryRun=false`) | **yes** | `MEDIA_RECONCILE_RUN` |
| Quota read/set | `GET/PUT /api/v1/admin/media/quotas` | per-role daily count + bytes budgets | Medium | yes (PUT) | `MEDIA_QUOTA_CHANGE` |
| Toggle transcode | *deliberately not offered* | flipping `media.processing.enabled` at runtime changes cap enforcement silently; keep it an env change with deploy trail | — | — | — |

**Quotas [PLANNED]** — reuse the existing Redis fixed-window `RateLimiter`
(`rl:{action}:{actorId}:{bucket}`, the same primitive gating reports at 20/h):
`media:upload-intent` N/day per user (count quota, one line in
`MediaAssetService.uploadIntent`), plus a bytes/day budget checked against
`SUM(original_bytes) WHERE owner_id=? AND created_at >= today` (indexed by
`idx_media_owner`). Suggested defaults: USER 100 uploads / 2 GB·day, RESEARCHER/
SCHOLAR 200 / 8 GB·day, ADMIN unlimited. Quota errors reuse the envelope in
[../errors/error-handling.md](../errors/error-handling.md) (`429 MEDIA_QUOTA_EXCEEDED`).

---

## 6. Logs surfaced in this section

| Log | Store | What this section shows | Status |
|-----|-------|------------------------|--------|
| `media_assets.error_message` (≤300 chars) + status | PG | inline in failed queues — the pipeline's own error trail | **[EXISTS]** |
| App-log tags `[MEDIA]`, `[MEDIA-VID]` | console (no file appender) | worker completions, transcode fallbacks ("transcode disabled/unavailable — passthrough"), rendition-delete warnings | **[EXISTS]** (not queryable — see [logs-audit.md](logs-audit.md)) |
| Request audit rows for `/api/v1/media/**` | Cassandra `audit_log_by_user` / `audit_log_by_resource` | per-user upload activity (intent/complete/delete, `UPLOAD` operation), duration_ms | **[EXISTS]** via `GET /api/v1/admin/audit?userId=…` |
| `[RABBIT-DLQ]` drain lines | console | future poison messages once media consumers exist | **[EXISTS]** (mechanism), **[PLANNED]** (relevance) |
| Admin media actions | audit module | every action in §5 writes an `AuditLog` business event | **[PLANNED]** |

---

## 7. Known leaks, hazards & debts (surface these on the board)

| # | Issue | Evidence | Severity |
|---|-------|----------|----------|
| L1 | **raw/ purge job missing** — `purge_original_at` is set on READY (+7 d) and `findByPurgeOriginalAtBefore` exists, but **nothing calls it**: originals accumulate in `raw/` forever | `MediaAssetRepository` (zero callers, recon-confirmed) | High (cost) |
| L2 | **Abandoned intents leak** — client PUTs to `raw/{id}` but never calls `complete`: row stays PENDING with `purge_original_at = NULL`, so even a future L1 job won't catch it. Purge sweep must also cover `PENDING AND created_at < now − 7d` | `MediaAssetService.uploadIntent`/`complete` | High (cost) |
| L3 | **Dedup delete hazard** — reference rows share `object_key`s with the source asset; `MediaAssetService.delete` deletes those objects from storage. Whichever owner deletes first breaks every other referrer's renditions (dangling URLs). Fix: refcount object keys (count rendition rows per `object_key` before `storage.delete`) — prerequisite for the admin-delete action | `MediaAssetService.dedupReference` + `delete` | High (correctness) |
| L4 | **Cap not enforced when transcode is off** — default config stores video passthrough at source resolution; 1080 is aspiration until `MEDIA_TRANSCODE_ENABLED=true` + ffmpeg ships in the runtime image | `VideoProcessor.process` | Medium (policy) |
| L5 | **Moderation scan is a no-op** — `AllowAllScanner`; FAILED_MODERATION queue will stay empty until a real scanner is registered | `MediaScanner` | Medium |
| L6 | **Crash-orphaned objects** — delete order is objects→rows by design ("a crash orphans an object — cheap, *nightly-reconciled*" per the service javadoc), **but no reconcile job exists**; likewise PROCESSING assets orphaned by an app restart stay PROCESSING forever | `MediaAssetService.delete` javadoc | Medium |
| L7 | **Export ZIPs never cleaned** on app-host temp storage despite `export_jobs.expires_at` | `DataExportService` | Medium |
| L8 | Path A entirely un-accounted (no rows, no bytes, no scan) — quota/usage numbers are misleading until convergence §3 | — | Structural |

---

## 8. Analytics & KPIs

| Metric | Definition | Source | Chart | Status |
|--------|-----------|--------|-------|--------|
| `media_assets_by_status` | `COUNT GROUP BY status` (point-in-time) | `media_assets` | stat tiles + stacked bar | **[PLANNED]** query |
| `media_throughput_daily` | assets reaching READY per day | `media_assets` (`updated_at` day, status READY) | line | **[PLANNED]** |
| `media_failure_rate` | terminal failures ÷ terminal outcomes, split by failure status & `type` | `media_assets` | line, per-type series | **[PLANNED]** |
| `processing_latency_p50/p95` | `updated_at − created_at` for terminal assets. *Caveat: includes client upload dwell between intent and complete; honest fix is dedicated `processing_started_at/ended_at` columns* | `media_assets` | percentile sparkline | **[PLANNED]** (proxy now, columns later) |
| `dedup_hit_rate` | READY rows with `stored_bytes=0` ÷ READY rows. *Proxy: no explicit reference flag exists — a `dedup_of` column would make this exact* | `media_assets` | line | **[PLANNED]** |
| `dedup_bytes_saved` | Σ `original_bytes` over reference rows | `media_assets` | big number | **[PLANNED]** |
| `stored_bytes_total` / `by_type` | owner-unfiltered `SUM(stored_bytes)` (+ `GROUP BY type`) | new repo query (per-user variant **[EXISTS]**: `sumStoredBytes`) | tiles + stacked area | **[PLANNED]** |
| `storage_growth_daily` | Σ `stored_bytes` by `created_at` day | `media_assets` | line | **[PLANNED]** |
| `top_consumers` | `SUM(stored_bytes) GROUP BY owner_id ORDER BY DESC LIMIT 50` | `media_assets` (`idx_media_owner`) | table | **[PLANNED]** |
| `tier_distribution` | share of `requested_tier` per period | `media_assets` | donut | **[PLANNED]** |
| `passthrough_share` | renditions labeled `original` ÷ all video renditions | `media_renditions` | line | **[PLANNED]** |
| `cap_compliance` | READY videos with `min(width,height) ≤ 1080` ÷ READY videos with non-null dims; null-dim bucket reported separately | `media_assets` | gauge + bucket bar | **[PLANNED]** |
| `raw_purge_backlog` | `COUNT(purge_original_at < now)` + PENDING older than 7 d | `media_assets` | gauge | **[PLANNED]** |
| `worker_saturation` | caller-runs executions per hour; queue depth | `MediaProcessingService` pool (needs instrumentation) | line | **[PLANNED]** |
| `storage_usage_cache_hit` | Redis `storage:usage:{userId}` hit ratio | `StorageUsageService` | line | **[PLANNED]** (nice-to-have) |

---

## 9. Alerts & thresholds

| Alert | Condition (suggested) | Why |
|-------|----------------------|-----|
| Failed queue growing | any FAILED_* count +20 in 1 h, or FAILED_VALIDATION > 5% of intents | bad client build, corrupt-upload wave, or storage misconfig (`MEDIA_RAW_MISSING`) |
| Stuck PROCESSING | any asset PROCESSING > 30 min | worker death / restart orphan (L6) — pool has no recovery |
| Abandoned PENDING | PENDING > 1 h count > 50/day | client flow broken; raw/ leak accelerating (L2) |
| Worker saturation | caller-runs > 0 in 15 min window | transcodes running on API threads — user latency |
| raw/ backlog | `raw_purge_backlog` > 1 000 or backlog age > 14 d | purge job down (or still unbuilt, L1) |
| Storage growth spike | daily growth > 3× 30-day median, or single owner > 10% of daily growth | abuse / runaway client |
| Cap regression | `passthrough_share` rises after transcode was enabled | ffmpeg missing from image; `ffmpegAvailable()` failing silently per-run |
| Media DLQ | messages appear in `irc.queue.dead-letter` with media type ids | future consumers poisoning (see [operations.md](operations.md)) |
| Dedup dangling refs | reconcile dry-run finds rendition rows whose `object_key` is absent in R2 | L3 fired in the wild |

---

## 10. Permissions & safety notes

- Every endpoint here lives under `/api/v1/admin/media/**` → **double-gated**
  (`SecurityConfig` filter-chain `hasRole('ADMIN')` + `@PreAuthorize`) — the
  platform convention ([architecture.md](architecture.md)). No phantom roles
  (`MODERATOR`/`SUPER_ADMIN`) in new annotations.
- **Content vs metadata:** the asset browser shows metadata by default; actually
  *viewing* a user's media is a content access — log it distinctly
  (`MEDIA_ADMIN_CONTENT_VIEW`) and gate the preview behind a click-through with
  reason capture, mirroring the chat privacy boundary in
  [chat-channels-live.md](chat-channels-live.md). Note today's boundary quirk:
  chat media is Path A, so it does **not** appear in `media_assets` — this
  dashboard cannot browse DM attachments, and convergence (C2) must add an
  explicit exclusion or the same click-through gate before it silently could.
- Never render presigned URLs in the dashboard; admin preview streams through
  the proxy (`GET /api/v1/media/{key}` **[EXISTS]**, Range-capable) with the
  admin's own audited request.
- Admin delete must be refcount-safe (L3 fix first) and must write both the
  audit row and, when moderation-motivated, link the `report_id`
  ([safety-reports.md](safety-reports.md), `Resolution.CONTENT_REMOVED`).
- Reconciliation deletes only on explicit `dryRun=false` + step-up; R2 LIST
  walks are bounded (`maxObjects`) and run async — never on page load.
- `STORAGE_UNAVAILABLE` (No-op storage backend) is a config state, not an error
  storm — the ops snapshot should show which `S3StorageService` impl is active
  (`CloudflareR2StorageService` vs `NoOpS3StorageService`).

---

## 11. Build order / dependencies

| Step | What | Depends on | Risk |
|------|------|-----------|------|
| 1 | **Read-only queries + endpoints**: status counts, platform SUMs, top consumers, growth, tier/dedup/cap metrics, ops snapshot (flags + ffmpeg probe + pool gauges via a small stats getter) | nothing — new SELECTs over existing indexed columns | zero |
| 2 | **raw/ purge job** (fixes L1+L2): scheduled sweep calling `findByPurgeOriginalAtBefore` + the abandoned-PENDING clause → `storage.delete("raw/"+id)`, null the marker / mark PENDING rows failed; expose last-run status + manual `purge-raw/run` | step 1 (backlog visibility) | low |
| 3 | **Refcount-safe delete** (fixes L3): guard `storage.delete` on rendition-row count per `object_key`; applies to owner delete too | none | medium (touches live path) |
| 4 | **Failed-queue console**: browse + inspect + retry + admin delete, with audit rows | steps 1, 3 | medium |
| 5 | **Orphan reconciliation** (L6): async R2-LIST vs DB diff job, dry-run report → gated delete; doubles as the Path-A prefix census for the R2 layout widget | step 2 | medium (billed LISTs) |
| 6 | **Quotas** via `RateLimiter` + bytes-budget check in `uploadIntent`; admin quota endpoints | step 1 baselines | low |
| 7 | **Rabbit consumer migration**: publish `media.process.requested` from `complete()`, consume in a `@RabbitListener` worker (retry/DLQ for free, multi-instance safe); wire `media.delete.requested` for rendition cleanup; add `processing_started_at/ended_at` for honest latency | steps 2–4 | medium |
| 8 | **Real `MediaScanner`** (unlocks FAILED_MODERATION queue) + step-up-gated moderation preview | step 4 | medium |
| 9 | **Convergence C1–C6** (§3) — the big one; each phase independently shippable | steps 3, 5, 7 | high, staged |

Steps 1–2 are safe week-one work and immediately give the dashboard real data;
nothing in this section blocks on other dashboard sections.
