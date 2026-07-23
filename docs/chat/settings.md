# Chat Privacy Settings — Read Receipts, Last-Seen, Typing

Per-user privacy switches for chat, and the **symmetric** rules the rest of the
module enforces from them. These are the WhatsApp/Telegram "Privacy" toggles: turn
off your read receipts, hide your last-seen, or stop broadcasting typing.

- **Base path:** `/api/v1`
- **Auth:** `Authorization: Bearer <jwt>` on every endpoint.
- **Errors:** the shared `ApiErrorResponse` envelope — see
  [Error handling](../errors/error-handling.md).

**The symmetric principle.** Read receipts and last-seen are **reciprocal**: a user
can never *take* a signal they refuse to *give*. If you turn read receipts off, you
stop sending blue ticks **and** you stop seeing others'. If you hide your last-seen,
you stop seeing anyone else's. This mirrors WhatsApp exactly and closes the "watch
without being watched" loophole. Typing is one-directional (turning it off only
stops *your* outbound typing events).

Rows are created lazily: a user with no saved settings behaves as **all-on**
(receipts on, last-seen visible, typing on), so the defaults need no write.

Related: [Conversations](./conversations.md) · [Messages](./messages.md) ·
[Realtime](./realtime.md).

---

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/messaging/settings` | Read my chat privacy settings |
| `PUT` | `/messaging/settings` | Update my chat privacy settings (partial) |

---

## `GET /messaging/settings`

My current chat privacy settings (defaults when never set).

**Response `200` (`ChatSettingsResponse`):**

```json
{
  "readReceiptsEnabled": true,
  "lastSeenVisible": true,
  "typingIndicatorsEnabled": true
}
```

## `PUT /messaging/settings`

Partial update — omit a field (or send `null`) to leave it unchanged. The row is
created on first write.

**Request body (`UpdateChatSettingsRequest`):**

```jsonc
{
  "readReceiptsEnabled": false,       // optional — stop sending AND seeing read/delivered receipts
  "lastSeenVisible": false,           // optional — hide my last-seen AND stop seeing others'
  "typingIndicatorsEnabled": true     // optional — whether I broadcast "typing…"
}
```

**Response `200`:** the full `ChatSettingsResponse` after the update.

---

## What each switch gates

| Setting | On (default) | Off |
|---------|--------------|-----|
| `readReceiptsEnabled` | You send `receipt.read` (blue tick) + `receipt.delivered` (double-tick), appear in group **"seen by"**, and see all of the above from peers who also share. | No blue/double-ticks to or from you. In a DM, `peerLastReadMessageId` / `peerLastDeliveredMessageId` come back **`null`**; `GET /messages/{id}/seen-by` returns **empty** for you, and you're dropped from everyone else's seen-by list. |
| `lastSeenVisible` | Your last-seen time shows to contacts, and you see theirs. | Your last-seen is hidden (online/offline dot still shows), and you stop seeing anyone's last-seen. Applies to `GET /presence` and the `presence` SSE event. |
| `typingIndicatorsEnabled` | You broadcast `typing` to the other members. | You never send a typing event (you still receive others'). |

**Interaction with the other privacy layers.** These settings compose with — they
never override — the existing suppression rules. Receipts, typing and presence are
**already** suppressed for a pending message request, a `RESTRICTED` thread, or a
block relationship (see [Realtime §Suppression](./realtime.md)); these toggles are an
additional, user-controlled gate on top.

---

## Where the effects surface

| Surface | Effect |
|---------|--------|
| [`POST /conversations/{id}/read`](./conversations.md#post-conversationsidread) | `receipt.read` only broadcast if **both** DM parties share read receipts. |
| [`POST /messages/{id}/delivered`](./messages.md#51-post-messagesmessageiddelivered) | `receipt.delivered` gated by the same both-share rule. |
| [`GET /messages/{id}/seen-by`](./messages.md#52-get-messagesmessageidseen-by--who-has-read-it-groups) | Empty if you disabled receipts; omits any reader who disabled theirs. |
| [`ConversationResponse.peerLastRead/Delivered`](./conversations.md#conversationresponse-schema) | `null` when either DM side has receipts off. |
| [`GET /presence`](./realtime.md#3-get-presence--batch-presence-lookup) + `presence` SSE | `lastSeenEpochMs` suppressed per the last-seen rule. |
| [`POST /conversations/{id}/typing`](./realtime.md#2-post-conversationsidtyping--typing-indicator) | No `typing` event emitted when you've turned typing off. |

---

## Schemas

**`ChatSettingsResponse`** / **`UpdateChatSettingsRequest`** (request fields are all
nullable — omit to keep the current value):

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| `readReceiptsEnabled` | boolean | `true` | Send **and** receive read/delivered receipts + group seen-by (symmetric). |
| `lastSeenVisible` | boolean | `true` | Expose **and** see last-seen timestamps (symmetric). |
| `typingIndicatorsEnabled` | boolean | `true` | Broadcast my typing indicator (one-way). |
