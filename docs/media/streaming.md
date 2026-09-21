# Adaptive Video Delivery (HLS/CMAF) + CDN + Signed URLs

**Canonical reference (built 2026-08-25).** Extends the transcode pipeline in
[pipeline.md](pipeline.md) with adaptive-bitrate delivery, read-time CDN URL
shaping, HMAC-signed private playback, and storage-cost reclaim. Everything
here is **additive** — no endpoint changed shape; every new capability rides
new variant keys / nullable DTO fields and is env-gated with defaults equal to
the previous behavior.

## HLS packaging

`HlsPackagingService` runs inside `MediaProcessWorker` after the MP4 ladder:

```
media/{assetId}/hls/master.m3u8            ← rendition row, label `hls`
media/{assetId}/hls/{rung}/init.mp4
media/{assetId}/hls/{rung}/seg_000.m4s …   ← 4s CMAF/fMP4 segments
media/{assetId}/hls/{rung}/playlist.m3u8
```

- **Remux, not re-encode**: each rung is `-c copy`-packaged (`-f hls
  -hls_segment_type fmp4 -hls_playlist_type vod -hls_flags
  independent_segments`), so packaging costs I/O, not CPU.
- **Aligned switching**: `VideoTranscodeService` forces IDR frames every 2s
  (`-force_key_frames expr:gte(t,n_forced*2)`), giving every rung identical
  keyframe instants; the 4s segmenter cuts all variants at the same
  boundaries — the precondition for seamless ABR.
- **Master playlist** is hand-written: `BANDWIDTH` from the rung's encode
  maxrate (+audio, +10% container), `AVERAGE-BANDWIDTH` from the actual file
  size over the real duration, `RESOLUTION` from the produced dims, `CODECS`
  `avc1.640029,mp4a.40.2` (High@4.1 + AAC-LC everywhere). The
  `media.video.hls.start-label` rung (480p) is listed first — players start
  there instantly, then adapt.
- **Ordering discipline**: segments upload before their variant playlist,
  variant playlists before the master, the `hls` rendition row last — a
  reader can never resolve a manifest to missing bytes.
- **Failure discipline**: an ffmpeg/packaging failure is *soft* (the MP4
  ladder still serves; warn-logged); a storage failure mid-upload *throws* so
  the worker's transient-retry re-runs packaging idempotently (same keys).
- **Cleanup**: asset deletion already prefix-sweeps `media/{assetId}/`, which
  removes all segments (only the master has a rendition row).

## Client contract (all additive)

| Surface | Field | Value |
|---|---|---|
| `PostResponse.media[]` / stories / status poll | `variants.hls` | master playlist URL |
| `PostResponse.media[]` | `variants.preview` | animated WebP teaser |
| `PostResponse.media[]` | `blurhash` | paint-before-bytes placeholder |
| Feed/reels lists (`FeedItemResponse`) | `videoHlsUrl` | REEL-only master URL; `videoUrl` stays progressive MP4 |

Selection rule for players: **HLS-aware players (expo-video, hls.js,
AVPlayer/ExoPlayer) prefer `hls`/`videoHlsUrl`; everything else keeps using
`bestVideoUrl` MP4s** — `MediaVariants.bestVideoUrl` deliberately excludes
`hls` so no legacy client ever receives a manifest it can't parse.

Feed hydration loads variants only for REEL rows (`bulkLoadReelVariants`) —
an all-image page costs zero extra queries; reels pages reuse the one bulk
IN-query discipline.

## Serving (`/api/v1/media/**`)

- `m3u8` → `application/vnd.apple.mpegurl`, `m4s` → `video/iso.segment`,
  `ts` → `video/mp2t` added to the MIME map; everything under `media/` stays
  `Cache-Control: max-age=31536000, public, immutable` (VOD trees are
  content-addressed and never rewritten — playlists included).
- **HEAD** is now served cheaply via a 1-byte ranged read (size/type/ETag,
  no body) instead of Spring's stream-and-discard default.
- Range/206, ETag/304 behavior unchanged.

## CDN (read-time URL shaping)

Rendition URLs persist proxy-relative (`/api/v1/media/{key}`) — portable by
design. `MediaCdnRewriter` rewrites them at hydration when
`MEDIA_CDN_BASE` is set, covering historical rows without a migration:

- `PROXY` mode — CDN origin is this app; path survives:
  `https://cdn.x.com/api/v1/media/media/{id}/hls/master.m3u8`
- `BUCKET` mode — CDN fronts the R2 custom domain; the app hop disappears:
  `https://cdn.x.com/media/{id}/hls/master.m3u8`

Recommended production posture: R2 bucket behind a Cloudflare custom domain
(`BUCKET` mode) → zero egress cost, zero video bytes through the JVM. Legacy
flat `mediaUrls` strings in Cassandra rows are *not* rewritten — those ride
the CDN only in PROXY mode (CDN in front of the API host).

## Signed playback URLs

`MediaSignedUrlService` — stateless HMAC-SHA256:
`?exp={epochSeconds}&sig=base64url(hmac(secret, key|exp))`. Enforcement is
scoped to `media.serving.protected-prefixes` (default empty), so enabling the
feature cannot break existing public URLs; protected keys answer
`Cache-Control: no-store, private` and 403 without a valid signature.
Constant-time comparison; fail-closed on config errors. Nothing signs URLs
yet — the service is the primitive for private-media surfaces (close-friends
stories, DM attachments) to adopt per-surface.

**Hard scope limits:** a surface that adopts a protected prefix must also
*sign* the URLs it emits (via `sign()`), or everything under the prefix 403s
fail-closed. And per-URL signatures structurally cannot protect an HLS tree —
manifests reference segments by relative URI and query params don't propagate
to child requests — so protect only non-HLS prefixes; HLS protection requires
a path-embedded token or CDN-edge token auth (Cloudflare/Bunny) instead.

## Storage reclaim

`LadderOriginalPurgeJob` (hourly, config-gated by
`MEDIA_PURGE_LADDER_ORIGINAL_DAYS`, default `-1` = off): once an asset has
had its HLS master for N days, the stored `original` rendition (up to 512 MB)
is deleted — object first, then row, then a `storedBytes` recount. The
candidate query *requires* the `hls` rendition, so passthrough deployments
(transcode off) are never touched. Leave off unless storage cost demands it:
the original is the dedup source and the only re-ladder input.

Safety rails: dedup reference rows (`storedBytes = 0`) are excluded at the
query, assets with same-hash siblings are skipped (shared objects never
dangle — dedup groups keep their original for good), and the media proxy
heals stale persisted URLs: a request for a purged
`media/{id}/original.*` answers **302 to the best surviving rendition**
(chat messages and legacy rows store the original URL verbatim and never
re-hydrate, so old links keep playing).

## Ops notes

- New env: `MEDIA_HLS_ENABLED` (default true — inert until
  `MEDIA_TRANSCODE_ENABLED=true`), `MEDIA_PREVIEW_ENABLED`,
  `MEDIA_CDN_BASE`/`MEDIA_CDN_MODE`, `MEDIA_SIGNED_URLS_ENABLED` +
  `MEDIA_SIGNING_SECRET`, `MEDIA_PURGE_LADDER_ORIGINAL_DAYS`.
- Already-processed assets have no HLS; an admin reprocess re-runs the worker,
  which skips existing rungs (`renditionExists`) and packages HLS from the
  stored rung files (it re-downloads them when not local).
- Segment count ≈ duration/4 per rung — a 60s reel ≈ 15 segments × rungs. R2
  Class A ops are $4.50/M; negligible, but keep it in mind for very long film
  uploads.
- The `hls` rendition row's `bytes` is the whole tree (segments + playlists),
  so `storedBytes` accounting stays truthful.
