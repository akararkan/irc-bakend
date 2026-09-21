# Generic File Uploads — any type, every surface

**Canonical reference (built 2026-08-25).** Companion to [pipeline.md](pipeline.md):
that doc owns what happens to images/video; this one owns how *any* file gets in —
the shared policy gates, the chunked/resumable path for big files, and the client
contract (web today, mobile later).

## One system, not per-screen code

Every upload on every surface funnels through `MediaIngestService.ingest`
(policy → security gate → quota → pipeline/passthrough → `media_assets`
accounting). There is no separate "generic file" endpoint per feature — a
surface accepts generic files the moment its `MediaSurface` allow-list
includes `FILE`:

| Surface | Generic FILE kind |
|---|---|
| CHAT_MEDIA, RESEARCH_MEDIA, QNA_ATTACHMENT, DOCUMENT | already allowed |
| **POST_MEDIA** | **allowed since 2026-08-25** (returned as `mediaTypes: "OTHER"`) |
| STORY, AVATAR/covers, VOICE, SOUND_* | image/video/audio only, by design |

Byte caps by kind (`media.limits`, overridable per surface via
`media.surfaces.<key>.*`): image 25MB (profile surfaces 10MB), video 512MB,
audio 20MB, file 100MB.

## Security gate (server-side, on every ingest)

`MediaIngestService.enforceContentSafety` + `FileSignatureInspector`
(pure-JDK magic-byte sniffing; config under `media.security.*`):

1. **Executable veto — DEFAULT BLOCKED.** Content whose leading bytes are
   PE/ELF/Mach-O/Java-class/shebang, or whose extension is on
   `media.security.blocked-extensions` (exe, dll, msi, bat, sh, ps1, vbs, js,
   jar, apk, dmg, …), is rejected with `MEDIA_TYPE_BLOCKED` on every surface.
   Escape hatch: `media.security.allow-executables=true` (don't).
2. **Spoof check.** A file whose sniffed family contradicts its declared kind
   (an EXE named `.jpg`, a PDF posing as a video) is rejected with
   `MEDIA_CONTENT_MISMATCH`. Lenient by design: unknown signatures pass (the
   decoders are the final arbiter), and container quirks stay legal (m4a
   declared `video/mp4`, audio-only MP4).
3. Kill switch: `media.security.magic-check-enabled=false`.

The client-declared MIME type and extension are never trusted alone. The
frontend mirrors the blocklist + caps in `src/lib/fileMeta.js` purely to fail
fast; the server is the authority.

**Malware scanning:** the `MediaScanner` hook exists with only the
`AllowAllScanner` no-op bound. Wiring ClamAV (or a SaaS scanner) behind that
interface — ideally before a DM attachment flips READY — is a deliberate open
decision, not an accident.

## Chunked / resumable uploads (big files)

`/api/v1/uploads/sessions` (auth required) — for anything the client considers
big (web uses ≥24MB). A dropped connection resumes from the last received
chunk instead of restarting.

```
POST   /api/v1/uploads/sessions                {surface, fileName, mime, totalBytes}
       → 201 {id, chunkBytes, totalChunks, expiresAt}     (fail-fast policy check)
PUT    /api/v1/uploads/sessions/{id}/chunks/{n}  raw application/octet-stream
       → 204   (any order; idempotent re-PUT; last chunk may be short)
GET    /api/v1/uploads/sessions/{id}           → {receivedChunks:[…], …}   (resume)
POST   /api/v1/uploads/sessions/{id}/complete  → 200 IngestResult
DELETE /api/v1/uploads/sessions/{id}           → 204 (cancel)
```

`complete` assembles the chunks and pushes them through the **same**
`ingest` path as an inline multipart upload — magic-byte gate, caps, quota,
image/video pipeline, accounting — and returns the same media-ref shape
(`IngestResult`: assetId/kind/url/variants/…), attachable via the JSON send
paths (chat `SendMessageRequest.media[]`, etc.).

Ops facts: chunks spool to `media.uploads.session-dir` (instance-local — a
session is pinned to its instance; sticky routing needed behind an LB);
`chunk-bytes` 5MB; TTL `session-ttl-hours` 24 with an hourly sweeper;
`max-open-sessions-per-user` 8. Sessions live in the `upload_sessions` table.

## Web client (ika)

- `src/lib/fileMeta.js` — shared kind/family/badge/caps/blocklist +
  `validateFiles(files, context)`; every picker fails fast through it.
- `http.uploadX` / `http.putRaw` (`src/api/http.js`) — XHR uploads with real
  progress + AbortController cancel, same envelope/refresh/step-up dances as
  the fetch funnel.
- `src/api/uploads.js` — `uploadChunked(file, {surface, onProgress, signal})`
  with resume + retry, and `toMediaRef` for chat sends. Chat auto-routes files
  ≥24MB through it (`useThread.sendFiles`).
- `src/components/FileChip.jsx` (+ `styles/attachments.css`) — THE shared
  generic-attachment renderer (type badge PDF/DOCX/ZIP/…, name, size,
  progress/cancel, remove; PDFs preview in a tab via the media proxy's
  Content-Type). Used by chat bubbles, post cards, composer trays,
  profile attachments.
- Composers: chat has photo/video + any-file pickers, drag-drop, paste,
  per-file validation, a real progress bar and cancel; the post composer has a
  separate "Attach a document or file" picker and progress/cancel too.

## Mobile client (ika-mobile-app — implemented 2026-08-25)

- `src/lib/fileMeta.ts` — caps/blocklist mirror + `checkAssets(assets, ctx)`;
  wired into chat, post, channel, story, and Q&A pickers.
- `http.uploadX` (RN XHR — native layer streams `{uri}` parts, real progress
  + AbortController cancel, same envelope/refresh/step-up as the fetch funnel).
- `src/api/uploads.js` — chunked sessions: legacy `expo-file-system` slice
  reads → staged chunk file → `uploadAsync` BINARY PUT; resume via `GET /{id}`;
  chat auto-routes files ≥24MB and sends one JSON message with the refs.
- Chat shows a live progress bar + cancel above the composer; the post
  composer gained a "Document or file" picker (FILE tiles), and post cards
  render OTHER-typed attachments as tappable rows (browser preview/download).

## Error codes added

`MEDIA_TYPE_BLOCKED`, `MEDIA_CONTENT_MISMATCH`, `UPLOAD_SESSION_NOT_FOUND`,
`UPLOAD_SESSION_EXPIRED`, `UPLOAD_SESSION_INCOMPLETE`, `UPLOAD_CHUNK_INVALID`
(all in `MediaMessages`).

Image moderation (docs/moderation/image-moderation.md) later added
`MEDIA_NSFW_BLOCKED` (400, image/poster confidently explicit — terminal, do
not retry) and `MEDIA_MODERATION_UNAVAILABLE` (503, scorer down under
FAIL_CLOSED — retryable). Both can fire on any upload surface, chunked
completes included.

## Known gaps (accepted)

- Posts persist `mediaUrls`, not original filenames — a post's file chip shows
  the storage basename (`original.pdf`). Fix = persist fileName on post media.
- Chunk spool is instance-local (see ops facts above).
- No malware scanning yet (see the decision note above).
- Document metadata (author fields in DOCX/PDF) is NOT stripped — only images
  get EXIF stripping. Flagged, not silently decided.
