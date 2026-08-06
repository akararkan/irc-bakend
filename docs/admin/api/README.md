# Admin API Reference — Index

Request/response JSON for **every admin endpoint**, one file per domain.
Every JSON key in these files was traced to the actual controller, DTO record,
entity or `Map`-building code — nothing is invented; where serialization has a
quirk (Jackson `non_null` omission, Lombok getter-name mangling, `Map.of` key
order), the file says so.

Written 2026-08-07 against the fully-implemented admin backend. Companion
docs: [frontend-dashboard-guide.md](../frontend-dashboard-guide.md) (how to
build the UI), [../errors/frontend-error-handling.md](../../errors/frontend-error-handling.md)
(the error envelope + client patterns),
[../errors/user-facing-messages.md](../../errors/user-facing-messages.md)
(every message string).

| File | Controllers | Endpoints |
|---|---|---|
| [users.md](users.md) | AdminUserController, AdminImpersonationController, step-up (`/api/v1/security/step-up`) | 34 |
| [content-moderation.md](content-moderation.md) | AdminContentController, AdminModerationController | 15 |
| [safety-audit.md](safety-audit.md) | AdminSafetyController, AuditLogController (+ audit SSE stream) | 18 |
| [research-qna-tags.md](research-qna-tags.md) | AdminResearchController, AdminQnaController, AdminTrendingController, TagAdminController, AdminKnowledgeController | 33 |
| [chat-live.md](chat-live.md) | AdminChatController, AdminChannelController, AdminStreamController, LegalHoldController | 30 |
| [sounds-media.md](sounds-media.md) | AdminSoundController, AdminMediaController, AdminStorageController | 26 |
| [notifications-logs.md](notifications-logs.md) | AdminNotificationController, AdminLogsController (explorer grammar, alerts, retention, OTP stats) | 22 |
| [analytics-feed-search.md](analytics-feed-search.md) | AdminAnalyticsController, AdminFeedController, AdminSuggestionsController, AdminSearchOpsController, SearchAdminController reindex hooks | 34 |
| [ops-activity-discovery.md](ops-activity-discovery.md) | AdminOpsController, AdminActivityController (+ break-glass), AdminDiscoveryController | 35 |

**~247 endpoints** documented in total.

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
