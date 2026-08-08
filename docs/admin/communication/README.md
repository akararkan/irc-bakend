# Communication — chat, channels, live, notifications

| Doc | What it answers |
|---|---|
| [chat-channels-live.md](chat-channels-live.md) | The privacy boundary and what admins may/may not see, channel verification & stats, invite abuse, live-stream control (force-stop, key rotation, recordings), the gift economy, legal holds |
| [notifications-email.md](notifications-email.md) | Notification volume dashboards, email deliverability, the digest job, the announcement composer, preference analytics |

## The privacy boundary — read this first

**Admins never see message content.** Not DMs, not group messages, not channel
posts, not live-stream chat, not `conversations.last_message_preview`. Admin
chat surfaces show **metadata and aggregates only**: counts, participants,
timestamps, rates.

This holds even inside the moderation queue. A chat message held by the
automated classifier shows a moderator its per-label scores and which field
tripped — never the body. See
[`../trust-safety/automated-moderation.md`](../trust-safety/automated-moderation.md).

Secrets are excluded from every DTO on this surface too: `live_streams.stream_key`
and `stream_guests.publish_key` never appear in an admin response. Key rotation
delivers the new value to the host by notification, and the API returns 204 with
no body.

API reference: [`../api/chat-live.md`](../api/chat-live.md) ·
[`../api/notifications-logs.md`](../api/notifications-logs.md).
