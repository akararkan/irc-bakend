# Admin API Reference — Sounds, Media & Storage

Complete request/response reference for three controllers:

| Controller | Base path | Concepts |
|---|---|---|
| `AdminSoundController` | `/api/v1/admin/sounds` | [Sound library](../sound-library.md) |
| `AdminMediaController` | `/api/v1/admin/media` | [Media & storage](../media-storage.md) |
| `AdminStorageController` | `/api/v1/admin/storage` | [Media & storage](../media-storage.md) |

UI wiring lives in the [frontend dashboard guide](../frontend-dashboard-guide.md).

**Conventions used throughout:**

- **Auth.** Bearer JWT. Sound endpoints accept role `ADMIN` **or** `MODERATOR` (class-level `@PreAuthorize`); all media and storage endpoints are `ADMIN`-only. A missing/expired token is `401`; a wrong role is `403`. Errors arrive in the canonical envelope (`errorCode` is the switch key) — see [frontend error handling](../../errors/frontend-error-handling.md).
- **Step-up.** Endpoints marked **step-up** additionally require a fresh re-auth marker (`stepup:{userId}` in Redis), armed via `POST /api/v1/security/step-up`. Absent/expired marker → `403 STEP_UP_REQUIRED`.
- **Serialization.** `AdminSoundRow`, `AdminSoundDetail` and `AdminMediaRow` are `@JsonInclude(NON_NULL)` — null fields are **omitted**, not sent as `null`. Sound timestamps are `Instant` (`"2026-08-05T14:30:00Z"`); media timestamps are `LocalDateTime` without zone (`"2026-08-06T09:15:00"`).
- **Page sizes.** Every `pageSize`/`size`/`limit`/`top` is clamped to **1–100** (`Pages.clamp`).
- The `@Size` caps quoted on request-body fields are the declared limits on the DTO records; the handlers do not run bean validation on them, so treat them as contract, not as a guaranteed `400`.

---

## Sound queue & detail

The review queue and library browse are served from the sound search index (Elasticsearch), hydrated per-id from Cassandra `sounds_by_id` — eventually consistent by design, and a row deleted between index and hydration is silently dropped (a page may come back shorter than `pageSize`).

**`AdminSoundRow`** (all list/detail/edit/import responses; `NON_NULL` — e.g. `artistName`, `coverArtUrl`, `official` are absent when unset):

```json
{
  "id": "8f2b6c1e-4a3d-4e5f-9a7b-2c1d3e4f5a6b",
  "title": "Ya Nabi Salam Alayka",
  "artistName": "Maher Zain",
  "category": "NASHEED",
  "status": "PENDING_REVIEW",
  "durationSeconds": 45,
  "uploaderId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  "official": false,
  "trendingExcluded": false,
  "audioUrl": "https://cdn.example.com/sounds/8f2b6c1e.mp3",
  "coverArtUrl": "https://cdn.example.com/sounds/8f2b6c1e.jpg",
  "useCount": 132,
  "createdAt": "2026-08-05T14:30:00Z",
  "updatedAt": "2026-08-05T14:30:00Z"
}
```

`status` ∈ `PENDING_REVIEW | APPROVED | REJECTED | ARCHIVED` (`SoundStatus`). `category` ∈ `NASHEED | QURAN_RECITATION | LECTURE_CLIP | NATURE | ORIGINAL | PLATFORM_MUSIC` (`SoundCategory`). `useCount` is the monotonic adoption counter.

### GET /api/v1/admin/sounds

Review queue / status-filtered library browse.

**Access**: `ADMIN` or `MODERATOR`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `status` | `SoundStatus` | `PENDING_REVIEW` | Case-insensitive. |
| `uploaderId` | UUID | — | Optional filter. |
| `cursor` | ISO date-time | — | Keyset cursor on `createdAt` (interpreted as UTC): pass the last row's `createdAt`, e.g. `2026-08-05T14:30:00`. |
| `pageSize` | int | `20` | Clamped 1–100. |

Ordering: **oldest-first** for `PENDING_REVIEW` (queue order), newest-first for every other status.

**Request body**: None.

**Response**: `200` — array of `AdminSoundRow` (see above), e.g. `[ { "id": "8f2b6c1e-…", "title": "Ya Nabi Salam Alayka", "status": "PENDING_REVIEW", … } ]`.

**Errors**
- `INVALID_STATUS` — 400 — `status` is not a `SoundStatus` (message lists the allowed values).

### GET /api/v1/admin/sounds/status-counts

Per-status document counts for the library-overview tiles.

**Access**: `ADMIN` or `MODERATOR`.

**Request body**: None.

**Response**: `200` — fixed key order; a value of `-1` is a sentinel meaning the ES count for that status failed:

```json
{
  "PENDING_REVIEW": 12,
  "APPROVED": 840,
  "REJECTED": 31,
  "ARCHIVED": 9
}
```

**Errors**: none specific (fail-soft to `-1` per status).

### GET /api/v1/admin/sounds/uploaders/{userId}

All sounds by one uploader, newest-first (repeat-offender review).

**Access**: `ADMIN` or `MODERATOR`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `cursor` | ISO date-time | — | Keyset cursor on `createdAt` (UTC); returns rows older than it. |
| `pageSize` | int | `20` | Clamped 1–100. |

**Request body**: None.

**Response**: `200` — array of `AdminSoundRow`. Unknown uploader → empty array (no 404).

**Errors**: none specific.

### GET /api/v1/admin/sounds/{id}

Single sound + its most recent adopting posts.

**Access**: `ADMIN` or `MODERATOR`.

**Request body**: None.

**Response**: `200` — `AdminSoundDetail`:

```json
{
  "sound": {
    "id": "8f2b6c1e-4a3d-4e5f-9a7b-2c1d3e4f5a6b",
    "title": "Ya Nabi Salam Alayka",
    "category": "NASHEED",
    "status": "APPROVED",
    "durationSeconds": 45,
    "uploaderId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
    "official": false,
    "trendingExcluded": false,
    "audioUrl": "https://cdn.example.com/sounds/8f2b6c1e.mp3",
    "useCount": 132,
    "createdAt": "2026-08-05T14:30:00Z",
    "updatedAt": "2026-08-06T10:02:11Z"
  },
  "recentPosts": [
    {
      "postId": "7c4e9d2a-1f6b-4c8d-a3e5-9b0f1a2c3d4e",
      "authorId": "2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d",
      "createdAt": "2026-08-05T18:22:41Z"
    }
  ]
}
```

`recentPosts` is the newest ≤ 20 rows of `posts_by_sound`; each entry has exactly the keys `postId`, `authorId`, `createdAt` (built with `Map.of`, so key *order* within an entry is not guaranteed; `createdAt` is a stringified instant).

**Errors**
- `SOUND_NOT_FOUND` — 404 — no such sound.

---

## Sound state machine

Transitions: `PENDING_REVIEW → APPROVED` (approve) or `→ REJECTED` (reject); `APPROVED → ARCHIVED` (archive, takedown); `REJECTED/ARCHIVED → APPROVED` (restore); hard delete removes the record entirely. Approve/restore (re)write the category-browse row and the search doc; reject/archive/takedown remove both, so the sound disappears from the user-facing picker while existing posts keep their audio (except takedown's optional mute). Every decision is recorded via `ModerationRecorder` + `AdminAuditor` (`ADMIN_SOUND_*`), snapshotting `useCount` as blast radius.

### POST /api/v1/admin/sounds/{id}/approve

Approve a pending sound (re-homed alias of the stray legacy `POST /api/v1/sounds/{id}/approve`). Notifies the uploader (`SoundApprovedEvent`).

**Access**: `ADMIN` or `MODERATOR`.

**Request body**: None.

**Response**: 204 No Content. Approving an already-`APPROVED` sound is a state no-op (still audited).

**Errors**
- `SOUND_NOT_FOUND` — 404 — no such sound.

### POST /api/v1/admin/sounds/{id}/reject

Reject a sound (any state → `REJECTED`). Notifies the uploader with `reasonCode` (`SoundRejectedEvent`).

**Access**: `ADMIN` or `MODERATOR`.

**Request body** (optional — `RejectBody`; `reasonCode` ≤ 100 chars, `note` ≤ 500):

```json
{
  "reasonCode": "COPYRIGHT",
  "note": "Commercial track, no license on file."
}
```

**Response**: 204 No Content.

**Errors**
- `SOUND_NOT_FOUND` — 404 — no such sound.

### POST /api/v1/admin/sounds/{id}/archive

Archive (`APPROVED → ARCHIVED`): stops **new** adoption; existing posts keep their audio.

**Access**: `ADMIN` or `MODERATOR`.

**Request body** (optional — `ReasonBody`; `reason` ≤ 500 chars):

```json
{ "reason": "Low quality duplicate of the official upload." }
```

**Response**: 204 No Content.

**Errors**
- `SOUND_NOT_FOUND` — 404 — no such sound.

### POST /api/v1/admin/sounds/{id}/restore

Restore (`REJECTED`/`ARCHIVED` → `APPROVED`) — re-review override / counter-notice restore.

**Access**: `ADMIN` or `MODERATOR`.

**Request body** (optional — `ReasonBody`):

```json
{ "reason": "Counter-notice accepted." }
```

**Response**: 204 No Content.

**Errors**
- `SOUND_NOT_FOUND` — 404 — no such sound.

### POST /api/v1/admin/sounds/{id}/takedown

Rights/safety takedown: archives the sound and optionally strips the audio track from every adopting post (the TikTok "muted audio" behaviour; muted posts are re-indexed). Walks `posts_by_sound` in pages of 500, hard-capped at 20 000 posts per call.

**Access**: `ADMIN` or `MODERATOR` + **step-up**.

**Request body** (optional — `TakedownBody`; `reason` ≤ 500 chars; `muteAdoptingPosts` defaults to `false`):

```json
{
  "reason": "DMCA notice 2026-0812 from rights holder.",
  "muteAdoptingPosts": true,
  "rightsClaimId": "DMCA-2026-0812"
}
```

**Response**: `200`:

```json
{ "mutedPosts": 37 }
```

`mutedPosts` is `0` when `muteAdoptingPosts` was false/omitted.

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `SOUND_NOT_FOUND` — 404 — no such sound.

### DELETE /api/v1/admin/sounds/{id}

GDPR-grade hard delete: purges `sounds_by_id`, the category-browse row, the use counter, all `posts_by_sound` rows and the search doc. Idempotent — deleting an unknown id still returns 204.

**Access**: `ADMIN` or `MODERATOR` + **step-up**.

**Request body** (optional — `ReasonBody`):

```json
{ "reason": "Uploader GDPR erasure request." }
```

**Response**: 204 No Content.

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.

---

## Categories & import

### POST /api/v1/admin/sounds/{id}/category

Move a sound to another category (rewrites its category-browse row; only `APPROVED` sounds get a browse row).

**Access**: `ADMIN` or `MODERATOR`.

**Request body** (required — `CategoryBody`; case-insensitive `SoundCategory`):

```json
{ "category": "QURAN_RECITATION" }
```

**Response**: 204 No Content.

**Errors**
- `INVALID_CATEGORY` — 400 — not a `SoundCategory` (message lists the allowed values).
- `SOUND_NOT_FOUND` — 404 — no such sound.

### PATCH /api/v1/admin/sounds/{id}

Edit display metadata. Partial: `title` is applied only when non-blank; `artistName` and `coverArtUrl` are applied when present — omit a field to keep its current value.

**Access**: `ADMIN` or `MODERATOR`.

**Request body** (required — `EditBody`; `title`/`artistName` ≤ 200 chars):

```json
{
  "title": "Ya Nabi Salam Alayka (Official)",
  "artistName": "Maher Zain",
  "coverArtUrl": "https://cdn.example.com/sounds/8f2b6c1e-v2.jpg"
}
```

**Response**: `200` — the updated `AdminSoundRow`.

**Errors**
- `SOUND_NOT_FOUND` — 404 — no such sound.

### POST /api/v1/admin/sounds/import

Bulk-seed first-party ("official") sounds: each item is created **pre-approved** (`status=APPROVED`), flagged `official=true`, with the importing admin as `uploaderId`.

**Access**: `ADMIN` or `MODERATOR`.

**Request body** (required — `ImportBody` of 1–100 `ImportItem`s; per item `title` and `audioUrl` are mandatory, `category` defaults to `PLATFORM_MUSIC`):

```json
{
  "items": [
    {
      "title": "Morning Birdsong",
      "artistName": "Platform Audio",
      "audioUrl": "https://cdn.example.com/official/birdsong.mp3",
      "coverArtUrl": "https://cdn.example.com/official/birdsong.jpg",
      "durationSeconds": 30,
      "category": "NATURE"
    }
  ]
}
```

**Response**: `201` — array of the created `AdminSoundRow`s (`"status": "APPROVED"`, `"official": true`).

**Errors**
- `INVALID_IMPORT_BATCH` — 400 — missing/empty `items` or more than 100 ("Provide 1–100 items.").
- `INVALID_IMPORT_ITEM` — 400 — an item lacks `title` or `audioUrl`.
- `INVALID_CATEGORY` — 400 — an item's `category` is not a `SoundCategory`.

---

## Trending sounds

### POST /api/v1/admin/sounds/{id}/trending-exclude

Pin a sound out of (or back into) the user-facing trending surface without archiving it — sets the `trendingExcluded` flag.

**Access**: `ADMIN` or `MODERATOR`.

**Request body** (required — `TrendingExcludeBody`):

```json
{ "excluded": true }
```

**Response**: 204 No Content.

**Errors**
- `SOUND_NOT_FOUND` — 404 — no such sound.

### GET /api/v1/admin/sounds/trending

Usage-based trending board over a rolling window of the `sound_adoptions_by_day` counter (one counter row per day×sound, bumped on every post/reel adoption).

**Access**: `ADMIN` or `MODERATOR`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `days` | int | `7` | Window, clamped 1–14. |
| `limit` | int | `20` | Clamped 1–100. |

**Request body**: None.

**Response**: `200` — rows in rank order; per-row key order is exactly `rank`, `soundId`, `adoptions`, `title`. `title` is **omitted** when the sound row no longer resolves (deleted sound, counter remains):

```json
[
  { "rank": 1, "soundId": "8f2b6c1e-4a3d-4e5f-9a7b-2c1d3e4f5a6b", "adoptions": 412, "title": "Ya Nabi Salam Alayka" },
  { "rank": 2, "soundId": "5d6e7f8a-9b0c-4d1e-8f2a-3b4c5d6e7f8a", "adoptions": 268 }
]
```

Note: this admin board shows **raw** adoption counts — the `trendingExcluded` flag is not applied here (it filters the user-facing surface).

**Errors**: none specific (per-day counter reads fail soft).

---

## Media browse & detail

**`AdminMediaRow`** (browse content and the `asset` key of detail; `NON_NULL` — e.g. `width`/`height`/`durationMs` are absent until processing measured them, `errorMessage` only on failures, `purgeOriginalAt` only while the raw original is retained):

```json
{
  "id": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
  "ownerId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  "type": "VIDEO",
  "status": "FAILED_PROCESSING",
  "mime": "video/mp4",
  "originalBytes": 48211456,
  "storedBytes": 0,
  "requestedTier": "HIGH",
  "errorMessage": "ffmpeg exited with code 1",
  "createdAt": "2026-08-06T09:15:00",
  "updatedAt": "2026-08-06T09:16:42",
  "purgeOriginalAt": "2026-08-13T09:15:00"
}
```

`type` ∈ `IMAGE | VIDEO | AUDIO | FILM | VIDEO_CLIP` (`MediaAssetType`). `status` ∈ `PENDING | UPLOADING | PROCESSING | READY | FAILED_VALIDATION | FAILED_MODERATION | FAILED_PROCESSING` (`MediaStatus`). `requestedTier` ∈ `DATA_SAVER | STANDARD | HIGH` (`MediaTier`). `storedBytes` is the physical sum of rendition bytes — dedup references store `0`.

### GET /api/v1/admin/media

Filtered asset browse (failed-queue triage), newest-first by `createdAt`. Every filter optional.

**Access**: `ADMIN`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `status` | `MediaStatus` | — | Case-insensitive. |
| `type` | `MediaAssetType` | — | Case-insensitive. |
| `ownerId` | UUID | — | |
| `from` | ISO date-time | — | `createdAt >= from`. |
| `to` | ISO date-time | — | `createdAt <= to`. |
| `page` | int | `0` | Spring pageable. |
| `size` | int | `25` | Clamped 1–100. |

**Request body**: None.

**Response**: `200` — `Page<AdminMediaRow>`:

```json
{
  "content": [
    {
      "id": "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
      "ownerId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
      "type": "VIDEO",
      "status": "FAILED_PROCESSING",
      "mime": "video/mp4",
      "originalBytes": 48211456,
      "storedBytes": 0,
      "requestedTier": "HIGH",
      "errorMessage": "ffmpeg exited with code 1",
      "createdAt": "2026-08-06T09:15:00",
      "updatedAt": "2026-08-06T09:16:42",
      "purgeOriginalAt": "2026-08-13T09:15:00"
    }
  ],
  "totalElements": 6,
  "totalPages": 1,
  "number": 0,
  "size": 25
}
```

> Standard Spring `Page` serialization — the wire body also carries `pageable`, `sort`, `first`, `last`, `numberOfElements`, `empty`; the fields shown are the stable ones to consume.

**Errors**
- `INVALID_STATUS` — 400 — `status` not a `MediaStatus` (message lists the allowed values).
- `INVALID_TYPE` — 400 — `type` not a `MediaAssetType` (message lists the allowed values).

### GET /api/v1/admin/media/{assetId}

Asset detail: row + produced renditions + dedup fan-out.

**Access**: `ADMIN`.

**Request body**: None.

**Response**: `200` — top-level key order `asset`, `renditions`, `dedupSiblings`; per-rendition key order `label`, `objectKey`, `url`, `bytes`, `width`, `height`, `mime`:

```json
{
  "asset": {
    "id": "9a1f3c5e-7b2d-4e6f-8a0b-1c2d3e4f5a6b",
    "ownerId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
    "type": "VIDEO",
    "status": "READY",
    "mime": "video/mp4",
    "originalBytes": 48211456,
    "storedBytes": 21073152,
    "width": 1920,
    "height": 1080,
    "durationMs": 42500,
    "requestedTier": "HIGH",
    "createdAt": "2026-08-01T11:00:00",
    "updatedAt": "2026-08-01T11:02:31",
    "purgeOriginalAt": "2026-08-08T11:00:00"
  },
  "renditions": [
    {
      "label": "1080p",
      "objectKey": "media/e3b0c44298fc1c149afbf4c8996fb924/1080p.mp4",
      "url": "https://media.example.com/media/e3b0c44298fc1c149afbf4c8996fb924/1080p.mp4",
      "bytes": 14680064,
      "width": 1920,
      "height": 1080,
      "mime": "video/mp4"
    },
    {
      "label": "poster",
      "objectKey": "media/e3b0c44298fc1c149afbf4c8996fb924/poster.jpg",
      "url": "https://media.example.com/media/e3b0c44298fc1c149afbf4c8996fb924/poster.jpg",
      "bytes": 183500,
      "width": 1920,
      "height": 1080,
      "mime": "image/jpeg"
    }
  ],
  "dedupSiblings": 1
}
```

Rendition labels: `1080p | 720p | 480p | 360p | avif | webp | jpeg | poster | captions | hls | original`. `dedupSiblings` = number of *other* asset rows sharing this `contentHash` (0 when the asset has no hash yet).

**Errors**
- `MEDIAASSET_NOT_FOUND` — 404 — no such asset.

---

## Reprocess & delete

### POST /api/v1/admin/media/{assetId}/reprocess

Retry a failed asset by re-running the in-process pipeline on the retained `raw/{assetId}` original. Resets the row to `PROCESSING`, clears `errorMessage`, resubmits, audits `ADMIN_MEDIA_REPROCESS`. Allowed source statuses: `FAILED_PROCESSING`, `FAILED_VALIDATION`, `FAILED_MODERATION`, and stuck `PROCESSING`.

**Access**: `ADMIN`.

**Request body**: None.

**Response**: 202 Accepted (empty body) — processing is asynchronous; poll the asset row.

**Errors**
- `ASSET_NOT_RETRYABLE` — 400 — asset is not in a retryable status ("Only failed (or stuck PROCESSING) assets can be reprocessed.").
- `MEDIA_RAW_MISSING` — 400 — the raw original is already purged/missing; the asset can never be reprocessed.
- `MEDIAASSET_NOT_FOUND` — 404 — no such asset.

### DELETE /api/v1/admin/media/{assetId}

Admin takedown of an asset. **Dedup-safe**: storage objects (all renditions plus `raw/{assetId}`) are deleted only when **no other** asset row shares the same `contentHash`; the rendition rows and the asset row itself are always deleted. Records a `MEDIA` moderation decision (`ADMIN_MEDIA_DELETE`) and an audit row.

**Access**: `ADMIN` + **step-up**.

**Request body** (optional — `ReasonBody`; `reason` ≤ 500 chars):

```json
{ "reason": "CSAM hash-match escalation, ticket TRUST-4411." }
```

**Response**: 202 Accepted (empty body).

**Errors**
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.
- `MEDIAASSET_NOT_FOUND` — 404 — no such asset.

---

## Purge-raw & reconcile

### POST /api/v1/admin/media/purge-raw/run

The `raw/` retention sweep: purges originals past their 7-day `purgeOriginalAt` (leak L1) **plus** abandoned `PENDING` upload intents older than 7 days that never got a purge date at all (leak L2). **Dry-run by default** — nothing is deleted unless you pass `dryRun=false`. On a real run, purged abandoned intents flip to `FAILED_VALIDATION` with `errorMessage` `"Abandoned upload intent purged by admin sweep."`, and the run is audited (`ADMIN_MEDIA_RAW_PURGE`).

**Access**: `ADMIN`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `dryRun` | boolean | **`true`** | `false` performs the deletions. |

**Request body**: None.

**Response**: `200` — key order `dryRun`, `candidates`, `purged` (`purged` is always `0` on a dry run; on a real run it can trail `candidates` when individual deletes fail):

```json
{
  "dryRun": true,
  "candidates": 14,
  "purged": 0
}
```

**Errors**: none specific (per-object failures are logged and skipped).

### POST /api/v1/admin/media/reconcile

Bucket↔DB reconcile: lists bucket objects under `prefix` and reports **orphans** — objects no `media_renditions.object_key` row references and no live `raw/{assetId}` explains (raw keys inside their 7-day retention are never flagged; membership is checked in batched IN-queries of 1000 keys). Dry-run by default; `dryRun=false` deletes the orphans and audits (`ADMIN_MEDIA_RECONCILE`). DB-but-not-bucket drift is the reverse direction and is caught by the media proxy 404ing, not this sweep.

**Access**: `ADMIN` + **step-up**.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `prefix` | string | `media/` | Bucket listing prefix. |
| `dryRun` | boolean | **`true`** | `false` deletes the orphans. |
| `maxObjects` | int | `1000` | Listing cap, clamped 1–10 000. |

**Request body**: None.

**Response**: `200` — key order `prefix`, `dryRun`, `objectsListed`, `listingTruncated`, `orphanCount`, `orphanBytes`, `orphanKeys`, `deleted` (present **only** when `dryRun=false`), `note`. `orphanKeys` is capped at the first 200 keys; `listingTruncated=true` means the listing hit `maxObjects` and there may be more:

```json
{
  "prefix": "media/",
  "dryRun": true,
  "objectsListed": 812,
  "listingTruncated": false,
  "orphanCount": 3,
  "orphanBytes": 10485760,
  "orphanKeys": [
    "media/ab12cd34ef56ab12cd34ef56ab12cd34/720p.mp4",
    "media/ab12cd34ef56ab12cd34ef56ab12cd34/poster.jpg",
    "raw/not-a-uuid"
  ],
  "note": "An orphan is a bucket object with no media_renditions.object_key row and no parseable raw/{assetId}. raw/ objects inside their 7-day retention are never flagged."
}
```

**Errors**
- `STORAGE_UNAVAILABLE` — 400 — the configured storage backend does not support listing (R2 not configured on this server).
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.

---

## Quotas

Per-role daily upload quotas (`MediaQuota` entity, table `media_quotas`). Roles **without** a row, or with `enabled=false`, are unenforced — that is how `ADMIN` and the staff tiers stay unlimited. Enforcement happens on the user upload-intent path against uploads since midnight UTC (declared original sizes); an over-quota uploader gets **`429 MEDIA_QUOTA_EXCEEDED`** ("Daily upload quota reached … resets at midnight UTC") — configured here, surfaced there.

**`MediaQuota` JSON**:

```json
{
  "role": "USER",
  "dailyUploads": 100,
  "dailyBytes": 2147483648,
  "enabled": true
}
```

### GET /api/v1/admin/media/quotas

List every configured quota row.

**Access**: `ADMIN`.

**Request body**: None.

**Response**: `200` — array of `MediaQuota` (may be empty; absent role = unenforced):

```json
[
  { "role": "USER", "dailyUploads": 100, "dailyBytes": 2147483648, "enabled": true },
  { "role": "RESEARCHER", "dailyUploads": 250, "dailyBytes": 5368709120, "enabled": true }
]
```

**Errors**: none specific.

### PUT /api/v1/admin/media/quotas/{role}

Create or update the quota for a role. Upsert with partial semantics: omitted (`null`) fields keep their current value; a **new** row starts from defaults `dailyUploads=100`, `dailyBytes=2147483648` (2 GiB), `enabled=true`. Audited (`ADMIN_MEDIA_QUOTA_CHANGE`).

**Access**: `ADMIN` + **step-up**.

**Params**: path `{role}` — case-insensitive `Role`: `USER | RESEARCHER | SCHOLAR | MODERATOR | SUPPORT | ANALYST | ADMIN`.

**Request body** (required — `QuotaRequest`; every field optional):

```json
{
  "dailyUploads": 50,
  "dailyBytes": 1073741824,
  "enabled": true
}
```

**Response**: `200` — the saved `MediaQuota`:

```json
{ "role": "USER", "dailyUploads": 50, "dailyBytes": 1073741824, "enabled": true }
```

**Errors**
- `INVALID_ROLE` — 400 — `{role}` is not a `Role` (message lists the allowed values).
- `INVALID_QUOTA` — 400 — `dailyUploads`/`dailyBytes` < 1 ("use enabled=false to lift a quota").
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.

---

## Ops snapshot

### GET /api/v1/admin/media/status-summary

Pipeline status funnel — the board's headline numbers. Every `MediaStatus` key is present (zero-filled), in enum order.

**Access**: `ADMIN`.

**Request body**: None.

**Response**: `200`:

```json
{
  "PENDING": 4,
  "UPLOADING": 1,
  "PROCESSING": 2,
  "READY": 1873,
  "FAILED_VALIDATION": 11,
  "FAILED_MODERATION": 2,
  "FAILED_PROCESSING": 6
}
```

**Errors**: none specific.

### GET /api/v1/admin/media/ops

One-call media board: pipeline funnel, backlog gauges, platform storage, quota config. Key order: `statusFunnel`, `abandonedPendingOver24h`, `rawPurgeDue`, `platformStoredBytes`, `quotas`.

**Access**: `ADMIN`.

**Request body**: None.

**Response**: `200`:

```json
{
  "statusFunnel": {
    "PENDING": 4,
    "UPLOADING": 1,
    "PROCESSING": 2,
    "READY": 1873,
    "FAILED_VALIDATION": 11,
    "FAILED_MODERATION": 2,
    "FAILED_PROCESSING": 6
  },
  "abandonedPendingOver24h": 3,
  "rawPurgeDue": 14,
  "platformStoredBytes": 48318382080,
  "quotas": [
    { "role": "USER", "dailyUploads": 100, "dailyBytes": 2147483648, "enabled": true }
  ]
}
```

`abandonedPendingOver24h` = `PENDING` intents older than 24 h; `rawPurgeDue` = assets whose `purgeOriginalAt` has passed (candidates for [purge-raw](#post-apiv1adminmediapurge-rawrun)).

**Errors**: none specific.

---

## Storage usage

### GET /api/v1/admin/storage/usage

Platform storage totals + top-N owners by stored bytes. Counts **physical** storage — dedup references store at 0 bytes, so a re-upload of existing content does not double-count.

**Access**: `ADMIN`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `top` | int | `20` | Number of owner rows, clamped 1–100. |

**Request body**: None.

**Response**: `200` — top-level key order `totalStoredBytes`, `topOwners`; `topOwners` is ordered by bytes descending (each row has exactly the keys `ownerId`, `storedBytes`, `assets`; built with `Map.of`, so key *order* within a row is not guaranteed):

```json
{
  "totalStoredBytes": 48318382080,
  "topOwners": [
    {
      "ownerId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
      "storedBytes": 9663676416,
      "assets": 412
    },
    {
      "ownerId": "2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d",
      "storedBytes": 4831838208,
      "assets": 96
    }
  ]
}
```

**Errors**: none specific.
