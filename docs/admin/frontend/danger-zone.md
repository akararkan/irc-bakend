# Danger-zone UX rules

Part of the [admin dashboard frontend guide](README.md).
Legend: **SU** = step-up required (§[auth-and-roles.md](auth-and-roles.md)) ·
roles in the *Who* column are the `hasRole`/`hasAnyRole` grants as coded ·
list endpoints paginate per [conventions.md](conventions.md).
Wire-level request/response JSON: [../api/](../api/README.md).

---

## 6. Danger-zone UX rules

Client-side **typed confirmation** (user types the resource name / "DELETE" /
the username) before submitting, on top of the server's step-up gate:

| Action | Why |
|--------|-----|
| `POST /admin/users/{id}/purge/now` | Expedites irreversible GDPR purge past the nightly cron |
| `POST /admin/users/{id}/deletion/request` · `POST /admin/users/bulk-action` | Account-level, batch blast radius |
| `DELETE /admin/research/{id}` · `DELETE /admin/qna/questions/{id}` · `DELETE /admin/qna/answers/{id}` · `DELETE /admin/content/comments/{id}` · `DELETE /admin/content/stories/{id}` | Hard deletes — no restore path |
| `DELETE /admin/sounds/{id}` · `DELETE /admin/media/{assetId}` | Hard delete incl. all R2 renditions |
| `DELETE /admin/ops/queues/dlq/{id}` | Discards a dead letter (row kept, message gone) |
| `DELETE /admin/ops/redis/keys?prefix=` | Cache flush — even allowlisted, it's a stampede risk |
| `POST /admin/notifications/announcements` (non-dry-run) | Mass notification; pair with the dry-run-first flow and the `confirmLargeAudience` checkbox (§4.12) |
| `POST /admin/channels/{id}/takedown` · `POST /admin/streams/{id}/force-stop` · `POST /admin/streams/{id}/rotate-key` | Kills a live public surface / invalidates a host's key |
| `POST /admin/chat/legal-holds/{id}/execute` | Releases private message content — the single most sensitive action in the product |
| `POST /admin/tags/backfill-posts` | Trending bumps are not idempotent; re-runs corrupt counts |

Further rules:

- **Dry-run first, always**: where a `dryRun` param exists (announcements,
  media reconcile, purge-raw, stream orphan sweep) default the toggle **on**
  — even where the API default is `false` (sweep-orphans).
- **Dual-control is a second person, not a second click**: legal holds and
  break-glass cases require a *different* admin to approve. Build the UI as a
  two-actor workflow; disable Approve for the opener rather than letting the
  409/403 teach them.
- **Never render secrets** — and the API already guarantees you can't:
  `stream_key`, `publish_key`, 2FA secrets, refresh-token values, OTP hashes
  and `conversations.last_message_preview` are excluded from every admin DTO
  by construction. Do not add UI fields for them, do not log tokens (access,
  impersonation) to the console, and don't persist the impersonation token.
- Surface the self-protection errors (`SELF_ACTION_FORBIDDEN`, `LAST_ADMIN`,
  `IMPERSONATION_TARGET_ADMIN`) as friendly inline messages — the server
  always wins these arguments.

---
