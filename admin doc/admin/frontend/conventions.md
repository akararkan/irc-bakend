# Cross-cutting conventions

Part of the [admin dashboard frontend guide](README.md).
Legend: **SU** = step-up required (§[auth-and-roles.md](auth-and-roles.md)) ·
roles in the *Who* column are the `hasRole`/`hasAnyRole` grants as coded ·
list endpoints paginate per [conventions.md](conventions.md).
Wire-level request/response JSON: [../api/](../api/README.md).

---

## 5. Cross-cutting conventions

### 5.1 Pagination — two shapes

| Store | Shape | Frontend handling |
|-------|-------|-------------------|
| Postgres-backed lists | Spring `Pageable`: `page`, `size`, `sort` → `Page<DTO>` (`content`, `totalElements`, `totalPages`, `number`) | Classic pager. `size` is clamped server-side to **≤100** (`Pages.clamp`) — asking for more silently gets 100; don't build a "show 500" option. |
| Cassandra-backed lists (audit, sounds queue, content posts) | Keyset: `cursor` (ISO-8601 date-time of the last row) + `pageSize` → plain array | Infinite scroll / "Load more": pass the last row's `createdAt` as the next `cursor`. No total counts exist — don't render "page X of Y". |

Responses are **raw DTOs / `Page<DTO>` — no success envelope**. Only errors
are enveloped ([../errors/error-handling.md](../../errors/error-handling.md)).

### 5.2 Time & filters

- Ranges: `from` / `to` as ISO-8601 date-times, both optional; server
  defaults are last **24 h** for logs and last **30 d** for analytics.
  Windowed endpoints take `window`/`windowDays`-style params instead.
- Filter names are consistent everywhere: `userId`, `status`, `type`, `q`,
  `sort`. Enums are parsed **case-insensitively**; a bad value 400s and the
  error message lists the allowed values — show that message, it's written
  for humans.
- Mutations accept an `Idempotency-Key` header (24 h replay dedup) — send one
  on every dangerous POST so step-up retries and double-clicks are safe.

### 5.3 Async jobs (202 + jobId)

Long-running triggers (`/search/reindex-all`, `/ops/jobs/{jobKey}/run`,
`/ops/es/chat-messages/backfill`, non-dry-run announcements) return **202**
with `{jobId, note}`. Poll `GET /api/v1/admin/ops/jobs/{jobKey}/runs` for the
outcome; the 7 single-index reindexes are the exception — synchronous,
returning final counts in the response.

### 5.4 CSV exports

Three endpoints produce `text/csv`:

| Endpoint | Gate |
|----------|------|
| `GET /api/v1/admin/analytics/export?from&to&dataset` | ADMIN, ANALYST |
| `POST /api/v1/admin/logs/export` | ADMIN + **SU** |
| `GET /api/v1/admin/users/{userId}/activity/export?format=csv` | ADMIN + **SU** + open break-glass case |

`EventSource`-style tricks don't apply — plain `fetch` with the Bearer
header, read the blob, honor the `Content-Disposition` filename, trigger a
client-side download. Don't open these in a new tab (no header there).

### 5.5 The audit SSE stream

`GET /api/v1/admin/audit/stream?token=<accessJWT>` — the **only** admin SSE.
Events: `connected`, `audit` (one JSON row per audited request, fanned out
cross-instance via Redis), `heartbeat` every 25 s. Rules:

- One `EventSource` in the shell; widgets filter the event flow client-side
  by `operation`, `outcome`, `path` prefix, `resourceType`.
- Pass the token as `?token=` (EventSource cannot set headers). Reconnect
  with backoff on error; a token refresh means tearing down and reopening
  with the new token.
- If no `audit` events arrive for >5 min while you're generating traffic
  yourself, surface a "stream may be broken" warning — the backend treats
  that as an alert condition too.

### 5.6 Honest `note` / `warning` fields

Many admin responses carry a `note` (data-sourcing caveats, cap notices,
idempotency promises) or `warning` (legal-hold export, permit-all on, job
pause). These are part of the API contract: **always render them** — a muted
info line under the widget for `note`, an alert banner for `warning`. Never
swallow them.

---
