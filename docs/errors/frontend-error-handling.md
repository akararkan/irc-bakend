# Frontend Error Handling Guide

> **Audience.** You, the frontend developer working on the React+Vite app. This is the practical
> guide to *consuming* this backend's errors: what arrives on the wire, which codes deserve
> dedicated UI, and which conventions (inline notes, deprecation headers, SSE) you must honor.
> The envelope itself is specified in [error-handling.md](error-handling.md) (canonical); every
> string and code that can travel in it is cataloged in
> [user-facing-messages.md](user-facing-messages.md). This doc tells you what to *do* with them.

---

## 1. The envelope

Every error — validation failure, expired JWT, 404, Cassandra hiccup, unexpected 500 — arrives as
one shape, `ApiErrorResponse`. There is no second format to parse (the handful of known escapes
are listed in [§1.2](#12-known-envelope-escapes)).

```json
{
  "timestamp":  "2026-07-20T14:30:00.123456Z",
  "status":     404,
  "error":      "Not Found",
  "message":    "Question not found with id: 3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
  "path":       "/api/v1/questions/3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
  "errorCode":  "QUESTION_NOT_FOUND",
  "details":    { "resource": "Question", "field": "id", "value": "3f8a1c2e-…" },
  "traceId":    "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
}
```

```ts
export interface ApiFieldError {
  field: string;
  message: string;
  rejectedValue?: unknown;
}

export interface ApiError {
  timestamp: string;                  // ISO-8601 UTC, 'Z'-suffixed
  status: number;                     // mirrors the HTTP status line
  error: string;                      // HTTP reason phrase — decoration, not data
  message: string;                    // human-readable; safe to display for 4xx
  path: string;                       // the request URI that failed
  errorCode?: string;                 // machine-readable — BRANCH ON THIS
  details?: Record<string, unknown>;  // per-error context (retryAfterSeconds, maxSize, …)
  fieldErrors?: ApiFieldError[];      // present only on VALIDATION_FAILED
  traceId: string;                    // quote this in bug reports / support flows
}
```

Notes on the shape:

- **The JSON field is `errorCode`** (the catalog's tables label the column "Code" — same thing).
- Null fields are **omitted entirely** (`@JsonInclude(NON_NULL)`) — type them optional, don't
  assume presence.
- `errorCode` is *usually* present but may be absent on a few legacy throws. Treat
  `errorCode ?? 'UNKNOWN'` as your switch input.

### 1.1 The three rules

1. **Branch on `errorCode`, never on `message` text.** Codes are the stable contract; wording is
   tuned freely (and lives centralized on the backend precisely so it *can* be tuned). A string
   match on `message` is a latent bug.
2. **Display `message` for 4xx.** The backend guarantees 4xx messages are complete, user-safe
   sentences that say what to do next — you don't need a client-side copy table for errors you
   handle generically. 5xx messages are deliberately generic; pair them with the `traceId`.
3. **Keep `traceId`.** Every error response carries one and the server logged a matching line
   before answering. Put it in your error console log, and surface it in any "contact support"
   affordance — it turns "it broke" into a greppable incident.

```ts
const err = (await res.json()) as ApiError;
switch (err.errorCode) {
  case 'STEP_UP_REQUIRED':   /* dedicated flow, §2.2 */ break;
  case 'RATE_LIMITED':       /* countdown, §2.3 */ break;
  default:                   toast.error(err.message);   // 4xx generic fallback
}
console.error(`[api] ${err.status} ${err.errorCode} trace=${err.traceId} ${err.path}`);
```

### 1.2 Known envelope escapes

Honest list — code you should still be defensive around:

- **406** responses are body-less by design (writing JSON would re-trigger the negotiation
  failure).
- **SSE-only requests** get status-only errors with no body — see [§5](#5-sse-streams).
- **Multipart post create** (`POST /api/v1/posts` multipart) has two ad-hoc bodies:
  502 `{"error":"upload_failed", …}` and 500 `{"error":"post_create_failed", …}` — note the key
  is `error`, not `errorCode` (catalog §1.49).
- Two admin endpoints return **400 with an empty body** (`GET /api/v1/admin/audit` without
  `?userId`, tag merge with `from == to`).

So: parse defensively (`res.json().catch(() => null)`) and fall back to `res.status`.

---

## 2. The client-side taxonomy

You do not need bespoke UI for 656 messages. You need dedicated flows for about a dozen codes and
a good generic fallback for everything else. Recommended layering, outermost first:

### 2.1 Auth — 401 vs 403

**401 means "who are you?", 403 means "not you."** Never respond to a 403 by refreshing tokens,
and never respond to a 401 by hiding a button.

**Session expiry (any 401 outside the auth screens)** — the expired-access-token case surfaces as
401 `AUTH_REQUIRED` (or `AUTH_UNAUTHORIZED` / `AUTH_INSUFFICIENT` depending on the path). The
flow is: **refresh once, retry once, then logout**:

```ts
async function apiFetch(input: string, init: RequestInit = {}, retried = false): Promise<Response> {
  const res = await fetch(input, withAuth(init));
  if (res.status !== 401 || isAuthEndpoint(input)) return res;
  if (retried || !(await refreshOnce())) {     // POST /api/v1/auth/refresh — single-flight!
    hardLogout('Your session expired. Please sign in again.');
    throw new SessionExpiredError();
  }
  return apiFetch(input, init, /* retried */ true);
}
```

- **Single-flight the refresh.** Ten components firing on mount produce ten 401s; they must all
  await the *same* refresh promise, not race ten rotations (rotation makes the losers' tokens
  invalid → spurious logouts).
- **Any 401 from `/api/v1/auth/refresh` itself is terminal** — the codes are
  `AUTH_REFRESH_TOKEN_EXPIRED` / `_INVALID` / `_MISSING` / `_NOT_FOUND` / `_REUSED`, and all of
  them mean log out. `AUTH_REFRESH_TOKEN_REUSED` deserves its own copy ("You were signed out of
  all devices for security") — the backend detected token reuse and revoked *every* session.
- **Login-screen 401s are form errors, not session errors**: `AUTH_BAD_CREDENTIALS` (wrong
  email/password — deliberately identical for unknown users), `AUTH_ACCOUNT_DISABLED` (offer
  "verify your email"), `AUTH_ACCOUNT_LOCKED` / `AUTH_ACCOUNT_EXPIRED` /
  `AUTH_CREDENTIALS_EXPIRED` (support/reset paths). Display the `message` — it already says what
  to do.

**403** — three codes, one meaning, one exception:

- `ACCESS_DENIED` — Spring Security / role check failed (e.g. non-admin on `/api/v1/admin/**`).
- `ACCESS_FORBIDDEN` / `FORBIDDEN` — service-layer ownership check ("Not the author").
- Show the message, disable the affordance, **do not retry** — the exception is
  `STEP_UP_REQUIRED`, below.

Ideally you never *see* ownership 403s: don't render edit/delete controls for content the current
user doesn't own.

### 2.2 Step-up — 403 `STEP_UP_REQUIRED`

Sensitive operations (2FA disable, recovery-code regeneration, and every `@RequiresStepUp` admin
mutation — user disable, role change, takedowns, GDPR purge, …) require a *recent* password
re-verification, independent of the JWT being valid. Without one you get:

```json
{ "status": 403, "errorCode": "STEP_UP_REQUIRED",
  "message": "This action requires you to confirm your identity." }
```

**This is not a permission failure.** The user *is* allowed — they just have to prove presence.
Flow:

1. Open a password re-verify modal (keep the original request + body around).
2. `POST /api/v1/security/step-up` with `{ "password": "…" }` — or `{ "code": "…" }` for a TOTP
   confirm. Success is **204 No Content** and arms a server-side window (default **300 s**).
3. Wrong password → 400 `STEP_UP_BAD_PASSWORD` — keep the modal open, show the message inline.
4. On 204, **replay the original request** — it now passes. No token changes hands; the marker is
   server-side.

```ts
if (res.status === 403 && err.errorCode === 'STEP_UP_REQUIRED') {
  const armed = await stepUpModal.confirm();     // handles STEP_UP_BAD_PASSWORD inline
  if (armed) return apiFetch(input, init);       // window lasts ~5 min — batch admin work
  throw err;                                     // user cancelled
}
```

Since the window covers subsequent sensitive calls too, an admin doing a run of moderations gets
prompted once, not per click.

### 2.3 Rate limiting — 429

Two codes, both with retry hints **in the body** (there is no `Retry-After` header):

- `RATE_LIMITED` — per-action burst exceeded. `details: { action, retryAfterSeconds }`.
- `MEDIA_QUOTA_EXCEEDED` — daily upload quota (count or bytes) for the user's role.
  `details: { dimension, role, resetsAt }` — resets midnight UTC.

```json
{ "status": 429, "errorCode": "RATE_LIMITED",
  "message": "Too many comment requests — please slow down",
  "details": { "action": "comment", "retryAfterSeconds": 30 } }
```

UI contract: **show a countdown, not a dead end.** Disable the submit control for
`retryAfterSeconds` (or until `resetsAt`) with the remaining time visible, keep the user's draft
intact, re-enable when the clock runs out. Do not auto-retry in a loop.

### 2.4 Validation — 400 `VALIDATION_FAILED`

```json
{ "status": 400, "errorCode": "VALIDATION_FAILED",
  "message": "One or more fields failed validation. Check 'fieldErrors' for details.",
  "fieldErrors": [
    { "field": "title",    "message": "must not be blank",            "rejectedValue": "" },
    { "field": "tagNames", "message": "size must be between 1 and 5", "rejectedValue": [] }
  ] }
```

Map `fieldErrors[].field` onto your form fields and render each `message` inline next to its
input. Two gotchas:

- One variant (Spring's method-validation wrapper) arrives **without** `fieldErrors` — fall back
  to displaying the top-level `message` as a form-level error. Always
  `err.fieldErrors ?? []`.
- Business-rule rejections often come as 400 `ILLEGAL_ARGUMENT` or a named code
  (e.g. `AUTH_NEW_PASSWORD_SAME_AS_CURRENT`, `OTP_INVALID`, `TWO_FA_INVALID`) with no
  `fieldErrors` — the `message` is written to be shown as-is near the offending control.

Also in the 400 family but **your bug, not the user's**: `MALFORMED_JSON`, `MISSING_PARAMETER`,
`MISSING_REQUEST_PART`, and `TYPE_MISMATCH`. Log these loudly. `TYPE_MISMATCH` has a special
tell: if the offending value was the literal string `"undefined"` or `"null"`, `details.hint` is
`frontend_path_param_unhydrated` — you templated an unhydrated variable into a URL
(`/api/v1/posts/undefined`). Fix the component, don't toast the user.

Upload sizing: 413 `FILE_TOO_LARGE` carries `details.maxSize` — validate client-side against it
too, but keep the server message as the fallback.

### 2.5 Not found — 404

- `{RESOURCE}_NOT_FOUND` (`USER_NOT_FOUND`, `QUESTION_NOT_FOUND`, `STORY_NOT_FOUND`, …) —
  the entity is gone (deleted, or the link is stale). Show a "this content is no longer
  available" state, not an error toast. `details` carries `{resource, field, value}` if you want
  to be specific. Treat the *family* generically: `errorCode.endsWith('_NOT_FOUND')`.
- `ENDPOINT_NOT_FOUND` — no route matched. That's a frontend URL-construction bug; log it.
- `MEDIA_NOT_FOUND` — the file is missing from object storage; render a broken-media
  placeholder, not a page error.

### 2.6 Conflict — 409

- `OPTIMISTIC_LOCK_CONFLICT` — someone edited the same row concurrently and server-side retries
  were exhausted. **Not a server fault**: re-fetch the resource, then either auto-retry the
  mutation on fresh state or show "this was changed by someone else — review and try again".
- `{RESOURCE}_DUPLICATE` (`USER_DUPLICATE`, `EMAIL_DUPLICATE`, …) — uniqueness violation;
  `details.field` tells you which input to mark inline ("username already taken"). Same
  suffix-family trick: `errorCode.endsWith('_DUPLICATE')`.
- `DATA_INTEGRITY_VIOLATION` / `RESOURCE_CONFLICT` — generic conflict; show the message.
- `LAST_ADMIN` — demoting the last remaining ADMIN is refused. Admin UI should explain rather
  than let the user retry into the same wall (better: disable the role dropdown for the last
  admin proactively).

### 2.7 Confirm-and-resend — `LARGE_AUDIENCE_CONFIRMATION_REQUIRED`

`POST /api/v1/admin/notifications/announcements` targeting ≥ half the platform without
`confirmLargeAudience: true` returns 400 `LARGE_AUDIENCE_CONFIRMATION_REQUIRED` — a fat-finger
guard, not a failure. Show a confirm dialog quoting the message (it interpolates audience size),
and on confirm **resend the identical request with `confirmLargeAudience: true`**. Do not set the
flag by default — that defeats the guard.

### 2.8 Policy blocks — `CONTENT_BLOCKED_BY_POLICY`

Content creation (posts, comments, …) whose text matches a BLOCK-severity platform keyword
returns 400 `CONTENT_BLOCKED_BY_POLICY` ("This content violates platform policy and cannot be
published."). Show the message on the composer and **keep the draft** — the user edits and
resubmits. There is nothing to retry unchanged; don't offer a retry button.

### 2.9 Infrastructure — 5xx

- `DATASTORE_UNAVAILABLE` (503) and `STORAGE_UNAVAILABLE` (503) are **transient by contract** —
  the datastore or object storage didn't answer. Show a "temporary problem, try again" state; a
  single delayed automatic retry is reasonable for reads.
- `STORAGE_ERROR` (502) — the storage service answered with an error; retry may help, message +
  traceId if it doesn't.
- `INTERNAL_ERROR`, `ILLEGAL_STATE`, `DATASTORE_QUERY_ERROR` (500) — bugs. Generic apology +
  `traceId`. The message never leaks internals, so it's safe to show verbatim.

---

## 3. Success with caveats — inline notes on 200s

Some 2xx responses carry `note` / `warning` / flag fields, and **they are part of the API
contract**: when the backend says a listing was capped or a series starts at collector
deployment, the UI must render that caveat. Silently dropping it turns an honest partial answer
into a lie. The full inventory is [user-facing-messages.md §2](user-facing-messages.md); the
patterns you must wire:

- **`degraded: true` on `GET /api/v1/search`** — Elasticsearch was unreachable; the result list
  is empty *because search failed*, not because nothing matched. Show "search is temporarily
  unavailable", **never** the empty-results state. The `X-Search-Degraded: true` response header
  mirrors the flag (absent when healthy). `degraded: false` + empty list = genuinely no results.
- **Capped / truncated listings** — e.g. admin tag merge returns `"truncated": true` when its
  5000-row cap was hit (re-run needed); activity erase responds with a "hard cap 10k rows per
  call" note; stream-recordings detail rows are capped with a note. Render the caveat next to
  the data ("showing first N — rerun for more"), don't imply completeness.
- **Collector-start caveats** — admin analytics bodies state that series begin at collector
  deployment ("postCreatesPerDay is collector-sourced and starts at collector deployment").
  Surface that under the chart; a series that starts mid-history without explanation reads as a
  crash to the viewer.
- **`warning` fields are stronger than notes** — e.g. the legal-hold execute response and the
  "permit-all is ON" config warning. Render them prominently (banner, not tooltip).
- **Async media status** — upload processing failures never come back on the upload call; they
  appear later in `GET /api/v1/media/{id}` → `errorMessage` ("Rejected by moderation scan.",
  "Unsupported or corrupt image.", …). Poll status and surface `errorMessage` on the asset.

Practical shape: make your response types carry `note?: string; warning?: string;` where the
endpoint docs say so, and have a shared `<ResponseCaveat note={…} warning={…} />` you drop next
to the affected data.

---

## 4. Deprecation headers

Two legacy routes were re-homed under `/api/v1/admin/**` and survive only as aliases. Calling the
old path still works but answers with:

```
Deprecation: true
Link: </api/v1/admin/sounds/{id}/approve>; rel="successor-version"
```

| Deprecated route | Successor |
|---|---|
| `POST /api/v1/sounds/{id}/approve` | `POST /api/v1/admin/sounds/{id}/approve` |
| `PUT /api/v1/channels/{id}/verified` | `PUT /api/v1/admin/channels/{id}/verified` |

Add a response interceptor so future deprecations self-report instead of rotting:

```ts
if (res.headers.get('Deprecation') === 'true') {
  console.warn(`[deprecated] ${init.method ?? 'GET'} ${input} → migrate to ${res.headers.get('Link')}`);
}
```

Log it, file it, migrate to the `Link` successor. The alias will eventually be removed.

---

## 5. SSE streams

Full spec: [../realtime/overview.md](../realtime/overview.md). The error-relevant contract:

- **Auth is a query param.** Browser `EventSource` can't set headers, so every identity-bearing
  stream takes `?token=<accessToken>` (an `ACCESS`-type JWT — a refresh token is rejected).
  A fresh token per subscribe; remember the URL outlives your in-memory token after a refresh —
  reconnect with the *current* one.
- **SSE errors have no JSON envelope.** SSE-negotiated requests get **status-only** error
  responses (403/404/503/500), and some streams write a plain-text 401 body
  (`Authentication required. Pass access token as ?token=<jwt>.`). All your `onerror` handler
  reliably has is "it closed" — so don't try to parse; reconnect.
- **No error events inside streams.** Streams emit only data events (`connected`, `heartbeat`,
  and the stream's typed events); failures just close the emitter. Event-name casing differs per
  stream (dotted lowercase on chat, lowercased enums on story streams, UPPERCASE on
  post/research/question) — register listeners exactly as each stream's doc spells them.
- **Reconnect discipline**: heartbeats come every 15 s (chat, notifications) or 25 s (the rest).
  Run a watchdog — **missed heartbeats > 2× cadence ⇒ `close()` and resubscribe** (EventSource
  won't notice a silently dead connection on its own). Otherwise let EventSource's built-in
  auto-reconnect work (`retry: 3000` on the busy streams); your `onerror` can be a no-op.
- **`connected` = reconcile.** Server timeouts (24 h / 10 min / 5 min) complete the emitter and
  the browser transparently reconnects, so `connected` fires repeatedly over a session. Treat
  *every* `connected` as "re-fetch authoritative state via REST" — events carry deltas, not
  counter values, and anything emitted while you were down is gone.

```ts
function subscribe(url: string, getToken: () => string, handlers: Record<string, (e: MessageEvent) => void>,
                   { heartbeatMs = 25_000 } = {}) {
  let es: EventSource, watchdog: number;
  const arm = () => { clearTimeout(watchdog);
    watchdog = window.setTimeout(() => { es.close(); open(); }, heartbeatMs * 2); };
  const open = () => {
    es = new EventSource(`${url}?token=${getToken()}`);   // EventSource can't set headers
    es.addEventListener('connected', e => { arm(); handlers.connected?.(e); }); // reconcile here
    es.addEventListener('heartbeat', arm);
    for (const [name, fn] of Object.entries(handlers)) es.addEventListener(name, e => { arm(); fn(e); });
    es.onerror = () => {};                                 // auto-reconnect handles it
  };
  open();
  return () => { clearTimeout(watchdog); es.close(); };
}
```

One cap to know about: the per-user 24 h streams (chat, notifications) allow **5 emitters per
user**, LRU-evicted — a user with many tabs will have the oldest tab's stream die silently; the
watchdog above recovers it.

---

## 6. Adding a new error (checklist)

When a backend change introduces a new error, the sources of truth are paired:

1. **Backend**: the code + message land as constants in the module's
   `ak.dev.irc.app.common.messages.{Module}Messages` class
   (`src/main/java/ak/dev/irc/app/common/messages/` — `AuthMessages`, `PostMessages`,
   `ChatMessages`, …). The constant name **is** the code string; message text lives beside it
   with an `_MSG` suffix. No inline literals at throw sites.
2. **Catalog**: a matching row is added to
   [user-facing-messages.md](user-facing-messages.md) — code + message are maintained as a pair,
   byte-for-byte. (Backend mechanics: [exception-design.md](exception-design.md).)
3. **Frontend**: ask "does this code need dedicated UI, or does the generic 4xx fallback
   (display `message`) cover it?" Most codes need nothing from you — that's the point of rule 2
   in §1.1. If it *does* need a flow (a new confirm-and-resend flag, a new retry hint), add the
   code to your taxonomy switch and this doc's table below.
4. **Never** pin behavior to `message` text, and never hardcode a message the backend already
   sends — you'd fork the copy.

---

## 7. Quick reference — the codes that matter

The ~30 codes a frontend must recognize. Everything else: show `message` (4xx) or generic +
`traceId` (5xx). Exhaustive list: [user-facing-messages.md](user-facing-messages.md).

| Code | HTTP | UI action |
|---|---|---|
| `AUTH_REQUIRED` / `AUTH_UNAUTHORIZED` / `AUTH_INSUFFICIENT` | 401 | Refresh once → retry once → logout (§2.1). |
| `AUTH_BAD_CREDENTIALS` | 401 | Login form error; message as-is. |
| `AUTH_ACCOUNT_DISABLED` | 401 | Login: offer email verification / support. |
| `AUTH_ACCOUNT_LOCKED` / `AUTH_ACCOUNT_EXPIRED` / `AUTH_CREDENTIALS_EXPIRED` | 401 | Login: support / password-reset path. |
| `AUTH_REFRESH_TOKEN_*` (any) | 401 | Hard logout. `_REUSED` gets "signed out of all devices for security" copy. |
| `AUTH_CURRENT_PASSWORD_INVALID` | 401 | Inline on the change-password form. |
| `ACCESS_DENIED` | 403 | Role denied — hide/disable the surface; never retry. |
| `FORBIDDEN` / `ACCESS_FORBIDDEN` | 403 | Ownership denied — show message; don't render the control next time. |
| `STEP_UP_REQUIRED` | 403 | Password re-verify modal → `POST /api/v1/security/step-up` → 204 → replay original (§2.2). |
| `STEP_UP_BAD_PASSWORD` | 400 | Keep the step-up modal open; inline error. |
| `VALIDATION_FAILED` | 400 | Map `fieldErrors[]` inline; fall back to `message` when absent (§2.4). |
| `ILLEGAL_ARGUMENT` | 400 | Show `message` next to the offending action. |
| `MALFORMED_JSON` / `MISSING_PARAMETER` / `MISSING_REQUEST_PART` | 400 | Client bug — log loudly, generic toast. |
| `TYPE_MISMATCH` | 400 | Client bug; `details.hint === 'frontend_path_param_unhydrated'` ⇒ you sent `undefined` in a URL. |
| `OTP_INVALID` / `TWO_FA_INVALID` | 400 | Inline on the code input; allow re-entry. |
| `CONTENT_BLOCKED_BY_POLICY` | 400 | Show on composer, keep the draft, no retry button (§2.8). |
| `LARGE_AUDIENCE_CONFIRMATION_REQUIRED` | 400 | Confirm dialog → resend with `confirmLargeAudience: true` (§2.7). |
| `*_NOT_FOUND` (family) | 404 | "Content no longer available" state, not an error toast (§2.5). |
| `ENDPOINT_NOT_FOUND` | 404 | Frontend URL bug — log it. |
| `MEDIA_NOT_FOUND` | 404 | Broken-media placeholder. |
| `OPTIMISTIC_LOCK_CONFLICT` | 409 | Re-fetch fresh state, then retry or prompt (§2.6). |
| `*_DUPLICATE` (family) | 409 | Inline "already taken" on `details.field`. |
| `RESOURCE_CONFLICT` / `DATA_INTEGRITY_VIOLATION` | 409 | Show `message`. |
| `LAST_ADMIN` | 409 | Explain; ideally disable the demote control pre-emptively. |
| `FILE_TOO_LARGE` | 413 | Show limit from `details.maxSize`; also validate client-side. |
| `RATE_LIMITED` | 429 | Countdown from `details.retryAfterSeconds`; keep draft; no auto-retry loop (§2.3). |
| `MEDIA_QUOTA_EXCEEDED` | 429 | Countdown to `details.resetsAt` (midnight UTC); disable upload. |
| `INTERNAL_ERROR` / `ILLEGAL_STATE` / `DATASTORE_QUERY_ERROR` | 500 | Generic apology + `traceId`. |
| `STORAGE_ERROR` | 502 | Storage errored — retry may help; then generic + `traceId`. |
| `STORAGE_UNAVAILABLE` / `DATASTORE_UNAVAILABLE` | 503 | Transient — "temporary problem, try again"; one delayed auto-retry for reads is fine. |

---

## See also

- [error-handling.md](error-handling.md) — the canonical envelope spec + full code catalog
- [user-facing-messages.md](user-facing-messages.md) — every string/code, all four channels
- [exception-design.md](exception-design.md) — how the backend throws (for context)
- [../realtime/overview.md](../realtime/overview.md) — SSE streams, event tables, reconnect spec
