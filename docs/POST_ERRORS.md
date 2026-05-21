# Post API — Complete Error & Exception Reference

> **The full, exhaustive list of every error, exception, status code, and
> error response shape that can come out of any endpoint under
> `/api/v1/posts/...`, `/api/v1/stories/...`, `/api/v1/sounds/...`,
> `/api/v1/highlights/...`, `/api/v1/close-friends/...`, and
> `/api/v1/hashtags/...`.**

Built directly from the source under
`ak.dev.irc.app.common.exception.*`, the JWT security filter, the post
package controllers and services, and every downstream dependency
(Cassandra, Redis, Elasticsearch, Cloudflare R2).

---

## Table of contents

1. [The 4 error-producing layers](#1-the-4-error-producing-layers)
2. [The unified error response shape (ApiErrorResponse)](#2-the-unified-error-response-shape-apierrorresponse)
3. [The 2 non-unified shapes (multipart create)](#3-the-2-non-unified-shapes-multipart-create)
4. [Master catalogue of every `errorCode` — wire-format samples](#4-master-catalogue-of-every-errorcode)
5. [JWT filter errors (run BEFORE the controller)](#5-jwt-filter-errors)
6. [Per-endpoint failure matrix](#6-per-endpoint-failure-matrix)
   - 6.1 [Posts — create, get, delete](#61-posts-create-get-delete)
   - 6.2 [Feeds & search](#62-feeds--search)
   - 6.3 [SSE stream](#63-sse-stream)
   - 6.4 [Suggestions](#64-suggestions)
   - 6.5 [Reactions](#65-reactions)
   - 6.6 [Views](#66-views)
   - 6.7 [Comments & replies](#67-comments--replies)
   - 6.8 [Saves (bookmarks)](#68-saves-bookmarks)
   - 6.9 [Shares](#69-shares)
   - 6.10 [Media (carousel)](#610-media-carousel)
   - 6.11 [Hashtags & mentions](#611-hashtags--mentions)
   - 6.12 [Sounds](#612-sounds)
   - 6.13 [Stories](#613-stories)
   - 6.14 [Story polls](#614-story-polls)
   - 6.15 [Close friends](#615-close-friends)
   - 6.16 [Highlights](#616-highlights)
7. [Field-level validation rules](#7-field-level-validation-rules)
8. [The `SecurityException` quirk — falls through to 500](#8-the-securityexception-quirk)
9. [Anonymous-safe endpoints (don't 401)](#9-anonymous-safe-endpoints)
10. [Bare-body endpoints (status code only, no JSON)](#10-bare-body-endpoints)
11. [Idempotent endpoints (don't error on repeat)](#11-idempotent-endpoints)
12. [Downstream failures (Cassandra / Redis / Elasticsearch / R2)](#12-downstream-failures)
13. [Rate limiting](#13-rate-limiting)
14. [Dedup guard](#14-dedup-guard)
15. [Frontend integration guide](#15-frontend-integration-guide)
    - 15.1 [TypeScript types](#151-typescript-types)
    - 15.2 [Universal handler](#152-universal-handler)
    - 15.3 [HTTP status quick-reference](#153-http-status-quick-reference)
16. [Server-side guidance for new code](#16-server-side-guidance)

---

## 1. The 4 error-producing layers

Errors come from **four distinct layers**. Knowing which layer produces a
given response tells you exactly what to expect:

| # | Layer | What it produces | Body shape |
|---|-------|------------------|------------|
| 1 | **JWT filter** (`JwtAuthenticationFilter`) | Runs BEFORE Spring routes the request. Validates the token from the `Authorization: Bearer ...` header or the access-token cookie. | `ApiErrorResponse` — but never goes through `GlobalExceptionHandler`. |
| 2 | **Security gate** (`@PreAuthorize`, `JwtAuthenticationEntryPoint`, `JwtAccessDeniedHandler`) | Triggers when a secured endpoint is hit by an anonymous user, or when the principal lacks the required role. | `ApiErrorResponse` — bypasses `GlobalExceptionHandler` because Spring Security writes the response directly via the entry-point / access-denied handlers. |
| 3 | **Controller short-circuits** | Hand-rolled `ResponseEntity.status(...).build()` inside Post controllers — typically `if (user == null) return ResponseEntity.status(UNAUTHORIZED).build();`. Also the multipart `502` / `500` custom bodies. | **Bare-body** (no JSON) for `401/403/404` cases. **Custom `{error, message, ...}`** for the multipart paths only. |
| 4 | **Global handler** (`GlobalExceptionHandler`) | Catches every other exception via `@RestControllerAdvice`. | `ApiErrorResponse` — the canonical shape. |

> **Why this matters:** when the frontend sees an `ApiErrorResponse`
> with `errorCode: "AUTH_TOKEN_INVALID"`, that came from layer 1
> (JWT filter), NOT layer 4. Both layers emit the same shape, so the
> frontend only needs one parser — but the log evidence for these two
> classes of error is in different log streams.

---

## 2. The unified error response shape (ApiErrorResponse)

Defined in `ak.dev.irc.app.common.dto.ApiErrorResponse`. Annotated with
`@JsonInclude(NON_NULL)` so any null field is **omitted** from the JSON
body — the frontend should treat each field as optional.

```json
{
  "timestamp":   "2026-05-20T14:30:00",
  "status":      404,
  "error":       "Not Found",
  "message":     "Post not found with id: 550e8400-e29b-41d4-a716-446655440000",
  "path":        "/api/v1/posts/550e8400-e29b-41d4-a716-446655440000",
  "errorCode":   "POST_NOT_FOUND",
  "details":     { "resource": "Post", "field": "id",
                   "value": "550e8400-e29b-41d4-a716-446655440000" },
  "fieldErrors": null,
  "traceId":     "a1b2c3d4-e5f6-7890-1234-567890abcdef"
}
```

### Field-by-field reference

| Field | Java type | JSON type | Always present? | Description |
|-------|-----------|-----------|------------------|-------------|
| `timestamp`   | `LocalDateTime` | string (ISO 8601 local, no zone) | **yes** | Server time the error was generated. |
| `status`      | `int`           | number | **yes** | HTTP status code (200 is never an error). |
| `error`       | `String`        | string | **yes** | HTTP reason phrase (`"Bad Request"`, `"Not Found"`, `"Forbidden"`, ...). |
| `message`     | `String`        | string | **yes** | Human-readable explanation safe to show users. |
| `path`        | `String`        | string | **yes** | Request URI that produced the error (query string excluded). |
| `errorCode`   | `String`        | string | **yes** (except for bare-body) | Machine-readable code. **Use this for `switch`/`case` — never the `message`.** |
| `details`     | `Map<String,Object>` | object | only when set | Contextual data. Examples below per error code. |
| `fieldErrors` | `List<FieldError>` | array | only on `VALIDATION_FAILED` | Per-field validation errors. |
| `traceId`     | `String`        | string | **yes** | Server log correlation id. **Surface in UI** so support can find the log line. |

### `FieldError` shape

Only present when `errorCode == "VALIDATION_FAILED"`.

```json
{
  "field":         "textContent",
  "message":       "must not be blank",
  "rejectedValue": ""
}
```

| Field | Type | Notes |
|-------|------|-------|
| `field`          | string | The body or query-param name that failed validation. Uses Bean Validation's JSON-path style — nested fields look like `"sources[0].title"`. |
| `message`        | string | The `defaultMessage` from the `@Size` / `@NotBlank` / `@Pattern` / etc. annotation. |
| `rejectedValue`  | any    | The value that was rejected. Can be `null`, `""`, a number, or any JSON-serializable value. |

---

## 3. The 2 non-unified shapes (multipart create)

The `POST /api/v1/posts` (multipart) endpoint deliberately returns
**hand-rolled JSON** in two failure scenarios — these are the only
endpoints in the entire Post package that diverge from
`ApiErrorResponse`.

### 3.1 R2 upload failed (HTTP `502 Bad Gateway`)

```json
{
  "error":   "upload_failed",
  "message": "<underlying R2/SdkClientException message>"
}
```

When: any one of the `files[]` parts failed during stream-to-R2.
Behaviour: every previously-successful R2 key is best-effort
`delete()`-ed before the response is written. **No post row is created.**

### 3.2 Database insert failed after R2 success (HTTP `500 Internal Server Error`)

```json
{
  "error":          "post_create_failed",
  "message":        "<underlying Cassandra / service error message>",
  "rolledBackFiles": 3
}
```

When: the R2 uploads succeeded but the subsequent `postService.createPost(...)`
call threw. Behaviour: every R2 key already uploaded is best-effort
deleted. `rolledBackFiles` is the count of those deletes attempted.

> **Frontend rule:** detect these two shapes by checking whether the
> response body has a top-level `error` field that is a string instead
> of `errorCode` (the unified shape has `errorCode`, never `error` as a
> string field). They are the **only** two exceptions to the unified
> response.

---

## 4. Master catalogue of every `errorCode`

Every code with: HTTP status, the **exact** message string the server
produces (where literal), the `details` shape, the trigger condition, and
sample wire-format JSON. Codes are sorted alphabetically.

> All samples include `timestamp` and `traceId` placeholders so they
> match the actual response one-to-one.

### `ACCESS_DENIED` — HTTP `403`

- **Trigger:** Spring Security `AccessDeniedException` or `AuthorizationDeniedException` (typically a `@PreAuthorize` mismatch on the principal's authorities). Comes from the JWT access-denied handler OR `GlobalExceptionHandler` (the handlers produce identical bodies).
- **Message:** `"You do not have the required permissions to access this resource."` (entry-point) OR `"You do not have permission to perform this action."` (global handler).
- **SSE quirk:** if the original `Accept` header was `text/event-stream`, the response is force-typed `application/json` so the browser doesn't try to consume the error as an event stream.

```json
{
  "timestamp": "2026-05-20T14:30:00",
  "status":    403,
  "error":     "Forbidden",
  "message":   "You do not have permission to perform this action.",
  "path":      "/api/v1/posts/.../reactions",
  "errorCode": "ACCESS_DENIED",
  "traceId":   "..."
}
```

### `ACCESS_FORBIDDEN` — HTTP `403`

- **Trigger:** thrown from service code as `new ForbiddenException(message)`. Used when business logic — not Spring Security — refuses the call.
- **Message:** the throw-site message.
- **Note:** the Post package today does NOT use `ForbiddenException` directly (it uses `SecurityException` instead — see [§8](#8-the-securityexception-quirk)). This code is still here so the frontend should handle it; it's emitted by the QnA/research/user packages.

### `AUTH_ACCOUNT_DISABLED` — HTTP `401`

- **Two sources:**
  1. JWT filter — when the user behind a valid JWT has `isEnabled() == false` (typically `deletedAt` set or email not verified).
  2. Spring Security login flow — `DisabledException`.
- **Message (filter):** `"Your account is disabled. Please contact support."`
- **Message (login flow):** `"Your account is disabled. Please verify your email or contact support."`

```json
{
  "status": 401, "error": "Unauthorized",
  "message": "Your account is disabled. Please contact support.",
  "path": "/api/v1/posts", "errorCode": "AUTH_ACCOUNT_DISABLED",
  "traceId": "..."
}
```

### `AUTH_ACCOUNT_EXPIRED` — HTTP `401`

- **Trigger:** Spring Security `AccountExpiredException`.
- **Message:** `"Your account has expired. Please contact support."`

### `AUTH_ACCOUNT_LOCKED` — HTTP `401`

- **Two sources:** JWT filter (user has `isAccountNonLocked() == false`); login flow (`LockedException`).
- **Message:** `"Your account is locked. Please contact support."`

### `AUTH_BAD_CREDENTIALS` — HTTP `401`

- **Trigger:** Spring Security `BadCredentialsException` during `/api/v1/auth/login` (note: the post controller never produces this directly — listed for completeness because the frontend may receive it from the auth controller during a flow that wraps post calls).
- **Message:** `"Invalid email or password."`

### `AUTH_CREDENTIALS_EXPIRED` — HTTP `401`

- **Trigger:** Spring Security `CredentialsExpiredException`.
- **Message:** `"Your credentials have expired. Please reset your password."`

### `AUTH_FAILED` — HTTP `401`

- **Trigger:** any other `AuthenticationException` not in the specific list.
- **Message:** `"Authentication failed: " + ex.getMessage()`

### `AUTH_INSUFFICIENT` — HTTP `401`

- **Trigger:** Spring Security `InsufficientAuthenticationException`. Typically when an endpoint requires full auth but only a remember-me / anonymous principal is present.
- **Message:** `"Full authentication is required to access this resource."`

### `AUTH_REQUIRED` — HTTP `401`

- **Trigger:** `JwtAuthenticationEntryPoint` fires when an unauthenticated request hits a secured endpoint (typically because no JWT was sent at all, AND the endpoint has `@PreAuthorize("isAuthenticated()")` or equivalent).
- **Message:** `"You must be authenticated to access this resource. Please log in or provide a valid token."`

```json
{
  "status": 401, "error": "Unauthorized",
  "message": "You must be authenticated to access this resource. Please log in or provide a valid token.",
  "path": "/api/v1/posts/.../reactions", "errorCode": "AUTH_REQUIRED",
  "traceId": "..."
}
```

### `AUTH_TOKEN_INVALID` — HTTP `401`

- **Trigger (JWT filter):** the supplied JWT failed `jwtTokenProvider.validateToken(...)` — signature bad, expired, malformed, unsupported, missing claims, OR the token threw `io.jsonwebtoken.JwtException` / `IllegalArgumentException` during parsing.
- **Message:** `"Invalid or expired JWT token. Please log in again."` (validation path) OR `"Invalid or expired token. Please log in again."` (parse-error path).
- **Frontend action:** clear the access token, attempt `/api/v1/auth/refresh`, otherwise prompt re-login.

### `AUTH_UNAUTHORIZED` — HTTP `401`

- **Trigger:** service code throwing `new UnauthorizedException(message)`. The Post package does not currently throw it directly; the frontend may see it from other packages.

### `AUTH_USER_NOT_FOUND` — HTTP `401`

- **Trigger:** JWT was valid but `UserDetailsService.loadUserByUsername(email)` threw `UsernameNotFoundException` — the user referenced by the token no longer exists (DB reset, account purged).
- **Message:** `"Your session is no longer valid. Please log in again."`

### `AUTH_WRONG_TOKEN_TYPE` — HTTP `401`

- **Trigger:** a token was supplied with `claim type != "ACCESS"` — typically the refresh token was sent on a regular endpoint by mistake.
- **Message:** `"This token type cannot be used for API access. Use an access token."`

### `BAD_REQUEST` — HTTP `400`

- **Trigger:** service code throwing `new BadRequestException(message)` without an explicit `errorCode`.
- **Message:** the throw-site message.

### `DATA_INTEGRITY_VIOLATION` — HTTP `409`

- **Trigger:** Spring `DataIntegrityViolationException` (uniqueness violation, FK violation, NOT NULL violation, check constraint). The root cause is logged but **NOT echoed in the response** — the message is intentionally generic to avoid leaking schema details.
- **Message:** `"A data integrity constraint was violated. This usually means a duplicate or invalid reference exists."`
- **Frontend action:** show a generic "couldn't save" message; on a known retry path (e.g. uploading the same sound twice), retry with a deduplicated id.

### `ENDPOINT_NOT_FOUND` — HTTP `404`

- **Trigger:** `NoHandlerFoundException` or `NoResourceFoundException` — no `@RequestMapping` matched the request path / method combination.
- **Message:** `"No endpoint found for <METHOD> <URI>"`

### `FILE_TOO_LARGE` — HTTP `413`

- **Trigger:** `MaxUploadSizeExceededException` from a multipart request. Configured by `spring.servlet.multipart.max-file-size` and `max-request-size`.
- **Message:** `"The uploaded file exceeds the maximum allowed size."`
- **`details`:** `{ "maxSize": <bytes-as-long> }`

```json
{
  "status": 413, "error": "Payload Too Large",
  "message": "The uploaded file exceeds the maximum allowed size.",
  "path": "/api/v1/posts",
  "errorCode": "FILE_TOO_LARGE",
  "details": { "maxSize": 52428800 },
  "traceId": "..."
}
```

### `ILLEGAL_ARGUMENT` — HTTP `400`

- **Trigger:** any `IllegalArgumentException` not caught by a more specific handler. In the Post package the two known sources are:
  1. `CassandraCommentService.replyTo(...)` → `"Comment not found: <commentId>"` (when the target reply parent does not exist in `comment_lookup`).
  2. `CassandraStoryPollService.castVote(...)` → `"Choice must be A or B"`.
- **Message:** the throw-site message.

```json
{
  "status": 400, "error": "Bad Request",
  "message": "Choice must be A or B",
  "path": "/api/v1/polls/.../vote",
  "errorCode": "ILLEGAL_ARGUMENT",
  "traceId": "..."
}
```

### `ILLEGAL_STATE` — HTTP `500`

- **Trigger:** any `IllegalStateException`. Always a server-side bug; logged at `ERROR` with full stack.
- **Message:** `"An unexpected state was encountered. Please try again or contact support."`

### `INTERNAL_ERROR` — HTTP `500`

- **Trigger:** the catch-all in `GlobalExceptionHandler.handleAllUncaught(...)`. Fires for any `Exception` not matched by a more specific handler — including `SecurityException` (see [§8](#8-the-securityexception-quirk)), `NullPointerException`, Cassandra `NoNodeAvailableException`, `DriverException`, deserialization failures past the body parser, etc.
- **Message:** `"An unexpected error occurred. Please try again later. If the problem persists, contact support with trace ID: <traceId>"`
- **`traceId` is interpolated into the message** so users can read it off the screen.
- The handler suppresses the body if the response was already committed (e.g. half-streamed audio/video).
- The handler also detects "client disconnect" patterns (`broken pipe`, `connection reset`) in the cause chain and downgrades to a silent log instead of writing a 500.

```json
{
  "status": 500, "error": "Internal Server Error",
  "message": "An unexpected error occurred. Please try again later. If the problem persists, contact support with trace ID: a1b2c3d4-...",
  "path": "/api/v1/posts/.../comments/...",
  "errorCode": "INTERNAL_ERROR",
  "traceId":   "a1b2c3d4-..."
}
```

### `MALFORMED_JSON` — HTTP `400`

- **Trigger:** `HttpMessageNotReadableException` — the JSON parser couldn't read the body. Causes: syntax error, wrong field types, missing closing brace, etc.
- **Message:** `"Malformed JSON request body. Please check your JSON syntax and field types."`

### `METHOD_NOT_ALLOWED` — HTTP `405`

- **Trigger:** `HttpRequestMethodNotSupportedException` — the path matches a handler but the HTTP method doesn't (e.g. `PATCH /api/v1/posts/{id}` instead of `DELETE`).
- **Message:** `"HTTP method '<method>' is not supported for this endpoint. Supported: <supportedSet>"`

### `MISSING_PARAMETER` — HTTP `400`

- **Trigger:** `MissingServletRequestParameterException` — a `@RequestParam` declared as required is absent.
- **Message:** `"Required parameter '<name>' of type '<type>' is missing"`
- **`details`:** `{ "parameter": "...", "expectedType": "..." }`

```json
{
  "status": 400, "error": "Bad Request",
  "message": "Required parameter 'userId' of type 'UUID' is missing",
  "path": "/api/v1/posts/suggestions",
  "errorCode": "MISSING_PARAMETER",
  "details": { "parameter": "userId", "expectedType": "UUID" },
  "traceId": "..."
}
```

### `POST_NOT_FOUND` — HTTP `404`

- **Trigger:** any throw of `new ResourceNotFoundException("Post", "id", postId)` (the resource name gets uppercased + underscored, so `"Post"` → `"POST_NOT_FOUND"`).
- **Message:** `"Post not found with id: <uuid>"`
- **`details`:** `{ "resource": "Post", "field": "id", "value": "<uuid>" }`

### `RATE_LIMITED` — HTTP `429`

- **Trigger:** `RateLimitExceededException` thrown by `RateLimiter.check(...)`. (See [§13](#13-rate-limiting).)
- **Message:** `"Too many <action> requests — please slow down"`
- **`details`:** `{ "action": "<name>", "retryAfterSeconds": <long> }`

```json
{
  "status": 429, "error": "Too Many Requests",
  "message": "Too many comment requests — please slow down",
  "path": "/api/v1/posts/.../comments",
  "errorCode": "RATE_LIMITED",
  "details": { "action": "comment", "retryAfterSeconds": 25 },
  "traceId": "..."
}
```

### `RESOURCE_CONFLICT` — HTTP `409`

- **Trigger:** `ConflictException` thrown without an explicit code. Usually for optimistic locking / version conflicts.
- **Message:** the throw-site message.

### `RESOURCE_DUPLICATE` — HTTP `409`

- **Trigger:** `new DuplicateResourceException(message)` (string-only constructor). For the typed constructor the code becomes `<RESOURCE>_DUPLICATE`.

### `RESOURCE_NOT_FOUND` — HTTP `404`

- **Trigger:** `EntityNotFoundException` (JPA legacy) OR `ResourceNotFoundException` with the message-only constructor.
- **Message:** the throw-site message.

### `STORAGE_UNAVAILABLE` — HTTP `503`

- **Trigger:** `software.amazon.awssdk.core.exception.SdkClientException` — the R2/S3 SDK couldn't reach storage. Network error, DNS, TLS, bad credentials.
- **Message:** `"File storage service is currently unavailable. Please try again later."`

### `TYPE_MISMATCH` — HTTP `400`

- **Trigger:** `MethodArgumentTypeMismatchException` — a path or query parameter can't be converted to the declared type. The most common case in the Post API is **malformed UUID** in a `{postId}` / `{commentId}` / `{storyId}` path segment, or a malformed `Instant` in a `cursor` query.
- **Message:** `"Parameter '<name>' must be of type '<type>'. Received: '<value>'"`
- **`details`:** `{ "parameter": "...", "expectedType": "...", "receivedValue": "..." }`

```json
{
  "status": 400, "error": "Bad Request",
  "message": "Parameter 'postId' must be of type 'UUID'. Received: 'not-a-uuid'",
  "path": "/api/v1/posts/not-a-uuid",
  "errorCode": "TYPE_MISMATCH",
  "details": { "parameter": "postId", "expectedType": "UUID", "receivedValue": "not-a-uuid" },
  "traceId": "..."
}
```

### `UNSUPPORTED_MEDIA_TYPE` — HTTP `415`

- **Trigger:** `HttpMediaTypeNotSupportedException` — the `Content-Type` of the request doesn't match what the handler accepts. Common when a client sends `application/x-www-form-urlencoded` to a JSON endpoint.
- **Message:** `"Content type '<contentType>' is not supported. Supported: [<list>]"`

### `VALIDATION_FAILED` — HTTP `400`

- **Trigger:** `@Valid` body failed Jakarta Bean Validation. Every offending field appears in `fieldErrors[]` with its annotation message and rejected value.
- **Message:** `"One or more fields failed validation. Check 'fieldErrors' for details."`
- See [§7](#7-field-level-validation-rules) for every request DTO's rules.

```json
{
  "status": 400, "error": "Validation Failed",
  "message": "One or more fields failed validation. Check 'fieldErrors' for details.",
  "path": "/api/v1/posts",
  "errorCode": "VALIDATION_FAILED",
  "fieldErrors": [
    { "field": "title",       "message": "must not be blank",      "rejectedValue": "" },
    { "field": "tags",        "message": "must not be empty",      "rejectedValue": [] },
    { "field": "abstractText","message": "size must be between 0 and 5000",
                              "rejectedValue": "..." }
  ],
  "traceId": "..."
}
```

---

## 5. JWT filter errors

The JWT filter runs **before** the Spring DispatcherServlet routes the
request to a controller. It can produce its own `ApiErrorResponse`
**without** going through `GlobalExceptionHandler`. The body shape is
identical (so the frontend doesn't need branching), but the log evidence
is in a different stream.

### Token resolution order

1. **HttpOnly cookie** (`access_token`) — checked first.
2. **`Authorization: Bearer <jwt>`** header — fallback.
3. If neither present and the endpoint is public → request flows through with `Anonymous` principal.
4. If neither present and the endpoint is secured → `JwtAuthenticationEntryPoint` fires → `401 AUTH_REQUIRED`.

### Filter-emitted error codes

| `errorCode` | HTTP | When |
|-------------|------|------|
| `AUTH_TOKEN_INVALID` | `401` | Signature bad / expired / malformed / unsupported / `JwtException` / `IllegalArgumentException` while parsing. |
| `AUTH_WRONG_TOKEN_TYPE` | `401` | Token claim `type != "ACCESS"` (refresh token sent on an API call). |
| `AUTH_ACCOUNT_DISABLED` | `401` | Valid token, but `User.isEnabled() == false`. |
| `AUTH_ACCOUNT_LOCKED` | `401` | Valid token, but `User.isAccountNonLocked() == false`. |
| `AUTH_USER_NOT_FOUND` | `401` | Valid token, but `UserDetailsService.loadUserByUsername(email)` threw `UsernameNotFoundException`. |
| `INTERNAL_ERROR` | `500` | Unexpected error in the filter (DB down while loading `UserDetails`, NPE). |
| `AUTH_REQUIRED` | `401` | No token AND endpoint is secured. Comes from `JwtAuthenticationEntryPoint`, not the filter itself. |

### Filter SKIPS `/api/v1/auth/...`

`shouldNotFilter()` returns `true` for any path under `/api/v1/auth/`,
so login/register/refresh never hit the JWT filter. This is irrelevant
for the Post APIs but explains why an expired token on `/auth/refresh`
does NOT produce `AUTH_TOKEN_INVALID`.

---

## 6. Per-endpoint failure matrix

For each endpoint:

- ✅ Each failure condition is listed.
- The "Body" column says exactly what comes back:
  - `ApiErrorResponse` — the unified shape.
  - `Bare` — `ResponseEntity.status(...).build()` with no body.
  - `Custom-502` / `Custom-500` — the hand-rolled multipart shapes from [§3](#3-the-2-non-unified-shapes-multipart-create).
- The "Layer" column points to which of the 4 layers ([§1](#1-the-4-error-producing-layers)) produced it.

### 6.1 Posts (create, get, delete)

#### `POST /api/v1/posts`  (JSON body)

| Condition | HTTP | `errorCode` | Body | Layer | Message |
|-----------|------|-------------|------|-------|---------|
| Missing or invalid JWT | `401` | `AUTH_TOKEN_INVALID` / `AUTH_REQUIRED` | `ApiErrorResponse` | JWT filter / Entry-point | (see §5) |
| Not authenticated (`user == null` after security) | `401` | — | **Bare** | Controller short-circuit | — |
| Body unparseable | `400` | `MALFORMED_JSON` | `ApiErrorResponse` | Global | `"Malformed JSON request body..."` |
| Body missing fields | `400` | `VALIDATION_FAILED` | `ApiErrorResponse` + `fieldErrors[]` | Global | `"One or more fields failed validation..."` |
| Cassandra write failure | `500` | `INTERNAL_ERROR` | `ApiErrorResponse` | Global | catch-all message |
| Sound id passed but not in DB | `200` | — | normal response | — | best-effort: a `WARN` is logged but the post is created |
| Elasticsearch async index fails | `200` | — | normal response | — | async — never affects the response |

#### `POST /api/v1/posts`  (multipart/form-data)

| Condition | HTTP | `errorCode` / `error` | Body | Notes |
|-----------|------|-----------------------|------|-------|
| Not authenticated | `401` | — | **Bare** | controller short-circuit |
| Any `files[i]` part is empty | `200` | — | — | empty files are silently skipped |
| `files[i]` exceeds `max-file-size` | `413` | `FILE_TOO_LARGE` | `ApiErrorResponse` with `details.maxSize` | global |
| Request total exceeds `max-request-size` | `413` | `FILE_TOO_LARGE` | `ApiErrorResponse` | global |
| R2 upload itself fails (any file) | `502` | `error: "upload_failed"` | **Custom-502** | rollback deletes previously-uploaded keys |
| R2 succeeded but `postService.createPost` threw | `500` | `error: "post_create_failed"` | **Custom-500** with `rolledBackFiles: N` | rollback deletes all R2 keys |
| `Content-Type` not multipart | `415` | `UNSUPPORTED_MEDIA_TYPE` | `ApiErrorResponse` | global |
| Bad `locationLat` / `locationLng` (not parseable as Double) | `200` | — | normal response | parser silently sets to `null` |
| Bad `sharedPostId` / `soundId` UUID | `200` | — | normal response | parser silently sets to `null` |

#### `GET /api/v1/posts/{id}`

| Condition | HTTP | `errorCode` | Body |
|-----------|------|-------------|------|
| `{id}` not a UUID | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` with `details.{parameter, expectedType, receivedValue}` |
| Post does not exist or was hard-deleted | `404` | — | **Bare** (no body) |
| Cassandra read fails | `500` | `INTERNAL_ERROR` | `ApiErrorResponse` |

#### `DELETE /api/v1/posts/{id}`

| Condition | HTTP | `errorCode` | Body |
|-----------|------|-------------|------|
| Not authenticated | `401` | — | **Bare** |
| `{id}` not a UUID | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` |
| Post does not exist | `404` | — | **Bare** |
| Caller not the author | `403` | — | **Bare** |
| Cassandra delete fails | `500` | `INTERNAL_ERROR` | `ApiErrorResponse` |

### 6.2 Feeds & search

#### `GET /api/v1/posts/by-author/{authorId}`

| Condition | HTTP | `errorCode` | Body |
|-----------|------|-------------|------|
| `{authorId}` malformed | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` |
| `cursor` malformed | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` (`expectedType=Instant`) |
| Author does not exist | `200` | — | `[]` (empty list — not 404) |

#### `GET /api/v1/posts/feed` and `/api/v1/posts/feed/cursor`

| Condition | HTTP | `errorCode` | Body |
|-----------|------|-------------|------|
| Anonymous AND no `?userId=` query param | `200` | — | `[]` (silent fallback — not 401) |
| Malformed `userId` query | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` |
| Malformed `cursor` | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` |
| Redis sorted-set read fails | `200` | — | normal response (falls back to Cassandra silently) |

#### `GET /api/v1/posts/reels` and `/api/v1/posts/feed/reels`

| Condition | HTTP | `errorCode` |
|-----------|------|-------------|
| `?day=` malformed (not `YYYY-MM-DD`) | `200` | — — server silently defaults to today |
| Bad `pageSize` / `size` / `page` types | `400` | `TYPE_MISMATCH` |

#### `GET /api/v1/posts/search`

| Condition | HTTP | `errorCode` | Body |
|-----------|------|-------------|------|
| `?q=` missing | `400` | `MISSING_PARAMETER` | `ApiErrorResponse` with `details.{parameter:"q", expectedType:"String"}` |
| Elasticsearch unreachable | `500` | `INTERNAL_ERROR` | `ApiErrorResponse` |
| ES query syntax error | `500` | `INTERNAL_ERROR` | `ApiErrorResponse` (query parsing happens server-side) |

### 6.3 SSE stream

#### `GET /api/v1/posts/{id}/stream`

| Condition | HTTP | `errorCode` | Body |
|-----------|------|-------------|------|
| `{id}` malformed | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` |
| Anonymous on a public stream | `200` | — | normal SSE handshake |
| Anonymous on a private stream | `403` | `ACCESS_DENIED` | `ApiErrorResponse` (forced `Content-Type: application/json` even though Accept was `text/event-stream`) |
| Connection drop mid-stream | — | — | no body — server logs at DEBUG only (`ClientAbortException` / `AsyncRequestNotUsableException` are swallowed) |

### 6.4 Suggestions

| Endpoint | Failure → status / code |
|----------|-------------------------|
| `GET /api/v1/posts/suggestions?userId=&limit=` | missing `userId` → `400 MISSING_PARAMETER`; malformed UUID → `400 TYPE_MISMATCH` |
| `POST /api/v1/posts/suggestions/recompute?userId=` | missing → `400 MISSING_PARAMETER`; malformed → `400 TYPE_MISMATCH`; success → `202 Accepted` (empty body) |

### 6.5 Reactions

#### `POST /api/v1/posts/{postId}/reactions`  (toggle on post)
#### `DELETE /api/v1/posts/{postId}/reactions`  (explicit unlike)
#### `POST /api/v1/posts/{postId}/comments/{commentId}/reactions`  (toggle on comment)
#### `DELETE /api/v1/posts/{postId}/comments/{commentId}/reactions`

| Condition | HTTP | `errorCode` | Body |
|-----------|------|-------------|------|
| Not authenticated | `401` | — | **Bare** |
| `{postId}` / `{commentId}` malformed | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` |
| Post / comment doesn't exist at toggle time | `200` | — | normal toggle response — no explicit 404. Cassandra writes the like row anyway; counter drift is reconciled. |
| Counter UPDATE fails (rare) | `500` | `INTERNAL_ERROR` | `ApiErrorResponse` |

#### `GET /api/v1/posts/{postId}/reactions/me`  (anonymous-safe)
#### `GET /api/v1/posts/users/{userId}/reactions`

| Condition | HTTP | `errorCode` |
|-----------|------|-------------|
| Anonymous on `/me` | `200` | — — returns `{postId, liked: false}` |
| Malformed UUID | `400` | `TYPE_MISMATCH` |
| Malformed `pageSize` | `400` | `TYPE_MISMATCH` |

### 6.6 Views

#### `POST /api/v1/posts/{postId}/views`

| Condition | HTTP | Body | Notes |
|-----------|------|------|-------|
| Anonymous | `200` | `{postId, counted: false, viewCount}` | NOT a 401 — anon viewers see the count but don't bump it |
| Authenticated, first view in 7d window | `200` | `{postId, userId, counted: true, viewCount}` | |
| Repeat view within 7d | `200` | `{postId, userId, counted: false, viewCount}` | Redis `SET NX` dedupe |
| Redis unreachable | `200` | normal response | falls back to a `views_by_post` Cassandra read — still correct, slightly slower |
| Cassandra unreachable | `500` | `ApiErrorResponse` `INTERNAL_ERROR` | |
| `{postId}` malformed | `400` | `TYPE_MISMATCH` | |

### 6.7 Comments & replies

#### `POST /api/v1/posts/{postId}/comments`

| Condition | HTTP | `errorCode` | Body |
|-----------|------|-------------|------|
| Not authenticated | `401` | — | **Bare** |
| `{postId}` malformed | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` |
| Body malformed JSON | `400` | `MALFORMED_JSON` | `ApiErrorResponse` |
| `text` is a non-string | `400` | `MALFORMED_JSON` | `ApiErrorResponse` |
| Same author + same `text` within ~3s window | `200` | — | returns existing comment (DedupGuard — see [§14](#14-dedup-guard)) |
| Cassandra write fails | `500` | `INTERNAL_ERROR` | `ApiErrorResponse` |

#### `POST /api/v1/posts/comments/{commentId}/replies`

| Condition | HTTP | `errorCode` | Body |
|-----------|------|-------------|------|
| Not authenticated | `401` | — | **Bare** |
| `{commentId}` malformed | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` |
| Target comment does not exist | `400` | `ILLEGAL_ARGUMENT` | `ApiErrorResponse` — message: `"Comment not found: <uuid>"` |
| Duplicate reply (same author + text within 3s) | `200` | — | returns existing reply |

#### `PATCH /api/v1/posts/comments/{commentId}`  (edit)
#### `DELETE /api/v1/posts/comments/{commentId}`  (soft-delete)

| Condition | HTTP | `errorCode` | Body | Note |
|-----------|------|-------------|------|------|
| Not authenticated | `401` | — | **Bare** | |
| `{commentId}` malformed | `400` | `TYPE_MISMATCH` | `ApiErrorResponse` | |
| Comment does not exist | `400` | `ILLEGAL_ARGUMENT` | `ApiErrorResponse` — `"Comment not found: <uuid>"` | |
| **Caller is not the author** | **`500`** | **`INTERNAL_ERROR`** | `ApiErrorResponse` | ⚠ See [§8](#8-the-securityexception-quirk) — semantically 403 |

#### `GET /api/v1/posts/{postId}/comments`
#### `GET /api/v1/posts/comments/{commentId}/replies`

| Condition | HTTP | `errorCode` |
|-----------|------|-------------|
| `{postId}` / `{commentId}` malformed | `400` | `TYPE_MISMATCH` |
| `cursor` malformed | `400` | `TYPE_MISMATCH` |
| Empty result | `200` | — — `[]` |

### 6.8 Saves (bookmarks)

#### `POST /api/v1/posts/{postId}/saves`
#### `DELETE /api/v1/posts/{postId}/saves`

| Condition | HTTP | Body |
|-----------|------|------|
| Not authenticated | `401` | **Bare** |
| `{postId}` malformed | `400` | `TYPE_MISMATCH` `ApiErrorResponse` |
| Cassandra failure | `500` | `INTERNAL_ERROR` `ApiErrorResponse` |

#### `GET /api/v1/posts/{postId}/saves/me`  (anonymous-safe — returns `{saved:false}`)
#### `GET /api/v1/posts/users/{userId}/saves`

| Condition | HTTP | `errorCode` |
|-----------|------|-------------|
| Bad UUID | `400` | `TYPE_MISMATCH` |
| Bad cursor | `400` | `TYPE_MISMATCH` |

### 6.9 Shares

#### `POST /api/v1/posts/{postId}/shares`

| Condition | HTTP | Body |
|-----------|------|------|
| Not authenticated | `401` | **Bare** |
| `{postId}` malformed | `400` | `TYPE_MISMATCH` |
| Body present but malformed | `400` | `MALFORMED_JSON` |
| Counter bump fails | `500` | `INTERNAL_ERROR` |

#### `GET /api/v1/posts/{postId}/shares`

| Condition | HTTP | `errorCode` |
|-----------|------|-------------|
| Bad UUID | `400` | `TYPE_MISMATCH` |

### 6.10 Media (carousel)

Base: `/api/v1/posts/{postId}/media`.

| Endpoint | Possible failures |
|----------|-------------------|
| `POST` add | `400 MALFORMED_JSON` (bad body); `400 TYPE_MISMATCH` (bad UUID); `400 VALIDATION_FAILED` if request shape changes to add validation (none today); `500 INTERNAL_ERROR` (Cassandra) |
| `GET` list | `400 TYPE_MISMATCH` on bad `{postId}` |
| `DELETE /{mediaId}?sortOrder=` | `400 MISSING_PARAMETER` if `sortOrder` omitted; `400 TYPE_MISMATCH` on bad UUID or non-int; **no 404** — unknown id silently a no-op |
| `PUT` replace-all | `400 MALFORMED_JSON` if body isn't a list; `500 INTERNAL_ERROR` on bulk delete/insert failure |

### 6.11 Hashtags & mentions

Base: `/api/v1`.

| Endpoint | Specific failures |
|----------|-------------------|
| `GET /hashtags/{tag}/posts` | `400 TYPE_MISMATCH` on bad cursor; unknown tag → `200 []` |
| `GET /hashtags/{tag}/usage` | always `200` — unknown tag → `{postCount: 0}` |
| `GET /users/{userId}/mentions` | `400 TYPE_MISMATCH` on bad `{userId}` |

### 6.12 Sounds

Base: `/api/v1/sounds`.

| Endpoint | Failures |
|----------|----------|
| `POST` (upload) | `400 MALFORMED_JSON`; `500 INTERNAL_ERROR` on Cassandra failure. (No `@Valid` on the body today, so empty `title`/`audioUrl` are NOT rejected — service stores them as-is.) |
| `GET /{id}` | `404` **Bare** when sound not found |
| `POST /{id}/approve` | `204 No Content` always — unknown id is silently a no-op |
| `GET /by-category/{category}?cursor=` | `400 TYPE_MISMATCH` on bad cursor |
| `GET /{id}/posts` | `400 TYPE_MISMATCH` on bad UUID |
| `GET /{id}/usage` | always `200` — unknown id → `{useCount: 0}` |

### 6.13 Stories

#### `POST /api/v1/stories`  (JSON)
#### `POST /api/v1/stories`  (multipart)

| Condition | HTTP | Body |
|-----------|------|------|
| Not authenticated | `401` | **Bare** |
| Body malformed JSON | `400` | `MALFORMED_JSON` `ApiErrorResponse` |
| Body invalid enum string (`storyType`, `visibility`) | `400` | `MALFORMED_JSON` or `TYPE_MISMATCH` `ApiErrorResponse` |
| R2 upload fails on multipart variant | `503` | `STORAGE_UNAVAILABLE` `ApiErrorResponse` — **note: NOT the special 502 shape** the post-multipart endpoint uses. Stories don't have the R2-rollback custom-body path. |
| Cassandra write fails | `500` | `INTERNAL_ERROR` `ApiErrorResponse` |

#### `GET /api/v1/stories/by-author/{authorId}`

| Condition | HTTP | `errorCode` |
|-----------|------|-------------|
| Bad UUID | `400` | `TYPE_MISMATCH` |
| Author has no active stories | `200` | — — `[]` |

#### `DELETE /api/v1/stories/{storyId}`

| Condition | HTTP | Body | Note |
|-----------|------|------|------|
| Not authenticated | `401` | **Bare** | |
| Bad UUID | `400` | `TYPE_MISMATCH` | |
| Story does not exist | `200` | empty (`200 OK`) | service silently returns — no 404 |
| **Caller is not the story author** | **`500`** | **`INTERNAL_ERROR`** | ⚠ semantically 403 — see [§8](#8-the-securityexception-quirk) |

#### `POST /api/v1/stories/{storyId}/views`
#### `GET /api/v1/stories/{storyId}/views`

| Condition | HTTP | `errorCode` |
|-----------|------|-------------|
| Not authenticated (`POST`) | `401` | **Bare** |
| Bad UUID | `400` | `TYPE_MISMATCH` |
| Viewer can't see the story (visibility filter) | `202 Accepted` | empty — silent skip |

### 6.14 Story polls

#### `POST /api/v1/stories/{storyId}/poll`

| Condition | HTTP | Body | Note |
|-----------|------|------|------|
| Not authenticated | `401` | **Bare** | |
| Bad `{storyId}` | `400` | `TYPE_MISMATCH` | |
| Body malformed | `400` | `MALFORMED_JSON` | |
| **Caller is not the story author** | **`500`** | **`INTERNAL_ERROR`** | ⚠ semantically 403 — see [§8](#8-the-securityexception-quirk) |

#### `GET /api/v1/stories/{storyId}/poll`

| Condition | HTTP | Body |
|-----------|------|------|
| Story has no poll | `404` | **Bare** |
| Bad UUID | `400` | `TYPE_MISMATCH` |

#### `POST /api/v1/polls/{pollId}/vote?choice=A|B`

| Condition | HTTP | `errorCode` | Body |
|-----------|------|-------------|------|
| Not authenticated | `401` | — | **Bare** |
| `choice` missing | `400` | `MISSING_PARAMETER` | `ApiErrorResponse` |
| `choice` is neither `A` nor `B` | `400` | `ILLEGAL_ARGUMENT` | `ApiErrorResponse` — message: `"Choice must be A or B"` |
| `{pollId}` malformed | `400` | `TYPE_MISMATCH` | |
| Repeated vote for same side | `200` | — | no-op, returns latest counts |
| Switching side | `200` | — | decrements old side, increments new |

#### `GET /api/v1/polls/{pollId}/vote/me`  (anonymous-safe)
#### `GET /api/v1/polls/{pollId}/results`  (public)
#### `GET /api/v1/polls/{pollId}/voters/{choice}`

| Condition | HTTP | `errorCode` |
|-----------|------|-------------|
| Bad UUID | `400` | `TYPE_MISMATCH` |
| Unknown `{pollId}` on `/results` | `200` | — — returns `{voteA:0, voteB:0}` |

> The `voters/{choice}` endpoint does **NOT** enforce that the caller is
> the story author — the comment in the controller flags this as a TODO.
> Treat the response as sensitive (author-only) on the frontend.

### 6.15 Close friends

Base: `/api/v1/close-friends`.

| Endpoint | Failures |
|----------|----------|
| `GET` | Not authenticated → `401` **Bare** |
| `POST?friendId=` | Not authenticated → `401` **Bare**; missing `friendId` → `400 MISSING_PARAMETER`; bad UUID → `400 TYPE_MISMATCH`; adding yourself → `204` silent no-op |
| `DELETE?friendId=` | Not authenticated → `401` **Bare**; missing/bad `friendId` → as above |
| `GET /is-member?candidateId=` | Anonymous → `200 false` (NOT 401); missing `candidateId` → `400 MISSING_PARAMETER`; bad UUID → `400 TYPE_MISMATCH` |

### 6.16 Highlights

Base: `/api/v1/highlights`.

| Endpoint | Failures |
|----------|----------|
| `POST` create | `400 MALFORMED_JSON` on bad body; `400 TYPE_MISMATCH` on bad UUID in body |
| `GET /by-author/{authorId}` | `400 TYPE_MISMATCH` on bad UUID |
| `POST /{highlightId}/stories/{storyId}?requesterId=` | `400 MISSING_PARAMETER` if `requesterId` missing; `400 TYPE_MISMATCH` on bad UUID; **`500 INTERNAL_ERROR`** when `requesterId ≠ story.authorId` (⚠ semantically 403); `404` **Bare** if the source story is gone (already expired); `404` **Bare** if the underlying `storyLookup` row is missing |
| `GET /{highlightId}/stories` | `400 TYPE_MISMATCH` |
| `DELETE /{highlightId}/stories/{storyId}?createdAt=` | `400 MISSING_PARAMETER` if `createdAt` missing; `400 TYPE_MISMATCH` if not an ISO instant |

---

## 7. Field-level validation rules

These are the **exact** Bean Validation constraints applied to the
post-package request DTOs today. A violation produces
`400 VALIDATION_FAILED` with `fieldErrors[]` carrying the offending
fields. Note: many of the Post DTOs are records WITHOUT validation
annotations — only the rules listed below are enforced.

### `CassandraPostService.CreatePostCommand` (JSON body of `POST /api/v1/posts`)

No `@Valid` constraints. The service accepts any combination of fields.
Validation is left to downstream Cassandra schema:

- `postType` — should be one of `TEXT`, `EMBEDDED`, `VOICE_POST`, `REEL`, `REPOST`, `STORY`. **Not enforced** — invalid strings are stored as-is.
- `visibility` — should be one of `PUBLIC`, `FOLLOWERS_ONLY`, `ONLY_ME`. **Not enforced.**

### `CassandraFeedController.CreateCommentRequest` (`POST /comments`)

`record CreateCommentRequest(String text, String mediaUrl, String mediaType)` — no annotations. All fields nullable.

### `CassandraFeedController.CreateReplyRequest` (`POST /replies`)

`record CreateReplyRequest(String text, String mediaUrl)` — no annotations.

### `CassandraFeedController.EditCommentRequest` (`PATCH /comments`)

`record EditCommentRequest(String text)` — no annotations.

### `CassandraFeedController.RecordShareRequest` (`POST /shares`)

`record RecordShareRequest(String caption)` — no annotations. Body is optional.

### `CassandraStoryController.CreateStoryRequest`

`record CreateStoryRequest(String storyType, String visibility, String mediaUrl, String thumbnailUrl, String textContent)` — no annotations.

### `CassandraStoryController.CreatePollRequest`

`record CreatePollRequest(String question, String optionA, String optionB)` — no annotations.

### `CassandraSoundController.UploadSoundRequest`

`record UploadSoundRequest(String title, String artistName, String audioUrl, String coverArtUrl, Integer durationSeconds, String category, UUID uploaderId, Boolean autoApprove)` — no annotations.

### `CassandraMediaController.AddMediaRequest`

`record AddMediaRequest(int sortOrder, String mediaType, String url, String thumbnailUrl, String s3Key, Integer durationSeconds, Long fileSizeBytes, String mimeType, String altText)` — no annotations.

### `CassandraHighlightController.CreateHighlightRequest`

`record CreateHighlightRequest(UUID authorId, String title, String coverUrl, int displayOrder)` — no annotations.

> **TL;DR — the Post APIs validate ALMOST NOTHING via Bean Validation
> today**. This means `VALIDATION_FAILED` is rare for Post endpoints
> compared to QnA / Research. The bulk of "bad body" failures surface
> as `MALFORMED_JSON` (parser failure) or eventually as
> `INTERNAL_ERROR` (NPE in service code on a null required field).

The QnA and Research APIs DO use rich Bean Validation — see
`QNA_API.md` and `RESEARCH_API.md` for those.

---

## 8. The `SecurityException` quirk

A handful of post-package services throw the plain
`java.lang.SecurityException` instead of `ForbiddenException`:

| Service method | Message |
|----------------|---------|
| `CassandraStoryService.deleteStory(storyId, actorId)` | `"Not the author"` |
| `CassandraStoryPollService.createPoll(...)` | `"Not the story author"` |
| `CassandraCommentService.editComment(commentId, authorId, ...)` | `"Not the author"` |
| `CassandraCommentService.deleteComment(commentId, authorId)` | `"Not the author"` |
| `CassandraHighlightService.addStoryToHighlight(...)` | `"Not the story author"` |

`SecurityException` extends `RuntimeException` and **is NOT in the
explicit handler list** in `GlobalExceptionHandler`. It falls through to
the catch-all `@ExceptionHandler(Exception.class)` and surfaces as:

```json
{
  "status": 500, "error": "Internal Server Error",
  "message": "An unexpected error occurred. Please try again later. If the problem persists, contact support with trace ID: <traceId>",
  "errorCode": "INTERNAL_ERROR",
  "traceId":   "<uuid>"
}
```

**Affected endpoints** — see the per-endpoint matrix for the full list:

- `PATCH  /api/v1/posts/comments/{commentId}`
- `DELETE /api/v1/posts/comments/{commentId}`
- `DELETE /api/v1/stories/{storyId}`
- `POST   /api/v1/stories/{storyId}/poll`
- `POST   /api/v1/highlights/{highlightId}/stories/{storyId}`

**Frontend recommendation:** when calling any of those endpoints **as a
non-owner** and the response is a generic `500 INTERNAL_ERROR`, treat it
as a permission failure (403-equivalent). The path will identify which
endpoint, and the operation (delete / edit / etc.) is enough context to
present a meaningful "you can't do that" message instead of a generic
500.

**Server fix when ready:** replace each `throw new SecurityException(...)`
with:

```java
throw new ForbiddenException("Only the author can do this",
                              "POST_<ACTION>_FORBIDDEN");
```

---

## 9. Anonymous-safe endpoints

These endpoints **do NOT return 401** when called without auth —
instead they return a sensible empty/default response. Useful for the
frontend to render UI without a token:

| Endpoint | Anonymous response |
|----------|---------------------|
| `GET /api/v1/posts/{id}` | full post |
| `GET /api/v1/posts/by-author/{authorId}` | profile feed |
| `GET /api/v1/posts/feed` | `[]` (no userId) |
| `GET /api/v1/posts/reels` | global reels |
| `GET /api/v1/posts/search` | search results |
| `GET /api/v1/posts/{postId}/reactions/me` | `{postId, liked: false}` |
| `GET /api/v1/posts/{postId}/saves/me` | `{postId, saved: false}` |
| `POST /api/v1/posts/{postId}/views` | counts but doesn't bump |
| `GET /api/v1/posts/{postId}/comments` | top-level comments |
| `GET /api/v1/posts/comments/{commentId}/replies` | replies |
| `GET /api/v1/posts/{postId}/shares` | recent shares |
| `GET /api/v1/posts/users/{userId}/saves` | user's saved posts |
| `GET /api/v1/posts/users/{userId}/reactions` | user's reactions |
| `GET /api/v1/posts/{id}/stream` | SSE stream (if public) |
| `GET /api/v1/sounds/{id}` | sound by id (or 404) |
| `GET /api/v1/sounds/by-category/{category}` | category list |
| `GET /api/v1/sounds/{id}/usage` | use count |
| `GET /api/v1/stories/by-author/{authorId}` | PUBLIC stories only |
| `GET /api/v1/polls/{pollId}/results` | live tally |
| `GET /api/v1/polls/{pollId}/vote/me` | `{pollId, choice: null}` for anon |
| `GET /api/v1/close-friends/is-member` | `false` for anon |
| `GET /api/v1/hashtags/{tag}/posts` | tagged posts |
| `GET /api/v1/hashtags/{tag}/usage` | post count |
| `GET /api/v1/users/{userId}/mentions` | mentions inbox |

---

## 10. Bare-body endpoints

These endpoints return only an HTTP status code with **no JSON body**.
The frontend must handle them by inspecting `response.status`.

| Status | When |
|--------|------|
| `401` | Authenticated mutations called without a JWT (post controllers short-circuit before the service layer). Every mutation endpoint in the post package does this. |
| `403` | `DELETE /api/v1/posts/{id}` when the caller is authenticated but is not the post's author. |
| `404` | `GET /api/v1/posts/{id}`, `GET /api/v1/sounds/{id}`, `GET /api/v1/stories/{storyId}/poll`, `POST /api/v1/highlights/{highlightId}/stories/{storyId}` when the resource is missing. |

**Frontend must detect these by status alone**, e.g.:

```ts
if (res.status === 401 && !res.headers.get('content-length')) promptLogin();
```

---

## 11. Idempotent endpoints

These endpoints **never error on repeat** — calling them twice with the
same arguments produces the same response. Useful when the network is
flaky:

| Endpoint | Repeat behaviour |
|----------|------------------|
| `POST /reactions` (post/comment toggle) | Even-numbered call → unlike; odd-numbered → like. Returns `{liked: <state-after>}`. |
| `DELETE /reactions` | No-op if not currently liked. |
| `POST /saves` (toggle) | Same toggle semantic as reactions. |
| `DELETE /saves` | No-op if not currently saved. |
| `POST /views` | Deduped — only the first view in 7d counts. |
| `POST /comments` (same author + text within 3s) | Dedup returns existing comment. |
| `POST /replies` (same author + text within 3s) | Dedup returns existing reply. |
| `DELETE /media/{mediaId}` | Unknown id → silent no-op. |
| `POST /sounds/{id}/approve` | Unknown / already-approved → silent no-op. |
| `POST /polls/{pollId}/vote?choice=A` (same side) | No-op, returns latest counts. |
| `POST /close-friends?friendId=` (self) | Silent no-op. |

---

## 12. Downstream failures

How each downstream system's failure surfaces:

### Cassandra (DB)

- `NoNodeAvailableException`, `DriverException`, query timeout →
  fall through to the catch-all → `500 INTERNAL_ERROR`.
- Counter writes have a special quirk: they are NOT idempotent at the DB
  layer — the service avoids retrying blindly. A failed counter bump
  leaves the row consistent but the counter slightly stale; a periodic
  reconciler sweeps drift.

### Redis

- Used for: view-dedupe (`SET NX`), home-feed cache (`ZSET`), dedup-guard
  for comments/replies, rate-limiter, email-throttle for notifications.
- **All Redis paths fail open**: if Redis is unreachable the request
  proceeds without dedupe / cache / rate-limit and never errors.

### Elasticsearch

- Search index is async on create/update/delete. Failures are logged
  but never propagate to the originating request.
- `GET /api/v1/posts/search` — if ES is unreachable, surfaces as
  `500 INTERNAL_ERROR`.

### Cloudflare R2 (S3-compatible storage)

- `SdkClientException` (network, DNS, TLS, credentials) →
  `503 STORAGE_UNAVAILABLE` via the global handler.
- The post-multipart create endpoint **wraps R2 failures specifically**
  and produces the `Custom-502 upload_failed` body instead (see [§3.1](#31-r2-upload-failed-http-502-bad-gateway)).
- Story multipart create does NOT wrap — R2 failures surface as the
  global `STORAGE_UNAVAILABLE`.

### RabbitMQ (notifications fan-out)

- Publishes happen in a `TransactionSynchronization.afterCommit` hook
  on the JPA side and synchronously inside Cassandra paths. RabbitMQ
  unreachable → logged at `ERROR`, never propagated.

---

## 13. Rate limiting

`RateLimiter` provides three pre-tuned bursts (none of which are currently
enforced on the Post APIs, but the QnA / Research / User APIs use them):

| Action | Burst / Window |
|--------|----------------|
| `reaction` | 30 calls / 10 seconds |
| `comment`  | 10 calls / 30 seconds |
| `social`   | 30 calls / 60 seconds |

When triggered:

```json
{
  "status": 429, "error": "Too Many Requests",
  "errorCode": "RATE_LIMITED",
  "message": "Too many comment requests — please slow down",
  "details": { "action": "comment", "retryAfterSeconds": 25 },
  "traceId": "..."
}
```

If Redis is unreachable, the limiter **fails open** — availability
beats throughput protection.

---

## 14. Dedup guard

`DedupGuard.isDuplicate(namespace, scope, actorId, text)` uses Redis
`SET NX` with a **3-second window** keyed on
`(namespace, scope, actorId, sha256(text)[:12])`.

Used by:

- `CassandraCommentService.createComment` — namespace `"post-comment"`.
- `CassandraCommentService.replyTo` — namespace `"post-reply"`.

When a duplicate is detected:

- **No error** is returned. The service returns the **existing** comment
  / reply row instead of creating a new one.
- HTTP status stays `200 OK`.

If Redis is unreachable the guard fails open (allows the write).

---

## 15. Frontend integration guide

### 15.1 TypeScript types

```ts
// The unified error shape
export interface ApiErrorResponse {
  timestamp:   string;                  // ISO 8601 local
  status:      number;
  error:       string;
  message:     string;
  path:        string;
  errorCode?:  string;                  // omitted on bare-body
  details?:    Record<string, unknown>;
  fieldErrors?: FieldError[];           // only on VALIDATION_FAILED
  traceId:     string;
}

export interface FieldError {
  field:         string;
  message:       string;
  rejectedValue: unknown;
}

// The 2 multipart-only shapes
export interface R2UploadError {
  error:   'upload_failed';
  message: string;
}

export interface DbInsertError {
  error:           'post_create_failed';
  message:         string;
  rolledBackFiles: number;
}

// Every error code as a string-union — exhaustive for the Post APIs
export type PostErrorCode =
  | 'ACCESS_DENIED'
  | 'ACCESS_FORBIDDEN'
  | 'AUTH_ACCOUNT_DISABLED'
  | 'AUTH_ACCOUNT_EXPIRED'
  | 'AUTH_ACCOUNT_LOCKED'
  | 'AUTH_BAD_CREDENTIALS'
  | 'AUTH_CREDENTIALS_EXPIRED'
  | 'AUTH_FAILED'
  | 'AUTH_INSUFFICIENT'
  | 'AUTH_REQUIRED'
  | 'AUTH_TOKEN_INVALID'
  | 'AUTH_UNAUTHORIZED'
  | 'AUTH_USER_NOT_FOUND'
  | 'AUTH_WRONG_TOKEN_TYPE'
  | 'BAD_REQUEST'
  | 'DATA_INTEGRITY_VIOLATION'
  | 'ENDPOINT_NOT_FOUND'
  | 'FILE_TOO_LARGE'
  | 'ILLEGAL_ARGUMENT'
  | 'ILLEGAL_STATE'
  | 'INTERNAL_ERROR'
  | 'MALFORMED_JSON'
  | 'METHOD_NOT_ALLOWED'
  | 'MISSING_PARAMETER'
  | 'POST_NOT_FOUND'
  | 'RATE_LIMITED'
  | 'RESOURCE_CONFLICT'
  | 'RESOURCE_DUPLICATE'
  | 'RESOURCE_NOT_FOUND'
  | 'STORAGE_UNAVAILABLE'
  | 'TYPE_MISMATCH'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'VALIDATION_FAILED';

// Discriminated union for switching on the response
export type PostApiError =
  | { kind: 'unified';      body: ApiErrorResponse; status: number }
  | { kind: 'r2-upload';    body: R2UploadError;    status: 502 }
  | { kind: 'db-insert';    body: DbInsertError;    status: 500 }
  | { kind: 'bare-401';     status: 401 }
  | { kind: 'bare-403';     status: 403 }
  | { kind: 'bare-404';     status: 404 };
```

### 15.2 Universal handler

```ts
export async function handlePostApiResponse<T>(res: Response): Promise<T> {
  if (res.ok) return res.json() as Promise<T>;

  // 1. Bare-body 401 / 403 / 404 (no JSON content)
  const contentType = res.headers.get('content-type') ?? '';
  const hasBody = res.headers.get('content-length') !== '0' &&
                  contentType.includes('application/json');

  if (!hasBody) {
    switch (res.status) {
      case 401: throw new AuthRequired();
      case 403: throw new Forbidden();
      case 404: throw new NotFound();
      default:  throw new ApiError(res.status, 'Empty response');
    }
  }

  const body = await res.json();

  // 2. Multipart custom-bodies (post-create only)
  if (typeof body?.error === 'string' && !body.errorCode) {
    if (body.error === 'upload_failed')      throw new R2UploadFailed(body.message);
    if (body.error === 'post_create_failed') throw new PostCreateFailed(body.message, body.rolledBackFiles);
  }

  // 3. Unified ApiErrorResponse
  const err = body as ApiErrorResponse;
  switch (err.errorCode as PostErrorCode) {
    case 'VALIDATION_FAILED':
      throw new ValidationFailed(err.fieldErrors!);

    case 'AUTH_TOKEN_INVALID':
    case 'AUTH_REQUIRED':
    case 'AUTH_UNAUTHORIZED':
    case 'AUTH_USER_NOT_FOUND':
    case 'AUTH_WRONG_TOKEN_TYPE':
      // Try /api/v1/auth/refresh — if that fails, prompt login
      throw new SessionExpired();

    case 'AUTH_ACCOUNT_DISABLED':
    case 'AUTH_ACCOUNT_LOCKED':
    case 'AUTH_ACCOUNT_EXPIRED':
    case 'AUTH_CREDENTIALS_EXPIRED':
      throw new AccountUnavailable(err.message);

    case 'AUTH_BAD_CREDENTIALS':
    case 'AUTH_FAILED':
    case 'AUTH_INSUFFICIENT':
      throw new AuthFailed(err.message);

    case 'ACCESS_DENIED':
    case 'ACCESS_FORBIDDEN':
      throw new Forbidden(err.message);

    case 'POST_NOT_FOUND':
    case 'RESOURCE_NOT_FOUND':
    case 'ENDPOINT_NOT_FOUND':
      throw new NotFound(err.message);

    case 'RATE_LIMITED':
      throw new RateLimited(err.details!.retryAfterSeconds as number);

    case 'FILE_TOO_LARGE':
      throw new FileTooLarge(err.details!.maxSize as number);

    case 'STORAGE_UNAVAILABLE':
      throw new StorageUnavailable(err.traceId);

    case 'METHOD_NOT_ALLOWED':
    case 'UNSUPPORTED_MEDIA_TYPE':
      throw new ProgrammerError(err.message);   // bug in client code

    case 'MISSING_PARAMETER':
    case 'TYPE_MISMATCH':
    case 'MALFORMED_JSON':
    case 'ILLEGAL_ARGUMENT':
    case 'BAD_REQUEST':
      throw new BadRequest(err.message, err.details);

    case 'RESOURCE_CONFLICT':
    case 'RESOURCE_DUPLICATE':
    case 'DATA_INTEGRITY_VIOLATION':
      throw new Conflict(err.message);

    case 'ILLEGAL_STATE':
    case 'INTERNAL_ERROR':
    default:
      // ⚠ Some 500s on edit/delete/poll-create/highlight-add are really
      // permission failures (SecurityException quirk).
      if (looksLikePermissionEndpoint(err.path, res.status)) {
        throw new Forbidden('Only the author can do this');
      }
      throw new InternalError(err.message, err.traceId);
  }
}

function looksLikePermissionEndpoint(path: string, status: number): boolean {
  if (status !== 500) return false;
  return (
    /^\/api\/v1\/posts\/comments\/[^/]+$/.test(path) ||             // edit / delete
    /^\/api\/v1\/stories\/[^/]+$/.test(path) ||                     // delete story
    /^\/api\/v1\/stories\/[^/]+\/poll$/.test(path) ||               // create poll
    /^\/api\/v1\/highlights\/[^/]+\/stories\/[^/]+$/.test(path)     // add story to highlight
  );
}
```

### 15.3 HTTP status quick-reference

| Status | Where it comes from in the Post APIs |
|--------|--------------------------------------|
| `200 OK` | Standard success, including anonymous-safe defaults and idempotent toggles. |
| `202 Accepted` | `POST /suggestions/recompute`, `POST /stories/{id}/views`. |
| `204 No Content` | `DELETE` of media/sound/close-friend (where applicable). |
| `400 Bad Request` | `VALIDATION_FAILED`, `MISSING_PARAMETER`, `TYPE_MISMATCH`, `MALFORMED_JSON`, `ILLEGAL_ARGUMENT`, `BAD_REQUEST`. |
| `401 Unauthorized` | JWT filter errors, security entry-point (`AUTH_REQUIRED`), or controller short-circuit (**bare-body**). |
| `403 Forbidden` | `ACCESS_DENIED` from `@PreAuthorize` mismatches, or controller short-circuit on `DELETE /posts/{id}` not-the-author (**bare-body**). |
| `404 Not Found` | `POST_NOT_FOUND`, `RESOURCE_NOT_FOUND`, `ENDPOINT_NOT_FOUND`, or controller short-circuit when an entity is missing (**bare-body**). |
| `405 Method Not Allowed` | `METHOD_NOT_ALLOWED`. |
| `409 Conflict` | `RESOURCE_CONFLICT`, `RESOURCE_DUPLICATE`, `DATA_INTEGRITY_VIOLATION`. |
| `413 Payload Too Large` | `FILE_TOO_LARGE`. |
| `415 Unsupported Media Type` | `UNSUPPORTED_MEDIA_TYPE`. |
| `429 Too Many Requests` | `RATE_LIMITED`. |
| `500 Internal Server Error` | `INTERNAL_ERROR`, `ILLEGAL_STATE`, the multipart `Custom-500` body, and the `SecurityException` quirk. |
| `502 Bad Gateway` | The post-multipart `Custom-502` for R2 upload failure. |
| `503 Service Unavailable` | `STORAGE_UNAVAILABLE`. |

### 15.4 Always surface the `traceId`

For every 5xx response, the error body includes `traceId`. Surface it
in the user-facing error UI so support can grep the server log instantly:

```
Sorry, something went wrong.
Reference: a1b2c3d4-e5f6-7890-1234-567890abcdef
```

For the `INTERNAL_ERROR` code specifically, the `traceId` is **also
interpolated into `message`**:

> `"An unexpected error occurred. Please try again later. If the
> problem persists, contact support with trace ID: a1b2c3d4-..."`

### 15.5 Rate-limit retry pattern

```ts
async function withRateLimitRetry<T>(call: () => Promise<T>, maxRetries = 1): Promise<T> {
  for (let i = 0; i <= maxRetries; i++) {
    try {
      return await call();
    } catch (e) {
      if (e instanceof RateLimited && i < maxRetries) {
        await sleep(e.retryAfterSeconds * 1000);
        continue;
      }
      throw e;
    }
  }
  throw new Error('unreachable');
}
```

### 15.6 SSE error handling

For `GET /api/v1/posts/{id}/stream`:

- `EventSource` cannot send custom headers — pass the JWT as `?token=`
  if the endpoint requires auth (the controller respects this).
- `403 ACCESS_DENIED` is **JSON** (not SSE) — the server forces
  `Content-Type: application/json` so the browser won't try to consume
  it as an event stream. Handle by listening for `onerror` and then
  fetching the JSON body via `await res.text()` if you need details.
- Use the server's `reconnectTime` hint (3s, sent on the handshake) to
  back off reconnects after a JVM restart.
- The server filters out events for the actor's own subscription — the
  originating tab does NOT receive an echo. Don't treat missing echo as
  an error.

---

## 16. Server-side guidance

When adding new code to the Post package, **prefer the `AppException`
family** so the response is the unified shape and the `errorCode` is
meaningful to the frontend:

```java
// 404 — formatted message "Post not found with id: ..."
throw new ResourceNotFoundException("Post", "id", postId);

// 403 — semantic permission failure
throw new ForbiddenException("Only the post author can delete this post",
                              "POST_DELETE_FORBIDDEN");

// 400 — invalid input with structured details
throw new BadRequestException(
    "Post body cannot exceed 10 000 characters",
    "POST_BODY_TOO_LONG",
    Map.of("length", body.length(), "max", 10_000));

// 409 — conflict
throw new ConflictException(
    "Post was already deleted",
    "POST_ALREADY_DELETED");

// 429 — rate limit (let RateLimiter do this)
rateLimiter.check("post-create", actorId, 5, Duration.ofMinutes(1));

// 401 — authentication failure that isn't from Spring Security
throw new UnauthorizedException(
    "Refresh token expired",
    "AUTH_REFRESH_EXPIRED");
```

**Avoid** raw `SecurityException` / `RuntimeException` — they fall
through to the catch-all and surface as `500 INTERNAL_ERROR`, losing
the semantic status code.

**Avoid** hand-rolling response bodies — the only existing
exceptions are the two multipart custom-bodies, and even those are a
historical wart. Use exceptions instead.

When you DO need a custom `errorCode`, follow the convention:

- `<RESOURCE>_<STATE>` (e.g. `POST_NOT_FOUND`, `POST_ALREADY_DELETED`)
- `<ACTION>_<REASON>` (e.g. `POST_DELETE_FORBIDDEN`, `COMMENT_RATE_LIMITED`)
- `AUTH_<SPECIFIC>` for any new auth failure mode.

The frontend's TypeScript union ([§15.1](#151-typescript-types)) should
be updated alongside any new code your service emits.
