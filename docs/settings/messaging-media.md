# Messaging, Media Pipeline & Storage (§9, §15, §20)

## Messaging settings (§9)

| Setting | Owner | Where |
|---------|-------|-------|
| Wallpaper, chat theme, font size | **[C]** | `settings.core` `MessageSettings` JSONB (sync only) |
| Auto-download photos/videos | **[C]** | `settings.core` `MediaSettings` (client policy) |
| Auto-delete (disappearing) | **[B]** | existing chat — Cassandra native TTL |
| Archive chats | **[B]** | existing chat — per-participant flag |
| Read receipts / typing | **[B]** | existing chat (`ChatUserSettings`) + [presence](presence.md) |
| Media upload quality | **[B+C]** | `MediaTier` hint → §20 below |

> **Seam:** message edit-window enforcement (`now - createdAt ≤ editWindow` →
> `409 EDIT_WINDOW_EXPIRED`) belongs in `MessageService.edit`; not yet applied.

## Media upload, quality & compression (§20) — the flagship

> **The user chooses their preferred upload tier; the backend enforces the
> maximum. Every asset is re-encoded and reduced in size on the server. Video is
> hard-capped at 1080 px on the short edge; images at HD class.**

Package `media`. Caps live in `MediaProperties` (`media.*` in `application.yaml`)
— see [config.md](config.md).

### Flow (spec §20.4)
1. **`POST /api/v1/media/upload-intent`** `{mime,sizeBytes,sha256,type}` + header
   `X-Media-Tier` → `MediaAssetService` validates mime/size against the type cap,
   **dedups on `sha256`** (a READY asset with the same hash → a new reference row,
   no re-encode), else creates a PENDING `media_assets` row and returns a
   **presigned PUT URL** (`S3StorageService.presignPut`) to a `raw/{id}` key.
2. Client **PUTs bytes directly to R2** (never through the app server — a 512 MB
   video must not pass through the JVM).
3. **`POST /api/v1/media/{id}/complete`** → `MediaAssetService` downloads the raw
   bytes and hands them to `MediaProcessingService`.
4. **`MediaProcessingService`** — a small **bounded thread pool** (not the API
   threads, §20.7): scan (`MediaScanner`) → transcode → upload renditions →
   accumulate `stored_bytes` → status `READY`.
   - **Images** (`ImageProcessor`, pure-JDK `ImageIO`): downscale to the cap,
     re-encode, **strip EXIF/GPS metadata** (a real privacy control, §20.5),
     apply the orientation flag to pixels.
   - **Video** (`VideoProcessor`): the **1080 px short-edge hard cap**. Passthrough
     (store validated original) unless `media.processing.enabled=true` and ffmpeg
     is present, then a real transcode.
5. **`GET /api/v1/media/{id}`** → status + renditions; **`DELETE`** removes
   renditions from storage then the rows then the raw original (§15 ordering).

### Status model (§20.7)
`PENDING → UPLOADING → PROCESSING → READY`, with `FAILED_VALIDATION` /
`FAILED_MODERATION` / `FAILED_PROCESSING`.

### Data model (§20.9)
`media_assets` (+ `content_hash` dedup index, `stored_bytes` for §15) and
`media_renditions` (`media_id`,`label`) — see [data-model.md](data-model.md).

> **Seams:** the RabbitMQ `media.process`/`media.delete` queues + bindings exist;
> processing currently runs on the in-process pool. AVIF/WebP output and the
> HLS ladder (720/480/360) are additional runs behind the processors. The
> `media.ready` SSE event is the completion signal to wire.

## Storage management (§15)

Server-side footprint only — device cache/downloads/temp files are **[C]** and
the backend has no visibility into the device's disk.

`StorageUsageService.usage(userId)` is a single indexed **`SUM`** over
`media_assets.stored_bytes` grouped by type (never an object-store `LIST`),
cached 1 h in Redis. **`GET /api/v1/storage/usage`** → `{totalBytes, byType{}}`.
