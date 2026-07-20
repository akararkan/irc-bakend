# Audit API

Base path: **`/api/v1/admin/audit`**

Admin-only access to the platform audit log. Every API request under `/api/**` is
captured by a server-side interceptor and persisted (asynchronously) to Cassandra,
then broadcast on an admin SSE feed via Redis pub/sub.

**Auth (all endpoints):** Bearer JWT with the **`ADMIN`** role.

> **Changed:** access control is now actually enforced. The controller carries a
> class-level `@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")`, and the security
> chain independently requires `ROLE_ADMIN` for everything under `/api/v1/admin/**`
> (belt-and-braces — a forgotten annotation can no longer expose an admin route).
> Note: the `SUPER_ADMIN` name in the annotation is vestigial — that role tier was
> removed from the platform's `Role` enum (only `USER` / `RESEARCHER` / `SCHOLAR` /
> `ADMIN` exist), so in practice **`ADMIN` is the requirement**.

Errors use the standard envelope — see
[../errors/error-handling.md](../errors/error-handling.md).

Siblings: [tags.md](./tags.md) · [search.md](./search.md) · [mentions.md](./mentions.md) ·
[media-proxy.md](./media-proxy.md) · [activity.md](./activity.md)

---

## What is captured

For every audited request, one entry records **metadata only** — request/response
bodies are never read, so credentials and payloads cannot leak into the log:

| Captured | Detail |
|---|---|
| `operation` | Coarse verb class: `READ` (GET/HEAD), `CREATE` (POST), `UPDATE` (PUT/PATCH), `DELETE`, `LOGIN`, `LOGOUT`, `UPLOAD` (multipart POST), `SYSTEM` (non-HTTP internal actions), `OTHER`. **`READ` operations are audited too** — every GET an admin or user makes leaves a row. |
| `outcome` | `SUCCESS` (2xx), `REDIRECT` (3xx), `CLIENT_ERROR` (4xx), `SERVER_ERROR` (5xx or unhandled exception), `SYSTEM` (non-HTTP). |
| Who | `userId` + `username` from the authenticated principal (null for anonymous requests). |
| What | `httpMethod`, `path`, `queryString` (truncated to 1000 chars), best-effort `resourceType` + `resourceId` parsed from the deepest UUID in the path (e.g. `/posts/{a}/comments/{b}` → `PostComment` / `{b}`). |
| Result | `statusCode`, `durationMs` (server-side wall clock), `errorCode` (exception class name when one escaped). |
| Where from | `ipAddress` (first `X-Forwarded-For` hop, then `X-Real-IP`, then socket address), `userAgent` (truncated to 400 chars). |
| `summary` | One-line human string, e.g. `POST /api/v1/posts → 201`. |

Not audited (noise/pathology exclusions): `/actuator*`, `/error`, `/favicon*`,
`/swagger*`, `/v3/api-docs`, `/health`, any path ending in `/stream` (SSE), and
heartbeat paths. The audit write is async and failure-isolated — a failed audit write
never affects the request being audited.

---

## 1. Search the audit log

```
GET /api/v1/admin/audit
```

**Auth:** `ADMIN` role required.

Audit entries for one user, newest first, cursor-paginated. `userId` is **required** —
the Cassandra storage is partitioned by user, so an unscoped query has no partition to
read; use the SSE stream (§3) for a global live view.

### Query parameters

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `userId` | UUID | **yes** | — | Whose requests to list. Omitting it returns `400` (empty body). |
| `operation` | enum | no | — | `READ` / `CREATE` / `UPDATE` / `DELETE` / `LOGIN` / `LOGOUT` / `UPLOAD` / `SYSTEM` / `OTHER`. |
| `outcome` | enum | no | — | `SUCCESS` / `REDIRECT` / `CLIENT_ERROR` / `SERVER_ERROR` / `SYSTEM`. |
| `from` | ISO-8601 date-time | no | — | Inclusive lower bound on `createdAt` (UTC). |
| `to` | ISO-8601 date-time | no | — | Inclusive upper bound (UTC). |
| `pageSize` | int | no | `50` | Rows fetched from storage per page. |
| `cursor` | ISO-8601 date-time | no | — | The `createdAt` of the **last row of the previous page**. Pages walk backward in time. |

> **Filtering caveat (documented honestly).** `operation`, `outcome`, `from`, and `to`
> are applied **in memory to the fetched page slice only**: the server reads `pageSize`
> rows from the user's partition (by time), then filters them. Consequences:
>
> - A page can return **fewer than `pageSize` rows — even zero — while older matches
>   still exist.** An empty page does **not** mean "no more matches"; it means none of
>   the `pageSize` newest-remaining rows matched.
> - To scan a range exhaustively, keep advancing `cursor` (use the `createdAt` of the
>   last row you received — you may need to track it from an unfiltered call when a
>   page filters down to zero) until pages come back short of `pageSize` from storage.
> - For narrow filters over long histories, prefer a larger `pageSize` to reduce
>   round-trips.

### Response `200`

An array of audit entries (no envelope). `null` fields are omitted from the JSON.

```json
[
  {
    "id":           "e4a10000-0000-4000-8000-000000000010",
    "userId":       "9a2c0000-0000-4000-8000-000000000002",
    "username":     "akram",
    "operation":    "CREATE",
    "outcome":      "SUCCESS",
    "resourceType": "Post",
    "resourceId":   "1b2c0000-0000-4000-8000-000000000001",
    "httpMethod":   "POST",
    "path":         "/api/v1/posts",
    "queryString":  null,
    "statusCode":   201,
    "durationMs":   84,
    "ipAddress":    "203.0.113.7",
    "userAgent":    "Mozilla/5.0 …",
    "summary":      "POST /api/v1/posts → 201",
    "errorCode":    null,
    "createdAt":    "2026-07-20T09:14:03"
  },
  {
    "id":         "e4a10000-0000-4000-8000-000000000011",
    "userId":     "9a2c0000-0000-4000-8000-000000000002",
    "username":   "akram",
    "operation":  "READ",
    "outcome":    "CLIENT_ERROR",
    "resourceType": "Research",
    "httpMethod": "GET",
    "path":       "/api/v1/researches/missing",
    "statusCode": 404,
    "durationMs": 11,
    "ipAddress":  "203.0.113.7",
    "userAgent":  "Mozilla/5.0 …",
    "summary":    "GET /api/v1/researches/missing → 404",
    "createdAt":  "2026-07-20T09:13:41"
  }
]
```

| Field | Type | Meaning |
|---|---|---|
| `id` | UUID | Audit entry id. |
| `userId` / `username` | UUID / string | Acting principal (null when anonymous). |
| `operation` / `outcome` | enum / enum | See "What is captured". |
| `resourceType` / `resourceId` | string / UUID | Best-effort parse from the path; may be null. |
| `httpMethod` / `path` / `queryString` | string | The request line (query string truncated at 1000 chars). |
| `statusCode` / `durationMs` | int / long | Response status and server-side duration. |
| `ipAddress` / `userAgent` | string | Client origin (proxy-aware IP; UA truncated at 400 chars). |
| `summary` | string | One-line rendering. |
| `errorCode` | string | Exception class simple name when one escaped the handler; usually null. |
| `createdAt` | date-time (UTC) | Entry time — feed the last row's value back as `cursor`. |

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | — (empty body) | `userId` missing. |
| 400 | `VALIDATION_FAILED` | Malformed UUID, enum, or date-time parameter. |
| 401 | `AUTH_REQUIRED` | No / invalid Bearer token. |
| 403 | `ACCESS_DENIED` | Authenticated but not `ADMIN`. |
| 503 | `DATASTORE_UNAVAILABLE` | Cassandra temporarily unreachable. |

---

## 2. Per-user history

```
GET /api/v1/admin/audit/users/{userId}
```

**Auth:** `ADMIN` role required.

The same per-user feed as §1 without the in-memory filters — a plain cursor-paginated
walk of one user's audit partition, newest first. Prefer this for "show me everything
user X did".

### Path & query parameters

| Name | In | Type | Required | Default | Notes |
|---|---|---|---|---|---|
| `userId` | path | UUID | yes | — | Whose requests to list. |
| `pageSize` | query | int | no | `50` | Rows per page. |
| `cursor` | query | ISO-8601 date-time | no | — | `createdAt` of the previous page's last row. Stop when a page comes back shorter than `pageSize`. |

### Response `200`

Same array-of-entries shape as §1.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Malformed `userId` or `cursor`. |
| 401 | `AUTH_REQUIRED` | No / invalid Bearer token. |
| 403 | `ACCESS_DENIED` | Authenticated but not `ADMIN`. |
| 503 | `DATASTORE_UNAVAILABLE` | Cassandra temporarily unreachable. |

---

## 3. Global live stream (SSE)

```
GET /api/v1/admin/audit/stream
```

**Auth:** `ADMIN` role required — **JWT principal only**. Unlike the activity stream,
there is no `?token=` fallback here, so a browser `EventSource` (which cannot set
headers) won't work directly; use a fetch-based SSE client that sends the
`Authorization` header.

Global realtime audit feed: **every** audit entry platform-wide is pushed to every
connected admin the moment it is recorded, across all app instances via Redis pub/sub
(channel `irc:audit:stream`). This is the intended tool for a global view — the REST
search (§1) is per-user by design.

Multiple admins and multiple tabs per admin may subscribe simultaneously.

### Events

| Event | Payload | Notes |
|---|---|---|
| `connected` | `{ "adminId": "…", "timestamp": "…" }` | Sent once on subscribe. |
| `audit` | One audit entry — same JSON shape as a §1 array element. | One event per audited request, platform-wide. |
| `heartbeat` | `{ "timestamp": "…" }` | Every **25 s**; keeps proxies from idling out the connection. |

```
event: audit
data: {"id":"e4a1…","username":"akram","operation":"READ","outcome":"SUCCESS","httpMethod":"GET","path":"/api/v1/posts/1b2c…","statusCode":200,"durationMs":9,"summary":"GET /api/v1/posts/1b2c… → 200","createdAt":"2026-07-20T09:14:03"}
```

Note that SSE endpoints themselves (paths ending in `/stream`) are excluded from
auditing, so watching the stream does not generate feedback-loop entries.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No authenticated principal. |
| 403 | `ACCESS_DENIED` | Authenticated but not `ADMIN`. |
