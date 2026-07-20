# Media Proxy API

Base path: **`/api/v1/media`**

Streams media objects (images, video, audio, PDFs) from Cloudflare R2 / S3 storage
through the API's own origin. Because media is served from the same origin as the
JSON API, browsers need no separate CORS story for `<img>`, `<video>`, and `<audio>`
tags — CORS headers are emitted by the app's single Spring Security CORS filter, the
same as every other endpoint.

Key behaviours:

- **Range / seek support** — the browser's `Range` header is forwarded verbatim to
  R2/S3, and the partial body streams back as `206 Partial Content` with a
  `Content-Range`. `Accept-Ranges: bytes` is always advertised so players know they
  may seek.
- **Aggressive caching** — every successful response carries
  `Cache-Control: max-age=604800, public` (**7 days**). Media keys are immutable
  (uploads mint new keys), so long cache lifetimes are safe.
- **Path traversal guarded** — any key containing `..` is rejected with `400` before
  touching storage.

> **Changed — error mapping.** Storage failures used to surface as generic `500`s.
> They are now mapped precisely: a **missing object key returns `404 MEDIA_NOT_FOUND`**
> (was 500), a storage **transport** failure (network/DNS/timeout reaching R2) returns
> `503 STORAGE_UNAVAILABLE`, and a storage **service** error (auth, throttling, 5xx
> from R2) returns `502 STORAGE_ERROR`. See the error table below.

Auth is Bearer JWT platform-wide; this endpoint is public (media URLs are embedded in
`<img>`/`<video>` tags which cannot send headers). Errors use the standard envelope —
see [../errors/error-handling.md](../errors/error-handling.md).

Siblings: [tags.md](./tags.md) · [search.md](./search.md) · [mentions.md](./mentions.md) ·
[activity.md](./activity.md) · [audit.md](./audit.md)

---

## 1. Stream a media object

```
GET /api/v1/media/**
```

**Auth:** Public — no token required.

Everything after `/api/v1/media/` is the storage object key, slashes included:

```
GET /api/v1/media/posts/media/abc123.jpg
→ streams object key "posts/media/abc123.jpg"
```

### Request headers

| Header | Required | Notes |
|---|---|---|
| `Range` | no | Standard byte-range (e.g. `bytes=0-1023`, `bytes=500000-`). Forwarded to R2/S3 as-is; the store returns only the requested bytes — nothing is buffered server-side. Sent automatically by `<video>`/`<audio>` when seeking. |

### Response `200` (full object) / `206` (range request)

The raw object bytes. No JSON envelope.

| Header | When | Value |
|---|---|---|
| `Content-Type` | always | From storage metadata; if missing or `application/octet-stream`, guessed from the key's file extension (see table below), falling back to `application/octet-stream`. |
| `Content-Length` | when known | Length of **this** body (the partial slice for a `206`, the full object otherwise). |
| `Accept-Ranges` | always | `bytes` — advertises seekability even on full responses. |
| `Content-Range` | `206` only | e.g. `bytes 0-1023/4194304` — passed through from storage. |
| `Cache-Control` | always | `max-age=604800, public` (7 days, cacheable by shared caches/CDNs). |

Extension → MIME map used when storage metadata is absent:

| Extensions | Content type |
|---|---|
| `jpg`, `jpeg` | `image/jpeg` |
| `png` / `gif` / `webp` / `svg` | `image/png` / `image/gif` / `image/webp` / `image/svg+xml` |
| `mp4` / `webm` / `mov` / `avi` / `mkv` | `video/mp4` / `video/webm` / `video/quicktime` / `video/x-msvideo` / `video/x-matroska` |
| `mp3` / `ogg` / `wav` / `aac` / `m4a` / `flac` | `audio/mpeg` / `audio/ogg` / `audio/wav` / `audio/aac` / `audio/mp4` / `audio/flac` |
| `pdf` | `application/pdf` |
| anything else | `application/octet-stream` |

### Example

```
GET /api/v1/media/posts/media/reel-4711.mp4
Range: bytes=1048576-

HTTP/1.1 206 Partial Content
Content-Type: video/mp4
Accept-Ranges: bytes
Content-Range: bytes 1048576-8388607/8388608
Content-Length: 7340032
Cache-Control: max-age=604800, public
```

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | — (empty body) | Empty key (`GET /api/v1/media/`) or a key containing `..` (path traversal attempt). |
| 404 | `MEDIA_NOT_FOUND` | The object key does not exist in storage. **New behaviour** — this was previously a `500`. |
| 502 | `STORAGE_ERROR` | The storage service returned an error (authentication/authorization failure, throttling, R2-side 5xx). The proxy is fine; the upstream store misbehaved. |
| 503 | `STORAGE_UNAVAILABLE` | Storage transport failure — the store could not be reached at all (network/DNS/timeout), or the storage backend is not configured on this deployment. Retryable. |

Notes:

- The two `400` guards return an empty body (no JSON envelope) — they are rejected
  before the error-handling pipeline is involved. All storage errors (`404`/`502`/`503`)
  use the standard envelope.
- A client that disconnects mid-stream (a `<video>` element seeking, pausing, or
  unmounting) is not an error — the server logs it at debug level and moves on; no
  error response is attempted on the dead connection.
