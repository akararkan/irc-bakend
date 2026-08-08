# Auth, roles, step-up & impersonation

Part of the [admin dashboard frontend guide](README.md).
Legend: **SU** = step-up required (§[auth-and-roles.md](auth-and-roles.md)) ·
roles in the *Who* column are the `hasRole`/`hasAnyRole` grants as coded ·
list endpoints paginate per [conventions.md](conventions.md).
Wire-level request/response JSON: [../api/](../api/README.md).

---

## 1. Auth & session plumbing

### 1.1 Tokens

| Concern | How it works |
|---------|--------------|
| Login | `POST /api/v1/auth/login` → `AuthResponse` (access + refresh token pair). Stateless JWT; no server session, CSRF disabled. |
| Sending it | `Authorization: Bearer <accessToken>` on every request. The auth filter resolves **cookie first, then header** — if the backend set token cookies at login, plain `fetch` with `credentials: 'include'` also works. |
| Refresh | `POST /api/v1/auth/refresh` (body optional — the refresh token is also read from its cookie). On a 401 from any admin call: refresh once, retry, then hard-logout to the login screen. |
| Logout | `POST /api/v1/auth/logout` (this session) · `POST /api/v1/auth/logout-all`. |
| sid-binding | Every access token is bound to a session id (`sid`). Admins can be force-logged-out via the session denylist (`sid:denied:{sid}`) — treat an unexpected 401 mid-session as "revoked", not a bug. |
| SSE | `EventSource` cannot set headers — every SSE endpoint accepts `?token=<accessJWT>` as a query-param fallback (§5.5). |

### 1.2 The double gate and the four staff roles

Everything under `/api/v1/admin/**` is gated **twice**: the security filter
chain requires a staff role for the whole prefix, and every controller carries
its own `@PreAuthorize`. The platform has seven roles; four are staff:
**ADMIN**, **MODERATOR**, **SUPPORT**, **ANALYST** (plus the non-staff
USER / RESEARCHER / SCHOLAR). The role arrives in the JWT claims — decode it
client-side to drive nav rendering, and let the server 403 be the backstop,
never the only check you skip.

Role → visible sections matrix ([architecture.md §6](../foundation/architecture.md)) — this
drives which nav entries render at all:

| Role | Read-write sections | Read-only sections | Never sees |
|------|--------------------|--------------------|------------|
| **ADMIN** | Everything | — | — (critical ops still require step-up) |
| **MODERATOR** | Content moderation, Safety & reports, moderation parts of Research/QnA and Chat/Channels/Live, Sounds | Users (inspection), Logs/Audit | Role changes, Operations, Search reindexes, Analytics exports, Feed config |
| **SUPPORT** | User inspection + non-destructive account aids | Notifications (stats/registry), Logs, Safety report intake (read) | Any takedown, any role change, any ops control |
| **ANALYST** | — (strictly read-only) | Analytics, Logs/Audit, Search & feed observability | Every mutation |

Per-endpoint grants in the §4 tables are the source of truth — e.g. the users
directory reads allow MODERATOR/SUPPORT, but every user mutation is
ADMIN-only at the class level.

### 1.3 403 handling — two different animals

A 403 from an admin route means one of two things; the error envelope's
`errorCode` disambiguates:

1. **`STEP_UP_REQUIRED`** — the caller has the right role but no armed
   step-up marker. Trigger the step-up modal and auto-retry (§2).
2. **Anything else** — the role genuinely lacks the grant (or an
   impersonation token hit a non-GET). Render a "you don't have access to
   this" state; do **not** loop into the step-up modal.

Error responses across the whole API use the canonical envelope documented in
[../errors/error-handling.md](../../errors/error-handling.md) — parse
`errorCode` + `message` from there; the frontend consumption patterns (retry
rules, step-up modal, 429 countdowns, SSE reconnects) are in
[../errors/frontend-error-handling.md](../../errors/frontend-error-handling.md),
and user-facing strings are catalogued in
[../errors/user-facing-messages.md](../../errors/user-facing-messages.md).

---

## 2. Step-up flow

Sensitive actions (danger **high**/**critical** — every **SU** row in §4)
require a short-lived re-verification marker in addition to the normal token.

| Piece | Detail |
|-------|--------|
| Arm | `POST /api/v1/security/step-up` with body `{"password": "…"}` **or** `{"code": "…"}` (2FA TOTP). `204` on success, `400` on a bad/empty credential. |
| TTL | Redis marker `stepup:{userId}`, default **300 s** (`app.security.step-up.ttl-seconds` — visible in `GET /api/v1/admin/ops/config`). One arm covers a burst of related actions, not a whole shift. |
| Enforcement | `@RequiresStepUp` on the handler → absent marker → **403 `STEP_UP_REQUIRED`**. Reads never require step-up, with deliberate exceptions: PII reveal, recordings, break-glass activity reads, contact-sync compliance, and `GET /admin/ops/config`. |

**Recommended modal UX** (matches the settings module's proven flow):

1. Fire the action optimistically. On 403 `STEP_UP_REQUIRED`, open a modal:
   "Confirm it's you" with a password field (and a 2FA-code tab when the admin
   has 2FA on).
2. `POST /api/v1/security/step-up`; on 204, **auto-retry the original
   request** with the same payload (mutations honor the `Idempotency-Key`
   header — generate one per action, reuse it on the retry, and replays
   within 24 h are deduplicated server-side).
3. Cache "armed until" client-side (now + 300 s) purely to *skip the modal
   proactively* — the server marker is authoritative; still handle a surprise
   `STEP_UP_REQUIRED` if the clock drifted.
4. Show a subtle "elevated — Ns left" chip while armed so admins understand
   why the second prompt appears later.

---

## 3. Impersonation ("view as user")

Read-only, always. No write-impersonation exists, by design.

| Step | Endpoint | Notes |
|------|----------|-------|
| Start | `POST /api/v1/admin/users/{userId}/impersonate` — body `{"reason": "…"}`, **min 10 chars**, **SU**, ADMIN-only | Returns `{token, targetUserId, targetUsername, expiresAt}` — a special JWT of type `IMPERSONATION` carrying only `ROLE_IMPERSONATED_READ`. **TTL 15 min.** One active session per admin (starting a new one revokes the old). Admins cannot be impersonated; nor can you impersonate yourself. |
| Use | Send the impersonation token as the Bearer (or `?token=` on SSE) on **GET requests only** | The auth filter rejects every non-GET before authorization even runs — expect 403 on any mutation. Chat/DM message content stays off-limits exactly as it is for admins. |
| End | `DELETE /api/v1/admin/impersonation` (with your **normal admin token**) | 204. Denylists the impersonation sid; also called implicitly when a new impersonation starts. Expiry alone also kills it. |

**Banner UX (non-negotiable):** while an impersonation token is in use, pin a
full-width, high-contrast banner: *"Viewing as @{targetUsername} — read-only ·
expires in {mm:ss} · [Exit]"*. Keep the impersonation token in memory only
(never persist it), keep the admin token untouched underneath, and hard-swap
back on Exit/expiry. Every request under the token is audit-attributed to the
**admin** with an `impersonating={targetId}` marker — tell the admin that in
the entry dialog, along with the mandatory reason field.

---
