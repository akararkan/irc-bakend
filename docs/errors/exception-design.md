# Exception Design Guide (backend contributors)

How to throw errors from service code so they land in the unified envelope documented in
[error-handling.md](error-handling.md). All classes live in
`src/main/java/ak/dev/irc/app/common/exception/`.

## The `AppException` pattern

`AppException extends RuntimeException` is the root business exception. It carries three things
that `GlobalExceptionHandler` copies straight into the response:

- an `HttpStatus` → the response status,
- a machine-readable `errorCode` → `errorCode` in the envelope,
- an optional `Map<String, Object> details` → `details` in the envelope (omitted when empty).

```java
// Simple
throw new AppException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

// With details — everything in the map lands in the response unchanged
throw new AppException(
        "Email already registered",
        HttpStatus.CONFLICT,
        "EMAIL_DUPLICATE",
        Map.of("email", email));
```

Prefer the **subclasses** below over raw `AppException` — they pin the status so a throw site can
never pair, say, `USER_NOT_FOUND` with a 409.

## When to throw which exception

| Situation | Throw | HTTP | Default `errorCode` |
|---|---|---|---|
| Input violates a **business rule** (not bean validation) | `BadRequestException` | 400 | `BAD_REQUEST` |
| Caller is not authenticated / token invalid | `UnauthorizedException` | 401 | `AUTH_UNAUTHORIZED` |
| Authenticated but not allowed (role/permission) | `ForbiddenException` | 403 | `ACCESS_FORBIDDEN` |
| Entity lookup missed | `ResourceNotFoundException("User", "id", userId)` | 404 | `{RESOURCE}_NOT_FOUND` (derived) |
| State conflict (stale version, concurrent modification) | `ConflictException` | 409 | `RESOURCE_CONFLICT` |
| Uniqueness violation (pre-checked, not DB-bounced) | `DuplicateResourceException("User", "email", email)` | 409 | `{RESOURCE}_DUPLICATE` (derived) |
| Actor exceeded a rate-limit window | `RateLimitExceededException(action, retryAfterSeconds)` | 429 | `RATE_LIMITED` |

Notes:

- `ResourceNotFoundException` and `DuplicateResourceException` have convention constructors that
  **derive** the code from the resource name (`resource.toUpperCase().replace(" ", "_") +
  "_NOT_FOUND"` / `"_DUPLICATE"`) and auto-fill `details` with `{resource, field, value}`. Use them —
  you get a consistent message *and* code for free.
- Every subclass also accepts an explicit `errorCode` (and most accept `details`) when the default
  is too generic: `new BadRequestException("Poll needs two options", "POLL_OPTIONS_INVALID")`.

## `SecurityException` → 403 (ownership violations)

Service-layer **author/owner checks** ("Not the author" on story delete, poll attach, QnA/Research
mutations) throw plain `java.lang.SecurityException`. `GlobalExceptionHandler` maps it to
**403 `FORBIDDEN`** explicitly — without that mapping it would fall through to the catch-all and
surface as a misleading `500 INTERNAL_ERROR`, and the client couldn't distinguish "not allowed"
from "server broke". The exception's message (when non-blank) is surfaced to the client, so write
it for humans: `throw new SecurityException("Not the author of this story");`

Use `ForbiddenException` when you want a custom `errorCode`; plain `SecurityException` is the
established idiom for simple ownership guards.

## `IllegalArgumentException` → 400 (malformed input)

Services throw `IllegalArgumentException` for **malformed input** that survives bean validation —
an unknown enum string, an out-of-range cursor, an inconsistent pair of parameters. It maps to
**400 `ILLEGAL_ARGUMENT`** with the exception's message passed through. This keeps low-level
utility/parsing code free of any dependency on the exception package while still producing a
correct 4xx.

By contrast, `IllegalStateException` maps to **500 `ILLEGAL_STATE`** — reserve it for broken
invariants ("this should never happen"), never for user input.

## How to add a new `errorCode`

1. **Pick the right status** via the table above; only add a raw `AppException` (or a new subclass)
   if no existing status/subclass fits.
2. **Name the code** `SCREAMING_SNAKE_CASE`, namespaced by domain noun first:
   `STORY_EXPIRED`, `POLL_ALREADY_VOTED`, `RESEARCH_NOT_PUBLISHED`. Auth codes use the `AUTH_`
   prefix. Grep the codebase first so you don't mint a second code for an existing failure.
3. **Throw it** with a human-readable message and any machine-usable context in `details`:

   ```java
   throw new ConflictException(
           "You already voted on this poll",
           "POLL_ALREADY_VOTED",
           Map.of("pollId", pollId, "votedOption", existing.getOption()));
   ```

4. **Document it** in the catalog in [error-handling.md](error-handling.md) (that table is the
   contract the frontend codes against).
5. **Don't** add a new `@ExceptionHandler` for it — anything extending `AppException` is already
   handled generically. New `@ExceptionHandler` methods are only for *third-party* exception types
   that would otherwise hit the 500 catch-all (that's how `OPTIMISTIC_LOCK_CONFLICT`,
   `DATASTORE_UNAVAILABLE`, `MEDIA_NOT_FOUND`, etc. were added).

## Rules of thumb

- **Never let infrastructure exceptions escape raw** when you can classify them — the catch-all
  logs an ERROR and returns opaque `INTERNAL_ERROR`, which pages someone. If a third-party
  exception has a meaningful client interpretation, give it a handler + stable code.
- **Messages are user-facing for 4xx** — no class names, SQL, or stack fragments. 5xx messages are
  deliberately generic; the `traceId` is the debugging handle.
- **Don't catch-and-wrap `AppException`s** in services — let them fly to the advice.
- SSE endpoints have special error rules (status-only, no JSON) — see
  [error-handling.md](error-handling.md#sse-error-semantics) and
  [../realtime/overview.md](../realtime/overview.md) before touching a `*/stream` controller.
