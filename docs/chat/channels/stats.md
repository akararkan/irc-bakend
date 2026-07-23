# Channels — statistics & realtime

Owner/admin analytics, plus the summary of channel-scoped realtime events.

Base path `/api/v1`. `{id}` = channel UUID.

---

## `GET /channels/{id}/stats` — analytics

Owner/admin only. **Response** — `200`, `ChannelStatsResponse`:

| field | type | notes |
|---|---|---|
| `subscriberCount` | long | exact (Postgres). |
| `onlineSubscribers` | long | currently present (presence-derived). |
| `joinedLast7Days` | long | |
| `joinedLast30Days` | long | |
| `leftLast30Days` | long | |
| `mutedCount` | long | subscribers muting the channel. |
| `notificationsEnabledCount` | long | active subscribers − muted. |
| `postCount` | long | |
| `postsByType` | map<string,long> | live count per message type; best-effort Redis. |
| `totalViews` | long | best-effort Redis aggregate. |
| `totalForwards` | long | best-effort Redis aggregate. |
| `joinsByDay` | map<string,long> | ISO date → joins, last 30 days. |
| `joinsBySource` | map<string,long> | [join source](admins.md#join-source-tracking) → active subscribers. |
| `topPosts` | [MessageResponse](posts.md#messageresponse)[] | most-viewed first. |

```json
{ "subscriberCount": 128, "onlineSubscribers": 17,
  "joinedLast7Days": 12, "joinedLast30Days": 40, "leftLast30Days": 3,
  "mutedCount": 5, "notificationsEnabledCount": 123,
  "postCount": 42, "postsByType": { "TEXT": 30, "IMAGE": 8, "POLL": 4 },
  "totalViews": 5120, "totalForwards": 77,
  "joinsByDay": { "2026-07-20": 4, "2026-07-21": 6 },
  "joinsBySource": { "DISCOVERY": 80, "INVITE_LINK": 30, "JOIN_REQUEST": 10,
                     "ADDED_BY_ADMIN": 5, "COMMENT": 2, "OWNER": 1 },
  "topPosts": [ { "messageId": 7395…, "views": 980, "…": "…" } ] }
```

**Accuracy.** Member-derived numbers are exact (Postgres); `postsByType`,
`totalViews`, `totalForwards` and `topPosts` are best-effort Redis aggregates
maintained on the hot paths — the per-post [counters](posts.md#views--forwards)
remain the durable source of truth. **Errors** — `403 ADMINS_ONLY`.

---

## Realtime

Channel activity rides the single per-user SSE stream — one connection covers
every conversation the caller is in (full transport in [realtime.md](../realtime.md)).

**Connect:** `GET /api/v1/messaging/stream` with `Accept: text/event-stream`.
Browsers use `EventSource`, which can't set headers, so the access token is
passed as `?token=<accessToken>` (falls back from the `Authorization` header).
Each event arrives as a named SSE event with a JSON `{ event, data }` envelope.
Channel-relevant events:

| event | `data` payload | meaning |
|---|---|---|
| `member.changed` | `{ conversationId, userId, memberChange }` — `SUBSCRIBED`/`UNSUBSCRIBED`/`ADDED`/`PROMOTED`/`DEMOTED` | membership delta — apply ±1 to `subscriberCount` locally (delta-not-counts). |
| `conversation.updated` | `{ conversationId, memberChange: "CHANNEL_INFO_CHANGED" }` | title/photo/cover/settings changed — re-fetch the channel. |
| `poll.updated` | `{ messageId, poll }` (viewer-neutral aggregate) | post-poll vote/retract/close. |
| `channel.join_request` | `{ joinRequest }` | to the channel's admins on a new request. |
| `message.comment` | `{ messageId, added }` (`messageId` = the POST id) | a comment was added/removed — apply ±1 to `comments`. |

Channel **stories** fan out on the **separate** story-tray SSE
(`GET /api/v1/stories/tray/stream`) — see [stories.md](stories.md#story-tray).
