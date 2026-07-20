# Authentication API

Registration, login, token refresh, logout, and password rotation for the IRC
platform. Tokens are JWTs delivered over **two channels simultaneously** —
HttpOnly cookies for browsers and the JSON response body for mobile/API
clients using `Authorization: Bearer` headers.

**Base path:** `/api/v1/auth`

There is intentionally **no forgot-password / reset-token flow**. The only way
to rotate a password is [`POST /change-password`](#post-change-password), which
requires an authenticated session and re-verifies the current password.

All errors use the unified envelope described in
[../errors/error-handling.md](../errors/error-handling.md).

Related: [users.md](users.md) · [profile.md](profile.md) ·
[social.md](social.md) · [search.md](search.md) ·
[security-model.md](security-model.md)

---

## Token model

Two JWT types are issued, distinguished by the `tokenType` claim:

| Type | Purpose | Default TTL | Where it may be used |
|---|---|---|---|
| `ACCESS` | Sent on every API request | 1 h (`3600000` ms) | `Authorization: Bearer` header or the `IRC_TOKEN` cookie |
| `REFRESH` | Exchanged at `POST /refresh` for a new pair | 7 d (`604800000` ms) | `POST /refresh` / `POST /logout` body, or the `IRC_TOKEN_REFRESH` cookie |

Both are HS256-signed with issuer `irc-platform`. Access-token claims:

| Claim | Value |
|---|---|
| `sub` | User id (UUID) |
| `email` | Account email |
| `username` | User-set handle (**never** the email — they are independent fields) |
| `fullName` | `fname + " " + lname` |
| `role` | `USER` / `RESEARCHER` / `SCHOLAR` / `ADMIN` |
| `authorities` | Spring authorities (`ROLE_*`; `SCHOLAR` also carries `ROLE_RESEARCHER`) |
| `tokenType` | `ACCESS` or `REFRESH` |

Refresh tokens carry only `tokenType=REFRESH`, a unique `jti`, and the subject.
Every issued refresh token is **also persisted** in the `refresh_tokens` table,
which is what makes revocation and reuse detection possible.

### Configuration (`app.jwt.*` in `application.yaml`)

| Key | Default | Meaning |
|---|---|---|
| `app.jwt.secret` | env `APP_JWT_SECRET` | Base64 HMAC signing key |
| `app.jwt.access-token-expiration-ms` | `3600000` | Access token TTL |
| `app.jwt.refresh-expiration-ms` / `refresh-token-expiration-ms` | `604800000` | Refresh token TTL |
| `app.jwt.issuer` | `irc-platform` | Required `iss` claim |

`AuthResponse.expiresIn` (seconds) always reflects the configured access TTL so
clients know when to refresh.

### Cookie + bearer flows

Every successful auth response sets **HttpOnly cookies** *and* returns the same
tokens in the JSON body:

| Cookie | Contents | Max-Age |
|---|---|---|
| `IRC_TOKEN` | Access token | `86400` s (config `jwt.cookie-max-age`) |
| `IRC_TOKEN_REFRESH` | Refresh token | Refresh TTL (7 d default) |

Cookies are `HttpOnly`, `SameSite=Strict` and `Secure=false` by default (all
configurable). On authenticated requests the server resolves the token
**cookie first, then the `Authorization: Bearer` header**. `/refresh` and
`/logout` likewise accept the refresh token from the request body or fall back
to the cookie. Browser clients therefore never need to touch tokens; API
clients ignore cookies and use the body values as Bearer tokens.

### `AuthResponse` shape

```json
{
  "accessToken":  "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType":    "Bearer",
  "expiresIn":    3600,
  "user":         { /* UserResponse — omitted on /refresh */ }
}
```

| Field | Type | Notes |
|---|---|---|
| `accessToken` | string | ACCESS JWT |
| `refreshToken` | string | REFRESH JWT (also persisted server-side) |
| `tokenType` | string | Always `"Bearer"` |
| `expiresIn` | long | Access-token TTL in **seconds** |
| `user` | object \| absent | Full [`UserResponse`](users.md#userresponse) on register / login / change-password; omitted on refresh (`NON_NULL` serialization) |

---

## `POST /register`

```
POST /api/v1/auth/register
```

**Auth:** none (public)

Creates the `users` row **and its linked `user_profiles` row** in one
transaction (the profile's `displayName` defaults to `fname + " " + lname`),
then immediately issues a token pair — no separate login step needed.

> **Role default:** the platform default role for new accounts is `USER`.
> The current build temporarily assigns `SCHOLAR` at registration as a
> development/seeding convenience — do not rely on this in clients; badges and
> permissions always derive from whatever `role` the response reports.

**Request body:**

```json
{
  "fname":    "Ahmad",
  "lname":    "Al-Rashid",
  "username": "ahmad.rashid",
  "email":    "ahmad@example.com",
  "password": "Str0ng!Pass"
}
```

| Field | Type | Constraints |
|---|---|---|
| `fname` | string | Required, max 80 chars |
| `lname` | string | Required, max 80 chars |
| `username` | string | Required, 3–50 chars. The user-set public handle — independent of the email, never derived from it |
| `email` | string | Required, valid email, unique |
| `password` | string | Required, 8–128 chars (BCrypt-hashed, strength 12) |

**Response:** `201 Created` — [`AuthResponse`](#authresponse-shape) with `user`

```json
{
  "accessToken":  "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType":    "Bearer",
  "expiresIn":    3600,
  "user": {
    "id":              "550e8400-e29b-41d4-a716-446655440000",
    "fname":           "Ahmad",
    "lname":           "Al-Rashid",
    "username":        "ahmad.rashid",
    "email":           "ahmad@example.com",
    "role":            "USER",
    "badges":          [],
    "isEmailVerified": false,
    "profile":         { "displayName": "Ahmad Al-Rashid", "followerCount": 0, "followingCount": 0 },
    "createdAt":       "2026-07-20T10:00:00"
  }
}
```

**Side effects:**

- Inserts `users` row (enabled immediately) and `user_profiles` row (1-to-1)
- Persists a `refresh_tokens` row for the new session
- Sets `IRC_TOKEN` + `IRC_TOKEN_REFRESH` HttpOnly cookies

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Any bean-validation constraint violated (`fieldErrors` lists each field) |
| 409 | `USER_DUPLICATE` | Email already registered (`details.field = "email"`) |
| 409 | `USER_DUPLICATE` | Username already taken (`details.field = "username"`) |

---

## `POST /login`

```
POST /api/v1/auth/login
```

**Auth:** none (public)

Authenticates with **username or email** plus password. The `username` field
accepts either identifier — the server resolves it to the account. Remember
that `username` and `email` are two independent fields on every account; the
handle is user-chosen and never derived from the email.

> **No account enumeration (current behavior):** a login attempt against a
> **non-existent account now returns the exact same generic `401
> AUTH_BAD_CREDENTIALS` error as a wrong password**. User-not-found is
> collapsed into bad-credentials inside the authentication provider
> (`hideUserNotFoundExceptions`), so the login response can no longer be used
> to probe which emails or usernames are registered.

**Request body:**

```json
{
  "username": "ahmad.rashid",
  "password": "Str0ng!Pass"
}
```

| Field | Type | Description |
|---|---|---|
| `username` | string | Required. Account **username or email** |
| `password` | string | Required. Account password |

**Response:** `200 OK` — [`AuthResponse`](#authresponse-shape) with `user`
(same shape as register).

**Side effects:**

- Updates `users.last_login_at`
- Persists a new `refresh_tokens` row (one per device/session)
- Sets both HttpOnly cookies

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Missing `username` or `password` |
| 401 | `AUTH_BAD_CREDENTIALS` | Wrong password **or unknown account** — deliberately indistinguishable (`"Invalid email or password."`) |
| 401 | `AUTH_ACCOUNT_DISABLED` | Account disabled / soft-deleted |
| 401 | `AUTH_ACCOUNT_LOCKED` | Account locked |

---

## `POST /refresh`

```
POST /api/v1/auth/refresh
```

**Auth:** none (public) — the refresh token itself is the credential.

Exchanges a valid refresh token for a **new access + refresh pair**. Rotation
is mandatory: the presented token is revoked the moment it is exchanged, and
the new pair replaces it (body + cookies).

> **Reuse detection:** if a refresh token that has **already been rotated
> (revoked)** is presented again, it is treated as theft. The server
> immediately revokes **every refresh token belonging to that user** — the
> whole session family, on all devices — clears the auth cookies, and returns
> `401 AUTH_REFRESH_TOKEN_REUSED`. All devices must log in again.

**Request body** (optional — the `IRC_TOKEN_REFRESH` cookie is used when the
body is absent):

```json
{ "refreshToken": "eyJ..." }
```

| Field | Type | Description |
|---|---|---|
| `refreshToken` | string | Optional. Falls back to the refresh cookie |

**Response:** `200 OK` — [`AuthResponse`](#authresponse-shape) **without**
`user` (only the token pair rotates):

```json
{
  "accessToken":  "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType":    "Bearer",
  "expiresIn":    3600
}
```

**Side effects:**

- Marks the presented `refresh_tokens` row revoked; inserts the replacement
- Rotates both HttpOnly cookies
- On reuse detection: revokes **all** of the user's refresh tokens and clears cookies

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_REFRESH_TOKEN_MISSING` | No token in body or cookie |
| 401 | `AUTH_REFRESH_TOKEN_INVALID` | JWT malformed, bad signature, or expired |
| 401 | `AUTH_WRONG_TOKEN_TYPE` | An ACCESS token was supplied (`details.expectedType = "REFRESH"`) |
| 401 | `AUTH_REFRESH_TOKEN_NOT_FOUND` | Token not in the database (already deleted / never issued) |
| 401 | `AUTH_REFRESH_TOKEN_REUSED` | Revoked token replayed — **all sessions terminated** |
| 401 | `AUTH_REFRESH_TOKEN_EXPIRED` | Stored token past its `expires_at` |

---

## `POST /logout`

```
POST /api/v1/auth/logout
```

**Auth:** Bearer JWT required

Ends the **current session**: revokes the supplied refresh token and clears
both auth cookies. Other devices stay logged in.

**Request body** (optional — refresh cookie used when absent):

```json
{ "refreshToken": "eyJ..." }
```

**Response:** `200 OK` (empty body)

**Side effects:**

- Revokes the matching `refresh_tokens` row (no-op if none resolved)
- Clears `IRC_TOKEN` + `IRC_TOKEN_REFRESH` cookies

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_TOKEN_INVALID` | Missing/invalid access token |

---

## `POST /logout-all`

```
POST /api/v1/auth/logout-all
```

**Auth:** Bearer JWT required

Terminates **every session** for the caller: revokes all of the user's refresh
tokens. Every other device is force-logged-out the next time it tries to
refresh; the caller's own access token remains technically valid until its
short TTL expires, but the cookies are cleared immediately.

**Request body:** none

**Response:** `200 OK` (empty body)

**Side effects:**

- Revokes **all** `refresh_tokens` rows for the user
- Clears both auth cookies

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_TOKEN_INVALID` / `AUTH_UNAUTHORIZED` | Not authenticated |

---

## `POST /change-password`

```
POST /api/v1/auth/change-password
```

**Auth:** Bearer JWT required

Authenticated password rotation — the **only** password-change path on the
platform (no forgot-password flow by design). The caller must re-supply their
current password (defense against session hijack). On success **every refresh
token for the user is revoked** — including the caller's old one — and a fresh
access/refresh pair is issued to the caller, so this device stays logged in
while **every other device must log in again** with the new password.

**Request body:**

```json
{
  "currentPassword": "Str0ng!Pass",
  "newPassword":     "Even$tr0nger!2026"
}
```

| Field | Type | Constraints |
|---|---|---|
| `currentPassword` | string | Required — re-verified against the stored hash |
| `newPassword` | string | Required, 8–128 chars, must differ from the current password |

**Response:** `200 OK` — [`AuthResponse`](#authresponse-shape) with a fresh
token pair (includes `user`).

**Side effects:**

- Re-hashes and persists the new password
- Revokes **all** existing refresh tokens (other sessions die)
- Issues + persists a new refresh token for the caller; rotates both cookies

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | New password shorter than 8 chars, missing fields |
| 400 | `AUTH_NEW_PASSWORD_SAME_AS_CURRENT` | New password equals the current one |
| 401 | `AUTH_CURRENT_PASSWORD_INVALID` | `currentPassword` does not match |
| 401 | `AUTH_REQUIRED` | Not authenticated |
