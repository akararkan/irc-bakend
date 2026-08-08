# Admin API Reference — Index

Request/response JSON for **every admin endpoint**, one file per domain.
Every JSON key in these files was traced to the actual controller, DTO record,
entity or `Map`-building code — nothing is invented; where serialization has a
quirk (Jackson `non_null` omission, Lombok getter-name mangling, `Map.of` key
order), the file says so.

Written 2026-08-07 against the fully-implemented admin backend; re-counted
against source 2026-08-08 when the automated-moderation controllers landed.
Companion docs: [frontend/](../frontend/README.md) (how to build the UI),
[../../errors/frontend-error-handling.md](../../errors/frontend-error-handling.md)
(the error envelope + client patterns),
[../../errors/user-facing-messages.md](../../errors/user-facing-messages.md)
(every message string).

| File | Controllers | Endpoints |
|---|---|---|
| [users.md](users.md) | AdminUserController (32), AdminImpersonationController (1), step-up (`/api/v1/security/step-up`) | 33 + 1 |
| [content-moderation.md](content-moderation.md) | AdminContentController (12), AdminModerationController (3) | 15 |
| [automated-moderation.md](automated-moderation.md) | **AdminAutoModerationController (6), AdminModerationSettingsController (8), AdminModerationModelController (15)** | **29** |
| [safety-audit.md](safety-audit.md) | AdminSafetyController (14), AuditLogController (4, + audit SSE stream) | 18 |
| [research-qna-tags.md](research-qna-tags.md) | AdminResearchController (10), AdminQnaController (6), AdminTrendingController (4), TagAdminController (4), AdminKnowledgeController (9) | 33 |
| [chat-live.md](chat-live.md) | AdminChatController (5), AdminChannelController (11), AdminStreamController (9), LegalHoldController (5) | 30 |
| [sounds-media.md](sounds-media.md) | AdminSoundController (16), AdminMediaController (10), AdminStorageController (1) | 27 |
| [notifications-logs.md](notifications-logs.md) | AdminNotificationController (9), AdminLogsController (13 — explorer grammar, alerts, retention, OTP stats) | 22 |
| [analytics-feed-search.md](analytics-feed-search.md) | AdminAnalyticsController (11), AdminFeedController (4), AdminSuggestionsController (2), AdminSearchOpsController (5), SearchAdminController reindex hooks (7) | 29 |
| [ops-activity-discovery.md](ops-activity-discovery.md) | AdminOpsController (19), AdminActivityController (9, + break-glass), AdminDiscoveryController (7) | 35 |

**271 endpoints under `/api/v1/admin/**`**, across 31 controllers, plus
`POST /api/v1/security/step-up` (documented in `users.md` because it is the
prerequisite for every step-up-gated call).

### What changed most recently

- **2026-08-08 — [automated-moderation.md](automated-moderation.md) is new**:
  29 endpoints for the AI moderation subsystem (review queue, runtime threshold
  tuning + dry-run, training-data manager, model registry with retrain /
  promote / rollback). Nothing else moved.
- **2026-08-08 — `POST /api/v1/admin/sounds`** added to
  [sounds-media.md](sounds-media.md): sounds became **admin-curated only**, so
  this is now the canonical way a sound enters the library and the old
  user-facing upload route is a deprecated `ADMIN`/`MODERATOR` alias.

## Conventions used in every file

- **Access line** = class-level + method-level `@PreAuthorize` roles, plus a
  step-up flag where `@RequiresStepUp` applies (arm via
  `POST /api/v1/security/step-up`, ~300 s window, 403 `STEP_UP_REQUIRED`
  when cold).
- **Page responses** are shown trimmed to
  `{"content":[…], "totalElements", "totalPages", "number", "size"}` — the
  standard Spring pageable fields are also present on the wire. Page sizes
  clamp to 1–100 (`Pages.clamp`).
- **Jackson**: global `non_null` inclusion — `null` fields and `null` map
  entries are omitted from responses. Timestamps are ISO-8601
  (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` for `LocalDateTime`).
- **Errors** arrive in the canonical envelope
  (`../../errors/error-handling.md`); each endpoint lists its specific codes.
- Endpoints that mutate write `ADMIN_*` business-audit rows; the files name
  the exact action per endpoint.

When an endpoint changes, update its section here **and** the paired row in
[user-facing-messages.md](../../errors/user-facing-messages.md) if its
messages changed.
