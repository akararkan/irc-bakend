# Notification Settings — Matrix, DND & Push (§8)

**[B] Backend-owned.** Package `settings.notification`. The existing platform has
in-app SSE + email delivery; this module adds the preference **matrix**, the
**Do-Not-Disturb** window, and a **push-token** registry — as standalone
components that the delivery pipeline can consult.

## Preference matrix

- `NotificationChannel` — `PUSH · IN_APP · EMAIL · SMS · DESKTOP`.
- `user_notification_prefs (user_id, event_type, channel, enabled)` — one row per
  override; an **absent row means the code default**, never "off".
- `NotificationDefaults.defaultEnabled(eventType, channel)` — the policy: IN_APP
  on for everything; PUSH on except the trending digest; EMAIL on for
  system/social/mentions and off for high-volume reactions; SMS off by default.
  Security/login alerts are in `BYPASS_ALL` and are always delivered.
- `NotificationPrefResolver.isEnabled(userId, eventType, channel)` resolves an
  explicit row over the default; `resolvedMatrix(...)` returns the full grid for
  the settings UI.

**Endpoints:** `GET /api/v1/settings/notifications` (matrix),
`PUT /api/v1/settings/notifications/{eventType}/{channel}` `{enabled}` —
`eventType` is validated against the 42-value `NotificationType`.

## Do-Not-Disturb (`user_dnd`)

`DndEvaluator.inQuietHours(dnd, now)` is a pure function that gets two things
right the spec calls out:

1. **User's own timezone.** The window is evaluated in the stored **IANA** zone
   (`Asia/Baghdad`), not a UTC offset — offsets break on DST.
2. **Cross-midnight windows** (22:00 → 07:00) are handled explicitly; the naive
   `start ≤ now < end` comparison silently disables DND for everyone who sets one.

Also honours a `days_mask` (bit per weekday) and a hard `mute_until` override.

**Endpoints:** `GET/PUT /api/v1/settings/notifications/dnd`
`{enabled,timezone,startTime("HH:mm"),endTime,daysMask,muteUntil}` — the timezone
is validated with `ZoneId.of`.

## Push tokens (`push_tokens`)

`PushTokenService` registers per-session tokens (upsert by token), lists and
deletes them. `settings.notification.push.PushSender` is the delivery interface;
`NoOpPushSender` is the default stand-in (logs) — a real FCM/APNs sender drops in
later. **Token hygiene:** delete on logout (session revoke) and on a provider
`UNREGISTERED`.

**Endpoints:** `GET/POST /api/v1/settings/notifications/push-tokens`,
`DELETE /.../push-tokens/{id}`.

## Delivery-pipeline seam

The resolver and evaluator are deliberately standalone so they can be wired
without disturbing the working hot path. **Seam:**
`CassandraNotificationService` delivery should, per recipient and channel: skip
if blocked/muted → check `NotificationPrefResolver.isEnabled` → if not a
bypass type, defer/drop on `DndEvaluator.inQuietHours` → fan out to the enabled
channels (SSE / email / `PushSender` / `SmsSender`). Security and login alerts
ignore both the matrix and DND (they are in `BYPASS_ALL`).
