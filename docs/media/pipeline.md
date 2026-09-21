# Media Resolution-Reduction Pipeline

> Generic any-file uploads (policy gates, magic-byte security, chunked/resumable
> sessions, client contract) are documented in [uploads.md](uploads.md).

**Canonical reference (built 2026-08-23).** Every image and video uploaded on any
surface is reduced to appropriate resolutions before it is stored and served.
Supersedes the "no domain surface consumes the pipeline" state described in
`admin doc/admin/content/media-storage.md` §3 — the convergence proposal there
(C1–C6) is now implemented.

## What every upload gets

**Images** (processed synchronously, in-request — the response already carries final URLs):

| Rung | Long edge | Format | Label |
|---|---|---|---|
| zoom / full-screen | 1440 (skipped when source ≤ 1080) | JPEG q82 (+WebP q80 when ffmpeg has libwebp) | `jpeg_1440` / `webp_1440` |
| feed / display | 1080 (chat: 1280, avatar: 512 square) | JPEG (+WebP) | `jpeg_1080` / `jpeg_1280` / `avatar_512` |
| list thumbnail | 320 | JPEG | `thumb_320` |
| square micro thumb | 150 center-crop | JPEG | `thumb_sq150` |

EXIF/GPS is always stripped (pixels are redrawn), the EXIF orientation flag is
applied first (portraits upright), transparency composites on white for JPEG,
downscales use progressive halving, and the decompression-bomb guard runs
*before* decode. Animated GIFs and non-media files pass through verbatim (with
accounting). WebP twins are best-effort: no ffmpeg/libwebp → JPEG-only.

**Video** (original stored + poster extracted in-request; ladder produced asynchronously):

- H.264/AAC MP4 rungs `360p`, `480p`, `720p`, `1080p` — never upscaled; only
  rungs ≤ the source's short edge are produced (the lowest always is). Rungs
  keyframe on a fixed 2s IDR grid (`-force_key_frames`) so HLS segments align
  across variants.
- **CMAF/fMP4 HLS** (`hls` variant key, `media/{assetId}/hls/master.m3u8`) —
  a `-c copy` remux of the ladder into 4s fMP4 segments + per-variant
  playlists + a hand-written master with accurate BANDWIDTH/RESOLUTION/CODECS.
  See [streaming.md](streaming.md) for the full contract.
- `poster.jpg` frame (t≈0.4s, ≤720px) for list surfaces, plus a **blurhash**
  placeholder (persisted on `media_assets.blurhash`, shipped on
  `PostMediaDto.blurhash`) and a short animated **`preview.webp`**
  (2.5s / 12fps / 240p) for hover/scrub teasers.
- The upload response returns the **original** URL (playable immediately via
  the Range-supporting proxy) with `processing: true`; renditions attach as
  they finish, ascending.
- Gated by `MEDIA_TRANSCODE_ENABLED` + ffmpeg on PATH; off → original+poster
  only (still READY, nothing breaks). HLS additionally gated by
  `MEDIA_HLS_ENABLED` (default on), previews by `MEDIA_PREVIEW_ENABLED`.

Storage keys are deterministic: `media/{assetId}/{label}.{ext}` — immutable,
served with `Cache-Control: public, max-age=31536000, immutable` + ETag/304.
`raw/…` (intent-flow staging) returns 404 from the public proxy.

## Client contract — the `variants` map

Media DTOs across surfaces expose one shared shape (built by
`ak.dev.irc.app.media.dto.MediaVariants`):

```json
"variants": {
  "thumb": "…/media/{id}/thumb_320.jpg",
  "thumbSmall": "…/thumb_sq150.jpg",
  "feed": "…/jpeg_1080.jpg",     "feedWebp": "…/webp_1080.webp",
  "full": "…/jpeg_1440.jpg",     "fullWebp": "…/webp_1440.webp",
  "poster": "…/poster.jpg",
  "v1080": "…/1080p.mp4", "v720": "…", "v480": "…", "v360": "…",
  "original": "…/original.mp4"
},
"processing": false
```

Clients should request the size-appropriate variant per context (thumb in
lists, feed in the feed, full only on zoom; video rendition by network/screen)
and prefer `*Webp` when supported. Empty map ⇒ legacy pre-pipeline media —
fall back to the flat URL fields.

Where it appears (all additive; old fields untouched):

- **Posts** — `PostResponse.media[]` (`PostMediaDto`: url/type/thumbnailUrl/
  assetId/variants/processing/width/height/durationSeconds). Video `url`
  upgrades to the best rendition at read time; reels' `videoUrl` likewise.
- **Stories** — `variants` + `mediaProcessing` on the story rows.
- **Chat** — the message's `MediaRef` is rewritten in place when the ladder
  lands (url→best rung, thumbnail→poster, real dims) and a `MESSAGE_EDITED`
  event is pushed — no new client protocol.
- **Profiles** — `profile.avatarUrl` is the 512 square, `profile.avatarThumbUrl`
  the 150 thumb.
- **Research media / QnA attachments** — `variants` + `processing` on their
  response DTOs.

## Architecture (backend)

```
multipart upload → MediaIngestService (per-surface policy: kinds/size/count/
    duration caps + daily quota) →
  IMAGE  → ImagePipeline (sync; semaphore-bounded) → renditions + READY row
  VIDEO  → original + poster stored → media_assets PROCESSING →
           afterCommit publish → irc.queue.media.process →
           MediaProcessWorker (prefetch 1) → ffprobe → per-rung ffmpeg →
           rendition rows as they finish → READY → MediaReadyDispatcher
  AUDIO/FILE/GIF → passthrough + accounting
```

- Retry/DLQ: 3 container attempts → `dead_letters` parking lot (admin
  requeue); `MediaStuckSweeper` republishes stale-PROCESSING assets from the
  DB (max `media.processing.max-attempts`, then `FAILED_PROCESSING`).
  The database is the source of truth; the broker is only the fast path.
- Deletes ride `irc.queue.media.delete` (dedup-safe: shared content hashes
  keep their objects). Post/story/chat/avatar/research/QnA delete paths all
  hook in — post deletion previously leaked every R2 object.
- Accounting is real now: every upload writes a `media_assets` row, so per-user
  storage usage, quotas and the admin media console cover all surfaces.
- Kill switch `MEDIA_INGEST_ENABLED=false` restores the exact legacy behavior
  everywhere (call sites read the same `IngestResult` either way).

## Config

See the `media:` block in `application.yaml`: rung ladder + per-rung
CRF/maxrate, duration caps (clip 90s / video & film 600s; story 60s, chat
180s, promo 300s via surface policy), byte caps (image 25MB — profile
surfaces 10MB, video 512MB, audio 20MB, file 100MB), thumbs, WebP toggle,
sync budget, worker concurrency, sweeper cadence, and
`media.surfaces.<key>.*` per-surface overrides.

## Deliberately deferred

WebP thumbnails, SSE `media.ready` for posts/stories, TTL-expired story
object cleanup (reconcile job covers it), sprite/storyboard scrub thumbnails,
per-title encoding, HEVC/AV1 rungs.

Built since the 2026-08-23 note (see [streaming.md](streaming.md)):
HLS/ABR packaging, CDN base-URL rewriting (`MEDIA_CDN_BASE`), HMAC-signed
playback URLs, blurhash placeholders, animated previews, and the
config-gated ladder-original purge (`MEDIA_PURGE_LADDER_ORIGINAL_DAYS`).
