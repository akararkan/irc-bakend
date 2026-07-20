# Email Preferences & Notification Emails

Base path: **`/api/v1/users/me/email-preferences`**

Controls which notifications also go out as **email**. Emails are a secondary
channel: the in-app inbox row ([notifications.md](./notifications.md)) and the
SSE push ([realtime.md](./realtime.md)) are always delivered — the toggles here
gate only the email copy.

- **Auth:** every endpoint requires `Authorization: Bearer <accessToken>` (JWT).
- **Errors:** shared envelope (`status`, `error`, `message`, `path`,
  `errorCode`, `details`, `traceId`) — see
  [../errors/error-handling.md](../errors/error-handling.md).

Source of truth: `EmailPreferencesController`, `CassandraNotificationService`
(email gate + throttle), `EmailService`, `NotificationKind`.

---

## When is an email actually sent?

For a given notification, an email fires only when **all** of these hold:

1. **The kind is email-eligible** — declared per kind on `NotificationKind`
   (see the [kinds catalog](./notifications.md#notification-kinds)).
2. **`master` is on** — `emailNotificationsEnabled`, the kill-switch above
   everything else.
3. **The kind's preference-category toggle is on:**

   | Pref category | Toggle field | Covers |
   |---|---|---|
   | `SOCIAL` | `social` | Engagement on your content: reactions, comments, replies, shares, follows, stories, research, Q&A |
   | `MENTIONS` | `mentions` | Direct `@username` mentions anywhere |
   | `SYSTEM` | `system` | Platform messages, moderation, account warnings, sound approval |
   | `TRENDING` | `trending` | The daily [`TRENDING_DIGEST`](./notifications.md#the-trending_digest-daily-digest) — **independent of `system`**: muting the digest never mutes account warnings, and vice versa |

4. **The per-group throttle allows it** — see below.

Sending is async and fail-silent: three retries with exponential backoff, then
the email is dropped (the in-app notification is already persisted). Failures
never surface to the triggering request.

### The per-group email throttle

A burst of same-group events emails **once per hour**, not once per event.
On the first email-worthy event of a group, the service claims a Redis key:

```
SET notif:email:throttle:{userId}:{groupKey}  "1"  NX  EX 3600
```

- **First event in a group → email sent** (the key is claimed for 1 hour).
- **Subsequent same-group events within the hour → no email**, even though they
  keep coalescing into the in-app row ("Alice and 4 others…").
- Aggregation is the same mechanism from the other side: coalesced updates never
  re-email — only a *fresh* insert can, and the throttle then gates it.
- The throttle is per `(user, groupKey)`, so a comment on post A and a comment
  on post B can each email within the same hour.
- If Redis is unavailable the check is **permissive** (email goes out) rather
  than dropping mail.

### Deliverability headers (what recipients' mail clients see)

Every outbound message carries hardened headers: `Reply-To`, unique
`Message-ID`, `X-Entity-Ref-Id`, `Auto-Submitted: auto-generated`,
`Precedence: bulk`, and RFC 2369 / RFC 8058 one-click unsubscribe:

```
List-Unsubscribe: <{base-url}/api/v1/users/me/email-preferences/unsubscribe-all>, <mailto:{from}?subject=unsubscribe>
List-Unsubscribe-Post: List-Unsubscribe=One-Click
```

The **`POST /unsubscribe-all`** endpoint below is the `List-Unsubscribe` target
— Gmail / Apple Mail / Outlook surface it as a native "Unsubscribe" button.
Transport is SMTP or Resend (HTTPS) depending on `irc.email.provider`; both
paths share the same retry behavior.

---

## Endpoints

### Get preferences

```
GET /api/v1/users/me/email-preferences
```

**Auth:** Bearer JWT.

Returns the caller's current flags. All flags default to `true` for new
accounts (including `trending`).

**Response `200`**

```json
{
  "master":   true,
  "social":   true,
  "mentions": true,
  "system":   true,
  "trending": true
}
```

| Field | Type | Notes |
|---|---|---|
| `master` | boolean | Kill-switch (`emailNotificationsEnabled`) — `false` = no emails of any kind |
| `social` | boolean | `emailSocialEnabled` — engagement emails |
| `mentions` | boolean | `emailMentionsEnabled` — @mention emails |
| `system` | boolean | `emailSystemEnabled` — platform/moderation emails |
| `trending` | boolean | `emailTrendingEnabled` — daily trending digest; **independent of `system`** |

**Errors:** 401 `AUTH_UNAUTHORIZED` / `AUTH_*` (missing/invalid token or
deactivated account).

**Side effects:** none.

---

### Update preferences (partial)

```
PATCH /api/v1/users/me/email-preferences
```

**Auth:** Bearer JWT.

Partial update — send **any subset** of the five flags; omitted fields keep
their current value. An empty/absent body is a no-op that returns the current
flags.

**Request body**

```json
{ "trending": false }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `master` | boolean | no | Master toggle |
| `social` | boolean | no | Social-engagement emails |
| `mentions` | boolean | no | Mention emails |
| `system` | boolean | no | System emails |
| `trending` | boolean | no | Trending-digest emails (independent of `system`) |

**Response `200`** — the full updated flag set:

```json
{
  "master":   true,
  "social":   true,
  "mentions": true,
  "system":   true,
  "trending": false
}
```

Changes take effect **immediately** — the server evicts its short-lived (60 s)
per-user email-context cache on update, so the very next outbound notification
respects the new flags.

**Errors**

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MALFORMED_JSON` | Body isn't valid JSON / a flag isn't a boolean |
| 401 | `AUTH_UNAUTHORIZED` / `AUTH_*` | Missing or invalid token |

**Side effects:** email-context cache evicted for the caller. No SSE events.

---

### Send a test email

```
POST /api/v1/users/me/email-preferences/test
```

**Auth:** Bearer JWT.

Queues a self-test email to the caller's own address. **Bypasses the
notification pipeline entirely** — no kind eligibility, no toggles, no
throttle — so it verifies the SMTP/Resend transport end to end. Use it when
emails appear "sent" in logs but never arrive (surfaces transport-level
failures and spam-foldering).

**Request body:** none.

**Response `200`** — queued:

```json
{ "queued": true, "to": "user@example.com" }
```

**Response `200`** — account has no email address:

```json
{ "queued": false, "reason": "no email on account" }
```

| Field | Type | Notes |
|---|---|---|
| `queued` | boolean | Whether the send was handed to the async mailer. `true` means *queued*, not *delivered* — the actual send is async with retries |
| `to` | string | Destination address (present when `queued: true`) |
| `reason` | string | Present when `queued: false` |

**Errors:** 401 `AUTH_UNAUTHORIZED` / `AUTH_*`.

**Side effects:** one async email send. No SSE events, no preference changes.

---

### Unsubscribe from all (one-click)

```
POST /api/v1/users/me/email-preferences/unsubscribe-all
```

**Auth:** Bearer JWT.

Turns the **master** toggle off — the caller receives **no further notification
emails of any kind** until they re-enable `master` via
[`PATCH`](#update-preferences-partial). The category flags are left untouched,
so re-enabling `master` restores the previous per-category setup.

This endpoint is the **`List-Unsubscribe` / RFC 8058 one-click target** baked
into every outbound email's headers, which is why it's a bare `POST` with no
body.

**Request body:** none.

**Response `200`**

```json
{ "emailNotificationsEnabled": false }
```

**Errors:** 401 `AUTH_UNAUTHORIZED` / `AUTH_*`.

**Side effects:** master toggle persisted off; email-context cache evicted so
the change applies immediately. In-app + SSE notification delivery is
unaffected.

---

## UI checklist

- Settings page exposes **five** toggles: `master`, `social`, `mentions`,
  `system`, `trending` — with `trending` presented as its own switch (it is not
  a child of `system`).
- Explain to users that toggles affect **email only**; in-app notifications keep
  arriving (that's by design — the inbox isn't intrusive).
- After PATCH, render the response body (the authoritative full flag set) rather
  than assuming your request applied.

---

**See also:** [Notifications REST API](./notifications.md) ·
[Realtime SSE stream](./realtime.md) ·
[Error envelope](../errors/error-handling.md)
