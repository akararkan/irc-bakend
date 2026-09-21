# Pages — chat, channels, live & notifications

Part of the [admin dashboard frontend guide](README.md).
Legend: **SU** = step-up required (§[auth-and-roles.md](auth-and-roles.md)) ·
roles in the *Who* column are the `hasRole`/`hasAnyRole` grants as coded ·
list endpoints paginate per [conventions.md](conventions.md).
Wire-level request/response JSON: [../api/](../api/README.md).

Section docs: [../communication/](../communication/README.md).

---

### 4.9 Chat, channels & live — bases `/api/v1/admin/chat`, `…/channels`, `…/streams`

`AdminChatController`, `AdminChannelController`, `AdminStreamController`,
`LegalHoldController`. ADMIN + MODERATOR except where noted. Doc:
[chat-channels-live.md](../communication/chat-channels-live.md). **Privacy boundary:** metadata
always, message content never — DTOs exclude `last_message_preview`, stream
keys, publish keys. Do not build UI that implies content is viewable.

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /api/v1/admin/chat/conversations` · `/overview` · `/calls` · `/calls/stats` · `/message-requests/stats` | filters + pageable / `from,to` | — | ADMIN, MODERATOR |
| `GET /api/v1/admin/channels` · `/{id}` · `/{id}/stats` · `/{id}/invite-links` | `q, verified, public, category` + pageable | — | ADMIN, MODERATOR |
| `PATCH /api/v1/admin/channels/{id}/verified` | `verified` bool | — | ADMIN, MODERATOR |
| `POST …/channels/{id}/takedown` · `/restore` · `/freeze` | `{reason, reportId?}` | **SU** | ADMIN, MODERATOR |
| `POST …/channels/{id}/unlist` · `/unfreeze` · `/invite-links/{inviteId}/revoke` | — | — | ADMIN, MODERATOR |
| `GET /api/v1/admin/streams` · `/{id}` · `/gifts/top` | `status, hostId` + pageable / `window, limit` | — | ADMIN, MODERATOR |
| `GET …/streams/{id}/recording` · `GET …/streams/recordings` | — | **SU** | content-adjacent read — loud confirm |
| `POST …/streams/{id}/force-stop` | `{reason}` | **SU** | ADMIN, MODERATOR |
| `POST …/streams/{id}/rotate-key` | — | **SU** | **ADMIN only** — key-level control never delegates |
| `DELETE …/streams/{id}/stage/{userId}` (remove guest) · `DELETE …/streams/{id}/recording` | `{reason}` | **SU** | ADMIN, MODERATOR |

The recordings fleet view caps detailed rows and says so in a `note` field —
render it. Rotate-key delivers the new key **to the host only**; the response
never contains it.

**Legal holds — base `/api/v1/admin/chat/legal-holds`** (`LegalHoldController`,
**ADMIN only**, the sole message-content release path, dual-control):

| Method + path | SU | Notes |
|---------------|----|-------|
| `GET /api/v1/admin/chat/legal-holds` | — | `status` = OPEN/APPROVED/EXECUTED/REJECTED |
| `POST /api/v1/admin/chat/legal-holds` | **SU** | `{conversationId, reason}` — reason is a case/court reference, mandatory |
| `POST …/{id}/approve` · `/reject` | **SU** | **A different admin than the opener** — self-approval 409s. Optional `{note}`. |
| `POST …/{id}/execute` | **SU** | One-shot: releases the newest ≤500 messages, flips to EXECUTED, cannot be re-run. Response carries a `warning` string — display it prominently in the export view. |

UI: show the opened-by/approved-by pair on every hold; grey out Approve for
the opener; treat Execute as the most dangerous button in the dashboard (§6).

### 4.12 Notifications & announcements — base `/api/v1/admin/notifications`

`AdminNotificationController` — ADMIN (stats/types also SUPPORT, ANALYST).
Doc: [notifications-email.md](../communication/notifications-email.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /stats` | `from, to` (ISO date-times, default last 30d) | — | + SUPPORT, ANALYST |
| `GET /types` | — (static registry of every NotificationType) | — | + SUPPORT, ANALYST |
| `POST /announcements` | `{title, body, audienceRole?, audienceLanguage?, activeSinceDays?, scheduledAt?, dryRun?, confirmLargeAudience?}` | **SU** | ADMIN |
| `GET /announcements` · `DELETE /announcements/{id}` (cancel a SCHEDULED one) | pageable | **SU** on DELETE | ADMIN |
| `POST /digest/run` | `date?` | — | ADMIN |
| `GET /email/stats` · `POST /email/test` · `DELETE /push-tokens/{id}` | — | — | ADMIN |

**Announcement composer flow (build it exactly like this):** always send
`dryRun: true` first → the 200 response reports the computed `audience` size;
show it ("This will notify N users"). Real send returns **202**. If the
audience is **≥ half the platform**, the server 400s unless
`confirmLargeAudience: true` — surface that as a second, explicit checkbox,
never auto-set it. `scheduledAt` is ISO-8601 local date-time; scheduled
announcements are cancellable until the sweep fires them. The `/stats`
response carries an honest `note` about its legacy-inbox sourcing — render it.
