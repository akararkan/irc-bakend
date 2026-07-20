# Security Model — Authorization, Enforcement, and Auth Flows

How the IRC platform decides who may call what: annotation-driven
authorization on every controller, a belt-and-braces admin rule at the filter
chain, an explicit local-testing kill switch, query-param tokens for SSE, and
enumeration-proof login.

Related: [auth.md](auth.md) · [users.md](users.md) · [profile.md](profile.md) ·
[social.md](social.md) · [search.md](search.md) ·
[../errors/error-handling.md](../errors/error-handling.md)

---

## 1. Annotation-driven authorization — enforced by default

The entire authorization surface is **annotation-driven**: every controller
method carries a `@PreAuthorize` rule (over a hundred across the app —
typically `isAuthenticated()`, admin endpoints `hasRole('ADMIN')`). The HTTP
filter chain itself stays permissive (`anyRequest().permitAll()`) so that
optional-auth endpoints — public profiles, anonymous view counts,
`/who-to-follow` — keep working; the annotations are what actually lock the
API down.

**As of the current build this method security is ENFORCED BY DEFAULT.**
`@EnableMethodSecurity` is registered whenever `app.security.permit-all` is
`false` or unset (`matchIfMissing = true`), so a fresh checkout / production
deploy is secure with no extra configuration.

Sessions are fully **stateless** (`SessionCreationPolicy.STATELESS`); CSRF,
form login, and HTTP Basic are disabled. Authentication state comes only from
the JWT resolved per request.

## 2. Admin routes: defense in depth

`/api/v1/admin/**` additionally requires **`ROLE_ADMIN` at the filter-chain
level**:

- Filter chain: `requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`
- Controllers: `@PreAuthorize("hasRole('ADMIN')")` on each admin method

The chain-level rule exists so that a forgotten annotation on a future admin
endpoint can never expose it — both layers must pass. Non-admins receive
`403 ACCESS_DENIED` in the unified error envelope.

## 3. `SECURITY_PERMIT_ALL` — local-testing kill switch

Setting the env var `SECURITY_PERMIT_ALL=true` (property
`app.security.permit-all`) **disables `@PreAuthorize` enforcement entirely** —
the method-security config is not registered and the admin chain rule is
skipped. The server logs a loud warning at startup.

This exists **only for throwaway local testing** (hitting endpoints from
Postman without minting tokens). It must never be set in staging or
production; with it on, every endpoint is effectively public.

| State | `@PreAuthorize` | `/api/v1/admin/**` chain rule |
|---|---|---|
| default (`false` / unset) | Enforced | `hasRole("ADMIN")` |
| `SECURITY_PERMIT_ALL=true` | **Ignored** | **Open** |

## 4. Request authentication — cookie + bearer JWT

A single JWT filter runs before authorization on every request (except
`/api/v1/auth/**`, which is public by design):

1. Resolve the access token — **`IRC_TOKEN` cookie first, then
   `Authorization: Bearer <token>`**.
2. No token → pass through anonymously (public endpoints work; protected ones
   fail at the `@PreAuthorize` layer).
3. Token present → must be valid, unexpired, and of type **`ACCESS`**
   (refresh tokens are rejected with `401 AUTH_WRONG_TOKEN_TYPE`); the user
   must still exist, be enabled, and be unlocked. Failures return `401` in
   the unified envelope (`AUTH_TOKEN_INVALID`, `AUTH_ACCOUNT_DISABLED`,
   `AUTH_ACCOUNT_LOCKED`, `AUTH_USER_NOT_FOUND`).

Token issuance, TTLs, rotation, and reuse detection are covered in
[auth.md](auth.md#token-model).

## 5. SSE endpoints accept `?token=<accessToken>`

Browser `EventSource` **cannot set request headers**, so streaming endpoints
(URIs ending in `/stream`, e.g. `GET /api/v1/notifications/stream`,
`GET /api/v1/stories/tray/stream`) accept the access token as a query
parameter:

```
GET /api/v1/notifications/stream?token=<accessToken>
```

Mechanics:

- The JWT filter deliberately does **not** 401 an SSE request with a missing,
  stale, or wrong-type cookie/header token — it passes the request through and
  lets the controller validate the `?token=` parameter itself (otherwise a
  stale cookie surfaces in the browser as a misleading "CORS / status null"
  error).
- The controller accepts only a **valid `ACCESS`-type** JWT from the query
  param; refresh tokens are rejected.
- Cookie/header auth still works for SSE when present and valid — `?token=` is
  the fallback for browser EventSource clients.

Because URLs can end up in logs, treat `?token=` as SSE-only; never use it for
regular endpoints.

## 6. Login does not reveal whether an account exists

The authentication provider is configured with
`hideUserNotFoundExceptions = true`: a login attempt against a **non-existent
account returns the exact same generic response as a wrong password** —
`401 AUTH_BAD_CREDENTIALS`, message `"Invalid email or password."`. Login
responses therefore cannot be used to enumerate registered emails or
usernames. (Registration necessarily reports duplicates — `409
USER_DUPLICATE` — since it must refuse to create the account.)

## 7. Roles and derived badges

Exactly **four roles** exist — the only authorization dimension:

| Role | Authorities | Notes |
|---|---|---|
| `USER` | `ROLE_USER` | Platform default for new accounts |
| `RESEARCHER` | `ROLE_RESEARCHER` | Can publish research; auto-carries the Researcher badge |
| `SCHOLAR` | `ROLE_SCHOLAR` + `ROLE_RESEARCHER` | Implies researcher; auto-carries the Scholar badge |
| `ADMIN` | `ROLE_ADMIN` | Gates `/api/v1/admin/**` |

Badges (`VERIFIED_SCHOLAR` / `VERIFIED_RESEARCHER`) are **derived from the
role at response time** — there is no verification workflow or separate
account-type tier. Role changes go through the admin API only.

## 8. Error surface

All authorization failures use the unified envelope
([../errors/error-handling.md](../errors/error-handling.md)):

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_TOKEN_INVALID` | Missing/expired/malformed access token on a protected endpoint |
| 401 | `AUTH_WRONG_TOKEN_TYPE` | Refresh token used as an access token |
| 401 | `AUTH_ACCOUNT_DISABLED` / `AUTH_ACCOUNT_LOCKED` | Valid token, unusable account |
| 401 | `AUTH_USER_NOT_FOUND` | Valid token, user since deleted |
| 401 | `AUTH_BAD_CREDENTIALS` | Login failure — wrong password **or** unknown account |
| 403 | `ACCESS_DENIED` | Authenticated but the `@PreAuthorize` / admin rule failed |

Finally: `username` is the **user-set handle**, never the email — the two are
independent credentials, and only the email (or the handle) plus password can
authenticate.
