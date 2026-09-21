# Image moderation — web & mobile client guide

What the [image moderation gate](image-moderation.md) means for the two
clients: **ika** (React web, `/Users/khi/Documents/ika`) and **ika-mobile-app**
(React Native / Expo). Written for anyone wiring an upload surface — post
composer, story camera, avatar picker, chat attachment, video upload.

The short version: **nothing new to call, two new errors to handle, one new
"image can disappear later" case to render gracefully.** The gate runs
server-side inside the one ingest funnel, so every existing upload endpoint —
multipart and chunked sessions alike — already goes through it.

## The three outcomes a client can observe

| Outcome | What the client sees | When |
|---|---|---|
| Allowed | The normal upload response. **The vast majority of uploads — including the review band, which is invisible to clients.** | `nsfw < 0.90` |
| Blocked | `400` with `errorCode: "MEDIA_NSFW_BLOCKED"` | `nsfw ≥ 0.90` |
| Screening down (strict mode only) | `503` with `errorCode: "MEDIA_MODERATION_UNAVAILABLE"` | scorer down **and** admins set `image.fallback=FAIL_CLOSED`; with the default fail-open policy this error never occurs |

Both errors use the canonical envelope
([errors/error-handling.md](../errors/error-handling.md)) — branch on
`errorCode`, never on `message`:

```json
{
  "status": 400,
  "errorCode": "MEDIA_NSFW_BLOCKED",
  "message": "This image appears to contain explicit content and can't be uploaded here.",
  "path": "/api/v1/posts",
  "timestamp": "…", "traceId": "…"
}
```

Handling rules:

- **`MEDIA_NSFW_BLOCKED` (400)** — terminal. Do **not** auto-retry (the same
  bytes score the same). Show the server `message` next to the offending file,
  keep the rest of the composer state (text, other files) intact, and let the
  user remove/replace the image. Tone: neutral and non-accusatory — false
  positives (beach/medical/art photos) are expected.
- **`MEDIA_MODERATION_UNAVAILABLE` (503)** — transient. Offer "Try again";
  simple backoff (a few seconds) is enough. Keep the selected file so retry is
  one tap.
- **Multi-file requests roll back atomically.** If file 3 of 5 is blocked, the
  whole request fails and files 1–2 are removed server-side. Surface the error
  against the batch, keep all files selected locally, and let the user drop the
  flagged one and resubmit. (You cannot tell *which* file tripped from the
  response — if that matters for your surface, upload sequentially.)
- **Videos too**: the poster frame is screened, so a video upload can return
  `MEDIA_NSFW_BLOCKED` exactly like an image.
- **Chunked upload sessions** get the error at the *complete* step, after the
  bytes are transferred — treat a failed complete with these codes the same as
  a direct-upload failure.

## The review band: images that disappear later

An image scoring in the uncertain middle (0.80–0.90) publishes normally but a
moderator sees it later. If they reject it, the **media asset is deleted** —
the post/story/message row survives, its image URL just starts failing.
Additionally the author receives the standard moderation system notification
("your image was removed…").

So both clients must render dead media benignly (most of this already exists —
verify per surface):

- `onError` on every `<img>` / `<Image>` in feed/story/chat/gallery → a quiet
  placeholder ("Image unavailable"), never a broken-image glyph or a crash.
- Cached/optimistic URLs can die at any time; don't retry-loop a 404ing image.
- The author's own view of the post should keep working with the image slot
  showing the placeholder.

There is **no** client-visible "pending review" state — do not build UI for
one.

## Web (ika)

Catch the codes centrally in the API layer so every composer inherits the
behavior, then let surfaces render it inline:

```js
// api/uploadErrors.js — single source of truth for upload error UX
export const UPLOAD_ERROR_UI = {
  MEDIA_NSFW_BLOCKED: {
    retryable: false,
    // Prefer the server's message; this is only the offline fallback.
    fallback: "This image appears to contain explicit content and can't be uploaded here.",
  },
  MEDIA_MODERATION_UNAVAILABLE: {
    retryable: true,
    fallback: "Image screening is temporarily unavailable — please try again in a moment.",
  },
};

export function describeUploadError(err) {
  // err.response?.data is the canonical envelope
  const body = err.response?.data ?? {};
  const known = UPLOAD_ERROR_UI[body.errorCode];
  if (!known) return null;                       // fall through to generic handling
  return {
    code: body.errorCode,
    retryable: known.retryable,
    message: body.message || known.fallback,
  };
}
```

In a composer (pattern — adapt to the surface's existing state shape):

```jsx
const [blocked, setBlocked] = useState(null);   // { fileName, message, retryable }

async function submit(files, text) {
  try {
    await createPost({ files, text });
  } catch (err) {
    const modErr = describeUploadError(err);
    if (modErr) {
      // Keep text + files in state; just mark the batch and stop the spinner.
      setBlocked({ message: modErr.message, retryable: modErr.retryable });
      return;
    }
    throw err;   // existing generic error path
  }
}
```

UX notes for web:

- Render the message **at the file chip / preview thumbnail**, not as a global
  toast — the user needs to know which action to take (remove the image).
- A blocked file should not clear the composer. Losing a typed post because one
  photo tripped the filter is the fastest way to make users hate the filter.
- The drag-and-drop and file-picker paths share the same submit call, so one
  handler covers both.

## Mobile (ika-mobile-app)

Uploads go through the XHR `uploadX` wrapper and `uploads.js` (chunked). Both
reject with the parsed envelope on non-2xx — same central mapping:

```js
// lib/uploadErrors.js (React Native — no DOM types, same logic)
export function describeUploadError(err) {
  const body = err?.data ?? err?.response?.data ?? {};
  switch (body.errorCode) {
    case 'MEDIA_NSFW_BLOCKED':
      return { retryable: false, message: body.message ?? 'This image can’t be uploaded here.' };
    case 'MEDIA_MODERATION_UNAVAILABLE':
      return { retryable: true, message: body.message ?? 'Screening is busy — try again shortly.' };
    default:
      return null;
  }
}
```

Mobile-specific rules:

- **Chat attachments** show per-message progress with cancel; on
  `MEDIA_NSFW_BLOCKED`, stop the progress row and swap it to an inline error
  state on that pending message bubble (with a "remove" affordance) — do not
  pop a blocking Alert over the conversation.
- **Chunked uploads**: the block arrives at the session *complete* call, i.e.
  after 100% progress. Make sure the progress UI can transition
  `100% → failed` without visual glitching, and that the retry path re-uses
  the picked asset rather than forcing a re-pick.
- **Story camera**: a block should return the user to the capture/edit screen
  with the message, not dump them back to the feed.
- **Offline queue** (if a surface queues uploads): `MEDIA_NSFW_BLOCKED` must
  dequeue permanently; `MEDIA_MODERATION_UNAVAILABLE` may stay queued for
  retry.
- Dead-media placeholders: React Native `<Image onError>` swap to the standard
  placeholder component, same as web.

## Copy guidelines (both clients)

- Prefer the server `message` — it is centrally maintained in the message
  registry and already catalogued in
  [user-facing-messages.md](../errors/user-facing-messages.md).
- Never say "NSFW score", show numbers, or imply certainty ("this is explicit
  content"). The model is sometimes wrong; the copy should leave room for that.
- Do not tell users *how* the detection works or what threshold applies —
  that's an evasion manual.

## What clients must NOT do

- Don't pre-screen client-side and skip the upload — the server gate is the
  authority; a client-side model would only desync the two.
- Don't special-case surfaces: the gate is uniform, so one error handler
  serves the whole app.
- Don't build a "pending review" badge — review-band publishing is deliberately
  invisible.
- Don't retry `MEDIA_NSFW_BLOCKED`, including with re-encoded/resized versions
  of the same image.
