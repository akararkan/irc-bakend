# Error Handling Reference

> **This is the canonical error reference.** Every other doc that mentions an error response should
> link here instead of re-documenting the envelope. For guidance on *throwing* errors from backend
> code, see [exception-design.md](exception-design.md).

Every error the API returns — validation failure, expired JWT, missing resource, Cassandra hiccup,
or an unexpected 500 — comes back in **one envelope**: `ApiErrorResponse`
(`src/main/java/ak/dev/irc/app/common/dto/ApiErrorResponse.java`), produced by
`GlobalExceptionHandler` (`src/main/java/ak/dev/irc/app/common/exception/GlobalExceptionHandler.java`)
and, for container-level errors, by `ApiErrorController` (same package).

---

## The `ApiErrorResponse` envelope

```json
{
  "timestamp":  "2026-07-20T14:30:00.123456Z",
  "status":     404,
  "error":      "Not Found",
  "message":    "Question not found with id: 3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
  "path":       "/api/v1/questions/3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f",
  "errorCode":  "QUESTION_NOT_FOUND",
  "details": {
    "resource": "Question",
    "field":    "id",
    "value":    "3f8a1c2e-9b47-4d1a-8e2f-6c5d4b3a2e1f"
  },
  "traceId":    "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
}
```

| Field | Type | Always present? | Meaning |
|---|---|---|---|
| `timestamp` | string (ISO-8601) | yes | **UTC**, `'Z'`-suffixed. The value is generated with `LocalDateTime.now(ZoneOffset.UTC)` and JacksonConfig appends a literal `Z` to every `LocalDateTime` — so it must actually *be* UTC or the label would lie on non-UTC servers. |
| `status` | int | yes | HTTP status code (mirrors the response status line). |
| `error` | string | yes | HTTP reason phrase (`"Bad Request"`, `"Not Found"`, …). |
| `message` | string | yes | Human-readable explanation. Safe to show to users for 4xx; generic for 5xx (no stack traces or internals ever leak). |
| `path` | string | yes | Request URI that caused the error. |
| `errorCode` | string | usually | **Machine-readable** code for frontend `switch`/`case` handling. See the catalog below. May be `null` only for `AppException`s constructed without a code. |
| `details` | object | no | Extra context, varies per error (e.g. `{"parameter": "size", "expectedType": "int"}`). Omitted when empty. |
| `fieldErrors` | array | validation only | One entry per invalid field: `{"field", "message", "rejectedValue"}`. Present only on `VALIDATION_FAILED`. |
| `traceId` | string (UUID) | yes | Correlation ID for server logs — see below. |

Null fields are omitted from the JSON entirely (`@JsonInclude(NON_NULL)`).

### Validation example (`fieldErrors`)

```json
{
  "timestamp":  "2026-07-20T14:31:05.987654Z",
  "status":     400,
  "error":      "Validation Failed",
  "message":    "One or more fields failed validation. Check 'fieldErrors' for details.",
  "path":       "/api/v1/questions",
  "errorCode":  "VALIDATION_FAILED",
  "fieldErrors": [
    { "field": "title",    "message": "must not be blank",                  "rejectedValue": "" },
    { "field": "tagNames", "message": "size must be between 1 and 5",       "rejectedValue": [] }
  ],
  "traceId":    "7c1d9e2f-3a4b-4c5d-8e9f-0a1b2c3d4e5f"
}
```

### `traceId` — correlating with server logs

Every handler in `GlobalExceptionHandler` logs a line tagged `[<traceId>]` *before* building the
response, so support/debugging is: take the `traceId` from the client's error payload, grep the
server logs for it.

Resolution order (`GlobalExceptionHandler.traceId()`):

1. **The request's MDC `traceId`**, if a correlation filter has put one into SLF4J's MDC — the
   error response then joins up with access/audit log lines carrying the same id.
2. Otherwise a **freshly generated UUID** — still self-correlating, because the handler's own log
   line carries the same id as the response body.

`ApiErrorController` (the `/error` backstop, see below) always self-generates its `traceId`.

---

## Error-code catalog

Built from every `@ExceptionHandler` in `GlobalExceptionHandler`. "SSE: status-only" means the
handler returns a body-less response when the request is SSE-negotiated (see
[SSE error semantics](#sse-error-semantics)).

### Validation & request-shape errors (4xx)

| `errorCode` | HTTP | When it happens |
|---|---|---|
| `VALIDATION_FAILED` | 400 | `@Valid` **body** validation failed (`MethodArgumentNotValidException`, with `fieldErrors`); **or** `@Validated` **parameter** validation failed — path variables / query params (`ConstraintViolationException`, with `fieldErrors`); **or** Spring 6.1 method-validation wrapper (`HandlerMethodValidationException`, no `fieldErrors`). |
| `MISSING_PARAMETER` | 400 | A required query parameter is absent. `details`: `parameter`, `expectedType`. |
| `MISSING_REQUEST_PART` | 400 | A required request **header** (`MissingRequestHeaderException`) or **multipart part** (`MissingServletRequestPartException`) is absent — e.g. a multipart upload without its `data` or `files[]` part. |
| `TYPE_MISMATCH` | 400 | A path/query parameter can't convert to its declared type. `details`: `parameter`, `expectedType`, `receivedValue`, `hint`. Special case: if the value is the JS literal `"undefined"`/`"null"`, the message and `hint` (`frontend_path_param_unhydrated`) point at the frontend templating an unhydrated variable into the URL. |
| `MALFORMED_JSON` | 400 | Request body isn't parseable JSON (`HttpMessageNotReadableException`). |
| `ILLEGAL_ARGUMENT` | 400 | A service threw `IllegalArgumentException` for malformed/invalid input — the exception's own message is surfaced. See [exception-design.md](exception-design.md#illegalargumentexception--400). |
| `METHOD_NOT_ALLOWED` | 405 | HTTP method not supported by the endpoint; message lists supported methods. |
| *(no body)* | 406 | Client's `Accept` header demands a representation we can't produce (e.g. `application/xml`). **Body-less by design** — writing JSON would re-trigger the same negotiation failure (`HttpMediaTypeNotAcceptableException`). |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Request `Content-Type` not supported; message lists supported types. |
| `FILE_TOO_LARGE` | 413 | Upload exceeds the max size. `details`: `maxSize`. |
| `RATE_LIMITED` | 429 | `RateLimiter` burst exceeded — see [the 429 shape](#the-429-rate-limit-shape). |

### Authentication & authorization (401 / 403)

| `errorCode` | HTTP | When it happens |
|---|---|---|
| `AUTH_BAD_CREDENTIALS` | 401 | Wrong email or password (`BadCredentialsException`). |
| `AUTH_ACCOUNT_DISABLED` | 401 | Account disabled (unverified email or suspended). |
| `AUTH_ACCOUNT_LOCKED` | 401 | Account locked. |
| `AUTH_ACCOUNT_EXPIRED` | 401 | Account expired. |
| `AUTH_CREDENTIALS_EXPIRED` | 401 | Credentials expired — password reset needed. |
| `AUTH_INSUFFICIENT` | 401 | Full authentication required for this resource. |
| `AUTH_FAILED` | 401 | Any other `AuthenticationException`. |
| `AUTH_UNAUTHORIZED` | 401 | Default code of the domain `UnauthorizedException`. |
| `AUTH_REQUIRED` | 401 | Container-level 401 via the `/error` backstop (`ApiErrorController`). |
| `ACCESS_DENIED` | 403 | Spring Security denied the request (`AccessDeniedException` / `AuthorizationDeniedException`, e.g. a failed `@PreAuthorize`). SSE: status-only. Also the `/error` backstop's 403 code. |
| `FORBIDDEN` | 403 | A service-layer ownership check threw plain `SecurityException` ("Not the author" on story delete, poll attach, QnA/Research mutations). Explicitly mapped so it doesn't surface as a misleading 500. |
| `ACCESS_FORBIDDEN` | 403 | Default code of the domain `ForbiddenException`. |

### Not found (404)

| `errorCode` | HTTP | When it happens |
|---|---|---|
| `ENDPOINT_NOT_FOUND` | 404 | No handler/route matches the URL (`NoHandlerFoundException` / `NoResourceFoundException`). SSE: status-only. Also the `/error` backstop's 404 code. |
| `RESOURCE_NOT_FOUND` | 404 | Legacy `jakarta.persistence.EntityNotFoundException` from services, or the message-only `ResourceNotFoundException` constructor. |
| `{RESOURCE}_NOT_FOUND` | 404 | Domain `ResourceNotFoundException("User", "id", userId)` — code derived from the resource name (`USER_NOT_FOUND`, `QUESTION_NOT_FOUND`, …). `details`: `resource`, `field`, `value`. |
| `MEDIA_NOT_FOUND` | 404 | Object key missing on R2/S3 (`NoSuchKeyException`) — a requested file does not exist in storage. More specific than `STORAGE_ERROR`; Spring picks the most specific handler. |

### Conflict & concurrency (409)

| `errorCode` | HTTP | When it happens |
|---|---|---|
| `OPTIMISTIC_LOCK_CONFLICT` | 409 | JPA optimistic-lock loss after `OptimisticLockRetry` exhausts its attempts (`OptimisticLockingFailureException`). The client should **re-read and retry** — it is not a server fault. |
| `DATA_INTEGRITY_VIOLATION` | 409 | Database constraint violation (unique / FK) that wasn't pre-checked. |
| `RESOURCE_CONFLICT` | 409 | Default code of the domain `ConflictException`. |
| `{RESOURCE}_DUPLICATE` / `RESOURCE_DUPLICATE` | 409 | Domain `DuplicateResourceException` — uniqueness violation caught at the business layer (`USER_DUPLICATE`, `EMAIL_DUPLICATE`, …). `details`: `resource`, `field`, `value`. |

### Server & infrastructure (5xx)

| `errorCode` | HTTP | When it happens |
|---|---|---|
| `ILLEGAL_STATE` | 500 | A service threw `IllegalStateException` — an invariant broke. Logged at ERROR with the full stack. |
| `DATASTORE_QUERY_ERROR` | 500 | Invalid CQL / Cassandra schema drift (`QueryValidationException`). A server bug with a stable code; message tells the user to contact support with the `traceId`. |
| `INTERNAL_ERROR` | 500 | Catch-all for anything unhandled. Never leaks the exception; message includes the `traceId`. SSE: status-only. Also the `/error` backstop's 5xx code. |
| `STORAGE_ERROR` | 502 | R2/S3 **service** error — auth failure, throttling, upstream 5xx (`S3Exception`). Bad gateway, not a generic 500. |
| `STORAGE_UNAVAILABLE` | 503 | AWS SDK **client-side** failure reaching storage (`SdkClientException`) — network/DNS/timeout before the service answered. |
| `DATASTORE_UNAVAILABLE` | 503 | Transient datastore unavailability: Cassandra driver timeout (`DriverTimeoutException`), no reachable nodes (`AllNodesFailedException`), or Spring's `QueryTimeoutException` / `DataAccessResourceFailureException`. 503 tells clients and load balancers to retry. SSE: status-only. |

### Codes with no fixed value

| Source | HTTP | Notes |
|---|---|---|
| `AppException` (direct) | any | Carries its own `status` + `errorCode` + optional `details`, all passed through verbatim. See [exception-design.md](exception-design.md). |
| `/error` backstop, other 4xx | varies | `ERROR_{status}` (e.g. `ERROR_400`) for statuses the backstop has no named code for. |

---

## SSE error semantics

Requests whose `Accept` header is **only** `text/event-stream` (the browser `EventSource` case)
cannot receive a JSON error body: Spring has no JSON↔SSE converter, so serialising
`ApiErrorResponse` throws `HttpMediaTypeNotAcceptableException`, which cascades back through the
same handler and rains double stack traces.

Therefore `GlobalExceptionHandler.isSseRequest()` detects SSE-only requests
(`Accept` contains `text/event-stream` and offers **no** `*/*` or `application/json` fallback) and
the affected handlers return a **status-only response with no body**:

- 403 `ACCESS_DENIED` path
- 404 `ENDPOINT_NOT_FOUND` path
- 503 `DATASTORE_UNAVAILABLE` path
- 500 catch-all path
- everything routed through the `/error` backstop

The status code is all `EventSource.onerror` needs to fire; browsers with
`Accept: text/event-stream, */*` still get the full JSON envelope. Some SSE controllers additionally
write a plain-text 401 directly to the response (instead of throwing) for the same reason — see
[../realtime/overview.md](../realtime/overview.md#authentication).

## Client-disconnect suppression

When the client closes the TCP connection mid-response (an `<audio>`/`<video>` element seeking,
pausing, or unmounting; a tab closing during an SSE stream), there is no response left to write an
error to. `GlobalExceptionHandler`:

- handles `ClientAbortException` and `AsyncRequestNotUsableException` with a **void** handler —
  logged at DEBUG only, no body, no ERROR noise;
- in the catch-all, walks the **cause chain** for those types or any `IOException` whose message
  looks like `broken pipe` / `connection reset` / `connection abort`, and suppresses those too;
- if the response is already **committed** (a half-written media stream), logs and bails instead of
  colliding with the existing `Content-Type`.

## The `/error` container backstop (`ApiErrorController`)

Spring Boot's default `BasicErrorController` answers with its own
`{timestamp, status, error, message, path}` shape — a *different* envelope, which the frontend used
to see as "QnA/Research return a different error shape". `ApiErrorController`
(`src/main/java/ak/dev/irc/app/common/exception/ApiErrorController.java`) replaces it so errors
raised **before** the `@RestControllerAdvice` can run — in a servlet filter, or by the container
itself — still come back as `ApiErrorResponse`.

- Status resolved from the servlet `ERROR_STATUS_CODE` attribute (falls back to 500).
- `path` is the *original* request URI (`ERROR_REQUEST_URI`), not `/error`.
- Messages are stable and non-leaky — raw container text is never echoed.
- Codes: `AUTH_REQUIRED` (401), `ACCESS_DENIED` (403), `ENDPOINT_NOT_FOUND` (404),
  `INTERNAL_ERROR` (any 5xx), `ERROR_{status}` otherwise.
- Same SSE status-only guard as the advice.

Errors that reach the `@RestControllerAdvice` never hit this controller.

## The 429 rate-limit shape

`RateLimitExceededException` (thrown by `RateLimiter` when an actor exceeds the per-action burst
for a window) is an `AppException`, so it flows through the standard envelope:

```json
{
  "timestamp":  "2026-07-20T14:32:10.456789Z",
  "status":     429,
  "error":      "Too Many Requests",
  "message":    "Too many comment requests — please slow down",
  "path":       "/api/v1/posts/9d2f6a1b-4c3e-4f5a-b6c7-d8e9f0a1b2c3/comments",
  "errorCode":  "RATE_LIMITED",
  "details":    { "action": "comment", "retryAfterSeconds": 30 },
  "traceId":    "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9"
}
```

Clients should back off for `details.retryAfterSeconds` before retrying. The retry hint lives in
the body (there is no `Retry-After` response header).

---

## See also

- [exception-design.md](exception-design.md) — how backend contributors throw and add errors
- [../realtime/overview.md](../realtime/overview.md) — SSE endpoints and why their errors are body-less
- [../realtime/messaging.md](../realtime/messaging.md) — how *asynchronous* failures (RabbitMQ consumers) are retried and dead-lettered instead of surfacing as API errors
